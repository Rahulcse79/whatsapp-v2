# 6. Build and deployment

Every command below was run against the real environment (FreeSWITCH 1.10.11 at
`/usr/local/freeswitch`, macOS x86_64). Adapt the prefix for a Linux deployment.

## 6.1 Prerequisites

* The installed FreeSWITCH with its development headers — `pkg-config --exists freeswitch`
  must succeed (the install ships `lib/pkgconfig/freeswitch.pc`).
* A C++17 compiler (Apple clang 14 here; GCC 9+/clang 10+ on Linux).
* CMake ≥ 3.16 and < 4.0 for the Lyra closure (some vendored trees still declare
  `cmake_minimum_required(2.8.12)`; CMake 4 removed that compatibility — the build passes
  `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`). CMake 4 is fine for the modules themselves.
* Ninja (optional, faster) and, **on macOS, an `llvm-ar`** for the Lyra archive merge — the
  shared CMake uses an `ar -M` MRI script that BSD `ar` does not support. `port install
  llvm-19` provides `llvm-ar-mp-19`; `build-lyra.sh` finds it automatically or honours
  `LYRA_AR=`.

## 6.2 Build the Lyra closure for the host (once, ~20-30 min; cached after)

```bash
cd freeswitch-lyra
CMAKE=/opt/local/bin/cmake ./scripts/build-lyra.sh
```

This stages the vendored trees (`third_party/*`), builds the exact same closure the Android
app uses (`pjsip/lyra/CMakeLists.txt`) for the host, and installs it to
`build/lyra-prefix/` (`lib/liblyra.a` + headers + `model_coeffs/`). A stamp skips the whole
step on re-run when nothing changed. It defines `IS_MOBILE_PLATFORM` so the host takes the
same code path Android's `__ANDROID__` build does (the vendored TensorFlow tree is pruned for
that path).

## 6.3 Build the modules and run the unit tests

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure     # config, path, and Lyra-adapter tests
```

Warnings enabled: `-Wall -Wextra -Wpedantic -Wshadow` on our code (FreeSWITCH headers are
included as SYSTEM so their own warnings do not drown ours). Hardening flags
(`-fstack-protector-strong`, `_FORTIFY_SOURCE=2`, `-fno-strict-aliasing`) are added where the
compiler accepts them.

### Enabling `lld`

If `lld` is installed (`port install lld` / distro package), link with it:

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld" \
      -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld"
```

It is not on by default because the stock macOS toolchain here has no `lld`; the module links
fine with the system linker. On Linux with a recent clang, `lld` roughly halves link time of
the TFLite-heavy adapter.

## 6.4 Install

```bash
sudo ./scripts/install.sh --prefix /usr/local/freeswitch --reload
```

Copies `mod_lyra.so` and `mod_lyra_record.so` into the module dir, the four model files into
`share/freeswitch/lyra/model_coeffs`, the sample configs into `autoload_configs` (never
overwriting an edited file — a new copy is left as `*.new`), and inserts the two `<load>`
lines into `modules.conf.xml` if absent (backing it up first). `--reload` then does
`reloadxml`, `load mod_lyra`, `load mod_lyra_record` and prints `lyra version` /
`lyra_recording status`.

It does **not** touch your dialplan — that is §6.5.

## 6.5 The one dialplan change (applied by hand, reviewed)

Recording needs FreeSWITCH in the media path for the recorded calls; today the dialplan
bypasses audio. Deploy the merged rules from `conf/dialplan/`:

```bash
# back up, then install the merged push-wake + resume rules
cp /usr/local/freeswitch/etc/freeswitch/dialplan/default/01_coralx_push_wake.xml{,.bak.$(date +%s)}
cp conf/dialplan/default/01_coralx_push_wake.xml /usr/local/freeswitch/etc/freeswitch/dialplan/default/
cp /usr/local/freeswitch/etc/freeswitch/dialplan/coralx.xml{,.bak.$(date +%s)}
cp conf/dialplan/coralx.xml /usr/local/freeswitch/etc/freeswitch/dialplan/
/usr/local/freeswitch/bin/fs_cli -x reloadxml
```

The only functional difference from the current rules, and only when
`lyra_recording.conf` has `enabled=true`:

```diff
   <action application="set" data="call_timeout=30"/>
+  <action application="set" inline="true" data="lyra_record_enabled=${lyra_recording(enabled)}"/>
+  <!-- recording ON: media path + Lyra + arm recorder -->
+  <action application="set" data="absolute_codec_string=lyra@16000h@20i,PCMU,PCMA"/>
+  <action application="set" data="execute_on_answer=lyra_record"/>
   ...
-  <anti-action application="set" data="bypass_media=true"/>
+  <anti-action application="set" data="bypass_media=${cond(${lyra_record_enabled} == true ? false : true)}"/>
```

When recording is disabled, `${cond(...)}` yields `true`, `lyra_record` no-ops, and the call
is byte-for-byte the call it is today.

## 6.6 ABI compatibility

The modules `#include <switch.h>` from the **installed** FreeSWITCH via `pkg-config`, compile
with the same C++ runtime that FreeSWITCH links (libc++ here, confirmed by `otool -L
libfreeswitch.dylib`), and are built as `mod_*.so` with no lib prefix (macOS `.so`, not
`.dylib`). Symbols are resolved from the running `freeswitch` at load (`-undefined
dynamic_lookup` on macOS; the default on Linux). The Lyra/TFLite/absl/glog symbols are
statically merged into `mod_lyra.so` and stay private (module loaded `RTLD_LOCAL`). Rebuild
the modules whenever the installed FreeSWITCH is upgraded to a different minor version.
