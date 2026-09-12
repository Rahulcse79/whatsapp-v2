# 06 — Modules and codecs: configured, present, loaded

Three lists disagree on this server, and most "it should work" confusion comes from
reading the wrong one:

1. `autoload_configs/modules.conf.xml` — what FreeSWITCH is **told** to load (37 modules).
2. `lib/freeswitch/mod/*.so` — what **exists** on disk.
3. `fs_cli -x "show modules"` — what is **actually loaded** (29 modules).

Checked on 2026-09-13.

## Configured but the `.so` does not exist (silently skipped at start-up)

| Module | What is lost | Symptom in this project |
|---|---|---|
| `mod_opus` | Opus | the app's Opus preference is never negotiated; calls land on PCMU/PCMA |
| `mod_av` | H.264 (FFmpeg) | no H.264; video is VP8 only (which is also all the app ships — `libmswebrtc.so`) |
| `mod_spandsp` | G.722, fax, tone detection | no G.722 |
| `mod_lua` | Lua scripting | **no Lua in the dialplan** — the push-wake design is pure dialplan + ESL for this reason |
| `mod_verto` | WebRTC/Verto | `verto_contact()` in the dial-string returns nothing (harmless) |
| `mod_enum`, `mod_signalwire` | ENUM lookups, SignalWire | unused |

`global_codec_prefs` in `vars.xml` was `OPUS,G722,PCMU,PCMA,H264,VP8` until 2026-09-07 and is
now `PCMU,PCMA,VP8` — the truth rather than the wish.

## Present on disk, configured, but not loaded

| Module | Why it matters |
|---|---|
| `mod_cdr_pg_csv` | in `modules.conf.xml` and on disk, yet `module_exists` says false: the log shows `dlopen … (not a mach-o file)` — `file` says it is an **x86-64 Linux ELF** copied onto the Mac on 2026-03-12, so it can never load here. **There is no CDR of any kind**: `mod_cdr_csv`, `mod_cdr_sqlite` and `mod_xml_cdr` are on disk but not configured to load. Call history lives only in the app |

## On disk, not configured (available if ever needed)

`mod_local_stream` (**hold music**: `hold_music=local_stream://moh` is silent without it —
vanilla loads this module; it was removed from the load list on 2026-03-12),
`mod_audio_stream` (the `ai_voice_agent` dialplan entry needs it — see [04](04-dialplan.md)),
`mod_h26x` (H.26x pass-through), `mod_skinny`, `mod_sms`, `mod_syslog`, `mod_test`,
`mod_xml_rpc`, `mod_xml_scgi`, `mod_cdr_csv` (vanilla's CDR writer, swapped for
`mod_cdr_pg_csv`), `mod_cdr_sqlite`, `mod_xml_cdr`.

To enable one: add `<load module="mod_x"/>` to `modules.conf.xml` and either restart or
`fs_cli -x "load mod_x"` (works for most; codec and endpoint modules are safest at start).

## Loaded (the 29 that run)

`mod_amr`, `mod_b64`, `mod_commands`, `mod_conference`, `mod_console`, `mod_db`,
`mod_dialplan_asterisk`, `mod_dialplan_xml`, `mod_dptools`, `mod_esf`, `mod_event_socket`,
`mod_expr`, `mod_fifo`, `mod_fsv`, `mod_g723_1`, `mod_g729`, `mod_hash`, `mod_httapi`,
`mod_logfile`, `mod_loopback`, `mod_native_file`, `mod_png`, `mod_rtc`, `mod_say_en`,
`mod_sndfile`, `mod_sofia`, `mod_tone_stream`, `mod_valet_parking`, `mod_voicemail`.

What the push-wake path needs is all in there: `mod_event_socket`, `mod_dptools`
(`event`, `park`, `ring_ready`, `sched_transfer`, `bridge`), `mod_commands`
(`uuid_transfer`, `sched_del`, `sofia_contact`, `show registrations`), `mod_sofia`.

## The effective codec set

`fs_cli -x "show codec"` on 2026-09-13:

| Audio | Video | Other |
|---|---|---|
| **G.711 µ-law (PCMU)**, **G.711 A-law (PCMA)**, G.729, G.723.1 6.3k, AMR (both packings), Speex, RAW L16 | **VP8**, VP9 (core `libvpx`) | B64, PROXY pass-through, PROXY video pass-through |

The profile offers `PCMU,PCMA,VP8` in that order (`inbound-codec-prefs` /
`outbound-codec-prefs` = `$${global_codec_prefs}`), and `inbound-codec-negotiation=generous`
lets the callee take any codec the caller offered that is in the loaded set. G.729/G.723.1/AMR
are loaded but not preferred; a phone that offers only one of them will still connect.

## What that means for a Coral X call

| Leg | Result |
|---|---|
| Audio, phone ↔ server (echo `9196`, tone `9198`, conference `3000`) | PCMU or PCMA |
| Audio, phone ↔ phone through the server (`1001` → `1003`) | PCMU/PCMA; the server transcodes nothing because both ends share the set |
| Video | VP8 both ways; `9196` echoes the caller's own camera |
| Opus / Lyra between two Coral X phones | only with the media-bypass test route (`00_whatsapp_v2_bypass.xml`, 1001 ↔ 1002), where the SDP is passed through untouched and the server never sees the media |
| SRTP | `a=crypto` on `RTP/AVP` → `488`; encryption must be off ([02](02-sip-profile-internal.md)) |

How to check quickly:

```bash
fs_cli -x "show codec" | cut -d, -f2 | sort -u          # the truth
fs_cli -x 'eval $${global_codec_prefs}'                   # the preference string
fs_cli -x "module_exists mod_opus"                        # false
fs_cli -x "show channels"                                 # read_codec / write_codec columns = what a live call negotiated
grep "Set Codec" /usr/local/freeswitch/var/log/freeswitch/freeswitch.log | tail -4   # the same, after the fact
```
