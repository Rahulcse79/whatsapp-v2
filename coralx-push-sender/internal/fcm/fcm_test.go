package fcm

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// fakeGoogle stands in for the token endpoint and FCM. It verifies the JWT
// the way Google would (RS256 over header.claims with the account's key) and
// records the send request so the test can assert the wire shape.
type fakeGoogle struct {
	t          *testing.T
	pub        *rsa.PublicKey
	sendStatus int
	sendBody   string
	tokens     atomic.Int32
	lastSend   atomic.Pointer[http.Request]
	lastBody   atomic.Pointer[string]
	server     *httptest.Server
}

func newFakeGoogle(t *testing.T, pub *rsa.PublicKey) *fakeGoogle {
	g := &fakeGoogle{t: t, pub: pub, sendStatus: http.StatusOK, sendBody: `{"name":"projects/p/messages/1"}`}
	mux := http.NewServeMux()
	mux.HandleFunc("/token", g.token)
	mux.HandleFunc("/send", g.send)
	g.server = httptest.NewServer(mux)
	t.Cleanup(g.server.Close)
	return g
}

func (g *fakeGoogle) token(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		http.Error(w, "bad grant_type", 400)
		return
	}
	parts := strings.Split(r.Form.Get("assertion"), ".")
	if len(parts) != 3 {
		http.Error(w, "bad assertion", 400)
		return
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || rsa.VerifyPKCS1v15(g.pub, crypto.SHA256, digest[:], sig) != nil {
		http.Error(w, "bad signature", 401)
		return
	}
	claimsJSON, _ := base64.RawURLEncoding.DecodeString(parts[1])
	var claims map[string]any
	_ = json.Unmarshal(claimsJSON, &claims)
	if claims["scope"] != "https://www.googleapis.com/auth/firebase.messaging" || claims["iss"] != "sender@p.iam.gserviceaccount.com" {
		http.Error(w, "bad claims", 401)
		return
	}
	g.tokens.Add(1)
	_, _ = io.WriteString(w, `{"access_token":"ya29.test","expires_in":3600,"token_type":"Bearer"}`)
}

func (g *fakeGoogle) send(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer ya29.test" {
		http.Error(w, "no bearer", 401)
		return
	}
	body, _ := io.ReadAll(r.Body)
	s := string(body)
	g.lastBody.Store(&s)
	g.lastSend.Store(r)
	w.WriteHeader(g.sendStatus)
	_, _ = io.WriteString(w, g.sendBody)
}

func newSender(t *testing.T) (*Sender, *fakeGoogle) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pemKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
	g := newFakeGoogle(t, &key.PublicKey)

	account, _ := json.Marshal(map[string]string{
		"type":         "service_account",
		"project_id":   "coral-x-test",
		"client_email": "sender@p.iam.gserviceaccount.com",
		"private_key":  pemKey,
		"token_uri":    g.server.URL + "/token",
	})
	s, err := New(account, WithEndpoints(g.server.URL+"/token", g.server.URL+"/send"))
	if err != nil {
		t.Fatal(err)
	}
	return s, g
}

func TestSendIsAHighPriorityDataMessageWithExactlyTheContractKeys(t *testing.T) {
	s, g := newSender(t)
	err := s.Send(context.Background(), Message{
		Token: "device-token",
		TTL:   30 * time.Second,
		Data: map[string]string{
			"type":       "incoming_call",
			"call_id":    "8f3c…",
			"account_id": "1001",
			"sent_at":    "1757700000000",
		},
	})
	if err != nil {
		t.Fatalf("send: %v", err)
	}

	var sent map[string]any
	if err := json.Unmarshal([]byte(*g.lastBody.Load()), &sent); err != nil {
		t.Fatal(err)
	}
	message := sent["message"].(map[string]any)
	if message["token"] != "device-token" {
		t.Fatalf("token: %v", message["token"])
	}
	if _, hasNotification := message["notification"]; hasNotification {
		t.Fatal("a notification message would never reach onMessageReceived in the background")
	}
	android := message["android"].(map[string]any)
	if android["priority"] != "HIGH" || android["ttl"] != "30s" {
		t.Fatalf("android block: %v", android)
	}
	data := message["data"].(map[string]any)
	if len(data) != 4 || data["type"] != "incoming_call" || data["account_id"] != "1001" || data["sent_at"] != "1757700000000" || data["call_id"] != "8f3c…" {
		t.Fatalf("data map is not the ADR-004 contract: %v", data)
	}
}

func TestBearerTokenIsCachedAcrossSends(t *testing.T) {
	s, g := newSender(t)
	m := Message{Token: "device-token", Data: map[string]string{"type": "incoming_call"}}
	for i := 0; i < 3; i++ {
		if err := s.Send(context.Background(), m); err != nil {
			t.Fatal(err)
		}
	}
	if got := g.tokens.Load(); got != 1 {
		t.Fatalf("expected one token exchange, got %d", got)
	}
}

func TestAnUnregisteredTokenIsReportedAsSuch(t *testing.T) {
	s, g := newSender(t)
	g.sendStatus = http.StatusNotFound
	g.sendBody = `{"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`

	err := s.Send(context.Background(), Message{Token: "gone", Data: map[string]string{"type": "incoming_call"}})
	if !errors.Is(err, ErrUnregistered) {
		t.Fatalf("expected ErrUnregistered, got %v", err)
	}
}

func TestUnconfiguredSenderSaysSo(t *testing.T) {
	s, err := NewFromFile("")
	if err != nil {
		t.Fatal(err)
	}
	if s.Configured() {
		t.Fatal("no key means not configured")
	}
	if err := s.Send(context.Background(), Message{}); !errors.Is(err, ErrNotConfigured) {
		t.Fatalf("expected ErrNotConfigured, got %v", err)
	}
}
