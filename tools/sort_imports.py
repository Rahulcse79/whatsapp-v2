#!/usr/bin/env python3
"""Sort Kotlin imports the way ktlint expects.

Every package move in this repository has cost a CI round trip to an ImportOrdering
finding. ktlint's rule is: lexicographic, with `java`, `javax` and `kotlin` last. That is
mechanical, so it should not be discovered by a five-minute build.

Usage:  python3 tools/sort_imports.py <file-or-directory>...
        python3 tools/sort_imports.py .          # whole repository
"""
from __future__ import annotations

import sys
from pathlib import Path

TRAILING_PREFIXES = ("java.", "javax.", "kotlin.")
SKIP_DIRS = {"build", ".git", ".gradle", ".idea", ".kotlin"}


def sort_file(path: Path) -> bool:
    """Rewrites `path` if its import order changes. Returns True when it did."""
    lines = path.read_text().split("\n")
    indexes = [i for i, line in enumerate(lines) if line.startswith("import ")]
    if not indexes:
        return False

    first, last = indexes[0], indexes[-1]
    imports = sorted({line for line in lines[first : last + 1] if line.startswith("import ")})

    # ktlint orders: everything else, then java/javax/kotlin, then ALIASED imports last.
    # The alias clause is easy to miss - it is what this tool got wrong on its first run.
    aliased = [i for i in imports if " as " in i]
    rest = [i for i in imports if " as " not in i]
    head = [i for i in rest if not i[len("import ") :].startswith(TRAILING_PREFIXES)]
    tail = [i for i in rest if i[len("import ") :].startswith(TRAILING_PREFIXES)]

    updated = lines[:first] + head + tail + aliased + lines[last + 1 :]
    if updated == lines:
        return False

    path.write_text("\n".join(updated))
    return True


def kotlin_files(target: Path):
    if target.is_file():
        yield target
        return
    for path in target.rglob("*.kt"):
        if not any(part in SKIP_DIRS for part in path.parts):
            yield path


def main(argv: list[str]) -> int:
    targets = [Path(a) for a in argv[1:]] or [Path(".")]
    changed = [p for t in targets for p in kotlin_files(t) if sort_file(p)]
    for path in changed:
        print(f"sorted {path}")
    print(f"{len(changed)} file(s) changed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
