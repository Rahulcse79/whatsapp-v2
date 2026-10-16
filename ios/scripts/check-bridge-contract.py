#!/usr/bin/env python3
"""Checks that the Objective-C bridge and the Swift that calls it agree.

WHY THIS EXISTS
---------------
None of this can be compiled on a machine without an iOS SDK, and the three mistakes it
catches are ones the compiler would find instantly and a reader would not:

  1. a method declared in PjsipBridge.h and never implemented in PjsipBridge.mm
     — a link error, hundreds of lines into an otherwise clean build;
  2. a delegate callback the protocol requires and Swift does not provide
     — silently never called, so a call rings for ever or never ends;
  3. a `bridge.something(...)` in Swift that names no method at all
     — the single most likely error when writing against a header you cannot compile.

It is a text check, not a parse: it cannot see a type mismatch. It can see a name that
is in one file and not the other, which is the class of error that survives review.

ON SWIFT NAMES
--------------
Methods carrying NS_SWIFT_NAME are checked by their stated Swift spelling. The rest are
checked by base name only. Reproducing Swift's importer here — preposition splitting,
omit-needless-words, NSError** becoming `throws` — would be a second implementation of
a rule that is allowed to change between toolchains, and a wrong reimplementation
reports failures that are not real. Annotating the ambiguous methods is the fix: the
annotation turns an inference into a fact this script can check.
"""

import pathlib
import re
import sys

IOS_ROOT = pathlib.Path(__file__).resolve().parent.parent
HEADER = IOS_ROOT / "WhatsAppV2" / "Bridge" / "PjsipBridge.h"
IMPL = IOS_ROOT / "WhatsAppV2" / "Bridge" / "PjsipBridge.mm"
SWIFT_DIR = IOS_ROOT / "WhatsAppV2"

METHOD = re.compile(r"^\s*[-+]\s*\([^)]+\)\s*([A-Za-z_][A-Za-z0-9_]*)", re.M)

# Only the Swift BASE name is extracted, not the whole selector.
#
# `NS_SWIFT_NAME(placeCall(to:fromAccount:profile:error:))` has nested parentheses, and
# a regex that tries to capture the balanced inner text either stops at the first `)` —
# producing a truncated name that matches nothing — or needs a real parser. The base
# name is what a `bridge.<name>` call site is checked against, so it is all this needs.
SWIFT_NAME = re.compile(r"NS_SWIFT_NAME\(\s*([A-Za-z_][A-Za-z0-9_]*)")


def block(text: str, start_pattern: str) -> str:
    """The text from an @interface/@protocol line to its matching @end."""
    match = re.search(start_pattern, text)
    if not match:
        return ""
    rest = text[match.start():]
    end = rest.find("\n@end")
    return rest[:end if end != -1 else len(rest)]


def main() -> int:
    header = HEADER.read_text()
    impl = IMPL.read_text()
    swift_files = {p: p.read_text() for p in sorted(SWIFT_DIR.rglob("*.swift"))}

    failures = 0

    # ---------------------------------------------------- 1. declared vs implemented
    class_block = block(header, r"@interface PjsipBridge\s*:\s*NSObject")
    class_methods = METHOD.findall(class_block)
    unimplemented = [
        m for m in class_methods
        if not re.search(r"^\s*[-+]\s*\([^)]+\)\s*" + re.escape(m) + r"\b", impl, re.M)
    ]
    print(f"PjsipBridge methods declared  : {len(class_methods)}")
    print(f"  implemented in the .mm      : {len(class_methods) - len(unimplemented)}")
    for m in unimplemented:
        print(f"  MISSING IMPLEMENTATION      : {m}")
        failures += 1

    # ---------------------------------------------------- 2. delegate callbacks
    proto_block = block(header, r"@protocol PjsipBridgeDelegate")
    proto_methods = METHOD.findall(proto_block)
    all_swift = "\n".join(swift_files.values())
    unhandled = [m for m in proto_methods if m not in all_swift]
    print(f"PjsipBridgeDelegate callbacks : {len(proto_methods)}")
    print(f"  handled in Swift            : {len(proto_methods) - len(unhandled)}")
    for m in unhandled:
        print(f"  NOT HANDLED IN SWIFT        : {m}")
        failures += 1

    # ---------------------------------------------------- 3. Swift call sites resolve
    # One declaration per `;`-terminated chunk of the class block. Splitting on the
    # terminator is both simpler and more robust than one regex spanning a declaration
    # that may be four lines long with a doc comment in the middle.
    declared = {}
    for chunk in class_block.split(";"):
        base = METHOD.search(chunk + ";")
        if not base:
            continue
        pinned = SWIFT_NAME.search(chunk)
        declared[base.group(1)] = pinned.group(1) if pinned else None
    swift_names = {name for name in declared.values() if name}
    properties = set(re.findall(r"@property[^;]*?\b([A-Za-z_][A-Za-z0-9_]*)\s*;", header))
    properties |= {"delegate", "running", "stackVersion"}

    unresolved = []
    call_count = 0
    for path, text in swift_files.items():
        for call in re.finditer(r"\bbridge\.([A-Za-z_][A-Za-z0-9_]*)", text):
            call_count += 1
            name = call.group(1)
            if name in properties or name in declared or name in swift_names:
                continue
            line = text[:call.start()].count("\n") + 1
            unresolved.append(f"{path.name}:{line}  bridge.{name}")

    print(f"Swift call sites into the bridge: {call_count}")
    print(f"  resolve to a declaration      : {call_count - len(unresolved)}")
    for u in unresolved:
        print(f"  UNRESOLVED                  : {u}")
        failures += 1

    annotated = sum(1 for v in declared.values() if v)
    print(f"  methods pinned with NS_SWIFT_NAME: {annotated}")

    print()
    print("bridge contract: OK" if failures == 0 else f"bridge contract: {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
