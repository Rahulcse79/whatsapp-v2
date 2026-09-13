package wake

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
)

const contact = `<sip:1001@192.168.2.191:46020;ob;pn-provider=fcm;pn-param=12;pn-prid=tok-1001;fs_nat=yes>`

type fakePusher struct {
	mu   sync.Mutex
	sent []fcm.Message
	err  error
	done chan struct{}
}

func (p *fakePusher) Send(_ context.Context, m fcm.Message) error {
	p.mu.Lock()
	p.sent = append(p.sent, m)
	p.mu.Unlock()
	if p.done != nil {
		p.done <- struct{}{}
	}
	return p.err
}

func (p *fakePusher) messages() []fcm.Message {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]fcm.Message(nil), p.sent...)
}

type fakeSwitch struct {
	mu       sync.Mutex
	resumed  []string
	timedOut []string
}

func (s *fakeSwitch) Resume(_ context.Context, uuid, _ string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resumed = append(s.resumed, uuid)
	return nil
}

func (s *fakeSwitch) Timeout(_ context.Context, uuid, _ string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.timedOut = append(s.timedOut, uuid)
	return nil
}

func (s *fakeSwitch) state() (resumed, timedOut []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.resumed...), append([]string(nil), s.timedOut...)
}

type fixture struct {
	m      *Manager
	pusher *fakePusher
	sw     *fakeSwitch
	reg    *registry.Registry
	now    time.Time
	mu     sync.Mutex
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	reg, _ := registry.New("")
	f := &fixture{
		pusher: &fakePusher{done: make(chan struct{}, 8)},
		sw:     &fakeSwitch{},
		reg:    reg,
		now:    time.Date(2026, 9, 12, 21, 0, 0, 0, time.UTC),
	}
	f.m = New(reg, f.pusher, f.sw, Config{
		WakeTimeout: 12 * time.Second,
		Now:         f.clock,
		Logger:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	return f
}

func (f *fixture) clock() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}

func (f *fixture) advance(d time.Duration) {
	f.mu.Lock()
	f.now = f.now.Add(d)
	f.mu.Unlock()
}

func (f *fixture) waitForPush(t *testing.T) {
	t.Helper()
	select {
	case <-f.pusher.done:
	case <-time.After(2 * time.Second):
		t.Fatal("no push was sent")
	}
}

func TestTheHappyPath_PushThenRegisterThenParkResumes(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-1", "1001", "1003")
	f.waitForPush(t)

	sent := f.pusher.messages()
	if len(sent) != 1 || sent[0].Token != "tok-1001" {
		t.Fatalf("expected one push to the registered token, got %+v", sent)
	}
	if sent[0].Data["type"] != "incoming_call" || sent[0].Data["account_id"] != "1001" ||
		sent[0].Data["call_id"] != "call-1" || sent[0].Data["sent_at"] != "1789246800000" || len(sent[0].Data) != 4 {
		t.Fatalf("payload is not the ADR-004 contract: %v", sent[0].Data)
	}

	// The device re-registers before the direct attempt has even given up …
	f.advance(2 * time.Second)
	f.m.OnRegister(ctx, "1001")
	if resumed, _ := f.sw.state(); len(resumed) != 0 {
		t.Fatal("must not transfer a channel that is still in its direct bridge attempt")
	}
	// … and the moment the caller parks, the call is released.
	f.advance(time.Second)
	f.m.OnPark(ctx, "call-1")
	if resumed, timedOut := f.sw.state(); len(resumed) != 1 || resumed[0] != "call-1" || len(timedOut) != 0 {
		t.Fatalf("expected a resume, got resumed=%v timedOut=%v", resumed, timedOut)
	}
}

func TestParkFirstThenRegisterResumes(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-2", "1001", "1003")
	f.waitForPush(t)
	f.advance(3 * time.Second)
	f.m.OnPark(ctx, "call-2")
	if resumed, timedOut := f.sw.state(); len(resumed)+len(timedOut) != 0 {
		t.Fatal("parked and unregistered: nothing should move yet")
	}
	f.advance(2 * time.Second)
	f.m.OnRegister(ctx, "1001")
	if resumed, _ := f.sw.state(); len(resumed) != 1 {
		t.Fatalf("expected the parked call to resume, got %v", resumed)
	}
	// A second REGISTER (the refresh the client sends anyway) must not transfer twice.
	f.m.OnRegister(ctx, "1001")
	if resumed, _ := f.sw.state(); len(resumed) != 1 {
		t.Fatalf("resumed twice: %v", resumed)
	}
}

func TestNoRegisterWithinTheTimeoutRoutesToTimeout(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-3", "1001", "1003")
	f.waitForPush(t)
	f.advance(3 * time.Second)
	f.m.OnPark(ctx, "call-3")
	f.advance(8 * time.Second)
	f.m.Sweep(ctx) // 11 s: still inside the budget
	if _, timedOut := f.sw.state(); len(timedOut) != 0 {
		t.Fatal("timed out early")
	}
	f.advance(2 * time.Second)
	f.m.Sweep(ctx) // 13 s
	if resumed, timedOut := f.sw.state(); len(timedOut) != 1 || len(resumed) != 0 {
		t.Fatalf("expected a timeout, got resumed=%v timedOut=%v", resumed, timedOut)
	}
	// A REGISTER that finally arrives is too late for this call.
	f.m.OnRegister(ctx, "1001")
	if resumed, _ := f.sw.state(); len(resumed) != 0 {
		t.Fatal("a call already moved on must not be transferred again")
	}
}

func TestAnExtensionWithoutATokenIsNotWaitedFor(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-4", "1005", "1003")
	if len(f.pusher.messages()) != 0 {
		t.Fatal("nothing to push to")
	}
	f.m.OnPark(ctx, "call-4")
	if _, timedOut := f.sw.state(); len(timedOut) != 1 {
		t.Fatalf("a parked caller with nothing to wait for gets the timeout route now, got %v", timedOut)
	}
}

func TestADeadTokenIsForgottenAndTheCallerNotKeptWaiting(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	f.pusher.err = fcm.ErrUnregistered
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-5", "1001", "1003")
	f.waitForPush(t)
	// Let the push goroutine record the outcome.
	deadline := time.Now().Add(2 * time.Second)
	for {
		if _, ok := f.reg.Lookup("1001"); !ok || time.Now().After(deadline) {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if _, ok := f.reg.Lookup("1001"); ok {
		t.Fatal("an UNREGISTERED token must be forgotten")
	}
	f.m.OnPark(ctx, "call-5")
	if _, timedOut := f.sw.state(); len(timedOut) != 1 {
		t.Fatalf("expected the timeout route, got %v", timedOut)
	}
}

func TestPushWakeIsIdempotentPerCall(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-6", "1001", "1003")
	f.m.OnPushWake(ctx, "call-6", "1001", "1003")
	f.waitForPush(t)
	select {
	case <-f.pusher.done:
		t.Fatal("a second push for the same call")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestACallerWhoHangsUpIsForgotten(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-7", "1001", "1003")
	f.waitForPush(t)
	f.m.OnPark(ctx, "call-7")
	f.m.OnHangup("call-7")
	f.m.OnRegister(ctx, "1001")
	if resumed, timedOut := f.sw.state(); len(resumed)+len(timedOut) != 0 {
		t.Fatalf("a hung-up call must not be moved: resumed=%v timedOut=%v", resumed, timedOut)
	}
	if len(f.m.Snapshot()) != 0 {
		t.Fatal("call still tracked after hangup")
	}
}

func TestAFailedTransferDoesNotLeakTheCall(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("1001", "192.168.2.196", contact, f.now)
	failing := &failingSwitch{}
	f.m.sw = failing
	ctx := context.Background()

	f.m.OnPushWake(ctx, "call-8", "1001", "1003")
	f.waitForPush(t)
	f.m.OnPark(ctx, "call-8")
	f.m.OnRegister(ctx, "1001")
	if len(f.m.Snapshot()) != 0 {
		t.Fatal("a call whose transfer failed (channel gone) must be dropped")
	}
}

type failingSwitch struct{}

func (failingSwitch) Resume(context.Context, string, string) error {
	return errors.New("-ERR No such channel!")
}
func (failingSwitch) Timeout(context.Context, string, string) error {
	return errors.New("-ERR No such channel!")
}
