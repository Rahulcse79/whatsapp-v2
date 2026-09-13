package esl

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// fakeServer speaks just enough of the inbound protocol to exercise the client:
// auth, one subscription, `api` echoes, and an event pushed on demand.
type fakeServer struct {
	listener net.Listener
	accepted chan net.Conn
	events   chan string
}

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &fakeServer{listener: l, accepted: make(chan net.Conn, 1), events: make(chan string, 8)}
	go s.serve(t)
	t.Cleanup(func() { l.Close() })
	return s
}

func (s *fakeServer) serve(t *testing.T) {
	conn, err := s.listener.Accept()
	if err != nil {
		return
	}
	s.accepted <- conn
	fmt.Fprint(conn, "Content-Type: auth/request\n\n")
	r := bufio.NewReader(conn)
	go func() {
		for body := range s.events {
			fmt.Fprintf(conn, "Content-Length: %d\nContent-Type: text/event-json\n\n%s", len(body), body)
		}
	}()
	for {
		line, err := readCommand(r)
		if err != nil {
			return
		}
		switch {
		case strings.HasPrefix(line, "auth "):
			if line == "auth ClueCon" {
				fmt.Fprint(conn, "Content-Type: command/reply\nReply-Text: +OK accepted\n\n")
			} else {
				fmt.Fprint(conn, "Content-Type: command/reply\nReply-Text: -ERR invalid\n\n")
			}
		case strings.HasPrefix(line, "event json "):
			fmt.Fprint(conn, "Content-Type: command/reply\nReply-Text: +OK event listener enabled json\n\n")
		case strings.HasPrefix(line, "api "):
			body := "+OK echo " + strings.TrimPrefix(line, "api ") + "\n"
			if strings.Contains(line, "fail") {
				body = "-ERR No such channel!\n"
			}
			fmt.Fprintf(conn, "Content-Type: api/response\nContent-Length: %d\n\n%s", len(body), body)
		}
	}
}

func readCommand(r *bufio.Reader) (string, error) {
	var lines []string
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", err
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			if len(lines) == 0 {
				continue
			}
			return strings.Join(lines, "\n"), nil
		}
		lines = append(lines, line)
	}
}

func TestDialAuthenticatesAndRunsCommands(t *testing.T) {
	s := newFakeServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, err := Dial(ctx, s.listener.Addr().String(), "ClueCon")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	if err := c.Subscribe(ctx, "CHANNEL_PARK", "CUSTOM sofia::register"); err != nil {
		t.Fatalf("subscribe: %v", err)
	}
	body, err := c.API(ctx, "uuid_transfer abc 1001 XML coralx-resume")
	if err != nil {
		t.Fatalf("api: %v", err)
	}
	if body != "+OK echo uuid_transfer abc 1001 XML coralx-resume" {
		t.Fatalf("unexpected api body %q", body)
	}
	if _, err := c.API(ctx, "uuid_transfer fail"); err == nil {
		t.Fatal("a -ERR body must surface as an error")
	}
}

func TestEventsAreDecodedFromJSON(t *testing.T) {
	s := newFakeServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, err := Dial(ctx, s.listener.Addr().String(), "ClueCon")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	s.events <- `{"Event-Name":"CUSTOM","Event-Subclass":"sofia::register","from-user":"1001","contact":"\"\" <sip:1001@10.0.0.5:5060;ob;pn-prid=tok>"}`
	select {
	case ev := <-c.Events():
		if ev.Name() != "CUSTOM" || ev.Subclass() != "sofia::register" || ev["from-user"] != "1001" {
			t.Fatalf("unexpected event %v", ev)
		}
		if !strings.Contains(ev["contact"], "pn-prid=tok") {
			t.Fatalf("contact not decoded verbatim: %q", ev["contact"])
		}
	case <-ctx.Done():
		t.Fatal("no event arrived")
	}
}

func TestMultiValuedHeadersDoNotDropTheEvent(t *testing.T) {
	// variable_DP_MATCH is an array on every channel that matched a dialplan
	// regex, which is every channel the sender cares about.
	ev, err := decodeEvent(`{"Event-Name":"CHANNEL_PARK","Unique-ID":"u1","variable_DP_MATCH":["1005","1005"],"n":1}`)
	if err != nil {
		t.Fatal(err)
	}
	if ev.Name() != "CHANNEL_PARK" || ev.UUID() != "u1" || ev["variable_DP_MATCH"] != "1005,1005" || ev["n"] != "1" {
		t.Fatalf("unexpected decode: %v", ev)
	}
}

func TestWrongPasswordIsRefused(t *testing.T) {
	s := newFakeServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := Dial(ctx, s.listener.Addr().String(), "wrong"); err == nil {
		t.Fatal("expected an authentication error")
	}
}

func TestServerGoingAwayEndsTheClient(t *testing.T) {
	s := newFakeServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, err := Dial(ctx, s.listener.Addr().String(), "ClueCon")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	// The server side of the connection goes away under the client.
	(<-s.accepted).Close()

	for range c.Events() {
		// drain until closed
	}
	if c.Err() == nil || c.Err() == io.EOF {
		t.Fatalf("expected a wrapped read error, got %v", c.Err())
	}
	if _, err := c.API(ctx, "status"); err == nil {
		t.Fatal("a command after the connection ended must fail")
	}
}
