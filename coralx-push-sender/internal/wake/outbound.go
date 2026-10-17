package wake

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
)

// Waking a device on the leg FreeSWITCH is already building, for a deployment
// whose dialplan this service does not own.
//
// # Why the ADR-004 flow cannot be used here
//
// The park/resume flow in wake.go needs three things from the dialplan: a
// `coralx::push_wake` event to announce the call, a park to hold the caller on
// ringback, and a `coralx-resume` context to bridge it afterwards. All three
// live in `deploy/freeswitch/`, and installing them assumes the dialplan is
// static XML this service can add a file to.
//
// On the Coral deployment it is not. `mod_xml_curl` is bound for the dialplan
// to the application on :8080, so every routing decision is generated per call
// over HTTP and the static `dialplan/default/*.xml` include is never reached
// for a user extension. A file dropped in there fires nothing. The generated
// extension ends in a plain `bridge user/<ext>` with `call_timeout=30` and
// `continue_on_fail=true`, and it contains no push hook of any kind — so on
// that deployment no push had ever been sent, which is exactly why a sleeping
// handset did not ring.
//
// # What this does instead
//
// It takes the one signal that needs no dialplan cooperation: FreeSWITCH
// firing CHANNEL_OUTGOING as it creates the leg toward the registered contact.
// The push goes out at that moment, in parallel with the INVITE, and the
// device has the dialplan's own `call_timeout` to wake up and ring. Measured
// on the deployment, FCM delivery to REGISTER is 923 ms against a 30 s
// window.
//
// # Why it keeps no call state
//
// There is nothing to resume: the caller is not parked, and the timeout
// context the park/resume flow transfers to does not exist on this
// deployment — routing a live channel into a missing context would end the
// call rather than rescue it. So this path pushes and forgets, and the
// generated dialplan keeps its own failure handling. The only state is a
// per-channel note that a push has gone out, so the three events FreeSWITCH
// fires for one leg cannot become three pushes.

// legDedupeWindow is how long a channel is remembered as already pushed.
//
// FreeSWITCH fires CHANNEL_OUTGOING, CHANNEL_CREATE and CHANNEL_ORIGINATE for
// the same leg within microseconds of each other, and a subscription that
// widens to catch a profile which omits one of them would otherwise push
// three times. A minute is longer than any single leg setup and far shorter
// than the gap between two real calls to the same extension.
const legDedupeWindow = time.Minute

// OnOutboundLeg is FreeSWITCH creating a leg toward ext on channel uuid.
//
// A push goes out only when the extension actually has a token this service
// can address: a trunk, a gateway, a conference leg or another product's
// handset all resolve to no token and are ignored without a word at info
// level. Idempotent per channel for [legDedupeWindow].
func (m *Manager) OnOutboundLeg(ctx context.Context, uuid, ext string) {
	if uuid == "" || ext == "" {
		return
	}
	now := m.cfg.Now()

	token, hasToken := m.registry.Lookup(ext)
	if !hasToken {
		m.cfg.Logger.Debug("outbound leg to an extension with no push token; ignoring",
			"uuid", uuid, "ext", ext)
		return
	}

	m.mu.Lock()
	if last, seen := m.legs[uuid]; seen && now.Sub(last) < legDedupeWindow {
		m.mu.Unlock()
		return
	}
	m.legs[uuid] = now
	m.mu.Unlock()

	log := m.cfg.Logger.With("uuid", uuid, "ext", ext)
	log.Info("outbound leg to a push device: sending FCM", "token", token.Redacted())
	go m.pushLeg(ctx, uuid, ext, token, now, log)
}

// pushLeg sends the wake for an outbound leg.
//
// Deliberately not [Manager.push]: that one drives the park/resume state
// machine, and a dead token there has a parked caller to release. Here there
// is no held call, so the only consequences are the log line and — when FCM
// says the token no longer exists — dropping it, which stops this service
// pushing at a device that has uninstalled the app on every future call.
func (m *Manager) pushLeg(ctx context.Context, uuid, ext string, token registry.Token, sentAt time.Time, log *slog.Logger) {
	err := m.pusher.Send(ctx, fcm.Message{
		Token: token.PRID,
		Data:  Payload(uuid, ext, sentAt),
		TTL:   m.cfg.PushTTL,
	})
	switch {
	case err == nil:
		log.Info("outbound leg: FCM accepted")
	case errors.Is(err, fcm.ErrUnregistered):
		m.registry.Forget(ext)
		log.Warn("outbound leg: FCM says the token is dead; forgetting it")
	case errors.Is(err, fcm.ErrNotConfigured):
		log.Warn("outbound leg: FCM is not configured (dry run); nothing was sent")
	default:
		log.Error("outbound leg: FCM send failed", "err", err)
	}
}

// sweepLegs drops the per-channel push notes that are past the dedupe window.
// Called by [Manager.Sweep] with the lock held.
func (m *Manager) sweepLegs(now time.Time) {
	for uuid, at := range m.legs {
		if now.Sub(at) >= legDedupeWindow {
			delete(m.legs, uuid)
		}
	}
}
