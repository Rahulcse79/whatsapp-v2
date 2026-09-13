// Command push-sender is the Coral X push gateway (ADR-004).
//
// It sits next to FreeSWITCH on the Event Socket, learns each device's FCM
// token from the RFC 8599 parameters on its REGISTER, sends a high-priority
// data message when the dialplan announces a call for that device, and
// releases the parked caller the moment the device's fresh REGISTER lands.
//
// Configuration is flags, each overridable by an environment variable named
// PUSH_SENDER_<FLAG> with dashes as underscores, e.g. PUSH_SENDER_ESL_PASSWORD.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/esl"
	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/httpapi"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
	"github.com/Rahulcse79/coralx-push-sender/internal/wake"
)

// The events the sender lives on. CHANNEL_PARK and CHANNEL_HANGUP_COMPLETE
// carry the caller's state; the two CUSTOM subclasses are the REGISTER and the
// dialplan's announcement.
var subscriptions = []string{"CHANNEL_PARK", "CHANNEL_HANGUP_COMPLETE", "CUSTOM", "sofia::register", "coralx::push_wake"}

type config struct {
	eslAddr        string
	eslPassword    string
	httpAddr       string
	serviceAccount string
	dataDir        string
	wakeTimeout    time.Duration
	pushTTL        time.Duration
	resumeContext  string
	timeoutContext string
	logLevel       string
}

func envOr(flagName, fallback string) string {
	key := "PUSH_SENDER_" + strings.ToUpper(strings.ReplaceAll(flagName, "-", "_"))
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}

func parseFlags() config {
	var c config
	flag.StringVar(&c.eslAddr, "esl-addr", envOr("esl-addr", "127.0.0.1:8021"), "FreeSWITCH event socket address")
	flag.StringVar(&c.eslPassword, "esl-password", envOr("esl-password", "ClueCon"), "event socket password")
	flag.StringVar(&c.httpAddr, "http-addr", envOr("http-addr", "127.0.0.1:8085"), "operator HTTP API address")
	flag.StringVar(&c.serviceAccount, "service-account", envOr("service-account", ""), "Firebase service-account JSON key; empty = dry run (no FCM)")
	flag.StringVar(&c.dataDir, "data-dir", envOr("data-dir", "data"), "where tokens.json lives")
	flag.DurationVar(&c.wakeTimeout, "wake-timeout", mustDuration(envOr("wake-timeout", "12s")), "how long a parked caller waits for the device's REGISTER")
	flag.DurationVar(&c.pushTTL, "push-ttl", mustDuration(envOr("push-ttl", "30s")), "FCM time-to-live for the wake message")
	flag.StringVar(&c.resumeContext, "resume-context", envOr("resume-context", "coralx-resume"), "dialplan context that bridges a woken call")
	flag.StringVar(&c.timeoutContext, "timeout-context", envOr("timeout-context", "coralx-timeout"), "dialplan context for a device that did not wake")
	flag.StringVar(&c.logLevel, "log-level", envOr("log-level", "info"), "debug, info, warn or error")
	flag.Parse()
	return c
}

func mustDuration(s string) time.Duration {
	d, err := time.ParseDuration(s)
	if err != nil {
		fmt.Fprintf(os.Stderr, "bad duration %q: %v\n", s, err)
		os.Exit(2)
	}
	return d
}

// eslSwitch moves calls on through whichever ESL connection is current.
type eslSwitch struct {
	client         atomic.Pointer[esl.Client]
	resumeContext  string
	timeoutContext string
}

func (s *eslSwitch) current() (*esl.Client, error) {
	c := s.client.Load()
	if c == nil || c.Err() != nil {
		return nil, errors.New("esl: not connected")
	}
	return c, nil
}

func (s *eslSwitch) transfer(ctx context.Context, uuid, ext, dialplanContext string) error {
	c, err := s.current()
	if err != nil {
		return err
	}
	// The dialplan scheduled its own fallback transfer under the channel's
	// UUID as the task group; it must not fire on top of this one.
	_, _ = c.API(ctx, "sched_del "+uuid)
	_, err = c.API(ctx, fmt.Sprintf("uuid_transfer %s %s XML %s", uuid, ext, dialplanContext))
	return err
}

func (s *eslSwitch) Resume(ctx context.Context, uuid, ext string) error {
	return s.transfer(ctx, uuid, ext, s.resumeContext)
}

func (s *eslSwitch) Timeout(ctx context.Context, uuid, ext string) error {
	return s.transfer(ctx, uuid, ext, s.timeoutContext)
}

// status is what /healthz reads.
type status struct {
	sw     *eslSwitch
	sender *fcm.Sender
}

func (s status) ESLConnected() bool  { _, err := s.sw.current(); return err == nil }
func (s status) FCMConfigured() bool { return s.sender.Configured() }
func (s status) ProjectID() string   { return s.sender.ProjectID() }

func main() {
	cfg := parseFlags()
	var level slog.Level
	if err := level.UnmarshalText([]byte(cfg.logLevel)); err != nil {
		fmt.Fprintf(os.Stderr, "bad -log-level %q\n", cfg.logLevel)
		os.Exit(2)
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
	slog.SetDefault(log)

	reg, err := registry.New(filepath.Join(cfg.dataDir, "tokens.json"))
	if err != nil {
		log.Error("cannot load the token registry", "err", err)
		os.Exit(1)
	}
	sender, err := fcm.NewFromFile(cfg.serviceAccount)
	if err != nil {
		log.Error("cannot load the service account", "err", err)
		os.Exit(1)
	}
	if sender.Configured() {
		log.Info("FCM configured", "project", sender.ProjectID())
	} else {
		log.Warn("FCM is NOT configured: running dry - pushes are logged, not sent (pass -service-account)")
	}

	sw := &eslSwitch{resumeContext: cfg.resumeContext, timeoutContext: cfg.timeoutContext}
	manager := wake.New(reg, sender, sw, wake.Config{WakeTimeout: cfg.wakeTimeout, PushTTL: cfg.pushTTL, Logger: log})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	api := &http.Server{Addr: cfg.httpAddr, Handler: httpapi.New(reg, manager, sender, status{sw, sender}, cfg.pushTTL), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		log.Info("HTTP API listening", "addr", cfg.httpAddr)
		if err := api.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP API failed", "err", err)
		}
	}()

	go func() {
		ticker := time.NewTicker(250 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				manager.Sweep(ctx)
			case <-ctx.Done():
				return
			}
		}
	}()

	backoff := time.Second
	for ctx.Err() == nil {
		started := time.Now()
		err := runConnection(ctx, cfg, log, reg, manager, sw)
		if ctx.Err() != nil {
			break
		}
		if time.Since(started) > 30*time.Second {
			backoff = time.Second // it was up; this is a fresh outage, not a retry storm
		}
		log.Warn("event socket connection ended; reconnecting", "err", err, "in", backoff)
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
		}
		if backoff < 15*time.Second {
			backoff *= 2
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = api.Shutdown(shutdownCtx)
	log.Info("stopped")
}

// runConnection holds one ESL session: subscribe, seed the registry from the
// live registrations, then dispatch events until the socket goes away.
func runConnection(ctx context.Context, cfg config, log *slog.Logger, reg *registry.Registry, manager *wake.Manager, sw *eslSwitch) error {
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	client, err := esl.Dial(dialCtx, cfg.eslAddr, cfg.eslPassword)
	cancel()
	if err != nil {
		return err
	}
	defer client.Close()

	if err := client.Subscribe(ctx, subscriptions...); err != nil {
		return err
	}
	sw.client.Store(client)
	log.Info("event socket connected", "addr", cfg.eslAddr)

	// Registrations that predate this process, and their tokens. The file
	// already has the ones seen before a restart; this catches a token that
	// registered while the sender was down.
	if csv, err := client.API(ctx, "show registrations"); err == nil {
		n := reg.ParseShowRegistrations(csv, time.Now())
		log.Info("seeded tokens from live registrations", "tokens", n, "known", len(reg.Users()))
	} else {
		log.Warn("could not read live registrations", "err", err)
	}

	for {
		select {
		case ev, ok := <-client.Events():
			if !ok {
				return client.Err()
			}
			dispatch(ctx, log, reg, manager, ev)
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func dispatch(ctx context.Context, log *slog.Logger, reg *registry.Registry, manager *wake.Manager, ev esl.Event) {
	log.Debug("event", "name", ev.Name(), "subclass", ev.Subclass(), "uuid", ev.UUID())
	switch ev.Name() {
	case "CHANNEL_PARK":
		manager.OnPark(ctx, ev.UUID())
	case "CHANNEL_HANGUP_COMPLETE":
		manager.OnHangup(ev.UUID())
	case "CUSTOM":
		switch ev.Subclass() {
		case "sofia::register":
			// mod_sofia labels the AOR user `from-user` on this event; the other
			// two spellings are fallbacks for a profile that renames them.
			user := first(ev["from-user"], ev["to-user"], ev["username"])
			host := first(ev["from-host"], ev["to-host"], ev["realm"])
			if user == "" {
				return
			}
			if token, ok := reg.Observe(user, host, ev["contact"], time.Now()); ok {
				log.Info("REGISTER with push token", "user", user, "host", host, "provider", token.Provider,
					"token", token.Redacted(), "agent", ev["user-agent"])
			} else {
				log.Debug("REGISTER without push token", "user", user, "agent", ev["user-agent"])
			}
			manager.OnRegister(ctx, user)
		case "coralx::push_wake":
			ext := first(ev["Push-Ext"], ev["Caller-Destination-Number"])
			caller := first(ev["Push-Caller"], ev["Caller-Caller-ID-Number"])
			if ev.UUID() == "" || ext == "" {
				log.Warn("push_wake event without a channel or extension", "event", ev)
				return
			}
			manager.OnPushWake(ctx, ev.UUID(), ext, caller)
		}
	}
}

func first(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
