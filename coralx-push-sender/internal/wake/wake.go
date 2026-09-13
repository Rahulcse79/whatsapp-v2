// Package wake holds a call for a device that has to be woken first.
//
// The dialplan tells us a call is coming (coralx::push_wake), tries the
// contact it has, and if that gets no progress rings the caller and parks.
// This package sends the push the moment the event arrives, then waits for
// the one thing that proves the device is reachable: a REGISTER that lands
// after the push. When it does and the caller is parked, the call is
// transferred to the resume context, which bridges it. When it does not
// within the wake timeout, the call goes to the timeout context instead of
// dead air. Everything here is driven by events and a clock, so it is tested
// without FreeSWITCH or FCM.
package wake

import (
	"context"
	"errors"
	"log/slog"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
)

// Pusher sends the wake-up. The data map is built here so the ADR-004
// contract lives in one place.
type Pusher interface {
	Send(ctx context.Context, m fcm.Message) error
}

// Switch moves a parked call on. Both operations cancel the dialplan's own
// fallback transfer first, so the two never fire on the same channel.
type Switch interface {
	Resume(ctx context.Context, uuid, ext string) error
	Timeout(ctx context.Context, uuid, ext string) error
}

// Config tunes the manager.
type Config struct {
	// WakeTimeout is how long a parked caller waits for the device's REGISTER
	// before being routed to the timeout context. ADR-004's budget is 12 s.
	WakeTimeout time.Duration
	// PushTTL is the FCM time-to-live: a push older than this is not delivered.
	PushTTL time.Duration
	// Now is the clock; tests inject one.
	Now func() time.Time
	// Logger receives one line per state change. Tokens are never logged whole.
	Logger *slog.Logger
}

// Manager is the per-call state, safe for concurrent use.
type Manager struct {
	registry *registry.Registry
	pusher   Pusher
	sw       Switch
	cfg      Config

	mu    sync.Mutex
	calls map[string]*call
}

type call struct {
	uuid, ext, caller string
	startedAt         time.Time
	deadline          time.Time
	hasToken          bool
	tokenGone         bool // FCM said the token is dead
	pushed            bool // FCM accepted the message
	registered        bool // a REGISTER for ext arrived after startedAt
	parked            bool
	done              bool
}

// New wires a manager. Zero-value config fields get ADR-004's defaults.
func New(reg *registry.Registry, pusher Pusher, sw Switch, cfg Config) *Manager {
	if cfg.WakeTimeout == 0 {
		cfg.WakeTimeout = 12 * time.Second
	}
	if cfg.PushTTL == 0 {
		cfg.PushTTL = 30 * time.Second
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.Default()
	}
	return &Manager{registry: reg, pusher: pusher, sw: sw, cfg: cfg, calls: map[string]*call{}}
}

// Payload is the FCM data map for one call — exactly the four ADR-004 keys.
// call_id is the FreeSWITCH channel UUID: the SIP Call-ID of the INVITE the
// device will receive does not exist yet (the B-leg is created on resume), and
// the UUID is what every log line on the server side is keyed by.
func Payload(callID, accountID string, sentAt time.Time) map[string]string {
	return map[string]string{
		"type":       "incoming_call",
		"call_id":    callID,
		"account_id": accountID,
		"sent_at":    strconv.FormatInt(sentAt.UnixMilli(), 10),
	}
}

// OnPushWake is the dialplan's announcement that ext is being called on
// channel uuid. Idempotent per uuid.
func (m *Manager) OnPushWake(ctx context.Context, uuid, ext, caller string) {
	now := m.cfg.Now()
	m.mu.Lock()
	if _, exists := m.calls[uuid]; exists {
		m.mu.Unlock()
		return
	}
	c := &call{uuid: uuid, ext: ext, caller: caller, startedAt: now, deadline: now.Add(m.cfg.WakeTimeout)}
	token, hasToken := m.registry.Lookup(ext)
	c.hasToken = hasToken
	m.calls[uuid] = c
	m.mu.Unlock()

	log := m.cfg.Logger.With("uuid", uuid, "ext", ext)
	if !hasToken {
		log.Info("push_wake: no push token for extension; the direct attempt is all there is")
		return
	}
	log.Info("push_wake: sending FCM", "token", token.Redacted())
	go m.push(ctx, c, token, now, log)
}

func (m *Manager) push(ctx context.Context, c *call, token registry.Token, sentAt time.Time, log *slog.Logger) {
	err := m.pusher.Send(ctx, fcm.Message{
		Token: token.PRID,
		Data:  Payload(c.uuid, c.ext, sentAt),
		TTL:   m.cfg.PushTTL,
	})
	m.mu.Lock()
	switch {
	case err == nil:
		c.pushed = true
		log.Info("push_wake: FCM accepted")
	case errors.Is(err, fcm.ErrUnregistered):
		c.tokenGone = true
		m.registry.Forget(c.ext)
		log.Warn("push_wake: FCM says the token is dead; forgetting it")
	case errors.Is(err, fcm.ErrNotConfigured):
		log.Warn("push_wake: FCM is not configured (dry run); waiting for a REGISTER anyway")
	default:
		log.Error("push_wake: FCM send failed", "err", err)
	}
	// A dead token on a parked caller has nothing to wait for.
	if c.tokenGone && c.parked && !c.done {
		m.finish(ctx, c, false)
	}
	m.mu.Unlock()
}

// OnRegister is a REGISTER landing for user. Every call waiting on that
// extension is released if it is parked, or marked so it is released the
// moment it parks.
func (m *Manager) OnRegister(ctx context.Context, user string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, c := range m.calls {
		if c.ext != user || c.done {
			continue
		}
		c.registered = true
		m.cfg.Logger.Info("push_wake: fresh REGISTER", "uuid", c.uuid, "ext", c.ext, "parked", c.parked)
		if c.parked {
			m.finish(ctx, c, true)
		}
	}
}

// OnPark is the caller reaching the park application: the direct attempt
// got no progress and the caller is now on ringback, waiting on us.
func (m *Manager) OnPark(ctx context.Context, uuid string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.calls[uuid]
	if !ok || c.done {
		return
	}
	c.parked = true
	switch {
	case c.registered:
		m.finish(ctx, c, true)
	case !c.hasToken || c.tokenGone:
		// Nothing can wake this device. 486 now beats ringback for 12 s first.
		m.finish(ctx, c, false)
	default:
		m.cfg.Logger.Info("push_wake: caller parked, waiting for REGISTER", "uuid", uuid, "ext", c.ext,
			"remaining", c.deadline.Sub(m.cfg.Now()).Round(time.Millisecond))
	}
}

// OnHangup forgets a channel that has gone away, whichever side ended it.
func (m *Manager) OnHangup(uuid string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if c, ok := m.calls[uuid]; ok {
		switch {
		case c.parked && !c.done:
			m.cfg.Logger.Info("push_wake: caller hung up while parked", "uuid", uuid, "ext", c.ext,
				"waited", m.cfg.Now().Sub(c.startedAt).Round(time.Millisecond))
		case !c.parked:
			// The direct attempt reached the device (or the caller gave up during
			// it); the push was a spare wake-up and nothing here was needed.
			m.cfg.Logger.Debug("push_wake: call ended without parking", "uuid", uuid, "ext", c.ext)
		}
		delete(m.calls, uuid)
	}
}

// Sweep enforces the wake timeout and garbage-collects calls that never
// parked (the direct attempt rang the phone and the hangup was missed).
func (m *Manager) Sweep(ctx context.Context) {
	now := m.cfg.Now()
	m.mu.Lock()
	defer m.mu.Unlock()
	for uuid, c := range m.calls {
		switch {
		case !c.done && c.parked && now.After(c.deadline):
			m.cfg.Logger.Warn("push_wake: no REGISTER within the wake timeout", "uuid", uuid, "ext", c.ext,
				"pushed", c.pushed)
			m.finish(ctx, c, false)
		case now.After(c.deadline.Add(2 * time.Minute)):
			delete(m.calls, uuid)
		}
	}
}

// finish moves the call on. Called with the lock held; the switch call is
// quick (one ESL api round trip) and ordering matters more than latency here.
func (m *Manager) finish(ctx context.Context, c *call, resume bool) {
	c.done = true
	var err error
	if resume {
		err = m.sw.Resume(ctx, c.uuid, c.ext)
	} else {
		err = m.sw.Timeout(ctx, c.uuid, c.ext)
	}
	if err != nil {
		m.cfg.Logger.Error("push_wake: could not move the call on", "uuid", c.uuid, "resume", resume, "err", err)
		delete(m.calls, c.uuid)
		return
	}
	m.cfg.Logger.Info("push_wake: call moved on", "uuid", c.uuid, "ext", c.ext, "resume", resume,
		"waited", m.cfg.Now().Sub(c.startedAt).Round(time.Millisecond))
}

// View is one call's state for the diagnostics endpoint.
type View struct {
	UUID       string    `json:"uuid"`
	Ext        string    `json:"ext"`
	Caller     string    `json:"caller"`
	StartedAt  time.Time `json:"started_at"`
	HasToken   bool      `json:"has_token"`
	Pushed     bool      `json:"pushed"`
	Registered bool      `json:"registered"`
	Parked     bool      `json:"parked"`
	Done       bool      `json:"done"`
}

// Snapshot lists the calls the manager knows about, oldest first.
func (m *Manager) Snapshot() []View {
	m.mu.Lock()
	defer m.mu.Unlock()
	views := make([]View, 0, len(m.calls))
	for _, c := range m.calls {
		views = append(views, View{
			UUID: c.uuid, Ext: c.ext, Caller: c.caller, StartedAt: c.startedAt, HasToken: c.hasToken,
			Pushed: c.pushed, Registered: c.registered, Parked: c.parked, Done: c.done,
		})
	}
	sort.Slice(views, func(i, j int) bool { return views[i].StartedAt.Before(views[j].StartedAt) })
	return views
}
