# 8. Security review

The attack surface is small — the module has no network face of its own — but the two real
risks are (1) SIP-supplied strings reaching the filesystem and (2) untrusted media reaching
the codec. Both are handled.

## 8.1 Path/filename injection (the main one)

`caller`, `callee` and `domain` come straight from SIP headers; the `uuid` is
FreeSWITCH-issued but still treated as untrusted. All of them feed the recording path. Two
independent defences, both in `record_path.cpp` and both unit-tested in
`tests/unit/test_record_path.cpp`:

1. **Per-value sanitisation** (`sanitizeAtom`): keep only `[A-Za-z0-9._-]`, turn every other
   byte (including `/`, `\`, `..` separators, spaces, `;`, `|`, NUL) into `_`, collapse any
   run of `.` to a single `.` so `..` cannot form, strip leading dots/dashes, cap at 64
   bytes, and replace an empty result with a marker. A filename pattern is sanitised as **one
   atom**, so no token value can introduce a path separator into the filename at all.
2. **Post-build confinement** (`isInside` after `lexicalNormalize`): the finished path is
   lexically normalised (resolving any `.`/`..`/`//`) and asserted to be the root itself or a
   descendant of it. Anything else is a hard error and no file is written.

Tested attacks that are neutralised (all confined to the root, none escaping):
`../../../../etc/cron.d/x`, `..`, `a/b/c`, `1000@host`, absolute-looking values, a
`directory-pattern` of `../../../../tmp`, 500-byte values. The configured root is always
authoritative; a SIP peer cannot choose where files land, only (within `[A-Za-z0-9._-]`) part
of the name.

## 8.2 Untrusted media

Lyra frames arrive from the network. The decoder path:

* validates the payload length is a whole number of valid Lyra frames before decoding;
* on any unrecognised size, conceals instead of feeding the library garbage;
* wraps every library call in `try/catch` and only ever passes the exact sample counts the
  library's `CHECK`s expect, so malformed media cannot trigger an abort.

RTP reception, SRTP and jitter buffering are the core stack's job, unchanged.

## 8.3 Information exposure

* **No audio or RTP is logged.** Logs carry sizes, counts, codec names, and the recording
  path — never sample data or packet contents (an explicit rule in both modules; the log
  lines were written to it).
* **Recordings contain call audio** and are sensitive. The module writes them under the
  configured root with FreeSWITCH's default directory permissions
  (`SWITCH_DEFAULT_DIR_PERMS`, 0750-ish). Deployments should place the root on storage with
  appropriate ownership/retention and, where required, encryption-at-rest; that is an
  operational control, noted in `06-build-and-deploy.md`.
* **Consent/lawfulness** of recording is a deployment and legal responsibility, surfaced by
  the fact that recording is a single explicit switch (`enabled`) that defaults to off.

## 8.4 Resource safety

* `max-seconds` caps any single recording; `min-free-mb` refuses to start when the disk is
  low and the core stops cleanly if it fills mid-call — a runaway call cannot fill the disk
  silently.
* No unbounded memory: per-frame buffers are presized to codec maxima; the recorder's buffer
  is the core's bounded 64 KB/direction; the config snapshot is immutable and shared.
* No global mutable state beyond the mutex-guarded config snapshot and atomic counters; no
  singletons beyond the one module state object FreeSWITCH's loader model requires.

## 8.5 Build hardening

`-Wall -Wextra -Wpedantic -Wshadow`, plus `-fstack-protector-strong`, `_FORTIFY_SOURCE=2` and
`-fno-strict-aliasing` where the toolchain accepts them (checked at configure time). The
module is loaded `RTLD_LOCAL` (no `SMODF_GLOBAL_SYMBOLS`), so the statically-linked TFLite /
absl / glog symbols stay private to `mod_lyra` and cannot clash with anything else in the
process. See `06-build-and-deploy.md` for enabling `lld`.
