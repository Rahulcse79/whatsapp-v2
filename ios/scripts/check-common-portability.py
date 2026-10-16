#!/usr/bin/env python3
"""Catches Kotlin that compiles for the JVM target and not for Kotlin/Native.

WHY
---
`:shared` has a `jvm()` target always and the iOS targets only on a machine with Xcode.
On the development machine that means `./gradlew :shared:jvmTest` is the ONLY compile
that ever runs, and it is the one compile that cannot see a commonMain file using a
JVM-only API — because on the JVM it resolves.

CI found exactly this on run 38864004040: six files used `@JvmInline` with no import.
`kotlin.jvm.*` is a default import **only on the JVM**, so the annotation resolved on
the JVM target and failed every Kotlin/Native one with `Unresolved reference 'JvmInline'`.
Thirty-two seconds into a job that had already spent four minutes warming caches.

WHAT IT CHECKS
--------------
1. `@Jvm*` annotations used in code without `import kotlin.jvm.<name>`.
2. `java.*` / `javax.*` imports anywhere under commonMain or commonTest.
3. `String.format`, which is a JVM extension with no common equivalent.
4. Backticked test names containing a comma — legal on the JVM, rejected by
   Kotlin/Native because the name becomes an Objective-C selector.

It is a text check and makes no claim to be a compiler. It catches the four things this
project has actually been bitten by.
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
ROOTS = [REPO / "shared" / "src" / "commonMain", REPO / "shared" / "src" / "commonTest"]

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def code_only(text: str) -> str:
    """The source with comments removed, so a KDoc mentioning `@JvmInline` is not a hit."""
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def main() -> int:
    problems = []
    checked = 0

    for root in ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.kt")):
            checked += 1
            raw = path.read_text()
            code = code_only(raw)
            rel = path.relative_to(REPO)

            for annotation in sorted(set(re.findall(r"@(Jvm[A-Za-z]+)", code))):
                if f"import kotlin.jvm.{annotation}" not in raw:
                    problems.append(
                        f"{rel}: uses @{annotation} without `import kotlin.jvm.{annotation}`. "
                        f"kotlin.jvm.* is a default import only on the JVM."
                    )

            for match in re.finditer(r"^import (java|javax)\.[\w.]+", raw, re.M):
                problems.append(f"{rel}: `{match.group(0)}` — there is no JDK on Kotlin/Native.")

            for match in re.finditer(r"\bString\.format\s*\(", code):
                line = raw[:match.start()].count("\n") + 1
                problems.append(f"{rel}:{line}: String.format is JVM-only.")

            for match in re.finditer(r"fun\s+`([^`]*)`", code):
                if "," in match.group(1):
                    problems.append(
                        f"{rel}: test name `{match.group(1)}` contains a comma. "
                        f"Kotlin/Native turns it into an Objective-C selector and rejects it."
                    )

    print(f"commonMain/commonTest files checked: {checked}")
    for p in problems:
        print(f"  {p}")
    print()
    print("common portability: OK" if not problems
          else f"common portability: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
