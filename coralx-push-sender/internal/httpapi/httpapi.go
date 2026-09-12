// Package httpapi is the sender's small operator surface: health, the token
// registry, the calls in flight, and a manual push for testing the wake path
// without placing a call.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/Rahulcse79/coralx-push-sender/internal/fcm"
	"github.com/Rahulcse79/coralx-push-sender/internal/registry"
	"github.com/Rahulcse79/coralx-push-sender/internal/wake"
)

// Status is what /healthz reports about the two upstreams.
type Status interface {
	ESLConnected() bool
	FCMConfigured() bool
	ProjectID() string
}

// Handler serves the API.
type Handler struct {
	registry *registry.Registry
	manager  *wake.Manager
	pusher   wake.Pusher
	status   Status
	pushTTL  time.Duration
}

// New builds the handler.
func New(reg *registry.Registry, m *wake.Manager, p wake.Pusher, s Status, pushTTL time.Duration) http.Handler {
	h := &Handler{registry: reg, manager: m, pusher: p, status: s, pushTTL: pushTTL}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.healthz)
	mux.HandleFunc("GET /tokens", h.tokens)
	mux.HandleFunc("GET /calls", h.calls)
	mux.HandleFunc("POST /push", h.push)
	return mux
}

func (h *Handler) healthz(w http.ResponseWriter, _ *http.Request) {
	body := map[string]any{
		"esl":     h.status.ESLConnected(),
		"fcm":     h.status.FCMConfigured(),
		"project": h.status.ProjectID(),
		"tokens":  len(h.registry.Users()),
	}
	code := http.StatusOK
	if !h.status.ESLConnected() {
		code = http.StatusServiceUnavailable
	}
	writeJSON(w, code, body)
}

func (h *Handler) tokens(w http.ResponseWriter, _ *http.Request) {
	type row struct {
		User     string    `json:"user"`
		Host     string    `json:"host"`
		Provider string    `json:"provider"`
		Param    string    `json:"param"`
		Token    string    `json:"token"` // redacted: a token names a device
		SeenAt   time.Time `json:"seen_at"`
	}
	rows := []row{}
	for _, user := range h.registry.Users() {
		t, _ := h.registry.Lookup(user)
		rows = append(rows, row{User: user, Host: t.Host, Provider: t.Provider, Param: t.Param, Token: t.Redacted(), SeenAt: t.SeenAt})
	}
	writeJSON(w, http.StatusOK, rows)
}

func (h *Handler) calls(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, h.manager.Snapshot())
}

// push sends the ADR-004 message to an extension's registered token without
// a call behind it. Acceptance criterion 2 is verified with exactly this: the
// device must answer with a fresh REGISTER, visible in `sofia status profile
// internal reg` as a new contact port and in the app log as a push wake.
func (h *Handler) push(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AccountID string `json:"account_id"`
		CallID    string `json:"call_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil || body.AccountID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "body must be {\"account_id\": \"<sip user>\"}"})
		return
	}
	token, ok := h.registry.Lookup(body.AccountID)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no push token registered for " + body.AccountID})
		return
	}
	if body.CallID == "" {
		body.CallID = "manual-" + time.Now().UTC().Format("20060102T150405.000Z")
	}
	sentAt := time.Now()
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	err := h.pusher.Send(ctx, fcm.Message{Token: token.PRID, Data: wake.Payload(body.CallID, body.AccountID, sentAt), TTL: h.pushTTL})
	switch {
	case err == nil:
		writeJSON(w, http.StatusAccepted, map[string]any{"sent": true, "call_id": body.CallID, "sent_at": sentAt.UnixMilli(), "token": token.Redacted()})
	case errors.Is(err, fcm.ErrUnregistered):
		h.registry.Forget(body.AccountID)
		writeJSON(w, http.StatusGone, map[string]string{"error": "FCM reports the token as unregistered; it has been forgotten"})
	case errors.Is(err, fcm.ErrNotConfigured):
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "FCM is not configured: start the sender with -service-account"})
	default:
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
	}
}

func writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(body)
}
