// Package registry remembers which push token belongs to which SIP user.
//
// The token arrives the RFC 8599 way: as `pn-provider`, `pn-param` and
// `pn-prid` URI parameters on the Contact the client REGISTERs with.
// FreeSWITCH keeps URI parameters in the contact it stores, so the same
// string is visible in three places and this package reads all three: the
// `sofia::register` event as each REGISTER lands, `show registrations` at
// start-up, and its own JSON file across restarts. The file matters because a
// registration expires (typically an hour) while the token it carried is still
// the only way to reach that device — a phone that has been asleep for a day
// is exactly the phone a push is for.
package registry

import (
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Token is one device's push identity as its REGISTER declared it.
type Token struct {
	Provider string    `json:"provider"` // pn-provider, e.g. "fcm"
	Param    string    `json:"param"`    // pn-param, the FCM sender id
	PRID     string    `json:"prid"`     // pn-prid, the FCM registration token
	Host     string    `json:"host"`     // the realm the user registered in
	Contact  string    `json:"contact"`  // the stored contact, for diagnostics
	SeenAt   time.Time `json:"seen_at"`  // when this token was last on a REGISTER
}

// Redacted is the token with the identifying part cut down for logs: a
// registration token names a device (ADR-004 §7), so it is never logged whole.
func (t Token) Redacted() string {
	if len(t.PRID) <= 8 {
		return "…"
	}
	return "…" + t.PRID[len(t.PRID)-8:]
}

// Registry is the user → token map, safe for concurrent use.
type Registry struct {
	mu        sync.RWMutex
	persistMu sync.Mutex // one writer of the file at a time
	tokens    map[string]Token
	path      string // "" means in-memory only
	senderID  string // "" means accept any FCM project (see WithSenderID)
}

// Option configures a Registry at construction.
type Option func(*Registry)

// WithSenderID restricts the registry to devices of one FCM project.
//
// A token is only meaningful to the project that minted it: FCM HTTP v1 is
// project-scoped, so a message signed by this sender's service account cannot
// be delivered to a token issued under a different sender id. Handsets of
// another product can and do register on the same SIP domain carrying their own
// RFC 8599 parameters — on this deployment extension 4038 registers with
// pn-param 58415781151 while ours mint under 273934559014 — and without this
// filter their tokens are stored here and pushed to, which is an attempt to
// reach another product's device with our credentials.
//
// Empty (the default) keeps the previous behaviour of accepting every token, so
// a deployment that has not set it is unaffected.
func WithSenderID(senderID string) Option {
	return func(r *Registry) { r.senderID = senderID }
}

// SenderAllowed reports whether a pn-param belongs to the project this registry
// serves. Always true when no sender id is configured.
func (r *Registry) SenderAllowed(param string) bool {
	return r.senderID == "" || param == r.senderID
}

// New returns a registry persisted at path (may be empty for no persistence).
// An existing file is loaded; a missing one is not an error. Tokens in the file
// that fail the configured sender check are dropped on load, so a store written
// before the restriction does not keep a foreign project's device reachable.
func New(path string, opts ...Option) (*Registry, error) {
	r := &Registry{tokens: map[string]Token{}, path: path}
	for _, opt := range opts {
		opt(r)
	}
	if path == "" {
		return r, nil
	}
	data, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return r, nil
	case err != nil:
		return nil, err
	}
	if err := json.Unmarshal(data, &r.tokens); err != nil {
		return nil, err
	}
	pruned := false
	for user, token := range r.tokens {
		if !r.SenderAllowed(token.Param) {
			delete(r.tokens, user)
			pruned = true
		}
	}
	// Rewrite the file too: another product's registration token identifies one of
	// its devices, and there is no reason for it to sit in this service's state
	// directory once it has been ruled out.
	if pruned {
		r.persist()
	}
	return r, nil
}

// Observe records what a REGISTER for user carried. A contact without pn-*
// parameters is not a push client and leaves any stored token alone: a desk
// phone sharing an extension must not erase the phone's token. It returns the
// token when one was found.
func (r *Registry) Observe(user, host, contact string, seenAt time.Time) (Token, bool) {
	provider, param, prid, ok := ParseContact(contact)
	if !ok {
		return Token{}, false
	}
	// Another product's handset on the same SIP domain. Storing its token would
	// let this sender try to wake a device its credentials cannot address.
	if !r.SenderAllowed(param) {
		return Token{}, false
	}
	token := Token{Provider: provider, Param: param, PRID: prid, Host: host, Contact: contact, SeenAt: seenAt}

	r.mu.Lock()
	r.tokens[user] = token
	r.mu.Unlock()
	r.persist()
	return token, true
}

// Lookup returns the token for user, if one was ever observed.
func (r *Registry) Lookup(user string) (Token, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	t, ok := r.tokens[user]
	return t, ok
}

// Forget drops user's token — FCM said it is no longer valid.
func (r *Registry) Forget(user string) {
	r.mu.Lock()
	delete(r.tokens, user)
	r.mu.Unlock()
	r.persist()
}

// Users lists every user with a token, sorted, for the diagnostics endpoint.
func (r *Registry) Users() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	users := make([]string, 0, len(r.tokens))
	for u := range r.tokens {
		users = append(users, u)
	}
	sort.Strings(users)
	return users
}

// persist writes the map atomically (temp file + rename) so a crash mid-write
// leaves the previous file, not half of a new one. Writers are serialised: two
// REGISTERs landing together both wrote the same temp file, and whichever
// renamed second found it gone, so the file could miss the later token.
func (r *Registry) persist() {
	if r.path == "" {
		return
	}
	r.persistMu.Lock()
	defer r.persistMu.Unlock()
	r.mu.RLock()
	data, err := json.MarshalIndent(r.tokens, "", "  ")
	r.mu.RUnlock()
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(r.path), 0o700); err != nil {
		return
	}
	tmp := r.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, r.path)
}

// ParseContact pulls the RFC 8599 parameters out of a contact string as
// FreeSWITCH stores it, e.g.
//
//	"" <sip:1001@10.0.0.5:46020;ob;pn-provider=fcm;pn-param=123;pn-prid=dXc…;fs_nat=yes;fs_path=…>
//
// or the `show registrations` form, sofia/internal/sip:1001@…;pn-prid=…. Values
// are percent-decoded, which undoes the escaping the client applies. It
// returns ok=false unless all three parameters are present and non-empty.
func ParseContact(contact string) (provider, param, prid string, ok bool) {
	uri := contact
	if start := strings.Index(uri, "<"); start >= 0 {
		uri = uri[start+1:]
		if end := strings.Index(uri, ">"); end >= 0 {
			uri = uri[:end]
		}
	}
	for _, segment := range strings.Split(uri, ";")[1:] {
		name, value, _ := strings.Cut(segment, "=")
		decoded, err := url.PathUnescape(value)
		if err != nil {
			decoded = value
		}
		switch strings.ToLower(name) {
		case "pn-provider":
			provider = decoded
		case "pn-param":
			param = decoded
		case "pn-prid":
			prid = decoded
		}
	}
	ok = provider != "" && param != "" && prid != ""
	return provider, param, prid, ok
}

// ParseShowRegistrations seeds the registry from the CSV `show registrations`
// prints (reg_user,realm,token,url,…). Rows without pn-* parameters are
// skipped. It returns how many tokens were observed.
func (r *Registry) ParseShowRegistrations(csv string, seenAt time.Time) int {
	count := 0
	for _, line := range strings.Split(csv, "\n") {
		fields := strings.Split(line, ",")
		if len(fields) < 4 || fields[0] == "reg_user" {
			continue
		}
		if _, ok := r.Observe(fields[0], fields[1], fields[3], seenAt); ok {
			count++
		}
	}
	return count
}
