package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
)

// recordingPusher captures exactly what Send was handed, so a test can assert the
// token and payload that reached FCM rather than only the HTTP status. It can be told
// to fail with a specific error to exercise the handler's error branches.
type recordingPusher struct {
	mu      sync.Mutex
	calls   int
	lastMsg fcm.Message
	err     error
}

func (p *recordingPusher) Send(_ context.Context, m fcm.Message) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls++
	p.lastMsg = m
	return p.err
}

// stubStatus satisfies the Status interface; /push never calls it.
type stubStatus struct{}

func (stubStatus) ESLConnected() bool  { return true }
func (stubStatus) FCMConfigured() bool { return true }
func (stubStatus) ProjectID() string   { return "whatsapp-39dcb" }

// twoAccounts seeds 4006 and 4038 with distinct, FAKE tokens — never real ones.
func twoAccounts(t *testing.T) *registry.Registry {
	t.Helper()
	reg, err := registry.New(t.TempDir() + "/tokens.json")
	if err != nil {
		t.Fatalf("registry.New: %v", err)
	}
	contact := func(user, sender, prid string) string {
		return "<sip:" + user + "@192.168.20.56;pn-provider=fcm;pn-param=" + sender + ";pn-prid=" + prid + ">"
	}
	if _, ok := reg.Observe("4006", "192.168.20.56", contact("4006", "273934559014", "FAKE-TOKEN-4006"), time.Now()); !ok {
		t.Fatal("seed 4006 failed")
	}
	if _, ok := reg.Observe("4038", "192.168.20.56", contact("4038", "58415781151", "FAKE-TOKEN-4038"), time.Now()); !ok {
		t.Fatal("seed 4038 failed")
	}
	return reg
}

func postPush(h http.Handler, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/push", strings.NewReader(body))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// The isolation guarantee, stated as a test: a push for one account carries ONLY that
// account's token and names ONLY that account, for both accounts, with one shared handler.
func TestPushSelectsExactlyTheRequestedAccountsToken(t *testing.T) {
	cases := []struct {
		account   string
		wantToken string
	}{
		{"4006", "FAKE-TOKEN-4006"},
		{"4038", "FAKE-TOKEN-4038"},
	}
	for _, c := range cases {
		pusher := &recordingPusher{}
		h := New(twoAccounts(t), nil, pusher, stubStatus{}, 30*time.Second)
		rec := postPush(h, `{"account_id":"`+c.account+`"}`)

		if rec.Code != http.StatusAccepted {
			t.Fatalf("%s: status = %d, want 202; body %s", c.account, rec.Code, rec.Body)
		}
		if pusher.calls != 1 {
			t.Fatalf("%s: pusher called %d times, want 1", c.account, pusher.calls)
		}
		if pusher.lastMsg.Token != c.wantToken {
			t.Errorf("%s: token sent = %q, want %q", c.account, pusher.lastMsg.Token, c.wantToken)
		}
		if got := pusher.lastMsg.Data["account_id"]; got != c.account {
			t.Errorf("%s: payload account_id = %q, want %q", c.account, got, c.account)
		}
		// The crux: the OTHER account's token must never appear.
		other := "FAKE-TOKEN-4038"
		if c.account == "4038" {
			other = "FAKE-TOKEN-4006"
		}
		if pusher.lastMsg.Token == other {
			t.Fatalf("%s: leaked the other account's token", c.account)
		}
	}
}

func TestPushUnknownAccountIs404AndNeverCallsFCM(t *testing.T) {
	pusher := &recordingPusher{}
	h := New(twoAccounts(t), nil, pusher, stubStatus{}, 30*time.Second)
	rec := postPush(h, `{"account_id":"9999"}`)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if pusher.calls != 0 {
		t.Fatalf("FCM was called %d times for an unknown account; must be 0", pusher.calls)
	}
}

func TestPushRejectsMissingAccountAndOversizeBody(t *testing.T) {
	pusher := &recordingPusher{}
	h := New(twoAccounts(t), nil, pusher, stubStatus{}, 30*time.Second)

	if rec := postPush(h, `{"call_id":"x"}`); rec.Code != http.StatusBadRequest {
		t.Errorf("missing account_id: status = %d, want 400", rec.Code)
	}
	if rec := postPush(h, `{not json`); rec.Code != http.StatusBadRequest {
		t.Errorf("invalid JSON: status = %d, want 400", rec.Code)
	}
	if pusher.calls != 0 {
		t.Errorf("FCM called %d times on rejected requests; must be 0", pusher.calls)
	}
}

// An UNREGISTERED result forgets ONLY the rejected account, and a later push to the
// other account still works — a failed send must not take a valid token with it.
func TestUnregisteredForgetsOnlyTheRejectedAccount(t *testing.T) {
	reg := twoAccounts(t)
	pusher := &recordingPusher{err: fcm.ErrUnregistered}
	h := New(reg, nil, pusher, stubStatus{}, 30*time.Second)

	if rec := postPush(h, `{"account_id":"4006"}`); rec.Code != http.StatusGone {
		t.Fatalf("status = %d, want 410 Gone", rec.Code)
	}
	if _, ok := reg.Lookup("4006"); ok {
		t.Error("4006 should have been forgotten after UNREGISTERED")
	}
	if _, ok := reg.Lookup("4038"); !ok {
		t.Fatal("4038 was wrongly removed; UNREGISTERED must forget only the rejected account")
	}
}

// A transport/5xx error must NOT delete the token — only UNREGISTERED does.
func TestTransientFailureKeepsTheToken(t *testing.T) {
	reg := twoAccounts(t)
	pusher := &recordingPusher{err: context.DeadlineExceeded}
	h := New(reg, nil, pusher, stubStatus{}, 30*time.Second)

	if rec := postPush(h, `{"account_id":"4006"}`); rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
	if _, ok := reg.Lookup("4006"); !ok {
		t.Error("a transient send failure must not forget a valid token")
	}
}

func TestTokensEndpointNeverReturnsAWholeToken(t *testing.T) {
	h := New(twoAccounts(t), nil, &recordingPusher{}, stubStatus{}, 30*time.Second)
	req := httptest.NewRequest(http.MethodGet, "/tokens", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	body := rec.Body.String()
	if strings.Contains(body, "FAKE-TOKEN-4006") || strings.Contains(body, "FAKE-TOKEN-4038") {
		t.Fatalf("/tokens leaked a full token: %s", body)
	}
	var rows []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &rows); err != nil {
		t.Fatalf("/tokens is not JSON: %v", err)
	}
}
