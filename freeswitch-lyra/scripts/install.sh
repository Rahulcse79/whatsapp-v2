#!/usr/bin/env bash
# Install the built modules, model files and sample configs into a FreeSWITCH prefix.
# Idempotent and non-destructive: existing config files are never overwritten (a new
# copy is left as *.new instead), and modules.conf.xml is edited only if the load lines
# are absent. It does NOT touch your dialplan — that change is yours to review; see
# docs/06-build-and-deploy.md for the two-line diff.
#
# Usage: scripts/install.sh [--prefix /usr/local/freeswitch] [--reload]
#   --reload   after installing, tell a running FreeSWITCH to load/reload (fs_cli)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$here/.." && pwd)"
prefix="/usr/local/freeswitch"
do_reload=0
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix="$2"; shift 2;;
    --reload) do_reload=1; shift;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done

mod_dir="$prefix/lib/freeswitch/mod"
conf_dir="$prefix/etc/freeswitch"
autoload="$conf_dir/autoload_configs"
models_dst="$prefix/share/freeswitch/lyra/model_coeffs"
build="$here/build"
fs_cli="$prefix/bin/fs_cli"

[ -d "$mod_dir" ] || { echo "error: $mod_dir not found — is FreeSWITCH installed at $prefix?" >&2; exit 1; }

echo "==> Installing modules into $mod_dir"
for m in mod_lyra mod_lyra_record; do
  so="$build/$m.so"
  [ -f "$so" ] || { echo "error: $so not built — run 'cmake --build build' first" >&2; exit 1; }
  install -m 0755 "$so" "$mod_dir/$m.so"
  echo "    $m.so"
done

echo "==> Installing Lyra model files into $models_dst"
mkdir -p "$models_dst"
src_models="$build/lyra-prefix/model_coeffs"
[ -d "$src_models" ] || src_models="$repo/third_party/lyra/lyra/model_coeffs"
for f in lyra_config.binarypb lyragan.tflite quantizer.tflite soundstream_encoder.tflite; do
  [ -f "$src_models/$f" ] || { echo "error: model file $src_models/$f missing" >&2; exit 1; }
  install -m 0644 "$src_models/$f" "$models_dst/$f"
  echo "    $f"
done

echo "==> Installing sample configs into $autoload (existing files kept)"
for c in lyra.conf.xml lyra_recording.conf.xml; do
  dst="$autoload/$c"
  if [ -f "$dst" ]; then
    install -m 0644 "$here/conf/autoload_configs/$c" "$dst.new"
    echo "    $c already present; new sample left as $c.new"
  else
    install -m 0644 "$here/conf/autoload_configs/$c" "$dst"
    echo "    $c"
  fi
done

echo "==> Ensuring modules.conf.xml loads both modules"
mc="$autoload/modules.conf.xml"
if [ -f "$mc" ]; then
  cp -p "$mc" "$mc.bak.$(date +%Y%m%d-%H%M%S)"
  add_load() {  # $1 = module name, $2 = anchor line to insert AFTER
    local mod="$1" anchor="$2"
    if grep -q "module=\"$mod\"" "$mc"; then
      echo "    $mod already listed"
    elif grep -q "$anchor" "$mc"; then
      # Insert right after the first anchor match, preserving indentation.
      awk -v mod="$mod" -v anchor="$anchor" '
        { print }
        !done && index($0, anchor) { print "    <load module=\"" mod "\"/>"; done=1 }
      ' "$mc" > "$mc.tmp" && mv "$mc.tmp" "$mc"
      echo "    added <load module=\"$mod\"/>"
    else
      echo "    WARN could not find anchor for $mod; add <load module=\"$mod\"/> by hand"
    fi
  }
  # Load mod_lyra with the other codecs and mod_lyra_record with the applications. Any
  # start-up position works: codecs are looked up at call-negotiation time, not at load.
  add_load mod_lyra 'module="mod_amr"'
  add_load mod_lyra_record 'module="mod_dptools"'
else
  echo "    WARN $mc not found; load the modules another way"
fi

if [ "$do_reload" = 1 ] && [ -x "$fs_cli" ]; then
  echo "==> Reloading FreeSWITCH"
  "$fs_cli" -x "reloadxml" || true
  "$fs_cli" -x "load mod_lyra" || true
  "$fs_cli" -x "load mod_lyra_record" || true
  "$fs_cli" -x "lyra version" || true
  "$fs_cli" -x "lyra_recording status" || true
fi

echo "==> Done. Review docs/06-build-and-deploy.md for the dialplan change (not applied automatically)."
