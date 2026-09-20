# Lyra Call Recording — Setup Guide (step by step)

Server-side recording of Lyra-to-Lyra calls in FreeSWITCH. Source lives in
`freeswitch-lyra/`. Two modules: **`mod_lyra`** (the Lyra codec) and **`mod_lyra_record`**
(the recording policy). Full design in `freeswitch-lyra/docs/`.

Verified on FreeSWITCH **1.10.11**, prefix `/usr/local/freeswitch`.

---

## 0. Paths at a glance

| What | Path |
|------|------|
| FreeSWITCH prefix | `/usr/local/freeswitch` |
| Modules (`.so`) | `/usr/local/freeswitch/lib/freeswitch/mod/` |
| Configs | `/usr/local/freeswitch/etc/freeswitch/autoload_configs/` |
| Lyra models | `/usr/local/freeswitch/share/freeswitch/lyra/model_coeffs/` |
| Recordings (default) | `/usr/local/freeswitch/var/lib/freeswitch/recordings/lyra/` |
| `fs_cli` | `/usr/local/freeswitch/bin/fs_cli` |

---

## 1. Prerequisites

**macOS**
```bash
xcode-select --install                 # Apple clang
sudo port install cmake ninja llvm-19  # llvm-ar is required for the Lyra archive merge
```

**Linux (Debian/Ubuntu)**
```bash
sudo apt-get install -y build-essential cmake ninja-build pkg-config
# GNU ar already supports the archive merge; no llvm-ar needed.
```

Both: the installed FreeSWITCH must expose its headers — `pkg-config --exists freeswitch`
must succeed (uses `/usr/local/freeswitch/lib/pkgconfig/freeswitch.pc`).

---

## 2. Build

```bash
cd /Users/rahulsingh/Desktop/whatsapp-v2/freeswitch-lyra
```

### 2a. Build the Lyra library archive (once, ~20–30 min; cached afterwards)

**macOS**
```bash
CMAKE=/opt/local/bin/cmake ./scripts/build-lyra.sh
```

**Linux**
```bash
./scripts/build-lyra.sh
```
Output: `build/lyra-prefix/lib/liblyra.a` + headers + `model_coeffs/`.

### 2b. Build the two modules

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```
Produces `build/mod_lyra.so` and `build/mod_lyra_record.so`.

> macOS note: if your `cmake` is 4.x, add `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` to the
> `build-lyra.sh` step (some vendored trees still require the old policy).

### 2c. (optional) Run the tests

```bash
ctest --test-dir build --output-on-failure      # unit tests (no FreeSWITCH needed)
```

---

## 3. Install

```bash
sudo ./scripts/install.sh --prefix /usr/local/freeswitch --reload
```

This copies:
- `mod_lyra.so`, `mod_lyra_record.so` → `.../lib/freeswitch/mod/`
- the 4 model files → `.../share/freeswitch/lyra/model_coeffs/`
- sample configs → `.../etc/freeswitch/autoload_configs/` (never overwrites an edited file)
- adds the two `<load>` lines to `modules.conf.xml`

`--reload` then loads both modules and prints their status.

Verify:
```bash
/usr/local/freeswitch/bin/fs_cli -x "module_exists mod_lyra"          # -> true
/usr/local/freeswitch/bin/fs_cli -x "module_exists mod_lyra_record"   # -> true
/usr/local/freeswitch/bin/fs_cli -x "lyra version"                    # -> 1.3.2
```

---

## 4. Configure recording

Edit `/usr/local/freeswitch/etc/freeswitch/autoload_configs/lyra_recording.conf.xml`:

```xml
<configuration name="lyra_recording.conf" description="Lyra recording">
  <settings>
    <param name="enabled"           value="true"/>                                             <!-- master switch -->
    <param name="recording-path"    value="/usr/local/freeswitch/var/lib/freeswitch/recordings/lyra"/>
    <param name="format"            value="wav"/>
    <param name="track"             value="stereo"/>   <!-- stereo (caller L / callee R) | mixed -->
    <param name="sample-rate"       value="16000"/>    <!-- 0 = follow the call -->
    <param name="directory-pattern" value="{date}"/>                       <!-- {date}=YYYYMMDD -->
    <param name="filename-pattern"  value="{time}_{caller}_{callee}_{uuid}"/>
    <param name="min-free-mb"       value="100"/>
    <param name="max-seconds"       value="0"/>        <!-- 0 = unlimited -->
  </settings>
</configuration>
```

Apply it at runtime (no restart):
```bash
/usr/local/freeswitch/bin/fs_cli -x "reloadxml"
/usr/local/freeswitch/bin/fs_cli -x "lyra_recording reload"
/usr/local/freeswitch/bin/fs_cli -x "lyra_recording status"    # shows enabled + path + counters
```

Saved files land at:
```
<recording-path>/<date>/<time>_<caller>_<callee>_<uuid>.wav
e.g. /usr/local/freeswitch/var/lib/freeswitch/recordings/lyra/20260920/111629_1004_1005_<uuid>.wav
```

`enabled=false` (the default) = zero change to calls: no recorder, no file, media bypass as
before.

---

## 5. Dialplan (required for real calls)

A Lyra call bypasses media today, so FreeSWITCH never sees the audio. To record real calls,
FreeSWITCH must stay in the media path **when recording is on**. Deploy the merged rules:

```bash
cd /Users/rahulsingh/Desktop/whatsapp-v2/freeswitch-lyra
DP=/usr/local/freeswitch/etc/freeswitch/dialplan
cp "$DP/default/01_coralx_push_wake.xml" "$DP/default/01_coralx_push_wake.xml.bak.$(date +%s)"
cp conf/dialplan/default/01_coralx_push_wake.xml "$DP/default/"
cp "$DP/coralx.xml" "$DP/coralx.xml.bak.$(date +%s)"
cp conf/dialplan/coralx.xml "$DP/"
/usr/local/freeswitch/bin/fs_cli -x "reloadxml"
```

The rules read `${lyra_recording(enabled)}`: when `true` they keep media in FreeSWITCH,
force Lyra end-to-end, and arm the recorder on answer; when `false` they bypass exactly as
before.

---

## 6. Verify a recording

```bash
# place a Lyra call between two handsets (e.g. 1004 -> 1005), then:
find /usr/local/freeswitch/var/lib/freeswitch/recordings/lyra -name '*.wav' | sort
/usr/local/freeswitch/bin/fs_cli -x "lyra_recording status"     # active / started / completed
afplay <file>        # macOS   (Linux: aplay / play <file>)
```

---

## 7. Turn it off

```bash
# set enabled=false in lyra_recording.conf.xml, then:
/usr/local/freeswitch/bin/fs_cli -x "reloadxml"
/usr/local/freeswitch/bin/fs_cli -x "lyra_recording reload"
```
Optionally restore the original dialplan from the `.bak.*` copies made in step 5.

---

## Quick command reference

```bash
fs_cli -x "lyra version"                 # Lyra library version
fs_cli -x "lyra status"                  # codec metrics (frames decoded, failures…)
fs_cli -x "lyra_recording status"        # recording config + counters
fs_cli -x "lyra_recording status json"   # machine-readable
fs_cli -x "lyra_recording enabled"       # true | false
fs_cli -x "lyra_recording reload"        # re-read lyra_recording.conf
fs_cli -x "lyra reload"                   # re-read lyra.conf (model-path, dtx)
```
