# 01 — Server layout, startup and the IP rule

## Where everything is

```
/usr/local/freeswitch/
├── bin/
│   ├── freeswitch            the daemon; running as: freeswitch -nc -nonat
│   ├── fs_cli                the console client (talks to mod_event_socket on 8021)
│   ├── lan-ip.sh             prints the IPv4 address SIP binds to (en0 first) — see below
│   ├── gentls_cert           would make a SIP TLS certificate; has not been run
│   └── fs_encode, fs_ivrd, fs_tts, fsxs, switch_eavesdrop, tone2wav
├── etc/freeswitch/           THE CONFIG ROOT (there is no /usr/local/freeswitch/conf)
│   ├── freeswitch.xml        top level: includes vars.xml, then every section below
│   ├── vars.xml              global $${variables}: lan_ip, domain, ports, codec prefs
│   ├── autoload_configs/     one file per module (modules.conf.xml, event_socket.conf.xml, …)
│   ├── sip_profiles/         internal.xml (5060), external.xml (5080), *-ipv6.xml
│   │   └── external/         gateway definitions for the external profile (only example.xml)
│   ├── directory/default.xml the SIP domain; includes directory/default/*.xml (users)
│   ├── dialplan/             default.xml, public.xml, features.xml, coralx.xml, skinny-patterns.xml
│   │   └── default/          the pieces of the default context, included alphabetically
│   ├── tls/                  dtls-srtp.pem, wss.pem — NO SIP TLS certificate
│   ├── ivr_menus/, lang/, yaml/, mime.types, extensions.conf (unused Asterisk-style dialplan)
│   └── *.bak.<timestamp>     hand-made backups of edited files (see 08-changelog.md)
├── lib/freeswitch/mod/       the *.so modules that exist (not the same list as modules.conf.xml)
├── share/freeswitch/sounds/  prompts (en/us/callie) and hold music sources
├── tools/
│   └── fs-rebind.sh          re-points SIP at the current IP after a network change
└── var/
    ├── log/freeswitch/freeswitch.log      debug-level log; freeswitch.xml.fsxml = the last parsed XML
    ├── lib/freeswitch/db/                 sqlite: sofia_reg_internal.db, core.db, voicemail_default.db, …
    ├── lib/freeswitch/recordings/         record-path of the internal profile
    └── lib/freeswitch/storage/            voicemail messages
```

The source checkout that matches the installed build (tag `v1.10.11`, git `f24064f`) is at
`~/Documents/GitHub/freeswitch`; it is where to read `mod_dptools.c` or `sofia_reg.c` when
the documentation and the behaviour disagree.

## How the XML is assembled

`freeswitch.xml` is small: it pulls in `vars.xml` first (so `$${…}` globals exist), then one
`<section>` per subsystem, each of which is an `X-PRE-PROCESS include` of a glob:

| Section | Included from | Notes |
|---|---|---|
| configuration | `autoload_configs/*.xml` | one `<configuration name="x.conf">` per module |
| dialplan | `dialplan/*.xml` | each file holds one or more `<context>`; `default.xml` itself includes `default/*.xml` at its **end** |
| directory | `directory/*.xml` | `default.xml` is the one domain; it includes `default/*.xml` (users) |
| phrases, chatplan, languages | `lang/`, `chatplan/` | stock |

Includes are expanded in **alphabetical order**, which is why the project's dialplan files
carry numeric prefixes (`00_…`, `01_…`, `03_…`). The fully expanded result of the last parse
is written to `var/log/freeswitch/freeswitch.xml.fsxml` — the fastest way to see what
FreeSWITCH actually read.

`fs_cli -x reloadxml` re-parses everything, including the `X-PRE-PROCESS exec-set` that
runs `lan-ip.sh`. It does **not** rebind sockets; a SIP profile must be restarted for that
(`sofia profile internal restart`) and the event socket needs a process restart.

## Starting and stopping

There is no launchd unit. The server is started by hand from the install root:

```bash
cd /usr/local/freeswitch && nohup ./bin/freeswitch -nonat -nc > /tmp/fs-start.log 2>&1 &
```

* `-nc` — no console; logs go to `var/log/freeswitch/freeswitch.log`.
* `-nonat` — do not ask the router for UPnP/NAT-PMP mappings. Keep it: the handsets are on
  the same LAN and a router pinhole would expose 5060 to the internet.

Stop with `fs_cli -x shutdown`. A profile restart (`sofia profile internal restart`) is
enough after a config change to the profile; a **process** restart is needed in two known
cases, both handled by `tools/fs-rebind.sh`: a leaked TCP listener ("Address already in
use" on TCP but not UDP) and a blank bind address (SIP detected while the interface was
down, `maddr=` empty).

Check it is up:

```bash
/usr/local/freeswitch/bin/fs_cli -x status
/usr/local/freeswitch/bin/fs_cli -x "sofia status"            # internal profile RUNNING on the right IP?
/usr/local/freeswitch/bin/fs_cli -x 'eval $${domain}'         # the SIP domain right now
```

## The IP rule — the thing that has broken most often

FreeSWITCH binds SIP to **one specific address chosen when the XML is parsed**, and the SIP
domain (`$${domain}`) is captured at the same moment. Two configurations were tried and
both failed on this Mac:

* `sip-ip="auto"` follows `$${local_ip_v4}`, which follows the **default route** — on this
  machine a USB-tethered interface, not the Wi-Fi the handsets are on. Registrations timed
  out with no response.
* A hardcoded address stopped being local the moment the Wi-Fi changed, and sofia refused
  to bind: *"The IP the profile is attempting to bind to is not local to this system"*.

What is in place now (`vars.xml` lines 69–71, `sip_profiles/internal.xml` lines 34–46):

```xml
<X-PRE-PROCESS cmd="exec-set" data="lan_ip=/usr/local/freeswitch/bin/lan-ip.sh"/>
<X-PRE-PROCESS cmd="set" data="domain=$${lan_ip}"/>
<X-PRE-PROCESS cmd="set" data="domain_name=$${domain}"/>
…
<param name="sip-ip"     value="$${lan_ip}"/>
<param name="rtp-ip"     value="$${lan_ip}"/>
<param name="ext-sip-ip" value="$${lan_ip}"/>
<param name="ext-rtp-ip" value="$${lan_ip}"/>
```

`lan-ip.sh` prefers **en0** (Wi-Fi), then the default-route interface, then `127.0.0.1` so
the server still starts. Because the value is re-evaluated on every `reloadxml`, a network
change is fixed with no edit:

```bash
/usr/local/freeswitch/tools/fs-rebind.sh
```

which runs `reloadxml` + `sofia profile internal restart`, falls back to a full process
restart if 5060/TCP is still not bound on the new address, and finishes by printing the
address clients must use. **Consequences for the app:** the SIP domain in every account is
the Mac's current LAN IP (`192.168.2.196` at home, `192.168.0.101` on the office Wi-Fi as of
September 2026); a phone that shows *Reconnecting…* after the Mac moved network is
behaving correctly.

## Databases

All sqlite, under `var/lib/freeswitch/db/`:

| File | Holds | Useful query |
|---|---|---|
| `sofia_reg_internal.db` | the internal profile's registrations, subscriptions, dialogs | `sqlite3 … "select sip_user, contact, expires from sip_registrations"` |
| `core.db` | channels, calls, the core `registrations` table (what `show registrations` prints), tasks | `show channels`, `show registrations` via `fs_cli` are easier |
| `voicemail_default.db` | voicemail boxes for the `default` profile | — |
| `call_limit.db`, `fifo.db` | mod_hash limits, mod_fifo queues | — |

Registration rows are **not** cleared when the profile or the process restarts — nothing in
`mod_sofia` deletes inbound registrations on start-up, only their expiry does (or
`sofia profile internal flush_inbound_reg`). A row therefore proves nothing about
reachability — see
[07-operations-cheatsheet.md](07-operations-cheatsheet.md#is-that-phone-really-reachable).

## Logging

`autoload_configs/logfile.conf.xml`: everything from `debug` up goes to
`var/log/freeswitch/freeswitch.log`, rotated at 1 GB (`rollover=1048576000`), 32 files kept,
`rotate-on-hup=true`. `switch.conf.xml` sets the core `loglevel=debug`. Every line is
prefixed with the channel UUID (`uuid=true`), so the whole life of a call is
`grep <uuid> freeswitch.log`; the SIP `Call-ID` appears in the same lines as
`variable_sip_call_id`. Sofia's own SIP trace is off (`sip-trace=no`, `debug=0`) and is
turned on per profile when needed — see the cheat sheet.
