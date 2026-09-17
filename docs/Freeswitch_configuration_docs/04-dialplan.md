# 04 — The dialplan

## How a call is routed

1. An INVITE arrives on the `internal` profile. If the caller is a registered user, the
   channel gets that user's variables, including `user_context=default`; otherwise it
   lands in the profile's `context=public`.
2. The XML dialplan walks the context's extensions **in file order**, top to bottom. For
   each `<extension>`, the `<condition>`s are tested (regex on a channel field); the first
   extension whose conditions pass has its `<action>`s **queued**, and unless it says
   `continue="true"` the walk stops there.
3. The queued actions then execute in order. `bridge` blocks until the far end hangs up or
   fails; `transfer` re-runs the walk in another context; `park` waits for someone
   (the push sender) to move the channel.

File order for the `default` context is: the body of `dialplan/default.xml` **first**, then
`dialplan/default/*.xml` in **alphabetical** order (the include sits at the end of
`default.xml`). That order is the single most important fact on this page — see the
precedence trap below.

The authoritative list of what is active is FreeSWITCH's own parsed copy,
`var/log/freeswitch/freeswitch.xml.fsxml` (comments in the source files are misleading;
several `<!-- -->` blocks close mid-file). The tables below were generated from it on
2026-09-13 and revised the same afternoon, after the April `cfwd_master` /
`agent_1003` / `agent_1004` rules and the pair-specific bypass file were retired (see
[08](08-changelog.md)).

**One rule now carries every handset-to-handset call:** `01_coralx_push_wake.xml`, with
`bypass_media=true`. The SDP crosses FreeSWITCH untouched, the handsets pick the codec
between themselves and RTP flows phone-to-phone — which is what carries Lyra (ADR-008), a
codec this server does not know. On a deployment where handsets sit behind different NATs
change it to `proxy_media=true` (RTP relayed, still not decoded).

## A call from 1003 to 1001, end to end

```mermaid
flowchart TD
    I["INVITE sip:1001@192.168.2.196<br/>from registered user 1003"] --> C{"context = default<br/>(user_context of 1003)"}
    C --> D1["default.xml body:<br/>ai_voice_agent · agent_5603"]
    D1 -- "none match 1001" --> D2["00_ladspa · 00_pizza_demo"]
    D2 -- "no match" --> D4["01_Talking_Clock · 01_example.com"]
    D4 -- "no match" --> D5["<b>01_coralx_push_wake</b><br/>user_exists(1001) = true<br/>bypass_media=true"]
    D5 --> E["event coralx::push_wake<br/>→ push sender sends FCM"]
    E --> B1["bridge user/1001@domain<br/>progress_timeout=3 · SDP passed through"]
    B1 -- "100/180 within 3 s" --> RING["phone rings · call proceeds"]
    B1 -- "PROGRESS_TIMEOUT /<br/>USER_NOT_REGISTERED" --> RR["ring_ready → 180 to caller<br/>sched_transfer +15 · park"]
    RR -- "sender: fresh REGISTER seen" --> RES["uuid_transfer → context coralx-resume<br/>bridge user/1001@domain"]
    RR -- "sender: 12 s, no REGISTER" --> TO["uuid_transfer → context coralx-timeout<br/>hangup USER_BUSY → 486"]
    RR -- "sender down: 15 s" --> RES
```

## Inventory — context `default`

In evaluation order. *Status* says whether the entry can actually work on this server.

| # | Number(s) | Extension | File | What it does | Status |
|---|---|---|---|---|---|
| 1 | `1234` | `ai_voice_agent` | `default.xml` | sets ~20 `AI_*` variables, plays a tone, `answer`, `audio_stream_ai` | **dead** — `mod_audio_stream` is on disk but not loaded, so the application does not exist. The block also holds a third-party API key in clear text; move it out of the dialplan |
| 2, 3 | `5603` | `agent_5603` (twice, identical) | `default.xml` | `bridge sofia/external/5603@192.168.20.56:5060` | depends on that host; duplicate entry is harmless |
| 4 | `101` | `101` | `default/00_ladspa.xml` (vanilla) | LADSPA audio-effects demo | **dead** — no `mod_ladspa` |
| 5 | `pizza`, `74992` | `pizza_demo` | `default/00_pizza_demo.xml` (vanilla) | JavaScript demo | **dead** — needs `mod_v8` |
| 6–8 | `9170`, `9171`, `9172` | `Talking Clock …` | `default/01_Talking_Clock.xml` (vanilla) | speaks time / date / both | works (`mod_say_en`) |
| — | 7 digits, 11 digits, `011…` | `*.example.com` | `default/01_example.com.xml` (vanilla) | `bridge sofia/gateway/${default_gateway}/…` | **dead** — no gateway is configured |
| 9 | any directory user (`user_exists` = true) | **`coralx-push-wake`** | `default/01_coralx_push_wake.xml` | `bypass_media=true` **for audio-only offers**, then the push-wake route, see [05](05-push-wake.md) | **the route every handset-to-handset call takes**, 1003 and 1004 included since the afternoon of 2026-09-13. Numbering-agnostic; it replaced `01_local_users_100x.xml` (`^(10[01][0-9])$`) and, for media bypass, `00_whatsapp_v2_bypass.xml` (1001↔1002 only). Since 2026-09-16 a call whose offer carries `m=video` does **not** bypass: a bypassed leg negotiates Lyra, and a Lyra leg REFERred into conference 3000 arrives with its audio stream zeroed and every merged member deaf ([conference-video/](conference-video/README.md)) |
| 10 | `*97` | `cisco_voicemail_star97` | `default/02_cisco_features.xml` | `voicemail check default 192.168.103.24 ${caller_id_number}` | **stale** — the domain is hardcoded to the address the server had before 2026-09-07, so no mailbox is found. Replace `192.168.103.24` with `$${domain}` |
| 11 | `vm:NNNN` | `cisco_voicemail_deposit` | same | `voicemail default 192.168.103.24 $1` | **stale**, same fix |
| 12 | `3000` | `cisco_conference_3000` | same | `conference 3000@whatsapp-video` | works — the conference room `docs/testing.md` names. The profile was `default` until 2026-09-15, which sets no `video-mode`: mod_conference ran passthrough and forwarded only the floor holder's camera, so every member of a video conference saw the same one face ([conference-video/](conference-video/README.md)) |
| 13 | `9196` | `whatsapp_v2_echo` | `default/03_whatsapp_v2_test_apps.xml` | `answer`, `echo` (audio **and video** back to the caller) | project test |
| 14 | `9197` | `whatsapp_v2_bridged_echo` | same | `bridge loopback/9196/default` — a real two-leg call so an in-dialog REFER has a B-leg | project test |
| 15 | `9198` | `whatsapp_v2_tone` | same | `answer`, endless two-tone `tone_stream` | project test |
| — | `9199` | *(none)* | — | deliberately unrouted: `NO_ROUTE_DESTINATION`, the transfer target that says no | project test |

### The precedence trap (and how it bit)

Anything in the body of `default.xml` matches before anything under `default/`. Until
the afternoon of 2026-09-13 the body held April's `cfwd_master`, `agent_1003` and
`agent_1004` (an AI-IVR experiment), and a second `cfwd_master` sat in
`default/00_cfwd.xml`. They were broken in three ways at once: `hash select/…` is not a
verb the `hash` *application* has, `${hash(call_forward/1003)}` is missing its `select/`
verb (it always expanded to `-ERR Usage`), and their `bridge user/1003` had no media
bypass and no `continue_on_fail`. A Lyra-only 1002→1003 therefore died as **488
INCOMPATIBLE_DESTINATION**: FreeSWITCH rewrote the offer to PCMU/PCMA+VP8, the callee
answered with the audio line refused (`m=audio 0`), and no codec matched the A-leg. There
is a second trap inside: a nested `<condition>` on a variable that is only set at execute
time (`${cfwd_to}`) is evaluated at *parse* time, where it is empty — the actions are
queued anyway and the extension does not stop the walk, so `agent_1003`, the bypass rule
and `coralx-push-wake` all queued their actions on one call and the first `bridge` decided
the outcome. All five rules are retired (`.bak.20260913-153513` next to each file); the
calls they matched now go through `coralx-push-wake` like every other handset.

### Anything else you dial

Falls off the end of the context → `NO_ROUTE_DESTINATION` (a `404` to the caller). The
vanilla `Local_Extension` was replaced by `coralx-push-wake`; there is no operator, no
`*98`, no `9664` music-on-hold and no `9999`-style demos beyond the ones listed.

## The other contexts

| Context | File | Entries | Relevance |
|---|---|---|---|
| `public` | `dialplan/public.xml` | `unloop`, `public_to_ai_ivrs` (1234 → default), `outside_call`, `call_debug`, `public_extensions` (**1000–1019 → `transfer` to `default`**), `public_conference_extensions` (35xx–38xx), `public_did` (5551212) | where unauthenticated calls land (external profile, or an ACL-allowed source). `public_extensions` means an inbound trunk call for 1001 still ends up in `local-users-100x` |
| `features` | `dialplan/features.xml` | `dx`, `att_xfer`, `is_transfer`, `cf`, `please_hold`, `is_secure` | stock helpers used by `execute_extension`; nothing in the project references them |
| `coralx-resume`, `coralx-timeout` | `dialplan/coralx.xml` | one extension each, `user_exists` like the push-wake rule; `coralx-resume` sets `bypass_media=true` again before its bridge | the push sender transfers parked calls here — [05](05-push-wake.md) |
| `skinny-patterns` | `dialplan/skinny-patterns.xml` | stock Cisco SCCP patterns | dead — `mod_skinny` is not loaded |

## Adding a route

* Put it in a new `dialplan/default/NN_name.xml` (an `<include>` with `<extension>`s), pick
  the prefix so it sorts where it must relative to `01_coralx_push_wake.xml`, and
  `fs_cli -x reloadxml`. No profile restart.
* Keep the per-call variables that the push route relies on out of new entries for
  1000–1019: `progress_timeout`, `continue_on_fail`, `hangup_after_bridge` are set inside
  `01_coralx_push_wake.xml` and `coralx.xml` on purpose.
* Verify with a real call and `grep <uuid> var/log/freeswitch/freeswitch.log | grep Dialplan`
  — every regex the walk tests is logged as `Regex (PASS|FAIL) [extension] field(value) =~ /re/`.
