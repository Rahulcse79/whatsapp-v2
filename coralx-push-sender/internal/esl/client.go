// Package esl is a minimal FreeSWITCH Event Socket (inbound) client.
//
// It does the four things the push sender needs and nothing else: authenticate,
// subscribe to events in JSON, run `api` commands, and hand every event to a
// callback. One goroutine reads the socket; command replies are matched to the
// command that is waiting for them in order, which is the order the server
// answers in, and everything else is an event.
package esl

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Event is one event as FreeSWITCH serialises it in `json` format: a flat map
// of header name to value. Values are not URL-encoded in this format, unlike
// `plain`, which is why the client asks for it.
type Event map[string]string

// Name is the Event-Name header, e.g. CUSTOM or CHANNEL_PARK.
func (e Event) Name() string { return e["Event-Name"] }

// Subclass is the Event-Subclass header of a CUSTOM event, e.g. sofia::register.
func (e Event) Subclass() string { return e["Event-Subclass"] }

// UUID is the Unique-ID of the channel the event is about, if any.
func (e Event) UUID() string { return e["Unique-ID"] }

// message is one framed message off the socket: headers plus an optional body.
type message struct {
	headers map[string]string
	body    string
}

func (m *message) contentType() string { return m.headers["Content-Type"] }

// Client is one inbound connection. It is safe for concurrent use; API calls
// are serialised because the protocol answers them in order.
type Client struct {
	conn    net.Conn
	reader  *bufio.Reader
	writeMu sync.Mutex
	cmdMu   sync.Mutex

	pending chan chan *message // one entry per command awaiting its reply, FIFO
	events  chan Event
	done    chan struct{}
	err     error
	errOnce sync.Once
}

// ErrClosed is returned once the connection has gone away.
var ErrClosed = errors.New("esl: connection closed")

// Dial connects, authenticates and starts the reader.
func Dial(ctx context.Context, addr, password string) (*Client, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("esl: dial %s: %w", addr, err)
	}

	c := &Client{
		conn:    conn,
		reader:  bufio.NewReader(conn),
		pending: make(chan chan *message, 64),
		events:  make(chan Event, 1024),
		done:    make(chan struct{}),
	}

	// The server speaks first: `Content-Type: auth/request`.
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	first, err := c.readMessage()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("esl: waiting for auth/request: %w", err)
	}
	if first.contentType() != "auth/request" {
		conn.Close()
		return nil, fmt.Errorf("esl: expected auth/request, got %q", first.contentType())
	}
	if err := c.write("auth " + password); err != nil {
		conn.Close()
		return nil, err
	}
	reply, err := c.readMessage()
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("esl: waiting for auth reply: %w", err)
	}
	if !strings.HasPrefix(reply.headers["Reply-Text"], "+OK") {
		conn.Close()
		return nil, fmt.Errorf("esl: authentication refused: %q", reply.headers["Reply-Text"])
	}
	_ = conn.SetReadDeadline(time.Time{})

	go c.readLoop()
	return c, nil
}

// Events delivers every event the server sends. The channel is closed when the
// connection ends; Err then says why.
func (c *Client) Events() <-chan Event { return c.events }

// Err is the error that ended the connection, or nil while it is up.
func (c *Client) Err() error {
	select {
	case <-c.done:
		return c.err
	default:
		return nil
	}
}

// Close shuts the connection down.
func (c *Client) Close() error {
	c.fail(ErrClosed)
	return c.conn.Close()
}

// Subscribe asks for the named events in JSON format. Names are as on the
// `event` command line, e.g. "CHANNEL_PARK", "CUSTOM sofia::register".
func (c *Client) Subscribe(ctx context.Context, names ...string) error {
	reply, err := c.command(ctx, "event json "+strings.Join(names, " "))
	if err != nil {
		return err
	}
	if !strings.HasPrefix(reply.headers["Reply-Text"], "+OK") {
		return fmt.Errorf("esl: event subscription refused: %q", reply.headers["Reply-Text"])
	}
	return nil
}

// API runs a blocking `api` command and returns the response body, e.g. "+OK".
// A body starting with "-ERR" is returned as an error.
func (c *Client) API(ctx context.Context, command string) (string, error) {
	reply, err := c.command(ctx, "api "+command)
	if err != nil {
		return "", err
	}
	body := strings.TrimSpace(reply.body)
	if strings.HasPrefix(body, "-ERR") {
		return body, fmt.Errorf("esl: %s: %s", firstWord(command), body)
	}
	return body, nil
}

func firstWord(s string) string {
	if i := strings.IndexByte(s, ' '); i > 0 {
		return s[:i]
	}
	return s
}

func (c *Client) command(ctx context.Context, line string) (*message, error) {
	// One command in flight at a time keeps the reply order trivially right.
	c.cmdMu.Lock()
	defer c.cmdMu.Unlock()

	if err := c.Err(); err != nil {
		return nil, err
	}
	replyCh := make(chan *message, 1)
	c.pending <- replyCh
	if err := c.write(line); err != nil {
		return nil, err
	}
	select {
	case reply := <-replyCh:
		if reply == nil {
			return nil, c.errOr(ErrClosed)
		}
		return reply, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.done:
		return nil, c.errOr(ErrClosed)
	}
}

func (c *Client) errOr(fallback error) error {
	if c.err != nil {
		return c.err
	}
	return fallback
}

func (c *Client) write(line string) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	_, err := io.WriteString(c.conn, line+"\n\n")
	if err != nil {
		c.fail(fmt.Errorf("esl: write: %w", err))
	}
	return err
}

func (c *Client) fail(err error) {
	c.errOnce.Do(func() {
		c.err = err
		close(c.done)
	})
}

func (c *Client) readLoop() {
	defer close(c.events)
	defer func() {
		// Anyone waiting on a reply gets a nil, which command() turns into Err().
		for {
			select {
			case ch := <-c.pending:
				ch <- nil
			default:
				return
			}
		}
	}()

	for {
		m, err := c.readMessage()
		if err != nil {
			c.fail(fmt.Errorf("esl: read: %w", err))
			c.conn.Close()
			return
		}
		switch m.contentType() {
		case "command/reply", "api/response":
			select {
			case ch := <-c.pending:
				ch <- m
			default:
				// A reply nobody asked for; the protocol does not do that, but
				// dropping it is better than blocking the event stream.
			}
		case "text/event-json":
			ev, err := decodeEvent(m.body)
			if err != nil {
				continue
			}
			select {
			case c.events <- ev:
			default:
				// The consumer is not keeping up. Events are advisory for this
				// service (a missed register is caught by the next one, a missed
				// park by the dialplan's own fallback), so drop rather than stall.
			}
		case "text/disconnect-notice":
			c.fail(fmt.Errorf("esl: server disconnected: %s", strings.TrimSpace(m.body)))
			c.conn.Close()
			return
		}
	}
}

// decodeEvent turns the JSON body into a flat Event. FreeSWITCH serialises a
// multi-valued header as a JSON array - `variable_DP_MATCH` on any channel
// that went through a dialplan regex is one - and a decode into
// map[string]string rejects the whole event for it, which is how the first
// version of this client lost every CHANNEL_PARK. Arrays are joined with
// commas; scalars are printed.
func decodeEvent(body string) (Event, error) {
	var raw map[string]any
	if err := json.Unmarshal([]byte(body), &raw); err != nil {
		return nil, err
	}
	ev := make(Event, len(raw))
	for name, value := range raw {
		switch v := value.(type) {
		case string:
			ev[name] = v
		case []any:
			parts := make([]string, 0, len(v))
			for _, item := range v {
				parts = append(parts, fmt.Sprint(item))
			}
			ev[name] = strings.Join(parts, ",")
		case nil:
			ev[name] = ""
		default:
			ev[name] = fmt.Sprint(v)
		}
	}
	return ev, nil
}

// readMessage reads one `Header: value` block terminated by a blank line, then
// Content-Length bytes of body if that header is present.
func (c *Client) readMessage() (*message, error) {
	m := &message{headers: map[string]string{}}
	for {
		line, err := c.reader.ReadString('\n')
		if err != nil {
			return nil, err
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			if len(m.headers) == 0 {
				continue // stray blank line between messages
			}
			break
		}
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		m.headers[strings.TrimSpace(name)] = strings.TrimSpace(value)
	}
	if lengthText, ok := m.headers["Content-Length"]; ok {
		length, err := strconv.Atoi(lengthText)
		if err != nil {
			return nil, fmt.Errorf("bad Content-Length %q", lengthText)
		}
		body := make([]byte, length)
		if _, err := io.ReadFull(c.reader, body); err != nil {
			return nil, err
		}
		m.body = string(body)
	}
	return m, nil
}
