# Video conferencing on the office server (`192.168.80.145`)

Handover for whoever operates that box. Nothing in this repository can fix any of it.
Item 2 is the conference defect; item 1 is a transport weakness that is worth fixing but,
contrary to an older note in `docs/HANDOFF.md`, is **not** currently stopping video calls —
see the correction under item 1.

Measured from the development Mac on 2026-09-15, not quoted from an older note:

```
$ nc -z -w 3 -v 192.168.80.145 5060      # TCP
nc: connectx to 192.168.80.145 port 5060 (tcp) failed: Connection refused
$ nc -z -w 3 -v 192.168.80.145 5061      # TLS
nc: connectx to 192.168.80.145 port 5061 (tcp) failed: Connection refused
$ nc -u -z -w 2 -v 192.168.80.145 5060   # UDP
Connection to 192.168.80.145 port 5060 [udp/sip] succeeded!

$ ping -D -s 1472 192.168.80.145   # 1500-byte datagram, don't-fragment
ok
$ ping -D -s 1972 192.168.80.145   # 2000 bytes
no reply
```

## 1. Accept TCP on 5060 (worth doing, but NOT currently blocking)

`docs/HANDOFF.md` records this as a hard blocker — "video calling cannot work until the
server accepts TCP", on the reasoning that an audio + video INVITE is ~2700 bytes, the path
carries 1500, fragments are dropped, and TCP is refused.

**The transport measurements above are still true. The conclusion drawn from them is not.**
On 2026-09-15 the handset placed video calls to `7002` and `7005` through this server that
connected and ran for 4:24 and 5:51, with video rendering on screen. So the offer does
reach the server — the INVITE evidently fits, or the path passes more than the probe
suggests. Whatever the explanation, "video cannot work here" is contradicted by video
working here, and the note in HANDOFF.md should be read as the open question it is rather
than as a settled blocker.

What remains true and worth fixing: there is **no TCP and no TLS transport at all**, so the
offer has no headroom. Any future addition to the SDP — ICE, another codec, SRTP — spends a
budget that is already close to the edge, and the failure mode when it goes over is silent:
a request retransmitted seven times over 32 seconds with no response of any kind, ending at
Timer B, with correct SDP, a working camera and correct codecs. That is a bad failure to
design toward on purpose.

In `sip_profiles/internal.xml` on that server:

```xml
<param name="sip-port" value="5060"/>
<!-- The profile must bind TCP as well as UDP. Sofia does both when the bind string
     carries them; check `sofia status profile internal` shows a TCP row. -->
```

and whatever firewall or SBC sits in front has to pass TCP 5060 through. Once it does, the
app needs its account's transport set to **TCP** (Settings → the account → Transport); it
is on UDP today because that is all the server offers.

TLS on 5061 would do just as well and is the better answer if the SBC is on an untrusted
segment.

## 2. Compose the conference instead of forwarding one camera

The same defect this repository just fixed on the development Mac almost certainly applies
here, because it is FreeSWITCH's default rather than anybody's choice: a conference profile
with no `video-mode` runs **passthrough** and forwards only the floor holder's camera, so
every member of a video conference sees the same one face.

Check which profile the conference extension uses:

```bash
fs_cli -x "reloadxml"
grep -rn "conference" /path/to/conf/dialplan/            # find the room's profile name
fs_cli -x "conference list"                              # while a call is up
```

In the flag list, **`video_muxing` must be present** and `video_floor_only` must not be.
If `video_muxing` is missing, the room is in passthrough and this is the cause.

The fix is the two blocks in this folder, applied to that server's configuration:

- [`conference.conf.whatsapp-video.profile.xml`](conference.conf.whatsapp-video.profile.xml)
  — the profile. Adjust `rate` to whatever that server's audio codecs are; `8000` matches
  PCMU/PCMA.
- [`conference_layouts.wa-portrait.xml`](conference_layouts.wa-portrait.xml) — the portrait
  layout group the profile names. Without it, `video-layout-name=group:wa-portrait` has
  nothing to resolve and the room falls back to a default layout.

Then point the conference extension at the new profile, as
[`02_cisco_features.conference-3000.xml`](02_cisco_features.conference-3000.xml) does for
`3000` on the Mac.

**Check before applying:** `video-mode=mux` needs a video codec the server can actually
*decode*, because composing means decoding every member and re-encoding one canvas. VP8 is
in the FreeSWITCH core (`switch_vpx.c`); H.264 needs `mod_av`, which is not loaded on the
Mac's build and may not be on that one either. If the members negotiate H.264 and `mod_av`
is absent, they will be heard and not seen.

## 3. `mod_opus` (not blocking, worth doing)

Configured in `modules.conf.xml`, `.so` absent. Installing it roughly halves the bandwidth
of every call with **no client change** — the app already registers and offers Opus.

## What was verified where

| Claim | Verified on | How |
|---|---|---|
| Passthrough is the cause of "one face for everybody" | the development Mac | conferences on `3000@default` logged no canvas; after the change `conference list` shows `video_muxing` |
| The portrait layout group loads and applies | the development Mac | `Adding layout group wa-portrait` … `Layout set to wa-1` in the FreeSWITCH log |
| TCP 5060 refused, 2000-byte datagrams dropped | `192.168.80.145` | the probes at the top of this page |
| Video calls DO connect through this server despite that | `192.168.80.145` | two outgoing video calls on 2026-09-15, 4:24 and 5:51, video on screen |
| The composed grid renders correctly on a handset | the development Mac, **one member only** | `wa-1` fills the canvas, 720x1280 decoded, undistorted; two or more members not yet exercised |
