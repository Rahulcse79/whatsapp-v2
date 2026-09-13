# coralx-push-sender

The push gateway for Coral X (`whatsapp-v2`), per its ADR-004: the piece that makes a
call ring a handset that is asleep, dozing, or swiped from Recents.

```
caller ──INVITE──▶ FreeSWITCH ──event coralx::push_wake──▶ push-sender ──FCM HIGH data──▶ handset
                       │  3 s direct attempt (a live app answers; a dead socket does not)      │
                       │  ring_ready + park                                                     ▼
                       ◀────────── uuid_transfer … coralx-resume ◀── sofia::register ◀── REGISTER
                       │  bridge → 180 Ringing → answer
                       └─ no REGISTER in 12 s → coralx-timeout → 486 Busy Here
```

"Registered" in FreeSWITCH is not "reachable": the INVITE to a dead contact gets no
`100 Trying` and dies as `ORIGINATOR_CANCEL`. The sender wakes the device with a
high-priority FCM data message, waits for the one thing that proves it is back — a
REGISTER that lands *after* the push — and only then lets the call through.

A plain-words walkthrough with diagrams — how every value is learned at runtime and the
four things you set per deployment — is in the client repository:
`docs/Freeswitch_configuration_docs/09-push-sender-how-it-works.md`.

## What it does

* **Token registry.** Reads the RFC 8599 `pn-provider` / `pn-param` / `pn-prid` URI
  parameters off every REGISTER (`sofia::register` events, seeded from
  `show registrations` at start-up, persisted to `data/tokens.json`). The token is never
  logged whole.
* **Wake.** On `coralx::push_wake` sends the ADR-004 data message — exactly
  `type=incoming_call`, `call_id` (the channel UUID), `account_id` (the SIP user),
  `sent_at` (epoch ms) — with `android.priority=HIGH`, no notification block, 30 s TTL.
  Idempotent per channel.
* **Park/resume.** When the caller parks, waits for the device's REGISTER and transfers
  the call to `coralx-resume` (bridge), or after `-wake-timeout` (12 s) to
  `coralx-timeout` (486). A dead token (`UNREGISTERED` from FCM) is forgotten and the
  caller is not kept waiting.
* **Operator API** on `127.0.0.1:8085`: `GET /healthz`, `GET /tokens`, `GET /calls`,
  `POST /push {"account_id":"1001"}` (sends the wake without a call — acceptance test 2).

No dependencies outside the Go standard library. FCM v1 auth is a service-account JWT.

## Install

### 1. FreeSWITCH (three files, all in `deploy/freeswitch/`)

| File | Goes to | What it does |
|---|---|---|
| `dialplan/default/01_coralx_push_wake.xml` | `etc/freeswitch/dialplan/default/` | for any number that is a directory user (`user_exists`): announce → 3 s direct attempt (`progress_timeout=3`, `continue_on_fail` limited to the "unreachable" causes) → `ring_ready` → `sched_transfer +15` (fallback if the sender is down) → `park` |
| `dialplan/coralx.xml` | `etc/freeswitch/dialplan/` | the `coralx-resume` (bridge) and `coralx-timeout` (486; voicemail variant commented) contexts |
| `directory/dial-string.snippet.xml` | replaces the `dial-string` param in `etc/freeswitch/directory/default.xml` | strips the `pn-*` parameters before dialling: sofia copies them into the Request-URI, Route and To, which took the INVITE from 1306 to 1675 bytes and fragmented it |

Then `fs_cli -x reloadxml`. Requirements: `mod_event_socket`, `mod_dptools`, `mod_commands`,
`mod_sofia` — all loaded in a stock build. **No `mod_lua` needed.** Notes:

* The `event` application must be given `Event-Name=CUSTOM`; without it FreeSWITCH fires
  a `CHANNEL_APPLICATION` event that a `CUSTOM` subscription never sees.
* Nothing in the three files names an IP address or an extension range: `$${domain}` is
  the server's own domain and `user_exists` asks the directory. Extensions with their own
  rule earlier in `default.xml` (on this machine `agent_1003`, `agent_1004`) never reach
  `01_coralx_push_wake.xml`; move them or add the same actions.
* Rollback: delete `01_coralx_push_wake.xml` (restore the previous rule from its
  `.bak.<timestamp>` if there was one), delete `coralx.xml`, restore the dial-string,
  `reloadxml`.

### 2. Firebase (once per deployment; never in git)

1. Create a Firebase project; add an Android app with package `com.whatsappv2`.
2. Download `google-services.json` and put it at `whatsapp-v2/app/google-services.json`
   (gitignored — the client build turns it into resources itself; the plugin stays off).
   Rebuild and install the client.
3. Project settings → Service accounts → *Generate new private key*. Save the JSON as
   `data/firebase-service-account.json` here (gitignored). It is the only place the key
   lives; the APK never carries it.

### 3. Run

```
go build -o bin/push-sender ./cmd/push-sender
./bin/push-sender -service-account data/firebase-service-account.json
```

Every flag has a `PUSH_SENDER_<FLAG>` environment variable; `-h` lists them. Without
`-service-account` it runs **dry**: everything except the FCM send works, which is how the
park/resume path is tested with a scripted SIP UA. `deploy/launchd/` has a user agent for
the Mac.

## Verify

```
curl -s localhost:8085/healthz        # {"esl":true,"fcm":true,"project":"…","tokens":1}
curl -s localhost:8085/tokens         # the handset's user, realm, and a redacted token
curl -s -X POST localhost:8085/push -d '{"account_id":"1001"}'
```

After the last one the handset must REGISTER within a few seconds: a new contact port in
`fs_cli -x "sofia status profile internal reg"` and `Push wake: LOGIN|REFRESH` in the
app's log. Then place a real call with the app swiped away; the sender log shows
`push_wake: sending FCM` → `caller parked` → `fresh REGISTER` → `call moved on resume=true`,
and the caller hears ringback throughout.

## Tests

`go test -race ./...` covers the ESL framing (including FreeSWITCH's array-valued JSON
headers, which silently dropped every `CHANNEL_PARK` in the first version), the token
parser against a real stored contact, the FCM wire shape (HIGH, data-only, the four keys,
token caching, `UNREGISTERED`), and the park/resume/timeout state machine with a fake
clock.
