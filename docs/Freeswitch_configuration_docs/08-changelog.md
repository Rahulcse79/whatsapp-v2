# 08 — Changelog: every deviation from a vanilla 1.10.11

Dates come from the backup-file suffixes where one exists and from file modification
times otherwise (`stat -f '%Sm'`). "Backup" is the copy of the file **before** the change,
kept next to it; restoring it is the rollback. Newest first.

| Date | File(s) | Change | Why | Backup |
|---|---|---|---|---|
| 2026-09-13 01:40 | `dialplan/default/01_coralx_push_wake.xml` (new), `dialplan/default/01_local_users_100x.xml` (removed), `dialplan/coralx.xml` | the push-wake hook and both contexts now match `${user_exists(id ${destination_number} $${domain})}` instead of `^(10[01][0-9])$` | one set of files for every deployment — no extension range to edit ([09](09-push-sender-how-it-works.md)) | `…/01_local_users_100x.xml.bak.20260913-014003`, `dialplan/coralx.xml.bak.20260913-014003` |
| 2026-09-12 22:11 | `directory/default.xml` | `dial-string` wrapped in two `regex()` passes that strip `pn-*` from the contact | the INVITE to a push-registered phone was 1675 bytes (fragmented); 1306 after — [05](05-push-wake.md) | `directory/default.xml.bak.20260912-221132` |
| 2026-09-12 21:53 | `dialplan/default/01_local_users_100x.xml` | single `bridge` → announce, 3 s direct attempt, `ring_ready`, `sched_transfer`, `park` | push wake (ADR-004) | `…/01_local_users_100x.xml.bak.20260912-215353` |
| 2026-09-12 21:53 | `dialplan/coralx.xml` | **new** — contexts `coralx-resume` and `coralx-timeout` | push wake | delete the file |
| 2026-09-11 13:09 | `dialplan/default/00_whatsapp_v2_bypass.xml` | **new** — 1001 ↔ 1002 bridged with `bypass_media=true` | let two Coral X phones negotiate a codec the server lacks (Lyra, ADR-008) | delete the file |
| 2026-09-11 02:09 | `dialplan/default/03_whatsapp_v2_test_apps.xml` | **new** — 9196 echo (audio+video), 9197 bridged echo, 9198 tone; 9199 left unrouted | self-answering far ends for one-handset testing (calls, video, transfer, conference) | delete the file |
| 2026-09-10 11:24 | `bin/lan-ip.sh`, `vars.xml`, `sip_profiles/internal.xml` | `lan_ip` resolved by the script on every parse (en0 → default route → 127.0.0.1); comments updated | the third attempt at surviving a network change — [01](01-server-layout-and-startup.md) | the 09-07/09-08 backups below |
| 2026-09-08 10:34 | `tools/fs-rebind.sh` | **new** — reloadxml + profile restart, full restart when TCP is still not bound | the leaked-TCP-listener and blank-address failure modes | delete the file |
| 2026-09-08 10:32 | `sip_profiles/internal.xml` | `sip-ip`, `rtp-ip`, `ext-sip-ip`, `ext-rtp-ip`: `auto` → `$${lan_ip}` | `auto` followed the default route (USB tether), not the Wi-Fi the phones are on | `sip_profiles/internal.xml.bak.20260908-103231` |
| 2026-09-07 23:13 | `dialplan/default/01_local_users_100x.xml` | `^(1001\|1002)$` bridging to a literal `192.168.103.24` → `^(10[01][0-9])$` bridging to `$${domain}` | 1000/1005 fell through to `NO_ROUTE_DESTINATION`; the literal domain no longer had registrations (`SUBSCRIBER_ABSENT`) | `…/01_local_users_100x.xml.bak.20260907-231311` (the 2026-04-24 version) |
| 2026-09-07 22:32 | `vars.xml` | `domain=192.168.103.24` → `domain=$${lan_ip}`; `global_codec_prefs`/`outbound_codec_prefs` `OPUS,G722,PCMU,PCMA,H264,VP8` → `PCMU,PCMA,VP8` | the domain followed the machine; the codec list named modules that do not exist ([06](06-modules-and-codecs.md)) | `vars.xml.bak.20260907-223231` |
| 2026-04-24 17:36 | `dialplan/default/00_cfwd.xml` | **new** — `cfwd_master` for 1003 (duplicate of the one in `default.xml`) | call-forward experiment | delete the file |
| 2026-04-24 17:20 | `dialplan/default.xml` | `cfwd_master` added and `agent_5603` duplicated in the context body, ahead of the `default/*.xml` include. `ai_voice_agent` (1234), `agent_1003`, `agent_1004` and the first `agent_5603` were already there (the 15:23 backup has them) | call forwarding for 1003/1004, a trunk to `192.168.20.56`, an AI IVR experiment (its module is not loaded; it embeds an API key) | `dialplan/default.xml.bak.20260424152306`; an older Feb-25 draft sits in the stray file `dialplan/default.xml\` |
| 2026-04-24 17:10 | `dialplan/default/02_cisco_features.xml` | **new** — `*97`, `vm:NNNN`, `3000` | Cisco-phone features; the voicemail entries **hardcode `192.168.103.24`** and are stale since 09-07 ([04](04-dialplan.md)) | delete the file |
| 2026-04-24 14:45 | `directory/default/1001.xml`, `1002.xml`, `1003.xml`, `1004.xml` | `cfwd_enabled`, `cfwd_destination`, `call_forward_all_destination` variables added | call-forward experiment | the other sixteen user files (2026-01-28) are vanilla and show the original shape |
| 2026-03-12 18:19 | `autoload_configs/modules.conf.xml` | vs vanilla: `mod_cdr_csv` replaced by `mod_cdr_pg_csv` (whose `.so`, dated the same day, is a Linux ELF binary and fails `dlopen` on macOS), and **`mod_local_stream` removed** (hold music has been silent since) | — | no backup; the vanilla file is `conf/vanilla/autoload_configs/modules.conf.xml` in the source tree |
| 2026-03-12 17:25 | `autoload_configs/acl.conf.xml` | the commented vanilla `192.168.0.0/24` node in the `domains` list replaced by an active `allow 192.168.20.0/24` | the `5603@192.168.20.56` trunk can call in unauthenticated | no backup; remove the node to revert |
| 2026-01-28 | everything else | installation of the stock configuration | — | `README_IMPORTANT.txt` is the upstream warning about it |

## Not changed from vanilla, but worth knowing

* `autoload_configs/event_socket.conf.xml` — `::`, 8021, `ClueCon`. Stock, and the first
  thing to change when the box leaves the LAN.
* `autoload_configs/switch.conf.xml` — `max-sessions=1000`, `sessions-per-second=30`,
  `loglevel=debug`. Stock.
* `sip_profiles/external.xml` — stock, one example gateway, nothing registers to it.
* No TLS certificate was ever generated (`tls/` has only the DTLS-SRTP and WSS ones).

## Outside FreeSWITCH, part of the same setup

| What | Where | Since |
|---|---|---|
| `coralx-push-sender` (ESL client + FCM sender) | `~/Desktop/coralx-push-sender` (own git repo); runs by hand or from `deploy/launchd/com.coralx.push-sender.plist`; log `~/Library/Logs/coralx-push-sender.log` | 2026-09-12 |
| Canonical copies of the push-wake FreeSWITCH files | `coralx-push-sender/deploy/freeswitch/` | 2026-09-12 |
| `sngrep` | `~/.local/bin/sngrep` (built from source) | 2026-09 |
| The FreeSWITCH source matching the build | `~/Documents/GitHub/freeswitch` (tag `v1.10.11`) | — |

## Keeping this page true

When you edit a file under `/usr/local/freeswitch/etc/freeswitch`:

```bash
cp -p <file> <file>.bak.$(date +%Y%m%d-%H%M%S)      # before
# edit
/usr/local/freeswitch/bin/fs_cli -x reloadxml          # after (plus a profile restart for sip_profiles/*)
```

then add a row here — the file, the change, the reason, the backup name. A change with
no row is the next developer's mystery.
