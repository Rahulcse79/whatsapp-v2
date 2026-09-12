# 07 — Operations cheat sheet

`fs_cli` is `/usr/local/freeswitch/bin/fs_cli` (not on `PATH`). `fs_cli -x "<command>"` runs
one command and exits; plain `fs_cli` opens the console (`/quit` to leave). Everything
below assumes `alias fs_cli=/usr/local/freeswitch/bin/fs_cli`.

## Is the server up, and on which address?

```bash
fs_cli -x status                                  # version, uptime, sessions, max-sessions
fs_cli -x "sofia status"                          # profiles: internal must be RUNNING (0) on the LAN IP
fs_cli -x "sofia status profile internal" | head  # bind URLs, codecs, NAT settings as loaded
fs_cli -x 'eval $${domain}'                       # the SIP domain right now = what the app must use
ipconfig getifaddr en0                            # what lan-ip.sh will pick next time
```

The Mac changed network → `/usr/local/freeswitch/tools/fs-rebind.sh` (see
[01](01-server-layout-and-startup.md#the-ip-rule--the-thing-that-has-broken-most-often)).

## Reload after editing config

| You edited | Run |
|---|---|
| anything under `dialplan/`, `directory/`, `vars.xml`, `autoload_configs/*.conf.xml` that a module re-reads | `fs_cli -x reloadxml` |
| `sip_profiles/internal.xml` | `fs_cli -x reloadxml` then `fs_cli -x "sofia profile internal rescan"` (adds gateways only) or `… restart` (rebinds — drops calls on that profile) |
| `vars.xml` → `lan_ip`/`domain` | `tools/fs-rebind.sh` |
| `modules.conf.xml` | restart the process, or `fs_cli -x "load mod_x"` for one module |
| `event_socket.conf.xml` | restart the process |

`reloadxml` prints `+OK [Success]`; a parse error names the file and line. The result of
the last successful parse is `var/log/freeswitch/freeswitch.xml.fsxml`.

## Is that phone really reachable?

A registration row proves that a REGISTER was accepted up to an hour ago, nothing more.
Three views of the same table, and what each is good for:

```bash
fs_cli -x "sofia status profile internal reg"          # human: Call-ID, Contact, Agent, EXP, Ping-Status
fs_cli -x "sofia status profile internal reg 1001"     # one user
fs_cli -x "show registrations"                          # CSV: reg_user,realm,token,url,expires,network_ip,network_port
fs_cli -x "sofia_contact 1001@$(fs_cli -x 'eval $${domain}')"   # the dial string a bridge would use, or error/user_not_registered
```

* **Compare the contact port across time.** A Coral X process that restarted (reinstall,
  crash, swipe) binds a new random UDP port; the old row stays until it expires. Same
  port after a reinstall = you are looking at a stale row.
* **`Ping-Status: Reachable` is not measured** unless `nat-options-ping`/`all-reg-options-ping`
  is on (it is not), so ignore it.
* **The proof is a call**: `originate` from the console and watch the log (below), or
  `curl -s -X POST localhost:8085/push -d '{"account_id":"1001"}'` and watch for a new
  contact port (push wake, [05](05-push-wake.md)).
* Drop a stale row: `fs_cli -x "sofia profile internal flush_inbound_reg 1001@<domain>"`
  (a bare argument is treated as a Call-ID; `user@domain` deletes that user's rows;
  `@domain` the whole domain). Adding `reboot` also sends a `check-sync` NOTIFY, which
  PJSIP ignores — with the app, use *Register now* on the account-status screen instead.

## Place or end a call from the console

```bash
# ring 1001 from the server and, when answered, connect it to the echo test
fs_cli -x "originate user/1001@$(fs_cli -x 'eval $${domain}') &echo"
# ring 1001 and route the answered leg through the dialplan as if it had dialled 9196
fs_cli -x "originate {origination_caller_id_number=1003}user/1001@$(fs_cli -x 'eval $${domain}') 9196 XML default"
fs_cli -x "show channels"                 # live legs: uuid, state, codecs, application
fs_cli -x "show calls"                    # bridged pairs
fs_cli -x "uuid_kill <uuid>"              # end one leg
fs_cli -x hupall                          # end everything
```

`originate … 9196 XML default` is how a test call is made with **no second handset**.

## Read the log

```bash
LOG=/usr/local/freeswitch/var/log/freeswitch/freeswitch.log
tail -f $LOG                                              # everything, debug level — noisy
grep <uuid> $LOG                                          # one channel's whole life (every line is prefixed with its UUID)
grep -n "Dialplan:" $LOG | grep <uuid>                    # every regex the walk tested: Regex (PASS|FAIL) [extension] …
grep "EXECUTE \[depth" $LOG | grep <uuid>                 # every application that ran, with its data
grep -E "push_wake|PROGRESS_TIMEOUT|USER_NOT_REGISTERED" $LOG | tail   # the wake path
grep "<sip-call-id>" $LOG                                 # find the channel UUID from a Call-ID seen in sngrep/the app log
grep -E "Hangup .*\[" $LOG | tail                         # hangup causes: [ORIGINATOR_CANCEL], [USER_BUSY], …
```

The channel UUID is what the push sender calls `call_id`, and what `uuid_*` commands take.

## Trace SIP

```bash
fs_cli -x "sofia profile internal siptrace on"    # full SIP messages into the log (and the console)
fs_cli -x "sofia profile internal siptrace off"
fs_cli -x "sofia loglevel all 9"                  # sofia-sip stack debugging; "sofia loglevel all 0" to stop
fs_cli -x "sofia global siptrace on"              # every profile
```

`sngrep` (built from source, in `~/.local/bin`) shows live call flows on the wire without
touching the server: `sngrep -d en0 port 5060`. It sees UDP fragments as separate packets,
which is how the 1675-byte INVITE was noticed.

## The event socket by hand

```bash
python3 - <<'PY'
import socket
s = socket.create_connection(("127.0.0.1", 8021)); f = s.makefile("rb")
def headers():                       # one message = header lines up to a blank line
    h = {}
    while (line := f.readline().decode().rstrip("\n")) != "":
        k, _, v = line.partition(":"); h[k.strip()] = v.strip()
    return h
headers()                            # Content-Type: auth/request
s.sendall(b"auth ClueCon\n\n"); print(headers()["Reply-Text"])
s.sendall(b"api sofia status\n\n"); h = headers()
print(f.read(int(h["Content-Length"])).decode())
PY
```

`fs_cli` itself is an ESL client; `fs_cli -x "…"` is `api …` over this socket. Event
subscriptions: `event plain CHANNEL_PARK CUSTOM sofia::register` (custom subclasses must
come **after** the word `CUSTOM`; anything after it is read as a subclass).

## Failures seen on this box, and the fix

| Symptom | Cause | Fix |
|---|---|---|
| Every REGISTER times out, no response at all | SIP bound to the wrong interface (`auto` = default route = USB tether) | `tools/fs-rebind.sh`; the config now uses `lan-ip.sh` |
| `The IP the profile is attempting to bind to is not local to this system` | the Mac moved network; the old address was captured at parse time | `tools/fs-rebind.sh` |
| `bind(): Address already in use` on TCP only | sofia leaked its TCP listener on the last profile stop | process restart (`fs-rebind.sh` does it) |
| `sip:mod_sofia@:5060; maddr=` with an empty address | detection ran while the interface was down | process restart after the network is up |
| `488 Not Acceptable Here`, log says `a=crypto in RTP/AVP, refer to rfc3711` | the app offered SDES crypto on an AVP m-line | media encryption **off** in the account |
| `ORIGINATOR_CANCEL` after 30 s, no `100 Trying` from the phone | the contact is stale: the process died or is dozing | that is what push wake is for; check the sender is running and `curl localhost:8085/tokens` knows the extension |
| Call to 1001 from the console lands on the app drawer / nowhere | you originated into a stale row right after a reinstall | wait for the new contact **port** in `sofia status … reg` first |
| Phone shows *Reconnecting…* after `svc wifi` toggles | the TC15 hopped to another saved SSID | check `adb shell ip -4 addr show wlan0` against the Mac's subnet |
| `NO_ROUTE_DESTINATION` / `404` | nothing in the context matched | `grep Dialplan: $LOG | grep <uuid>` shows every regex tried |
| `USER_NOT_REGISTERED` instantly | the row expired; with push wake this is normal for an asleep phone | the sender still has the token; the call parks and the wake proceeds |
| `*97` / `vm:NNNN` find no mailbox | the voicemail extensions hardcode the pre-September domain | see [04](04-dialplan.md) |
| Hold music is silence | `mod_local_stream` not loaded | [06](06-modules-and-codecs.md) |
| The push sender logs `waiting for auth/request: i/o timeout` right after a restart | the event socket was slow to greet the new connection | it reconnects on its own within 20 s |

## Security notes (do these before the server is reachable from anywhere but this LAN)

* `event_socket.conf.xml`: `listen-ip` is `::` (all interfaces) with the stock password.
  Bind it to `127.0.0.1` — the sender and `fs_cli` are local — and change the password
  (the sender takes `-esl-password`).
* `vars.xml` `default_password` is the stock value shared by all twenty users.
* `dialplan/default.xml` embeds a third-party API key in clear text inside `ai_voice_agent`.
* The `domains` ACL allows `192.168.20.0/24` in without authentication.
* `-nonat` is set; keep it, so the router is never asked to open 5060.
