#!/usr/bin/env bash
#
# Parses every Swift source in the app with `swiftc -parse`.
#
# ## What this does and does not prove
#
# `-parse` runs the lexer and the parser and stops. It does NOT resolve imports, so it
# needs no iOS SDK and runs on a Mac that has only the Command Line Tools — which is the
# whole reason it exists: it is the only compiler check available on the machine this
# port is being written on.
#
# It therefore catches every syntax error and nothing else. A misspelled type, a wrong
# argument label, a missing protocol conformance — all of those are type checking, and
# type checking needs the SDK. Do not read a pass here as "it builds"; read it as "the
# parser got to the end of the file", which is a real thing to know and the only thing
# this machine can say.
#
# Usage:  ios/scripts/check-swift-syntax.sh [directory]
set -uo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
failures=0
checked=0

while IFS= read -r file; do
  checked=$((checked + 1))
  if ! output="$(swiftc -parse "$file" 2>&1)"; then
    failures=$((failures + 1))
    echo "FAIL  ${file#"$root"/}"
    echo "$output" | sed 's/^/        /' | head -12
  elif [ -n "$output" ]; then
    # swiftc exits 0 on warnings; show them rather than hiding them.
    echo "WARN  ${file#"$root"/}"
    echo "$output" | sed 's/^/        /' | head -6
  fi
done < <(find "$root" -name '*.swift' -not -path '*/build/*' -not -path '*/.build/*' | LC_ALL=C sort)

echo
echo "swiftc -parse: $checked file(s), $failures failure(s)"
[ "$failures" -eq 0 ]
