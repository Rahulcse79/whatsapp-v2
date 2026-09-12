#!/usr/bin/env bash
#
# Proves the TensorFlow keep-list (TENSORFLOW_KEEP in pins.sh) still covers what
# TensorFlow Lite's CMake build names by path.
#
# TensorFlow is vendored the other way round from every other tree: 472 of its 28,000
# files are kept. The risk is the mirror image of a bad prune — a path lite's build names
# that the keep-list forgot — so this reads every `${TF_SOURCE_DIR}/...` and
# `${TFLITE_SOURCE_DIR}/...` reference out of lite's CMake files and asserts it exists.
# Header includes are not checked here; the build checks those, on three ABIs, in CI.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

tf="third_party/tensorflow"
[ -d "$tf" ] || { echo "$tf is not vendored — nothing to verify"; exit 0; }

status=0
refs="$(grep -rhoE '\$\{(TF_SOURCE_DIR|TFLITE_SOURCE_DIR)\}/[A-Za-z0-9_./-]+' \
          "$tf/tensorflow/lite/CMakeLists.txt" "$tf/tensorflow/lite/tools/cmake" 2>/dev/null \
        | sed -E 's#\$\{TF_SOURCE_DIR\}/#tensorflow/#; s#\$\{TFLITE_SOURCE_DIR\}/#tensorflow/lite/#' \
        | sort -u)"
count=0
while IFS= read -r ref; do
  [ -n "$ref" ] || continue
  count=$((count + 1))
  # A reference may be a directory, a file, or a glob root; existence of the path or its
  # parent directory is what a keep-list can promise.
  if [ ! -e "$tf/$ref" ] && [ ! -d "$tf/$(dirname "$ref")" ]; then
    echo "::error::tensorflow/lite's CMake names $ref, which the keep-list did not keep"
    status=1
  fi
done <<< "$refs"
[ $status -eq 0 ] && echo "tensorflow keep-list covers all $count paths lite's CMake names"
exit $status
