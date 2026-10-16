#!/usr/bin/env python3
"""Checks that every PJSIP symbol PjsipBridge.mm uses exists in the vendored headers.

WHY
---
The bridge is 1,500 lines of calls into a C API that cannot be compiled on a machine
without an iOS SDK. An invented function name, a misremembered struct field or a
constant that does not exist produces a wall of compiler errors on the first real
build, and every one of them is findable here with grep — the headers are in
third_party/pjproject and they are the same headers the cross-compile installs.

WHAT IT CHECKS
--------------
Every `pjsua_*`, `pjmedia_*`, `pjsip_*` and `PJ*` identifier in the .mm is looked up in
pjproject's public headers. Anything not found is reported.

WHAT IT CANNOT CHECK
--------------------
Arity, argument types and struct field paths. `pjsua_call_get_info(a, b)` with the wrong
types passes here and fails the compiler. This catches existence, which is the half that
is easy to get wrong when writing against a header you cannot compile against.
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
BRIDGE = REPO / "ios" / "WhatsAppV2" / "Bridge" / "PjsipBridge.mm"
PJ = REPO / "third_party" / "pjproject"

HEADER_DIRS = [
    PJ / "pjlib" / "include",
    PJ / "pjlib-util" / "include",
    PJ / "pjnath" / "include",
    PJ / "pjmedia" / "include",
    PJ / "pjsip" / "include",
]

# Identifiers the bridge defines itself, or that come from the config_site this project
# supplies rather than from pjproject's headers.
LOCAL = {
    "PJSUA_MAX_CALLS", "PJSUA_MAX_ACC", "PJSUA_MAX_CONF_PORTS",
    "PJSUA_DONT_RELEASE_SND_DEV", "PJSUA_DEFAULT_CODEC_QUALITY",
    "PjStr", "PjError", "PjBlock", "PjsipBridge", "PjsipBridgeDelegate", "PumpMain",
    # This class's own selectors, which match the pj_* pattern by coincidence.
    "pj_sync", "pj_async",
}

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')


def main() -> int:
    source = BRIDGE.read_text()
    code = STRING.sub('""', LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", source)))

    headers = "\n".join(
        p.read_text(errors="replace")
        for d in HEADER_DIRS if d.is_dir()
        for p in d.rglob("*.h")
    )

    used = set(re.findall(r"\b(pjsua_[a-z0-9_]+|pjmedia_[a-z0-9_]+|pjsip_[a-z0-9_]+"
                          r"|PJSUA_[A-Z0-9_]+|PJMEDIA_[A-Z0-9_]+|PJSIP_[A-Z0-9_]+"
                          r"|PJ_[A-Z0-9_]+|pj_[a-z0-9_]+)\b", code))
    used -= LOCAL

    missing = sorted(s for s in used if not re.search(r"\b" + re.escape(s) + r"\b", headers))

    print(f"PJSIP identifiers used by the bridge : {len(used)}")
    print(f"  found in the vendored headers      : {len(used) - len(missing)}")
    for s in missing:
        for m in re.finditer(r"\b" + re.escape(s) + r"\b", code):
            line = source[:source.index(m.group(0), m.start() - 200 if m.start() > 200 else 0)].count("\n") + 1 \
                   if False else code[:m.start()].count("\n") + 1
            print(f"  NOT IN ANY HEADER                  : {s}  (first use ~line {line})")
            break

    print()
    print("pjsip symbols: OK" if not missing else f"pjsip symbols: {len(missing)} unknown")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
