# 02 — The `internal` SIP profile

File: `sip_profiles/internal.xml` (backup of the vanilla file:
`internal.xml.bak.20260908-103231`). This is the profile every Coral X handset registers
against. The `external` profile (5080, no auth, for trunks) is summarised at the end.

Every parameter below is **active** in the file (comments stripped) as of 2026-09-13.
Where a value is `$${…}` the resolved value is given in the third column.

## Binding and addressing

| Parameter | Value | Resolved / meaning |
|---|---|---|
| `sip-port` | `$${internal_sip_port}` | **5060**, UDP and TCP |
| `sip-ip`, `rtp-ip` | `$${lan_ip}` | the address `bin/lan-ip.sh` prints (en0). Edited from `auto` — see [01](01-server-layout-and-startup.md#the-ip-rule--the-thing-that-has-broken-most-often) |
| `ext-sip-ip`, `ext-rtp-ip` | `$${lan_ip}` | same address advertised in Contact/SDP — there is no NAT between server and handsets |
| `tls` | `$${internal_ssl_enable}` = **false** | TLS 5061 (`tls-sip-port`) is configured but off; `tls/` holds no SIP certificate. Accounts must use UDP or TCP |
| `tls-only`, `tls-verify-policy`, `tls-version`, `tls-ciphers` | false / none / `$${sip_tls_version}` / `$${sip_tls_ciphers}` | irrelevant while `tls=false` |
| `ws-binding`, `wss-binding` | `:5066`, `:7443` | WebSocket listeners for browser clients; unused by the app |
| `apply-nat-acl` | `nat.auto` | RFC 1918 sources are treated as behind NAT → registrations show `Registered(UDP-NAT)` and `fs_nat=yes` even on the same LAN. Harmless; it is why FreeSWITCH replies to the source address/port rather than the Contact |
| `apply-inbound-acl` | `domains` | INVITEs from an address in the `domains` ACL skip digest auth. That list is `deny` by default plus `allow domain=$${domain}` (= every *currently registered* endpoint) and `allow 192.168.20.0/24` — see ACLs below |
| `local-network-acl` | `localnet.auto` | stock |

## Authentication and registration

| Parameter | Value | Why it matters to the app |
|---|---|---|
| `auth-calls` | `$${internal_auth_calls}` = **true** | an INVITE from an unregistered source gets `407` and must carry `Proxy-Authorization` |
| `auth-subscriptions` | true | SUBSCRIBE is challenged too |
| `auth-all-packets` | false | only INVITE/REGISTER/SUBSCRIBE are challenged |
| `challenge-realm` | `auto_from` | the realm in `401`/`407` is the host part of the From URI (`Auth-Realm: 192.168.2.196` in `sofia status … reg`). The app answers any realm — its credentials are created with realm `*` (`AccountConfigFactory.kt`) — so this only matters for clients that pin a realm |
| `nonce-ttl` | 60 | a nonce is valid 60 s; PJSIP re-authenticates transparently |
| `inbound-reg-force-matching-username` | true | the auth username must equal the SIP user (`1001` registers as `1001`). The app's *auth username* field, if set, must therefore be the extension |
| `force-register-domain`, `force-register-db-domain`, `force-subscription-domain` | `$${domain}` | whatever domain a REGISTER names, it is stored under `$${domain}`. This is what makes `sofia_contact(*/1001@$${domain})` find the phone regardless of what the phone put in its To/From host |
| `log-auth-failures` | false | wrong passwords are **not** logged; turn on when debugging `401` loops |
| `context` | `public` | the context for calls that pass the ACL *without* a user. Registered users override it with their directory `user_context=default` |
| `dialplan` | `XML` | — |
| `manage-presence` | true | — |
| `presence-hosts` | `$${domain},$${local_ip_v4}` | — |
| `presence-privacy` | `$${presence_privacy}` | stock |

**Not set: `multiple-registrations`.** Consequence: a fresh REGISTER for a user with a
different Call-ID **replaces** the old row (`delete from sip_registrations where sip_user=…`
in `sofia_reg.c`). One phone per extension; a second device on the same extension steals it.
The push-wake design relies on this (the woken app's new registration replaces the stale
one).

## Codecs and media

| Parameter | Value | Meaning |
|---|---|---|
| `inbound-codec-prefs`, `outbound-codec-prefs` | `$${global_codec_prefs}` = **`PCMU,PCMA,VP8`** | edited from the vanilla `OPUS,G722,PCMU,PCMA,H264,VP8` on 2026-09-07 because those modules do not exist here — [06](06-modules-and-codecs.md) |
| `inbound-codec-negotiation` | `generous` | the callee may pick any codec the caller offered that the server knows |
| `inbound-late-negotiation` | **true** | the SDP is not negotiated until the callee answers, so a bridged call can pass the callee's choice back. Required for the `00_whatsapp_v2_bypass.xml` media-bypass test |
| `rfc2833-pt` | 101 | DTMF payload type — matches the app's `telephone-event/8000` |
| `dtmf-duration` | 2000 | — |
| `rtp-timer-name` | `soft` | — |
| `rtp-timeout-sec` | 300 | a leg with no RTP for 5 minutes is hung up (`MEDIA_TIMEOUT`) |
| `rtp-hold-timeout-sec` | 1800 | 30 minutes on hold |
| `hold-music` | `$${hold_music}` = `local_stream://moh` | **silent today**: `mod_local_stream` is not loaded ([06](06-modules-and-codecs.md)) |
| `record-path`, `record-template` | `$${recordings_dir}`, `${caller_id_number}.${target_domain}.${strftime(…)}.wav` | server-side recording, if a dialplan ever calls `record_session`; the app records on the device |

**SRTP.** There is no `rtp-secure-media` parameter. Measured on 2026-09-13 with a scripted
INVITE to 9196: an offer with `a=crypto` on an **`RTP/AVP`** m-line is answered
`488 Not Acceptable Here`; the same offer without it is answered `200`. This is the same
behaviour the office server shows. The app's account must therefore have media encryption
**disabled** (or use a proper `RTP/SAVP` offer) against this server; `SrtpPolicy.OPTIONAL`
with `a=crypto` on AVP does not connect.

## Timers, watchdog, tracing

| Parameter | Value |
|---|---|
| `debug`, `sip-trace`, `sip-capture` | 0 / no / no — turn on with `sofia profile internal siptrace on` (see the cheat sheet) |
| `watchdog-enabled` | no |
| `forward-unsolicited-mwi-notify` | false |

## ACLs (`autoload_configs/acl.conf.xml`)

| List | Default | Nodes | Used by |
|---|---|---|---|
| `lan` | allow | deny `192.168.42.0/24`, allow `192.168.42.42/32` | stock example, unused |
| `domains` | **deny** | allow `domain=$${domain}` (dynamic: the IPs of registered users), allow `192.168.20.0/24` | `apply-inbound-acl` on the internal profile. The `/24` is a local addition — it lets the `5603@192.168.20.56` trunk in `dialplan/default.xml` call in without auth |

## What the app must be configured with, and why

| Account field in Coral X | Value for this server | The profile line that dictates it |
|---|---|---|
| Username / extension | `1000`–`1017` by hand, `1018`/`1019` automation only | directory, `docs/testing.md` |
| Auth username | empty (defaults to the username) | `inbound-reg-force-matching-username=true` |
| Domain | the Mac's current LAN IP (`fs_cli -x 'eval $${domain}'`) — it is where the REGISTER is sent | `sip-ip=$${lan_ip}`; `force-register-domain` stores it under `$${domain}` whatever the phone wrote |
| Registrar / proxy | empty (the domain is the registrar) | — |
| Transport | UDP (TCP also works) — **not TLS** | `tls=false` |
| Media encryption | **disabled** | the `488` on `a=crypto`/`RTP/AVP` above |
| Codecs | PCMU, PCMA; VP8 for video | `global_codec_prefs`, [06](06-modules-and-codecs.md) |
| Registration expiry | the account's value (30–86400 s, default 3600 — `SipAccount.DEFAULT_EXPIRY_SECONDS`) | the profile accepts it as sent; `nonce-ttl` does not limit it |

## The `external` profile in one paragraph

`sip_profiles/external.xml`: port `$${external_sip_port}` = **5080**, `auth-calls=false`,
`context=public`, `sip-ip`/`rtp-ip` = `$${local_ip_v4}` (the default-route address, *not*
`lan_ip`), `ext-*-ip` from STUN (`stun:stun.freeswitch.org` in `vars.xml`), same codec
prefs, `inbound-late-negotiation=true`. It exists for carrier/trunk traffic and has one
example gateway (`external/example.xml`, not registered). The only thing in the project's
dialplan that touches it is the `agent_5603` extension, which bridges to
`sofia/external/5603@192.168.20.56:5060`. Handsets should never register here.
