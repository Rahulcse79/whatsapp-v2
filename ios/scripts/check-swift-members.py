#!/usr/bin/env python3
"""Checks that members the SwiftUI views reach for actually exist on their types.

WHY
---
Without an iOS SDK there is no type checking, and the commonest mistake when writing a
view against a view-model you wrote an hour ago is naming a property that does not
exist. `swiftc -parse` cannot see it — the syntax is perfect — so it surfaces as a wall
of "value of type 'X' has no member 'y'" on the first real build, which on this project
costs a ten-minute CI round trip.

HOW
---
For each `@EnvironmentObject`/`@ObservedObject`/`@StateObject` declaration it learns the
property's type, then finds every `name.member` use and checks `member` against the
declared members of that type — including members added in extensions and in other
files, which is how SipEngine is written.

LIMITS
------
Names only. It cannot check argument labels, arity or types, and it deliberately skips
anything chained past the first member (`a.b.c` checks only `b`). It also skips
Foundation and SwiftUI members on non-object properties. Existence is the half that is
cheap to check and expensive to get wrong.
"""

import pathlib
import re
import sys

IOS_ROOT = pathlib.Path(__file__).resolve().parent.parent
SWIFT_DIR = IOS_ROOT / "WhatsAppV2"

DECL = re.compile(
    r"@(?:EnvironmentObject|ObservedObject|StateObject)\s+(?:private\s+)?var\s+"
    r"(\w+)\s*:\s*(\w+)")
MEMBER = re.compile(r"\b(?:var|let|func)\s+(\w+)")
# Members Swift synthesises or that come from ObservableObject/NSObject.
INHERITED = {
    "objectWillChange", "init", "deinit", "self", "description", "hash", "isEqual",
    "projectedValue", "wrappedValue",
}


def main() -> int:
    files = {p: p.read_text() for p in sorted(SWIFT_DIR.rglob("*.swift"))}
    whole = "\n".join(files.values())

    # Declared members per type, gathered across every file and extension.
    members: dict[str, set[str]] = {}
    for text in files.values():
        for match in re.finditer(r"^(?:@\w+(?:\([^)]*\))?\s+)*(?:public |private |internal |final )*"
                                 r"(?:class|struct|enum|extension)\s+(\w+)", text, re.M):
            name = match.group(1)
            # The body: from the opening brace to the matching close.
            start = text.find("{", match.end())
            if start == -1:
                continue
            depth, i = 0, start
            while i < len(text):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            members.setdefault(name, set()).update(MEMBER.findall(text[start:i]))

    problems = []
    checked = 0
    for path, text in files.items():
        bindings = dict((n, t) for n, t in DECL.findall(text))
        if not bindings:
            continue
        for name, type_name in bindings.items():
            known = members.get(type_name)
            if not known:
                continue
            for use in re.finditer(rf"\b{re.escape(name)}\.(\w+)", text):
                member = use.group(1)
                checked += 1
                if member in known or member in INHERITED:
                    continue
                line = text[:use.start()].count("\n") + 1
                problems.append(f"{path.name}:{line}  {name}.{member} "
                                f"— {type_name} has no such member")

    print(f"member accesses checked: {checked}")
    for p in sorted(set(problems)):
        print(f"  {p}")
    print()
    print("swift members: OK" if not problems else f"swift members: {len(set(problems))} unknown")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
