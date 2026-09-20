# Integration tests

Real SIP/RTP Lyra calls through the local FreeSWITCH, proving the modules end to end.

## What it proves

* **A. disabled** — a Lyra call connects and nothing is recorded (zero overhead path).
* **B. enabled** — a Lyra call produces one playable WAV of the right rate/channels/duration,
  named by the configured pattern (caller/callee/uuid ⇒ unique, no collisions).
* **C. concurrency** — N simultaneous calls each produce their own valid WAV with no
  cross-call contamination; peak FreeSWITCH CPU and RSS are measured, not assumed.

## Pieces

* `sipua.py` — a dependency-free SIP/RTP user agent (REGISTER with MD5 digest, INVITE/answer,
  streams real Lyra RTP). Acts as `caller` or `callee`.
* `tools/lyra_gen_frames.cpp` — encodes a tone to genuine Lyra frames (links the archive), so
  the stream reaching FreeSWITCH is real Lyra that `mod_lyra` must decode to record.
* `tools/wav_check.cpp` — validates a WAV's RIFF/format/rate/channels/duration.
* `03_lyra_record_itest.xml` — a dedicated `itest<NNNN>` routing extension the harness
  installs and removes, so it never edits the production dialplan.
* `run_integration.sh` — orchestrates A/B/C, toggling recording via a private config copy and
  reload (the operator's config is restored on exit).

## Prerequisites

FreeSWITCH running with `mod_lyra` + `mod_lyra_record` loaded (`scripts/install.sh --reload`),
the helper tools built, and directory users available (vanilla FS ships 1000–1019, password
`1234`; the harness excludes the live handsets 1004/1005).

```bash
# build the helper tools (needs the Lyra archive from scripts/build-lyra.sh)
cmake -S tests/integration/tools -B build/tests/integration/tools
cmake --build build/tests/integration/tools -j
```

## Run

```bash
./tests/integration/run_integration.sh --concurrency "1 5 10" --seconds 8
# smaller: --concurrency "1"    ;  different users: --users "1000 1001 1006 1007"
```

Recordings and per-call logs are left in a temp dir (printed at the end) for inspection.
Paste the measured CPU/RSS/validity numbers into `docs/05-performance.md §5.4`.
