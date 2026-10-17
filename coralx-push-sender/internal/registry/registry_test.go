package registry

import (
	"os"
	"path/filepath"
	"strings"
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

// contactFor builds a stored contact for one user under one FCM sender id. The
// token is fake: a registration token names a device and real ones never appear
// in tests (ADR-004 §7).
func contactFor(user, senderID, prid string) string {
	return `"" <sip:` + user + `@192.168.20.56:5060;ob;pn-provider=fcm;pn-param=` + senderID +
		`;pn-prid=` + prid + `;pn-type=firebase;pn-silent=1>`
}

const (
	ourSender     = "273934559014" // whatsapp-39dcb
	foreignSender = "58415781151"  // another product on the same SIP domain
)

// The isolation that matters on a shared SIP domain: FCM HTTP v1 is
// project-scoped, so a token minted under another sender id is one this service
// cannot address and must not store. Extension 4038 on the live deployment is
// exactly this case.
func TestObserveRejectsATokenFromAnotherFCMProject(t *testing.T) {
	r, err := New("", WithSenderID(ourSender))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := r.Observe("4038", "192.168.20.56", contactFor("4038", foreignSender, "FAKE-FOREIGN"), time.Now()); ok {
		t.Fatal("a token from another FCM project must not be stored")
	}
	if _, ok := r.Lookup("4038"); ok {
		t.Fatal("the foreign account must not be reachable through this registry")
	}
	if _, ok := r.Observe("4006", "192.168.20.56", contactFor("4006", ourSender, "FAKE-OURS"), time.Now()); !ok {
		t.Fatal("our own project's token must still be accepted")
	}
}

// Unset means unchanged: a deployment that has not configured a sender id keeps
// the behaviour it had before the restriction existed.
func TestObserveAcceptsEveryProjectWhenNoSenderIDIsSet(t *testing.T) {
	r, err := New("")
	if err != nil {
		t.Fatal(err)
	}
	for _, sender := range []string{ourSender, foreignSender} {
		if _, ok := r.Observe("4006", "192.168.20.56", contactFor("4006", sender, "FAKE"), time.Now()); !ok {
			t.Fatalf("sender %s should be accepted when none is configured", sender)
		}
	}
}

// A store written before the restriction must not keep another project's device
// reachable once the restriction is turned on.
func TestForeignTokensArePrunedOnLoad(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens.json")
	open, err := New(path)
	if err != nil {
		t.Fatal(err)
	}
	open.Observe("4006", "192.168.20.56", contactFor("4006", ourSender, "FAKE-OURS"), time.Now())
	open.Observe("4038", "192.168.20.56", contactFor("4038", foreignSender, "FAKE-FOREIGN"), time.Now())

	restricted, err := New(path, WithSenderID(ourSender))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := restricted.Lookup("4038"); ok {
		t.Fatal("a persisted foreign token must be dropped on load")
	}
	if _, ok := restricted.Lookup("4006"); !ok {
		t.Fatal("our own persisted token must survive the load")
	}
	// The prune is written back: the foreign device's token must not stay on disk.
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "FAKE-FOREIGN") {
		t.Fatal("the pruned token is still in the persisted file")
	}
	if !strings.Contains(string(raw), "FAKE-OURS") {
		t.Fatal("the surviving token was lost from the persisted file")
	}
}

// The start-up seed goes through Observe, so it inherits the same filter.
func TestSeedingFromShowRegistrationsAppliesTheSenderFilter(t *testing.T) {
	r, err := New("", WithSenderID(ourSender))
	if err != nil {
		t.Fatal(err)
	}
	csv := "reg_user,realm,token,url\n" +
		"4006,192.168.20.56,tok,sofia/internal/sip:4006@h:5060;pn-provider=fcm;pn-param=" + ourSender + ";pn-prid=FAKE-OURS\n" +
		"4038,192.168.20.56,tok,sofia/internal/sip:4038@h:5060;pn-provider=fcm;pn-param=" + foreignSender + ";pn-prid=FAKE-FOREIGN\n"
	if n := r.ParseShowRegistrations(csv, time.Now()); n != 1 {
		t.Fatalf("expected exactly one eligible registration, got %d", n)
	}
	if _, ok := r.Lookup("4038"); ok {
		t.Fatal("seeding must not import another project's token")
	}
}

func TestSenderAllowed(t *testing.T) {
	unrestricted, _ := New("")
	if !unrestricted.SenderAllowed(foreignSender) {
		t.Fatal("with no sender id configured every project is allowed")
	}
	restricted, _ := New("", WithSenderID(ourSender))
	if restricted.SenderAllowed(foreignSender) {
		t.Fatal("a foreign sender id must not be allowed")
	}
	if !restricted.SenderAllowed(ourSender) {
		t.Fatal("our own sender id must be allowed")
	}
}
