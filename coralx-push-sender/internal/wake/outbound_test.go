package wake

import (
	"context"
	"testing"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
)

// contactFor builds a registered contact for one extension. The token is fake:
// a registration token names a device and real ones never appear in tests.
func outboundContact(ext string) string {
	return `<sip:` + ext + `@192.168.103.128:46587;transport=TCP;ob;pn-provider=fcm` +
		`;pn-param=273934559014;pn-prid=FAKE-` + ext + `;pn-type=firebase;pn-silent=1>`
}

// The whole point of this path: the deployment's dialplan announces nothing, so
// the leg FreeSWITCH builds toward the device is the only signal there is.
func TestOutboundLegToAPushDeviceSendsTheWake(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	f.waitForPush(t)

	sent := f.pusher.messages()
	if len(sent) != 1 {
		t.Fatalf("expected exactly one push, got %d", len(sent))
	}
	if sent[0].Token != "FAKE-2004" {
		t.Fatalf("pushed the wrong device: %q", sent[0].Token)
	}
	if sent[0].Data["account_id"] != "2004" {
		t.Fatalf("account_id must be the SIP user: %q", sent[0].Data["account_id"])
	}
	if sent[0].Data["call_id"] != "uuid-1" {
		t.Fatalf("call_id must be the channel uuid: %q", sent[0].Data["call_id"])
	}
	if sent[0].Data["type"] != "incoming_call" {
		t.Fatalf("type must stay the ADR-004 contract: %q", sent[0].Data["type"])
	}
}

// A trunk, a gateway, a conference leg or another product's handset all reach
// this with no token, and none of them is ours to wake.
func TestOutboundLegWithNoTokenSendsNothing(t *testing.T) {
	f := newFixture(t)

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "9196")

	if got := f.pusher.messages(); len(got) != 0 {
		t.Fatalf("an extension with no token must not be pushed: %+v", got)
	}
}

// FreeSWITCH fires CHANNEL_OUTGOING, CHANNEL_CREATE and CHANNEL_ORIGINATE for
// one leg. Three events are not three calls.
func TestOutboundLegIsIdempotentPerChannel(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())

	for range 3 {
		f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	}
	f.waitForPush(t)

	if got := f.pusher.messages(); len(got) != 1 {
		t.Fatalf("one leg must produce one push, got %d", len(got))
	}
}

// The next real call to the same extension is a different channel, so it is
// pushed even though the extension was pushed a moment ago.
func TestASecondCallToTheSameExtensionIsPushed(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	f.waitForPush(t)
	f.m.OnOutboundLeg(context.Background(), "uuid-2", "2004")
	f.waitForPush(t)

	if got := f.pusher.messages(); len(got) != 2 {
		t.Fatalf("two calls must produce two pushes, got %d", len(got))
	}
}

// The dedupe note is not a leak: Sweep drops it once the window has passed.
func TestSweepForgetsPushedLegs(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	f.waitForPush(t)

	f.m.mu.Lock()
	held := len(f.m.legs)
	f.m.mu.Unlock()
	if held != 1 {
		t.Fatalf("the channel should be noted while the window is open, got %d", held)
	}

	f.advance(legDedupeWindow + time.Second)
	f.m.Sweep(context.Background())

	f.m.mu.Lock()
	held = len(f.m.legs)
	f.m.mu.Unlock()
	if held != 0 {
		t.Fatalf("the note should be gone after the window, got %d", held)
	}
}

// This path holds no call, so a dead token has nothing to release - but it must
// still stop this service pushing at a device that has uninstalled the app.
func TestADeadTokenIsForgottenOnAnOutboundLeg(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())
	f.pusher.err = fcm.ErrUnregistered

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	f.waitForPush(t)

	// The push goroutine forgets the token after Send returns.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, ok := f.reg.Lookup("2004"); !ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("a token FCM reports as unregistered must be forgotten")
}

// Nothing in this path may touch the park/resume machinery: there is no parked
// caller, and the timeout context it transfers to does not exist on the
// deployment this path is for.
func TestOutboundLegNeverMovesACall(t *testing.T) {
	f := newFixture(t)
	f.reg.Observe("2004", "192.168.20.56", outboundContact("2004"), f.clock())

	f.m.OnOutboundLeg(context.Background(), "uuid-1", "2004")
	f.waitForPush(t)
	f.advance(time.Minute)
	f.m.Sweep(context.Background())

	resumed, timedOut := f.sw.state()
	if len(resumed) != 0 || len(timedOut) != 0 {
		t.Fatalf("the channel must be left to the dialplan: resumed=%v timedOut=%v", resumed, timedOut)
	}
}
