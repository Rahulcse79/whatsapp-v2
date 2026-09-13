package registry

import (
	"path/filepath"
	"testing"
	"time"
)

// A contact exactly as FreeSWITCH 1.10.11 stores one from the Coral X client:
// URI parameters kept (`;ob` and the pn-* ones), fs_nat/fs_path appended.
const storedContact = `"" <sip:1001@192.168.2.191:46020;ob;pn-provider=fcm;pn-param=1234567890;pn-prid=dXcQ3mZ9Tq2:APA91bFt4-Xn_9yQ0aM2pO6r8s1U3v5W7x9Y1z3A5b7C9d1E3f5G7h9I1j3K5l7M9n;fs_nat=yes;fs_path=sip%3A1001%40192.168.2.191%3A46020%3Bob%3Bpn-provider%3Dfcm%3Bpn-param%3D1234567890%3Bpn-prid%3DdXcQ3mZ9Tq2%3AAPA91bFt4-Xn_9yQ0aM2pO6r8s1U3v5W7x9Y1z3A5b7C9d1E3f5G7h9I1j3K5l7M9n>`

const fcmToken = "dXcQ3mZ9Tq2:APA91bFt4-Xn_9yQ0aM2pO6r8s1U3v5W7x9Y1z3A5b7C9d1E3f5G7h9I1j3K5l7M9n"

func TestParseContactReadsTheURIParameters(t *testing.T) {
	provider, param, prid, ok := ParseContact(storedContact)
	if !ok {
		t.Fatal("expected the three pn-* parameters")
	}
	if provider != "fcm" || param != "1234567890" || prid != fcmToken {
		t.Fatalf("got %q %q %q", provider, param, prid)
	}
}

func TestParseContactAcceptsTheShowRegistrationsForm(t *testing.T) {
	_, _, prid, ok := ParseContact("sofia/internal/sip:1001@192.168.2.191:46020;ob;pn-provider=fcm;pn-param=1;pn-prid=abc%3Adef;fs_nat=yes")
	if !ok || prid != "abc:def" {
		t.Fatalf("ok=%v prid=%q", ok, prid)
	}
}

func TestParseContactIgnoresANonPushClient(t *testing.T) {
	if _, _, _, ok := ParseContact(`"" <sip:1003@192.168.2.190:37900;ob;fs_nat=yes>`); ok {
		t.Fatal("a contact without pn-* parameters is not a push client")
	}
	// Header parameters — what the first client version sent — do not count.
	if _, _, _, ok := ParseContact(`<sip:1003@192.168.2.190:37900;ob>;pn-provider=fcm;pn-param=1;pn-prid=x`); ok {
		t.Fatal("pn-* outside the URI must not be read as a token")
	}
}

func TestObserveKeepsTheLastPushClientPerUser(t *testing.T) {
	r, err := New("")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 9, 12, 20, 0, 0, 0, time.UTC)

	if _, ok := r.Observe("1001", "192.168.2.196", storedContact, now); !ok {
		t.Fatal("expected a token")
	}
	// A desk phone re-registering the same extension must not erase the token.
	if _, ok := r.Observe("1001", "192.168.2.196", `<sip:1001@10.0.0.9:5060>`, now.Add(time.Minute)); ok {
		t.Fatal("no token expected from a plain contact")
	}
	token, ok := r.Lookup("1001")
	if !ok || token.PRID != fcmToken || !token.SeenAt.Equal(now) {
		t.Fatalf("token lost or replaced: %+v", token)
	}

	r.Forget("1001")
	if _, ok := r.Lookup("1001"); ok {
		t.Fatal("forgotten token still present")
	}
}

func TestRegistrySurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state", "tokens.json")
	r, err := New(path)
	if err != nil {
		t.Fatal(err)
	}
	r.Observe("1001", "192.168.2.196", storedContact, time.Now())

	again, err := New(path)
	if err != nil {
		t.Fatal(err)
	}
	token, ok := again.Lookup("1001")
	if !ok || token.PRID != fcmToken {
		t.Fatalf("token not reloaded: %+v", token)
	}
}

func TestSeedingFromShowRegistrations(t *testing.T) {
	r, _ := New("")
	csv := "reg_user,realm,token,url,expires,network_ip,network_port,network_proto,hostname,metadata\n" +
		"1003,192.168.2.196,77dabcbe,sofia/internal/sip:1003@192.168.2.190:37900;ob;fs_nat=yes,1789228485,192.168.2.190,37900,udp,mac,\n" +
		"1001,192.168.2.196,9a04d780,sofia/internal/sip:1001@192.168.2.191:46020;ob;pn-provider=fcm;pn-param=12;pn-prid=tok-1;fs_nat=yes,1789228752,192.168.2.191,46020,udp,mac,\n" +
		"\n3 total.\n"

	if n := r.ParseShowRegistrations(csv, time.Now()); n != 1 {
		t.Fatalf("expected 1 token, got %d", n)
	}
	if token, ok := r.Lookup("1001"); !ok || token.PRID != "tok-1" || token.Host != "192.168.2.196" {
		t.Fatalf("unexpected token %+v", token)
	}
}

func TestRedactedNeverShowsTheWholeToken(t *testing.T) {
	token := Token{PRID: fcmToken}
	if got := token.Redacted(); got != "…3K5l7M9n" {
		t.Fatalf("redaction leaks or is wrong: %q", got)
	}
}
