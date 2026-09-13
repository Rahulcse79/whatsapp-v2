// Package fcm sends FCM HTTP v1 data messages with a service-account key.
//
// The whole of what the client contract needs (ADR-004): a data-only message,
// Android priority HIGH, the four documented keys. Authentication is the
// standard service-account flow — an RS256 JWT exchanged for a bearer token —
// done with the standard library so the service has no dependencies to audit.
package fcm

import (
	"bytes"
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
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	scope       = "https://www.googleapis.com/auth/firebase.messaging"
	endpointFmt = "https://fcm.googleapis.com/v1/projects/%s/messages:send"
)

// ErrUnregistered means FCM no longer knows the token: the app was uninstalled,
// its data cleared, or the token rotated. The caller should forget it.
var ErrUnregistered = errors.New("fcm: token is not registered")

// ErrNotConfigured means no service-account key was given; Send logs and
// returns this so the rest of the service can be exercised without one.
var ErrNotConfigured = errors.New("fcm: no service account configured")

// serviceAccount is the subset of the key file this needs.
type serviceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// Sender sends messages for one Firebase project.
type Sender struct {
	account  *serviceAccount
	key      *rsa.PrivateKey
	client   *http.Client
	endpoint string
	tokenURI string
	now      func() time.Time

	mu          sync.Mutex
	accessToken string
	expiry      time.Time
}

// Option customises a Sender; used by tests to point it at a fake Google.
type Option func(*Sender)

// WithHTTPClient replaces the HTTP client.
func WithHTTPClient(c *http.Client) Option { return func(s *Sender) { s.client = c } }

// WithEndpoints replaces the token and send URLs.
func WithEndpoints(tokenURI, sendURL string) Option {
	return func(s *Sender) { s.tokenURI, s.endpoint = tokenURI, sendURL }
}

// WithClock replaces the clock.
func WithClock(now func() time.Time) Option { return func(s *Sender) { s.now = now } }

// NewFromFile loads a service-account JSON key. An empty path returns a Sender
// whose Send reports ErrNotConfigured, so the rest of the service still runs.
func NewFromFile(path string, opts ...Option) (*Sender, error) {
	if path == "" {
		s := &Sender{client: http.DefaultClient, now: time.Now}
		for _, o := range opts {
			o(s)
		}
		return s, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("fcm: read service account: %w", err)
	}
	return New(data, opts...)
}

// New builds a Sender from service-account JSON.
func New(keyJSON []byte, opts ...Option) (*Sender, error) {
	var account serviceAccount
	if err := json.Unmarshal(keyJSON, &account); err != nil {
		return nil, fmt.Errorf("fcm: parse service account: %w", err)
	}
	if account.ProjectID == "" || account.ClientEmail == "" || account.PrivateKey == "" {
		return nil, errors.New("fcm: service account is missing project_id, client_email or private_key")
	}
	key, err := parsePrivateKey(account.PrivateKey)
	if err != nil {
		return nil, err
	}
	if account.TokenURI == "" {
		account.TokenURI = "https://oauth2.googleapis.com/token"
	}
	s := &Sender{
		account:  &account,
		key:      key,
		client:   &http.Client{Timeout: 10 * time.Second},
		endpoint: fmt.Sprintf(endpointFmt, account.ProjectID),
		tokenURI: account.TokenURI,
		now:      time.Now,
	}
	for _, o := range opts {
		o(s)
	}
	return s, nil
}

// Configured reports whether a key was loaded.
func (s *Sender) Configured() bool { return s.account != nil }

// ProjectID is the Firebase project the key belongs to.
func (s *Sender) ProjectID() string {
	if s.account == nil {
		return ""
	}
	return s.account.ProjectID
}

// Message is what Send delivers. Data is sent verbatim as the message's data
// map; the caller builds it to the ADR-004 contract.
type Message struct {
	Token string
	Data  map[string]string
	TTL   time.Duration
}

// request is the FCM v1 wire shape. Data-only, HIGH priority, no notification:
// a notification message would be shown by the system and never wake the app
// in the background, which is the whole point.
type request struct {
	Message struct {
		Token   string            `json:"token"`
		Data    map[string]string `json:"data"`
		Android struct {
			Priority string `json:"priority"`
			TTL      string `json:"ttl,omitempty"`
		} `json:"android"`
	} `json:"message"`
}

// Send delivers one message. It retries once on an expired bearer token or a
// transient server error, and returns ErrUnregistered when FCM reports the
// token dead.
func (s *Sender) Send(ctx context.Context, m Message) error {
	if s.account == nil {
		return ErrNotConfigured
	}
	var body request
	body.Message.Token = m.Token
	body.Message.Data = m.Data
	body.Message.Android.Priority = "HIGH"
	if m.TTL > 0 {
		body.Message.Android.TTL = fmt.Sprintf("%ds", int(m.TTL.Seconds()))
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}

	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		status, respBody, err := s.post(ctx, payload)
		if err != nil {
			lastErr = err
			continue
		}
		switch {
		case status == http.StatusOK:
			return nil
		case status == http.StatusNotFound && strings.Contains(respBody, "UNREGISTERED"):
			return ErrUnregistered
		case status == http.StatusBadRequest && strings.Contains(respBody, "registration token"):
			return fmt.Errorf("%w: %s", ErrUnregistered, summarise(respBody))
		case status == http.StatusUnauthorized:
			s.invalidateToken()
			lastErr = fmt.Errorf("fcm: unauthorised: %s", summarise(respBody))
		case status == http.StatusTooManyRequests || status >= 500:
			lastErr = fmt.Errorf("fcm: %d: %s", status, summarise(respBody))
			select {
			case <-time.After(500 * time.Millisecond):
			case <-ctx.Done():
				return ctx.Err()
			}
		default:
			return fmt.Errorf("fcm: %d: %s", status, summarise(respBody))
		}
	}
	return lastErr
}

func (s *Sender) post(ctx context.Context, payload []byte) (int, string, error) {
	bearer, err := s.bearer(ctx)
	if err != nil {
		return 0, "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.endpoint, bytes.NewReader(payload))
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Authorization", "Bearer "+bearer)
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	resp, err := s.client.Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("fcm: send: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	return resp.StatusCode, string(body), nil
}

func summarise(body string) string {
	body = strings.TrimSpace(body)
	if len(body) > 300 {
		return body[:300] + "…"
	}
	return body
}

func (s *Sender) invalidateToken() {
	s.mu.Lock()
	s.expiry = time.Time{}
	s.mu.Unlock()
}

// bearer returns a cached access token, fetching a new one a minute before
// the old one expires.
func (s *Sender) bearer(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.accessToken != "" && s.now().Add(time.Minute).Before(s.expiry) {
		return s.accessToken, nil
	}
	assertion, err := s.signedJWT()
	if err != nil {
		return "", err
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.tokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := s.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("fcm: token exchange: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("fcm: token exchange: %d: %s", resp.StatusCode, summarise(string(body)))
	}
	var token struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &token); err != nil || token.AccessToken == "" {
		return "", errors.New("fcm: token exchange: no access_token in reply")
	}
	s.accessToken = token.AccessToken
	s.expiry = s.now().Add(time.Duration(token.ExpiresIn) * time.Second)
	return s.accessToken, nil
}

// signedJWT builds the RS256 assertion Google's token endpoint accepts.
func (s *Sender) signedJWT() (string, error) {
	now := s.now()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, err := json.Marshal(map[string]any{
		"iss":   s.account.ClientEmail,
		"scope": scope,
		"aud":   s.tokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	if err != nil {
		return "", err
	}
	signingInput := header + "." + base64.RawURLEncoding.EncodeToString(claims)
	digest := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, s.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("fcm: sign assertion: %w", err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(signature), nil
}

func parsePrivateKey(pemText string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemText))
	if block == nil {
		return nil, errors.New("fcm: private_key is not PEM")
	}
	if key, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		rsaKey, ok := key.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("fcm: private_key is not RSA")
		}
		return rsaKey, nil
	}
	key, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("fcm: parse private_key: %w", err)
	}
	return key, nil
}
