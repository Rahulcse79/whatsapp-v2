# 9. Verification and diagnostics

Exact commands for this install (`fs_cli` at `/usr/local/freeswitch/bin/fs_cli`).

## 9.1 Build / install / load

```bash
# build the Lyra archive, the modules, run unit tests
CMAKE=/opt/local/bin/cmake ./scripts/build-lyra.sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
ctest --test-dir build --output-on-failure

# install and load
sudo ./scripts/install.sh --reload

# module loaded?
fs_cli -x "module_exists mod_lyra"            # -> true
fs_cli -x "module_exists mod_lyra_record"     # -> true
```

## 9.2 Codec registered

```bash
fs_cli -x "lyra version"                       # -> 1.3.2 (the library)
fs_cli -x "lyra status"                        # config + metrics
# Lyra in the registered codec set (core DB is authoritative on this build;
# `show codec` can print 0 after a swap-out — see docs/01-analysis.md):
sqlite3 /usr/local/freeswitch/var/lib/freeswitch/db/core.db \
  "select name,ikey from interfaces where type='codec' and name like '%Lyra%';"
```

## 9.3 Recording configuration

```bash
fs_cli -x "lyra_recording status"              # enabled?, path, counters
fs_cli -x "lyra_recording status json"         # same, machine-readable
fs_cli -x "lyra_recording enabled"             # -> true | false  (what the dialplan reads)
# after editing lyra_recording.conf.xml:
fs_cli -x "reloadxml" && fs_cli -x "lyra_recording reload"
```

## 9.4 Place a test call and verify a recording

```bash
# full automated E2E (disabled + enabled + concurrency), see docs/05:
./tests/integration/run_integration.sh --concurrency "1 5 10" --seconds 8

# or by hand: enable recording, then from the two connected handsets (1004/1005 are the
# live test extensions) place a call. Then:
ls -lt /usr/local/freeswitch/var/lib/freeswitch/recordings/lyra/*/            # newest first
newest=$(find /usr/local/freeswitch/var/lib/freeswitch/recordings/lyra -name '*.wav' | xargs ls -t | head -1)
./build/tests/integration/tools/wav_check "$newest" --rate 16000 --channels 2 --min-seconds 1
afplay "$newest"        # macOS; or `play`/`ffplay` elsewhere — confirms it is audible
```

## 9.5 Watch it work live

```bash
# codec negotiation for a call (are both legs on lyra?)
fs_cli -x "uuid_dump <call-uuid>" | grep -iE "read_codec|write_codec|rtp_use_codec"
# the recording bug is attached:
fs_cli -x "uuid_buglist <call-uuid>"           # shows "session_record"
# follow the module's own log lines:
fs_cli -x "console loglevel debug"
tail -f /usr/local/freeswitch/var/log/freeswitch/freeswitch.log | grep -iE "Lyra"
```

## 9.6 Diagnose the two usual problems

**No recording appears.**

```bash
fs_cli -x "lyra_recording status"     # enabled=false? skipped_not_lyra rising? failed_path/failed_disk?
fs_cli -x "uuid_dump <uuid>" | grep -iE "bypass_media|read_codec"   # bypass still on, or not lyra?
```
`bypass_media: true` on the channel means the dialplan change (§6.5) is not deployed or
`enabled` is false — a bypassed call is never seen by the server. `read_codec` not `lyra`
means the call fell back to another codec (out of scope by design; see `skipped_not_lyra`).

**Codec will not negotiate.**

```bash
fs_cli -x "lyra status"                # did the module load? library version present?
grep -iE "mod_lyra|Lyra codec" /usr/local/freeswitch/var/log/freeswitch/freeswitch.log | tail
```
"models ... unusable" ⇒ `model-path` wrong or model files missing (`ls
$prefix/share/freeswitch/lyra/model_coeffs`). "not at the desired implementation" ⇒ the
account/profile is asking for a ptime/rate the module was not registered for; check
`sample-rates`/`ptime` in `lyra.conf.xml`.
```
