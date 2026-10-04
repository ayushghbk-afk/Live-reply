#!/usr/bin/env python3
"""Turn `aapt2 dump xmltree --file AndroidManifest.xml <apk>` output into a compact,
readable component/permission summary.

Used by .github/workflows/*.yml to publish what is actually inside the built APK as
check-run annotations (the API truncates each annotation at 4096 characters, so the
summary has to be terse) and into dist/BUILD-REPORT.txt.

Input : path to a file containing the aapt2 xmltree dump (or "-" for stdin)
Output: one line per component plus the permission block, e.g.

    manifest            package=com.liveaireply.app versionCode=2 versionName=1.0.1
    uses-permission     android.permission.INTERNET

Does not import anything outside the standard library, so it runs on a bare runner.
"""

from __future__ import annotations

import re
import sys

COMPONENT_TAGS = {
    "application",
    "activity",
    "activity-alias",
    "service",
    "receiver",
    "provider",
    "uses-permission",
    "uses-permission-sdk-23",
    "queries",
    "property",
    "meta-data",
}

# Attributes worth reporting, in the order they should appear.
WANTED = (
    "name",
    "package",
    "versionCode",
    "versionName",
    "minSdkVersion",
    "targetSdkVersion",
    "exported",
    "permission",
    "foregroundServiceType",
    "process",
    "isAccessibilityTool",
    "debuggable",
    "allowBackup",
    "usesCleartextTraffic",
    "authorities",
)

# Tags whose inner components (intent-filter, meta-data, ...) are noise for this summary.
SKIP_CHILDREN_OF = {"intent-filter"}

# android:foregroundServiceType is a bitmask; spell the bits out for readability.
FOREGROUND_SERVICE_TYPES = {
    0x00000001: "dataSync",
    0x00000002: "mediaPlayback",
    0x00000004: "phoneCall",
    0x00000008: "location",
    0x00000010: "connectedDevice",
    0x00000020: "mediaProjection",
    0x00000040: "camera",
    0x00000080: "microphone",
    0x00000100: "health",
    0x00000200: "remoteMessaging",
    0x00000400: "systemExempted",
    0x00000800: "shortService",
    0x40000000: "specialUse",
}


def decode_flags(value: int) -> str:
    names = [name for bit, name in FOREGROUND_SERVICE_TYPES.items() if value & bit]
    return f"{value} ({'|'.join(names)})" if names else str(value)

_ELEMENT = re.compile(r"^E:\s+([A-Za-z0-9_.-]+)")
_ATTRIBUTE = re.compile(r"^A:\s+(.+?)\s*=\s*(.*)$")


def attribute_name(raw: str) -> str:
    """`http://schemas.android.com/apk/res/android:exported(0x01010010)` -> `exported`."""
    name = raw.split("(")[0].strip()
    return name.rsplit(":", 1)[-1]


def attribute_value(raw: str) -> str:
    """Decode the aapt2 value forms: plain, typed booleans, typed ints, strings."""
    value = raw.strip()
    raw_match = re.search(r'\(Raw:\s*"(.*)"\)$', value)
    if raw_match:
        value = raw_match.group(1)
    typed = re.match(r"^\(type 0x([0-9a-fA-F]+)\)(.*)$", value)
    if typed:
        kind, payload = int(typed.group(1), 16), typed.group(2).strip()
        if kind == 0x12:  # TYPE_INT_BOOLEAN
            return "true" if payload not in ("0x0", "0", "false") else "false"
        if kind == 0x11 and payload.startswith("0x"):  # TYPE_INT_HEX
            return str(int(payload, 16))
        if kind == 0x10:  # TYPE_INT_DEC (aapt2 prints these in hex form)
            return str(int(payload, 0))
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    return value


def summarise(text: str) -> str:
    """aapt2 prints a flat list of `E:` lines (no closing tags), so nesting is taken from
    the indentation and a stack of open elements."""
    lines: list[str] = []
    current: str | None = None
    attributes: dict[str, str] = {}
    stack: list[tuple[int, str]] = []  # (indent, tag) of the currently open elements

    def ordered(values: dict[str, str]) -> dict[str, str]:
        return {k: values[k] for k in WANTED if k in values}

    def flush() -> None:
        if current is None:
            return
        detail = " ".join(f"{k}={v}" for k, v in ordered(attributes).items())
        if current in ("manifest", "uses-permission", "uses-permission-sdk-23", "queries"):
            lines.append(f"{current:<18}{detail}".rstrip())
        elif detail:
            lines.append(f"  {current:<16}{detail}".rstrip())

    def inside_skipped() -> bool:
        return any(tag in SKIP_CHILDREN_OF for _, tag in stack)

    for raw_line in text.splitlines():
        line = raw_line.strip()
        indent = len(raw_line) - len(raw_line.lstrip())
        element = _ELEMENT.match(line)
        if element:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            tag = element.group(1)
            if not inside_skipped():
                flush()
                current = tag
                attributes = {}
            else:
                # Inside <intent-filter>: those attributes are intent plumbing, not a
                # component declaration, so they are not reported.
                current = None
            stack.append((indent, tag))
            continue
        if inside_skipped():
            continue
        attribute = _ATTRIBUTE.match(line)
        if attribute and current is not None:
            name = attribute_name(attribute.group(1))
            if name in WANTED and name not in attributes:
                attributes[name] = attribute_value(attribute.group(2))
    flush()

    # Spell out foreground service types, which aapt2 prints as a bare bitmask.
    return "\n".join(
        re.sub(
            r"foregroundServiceType=(\d+)",
            lambda m: f"foregroundServiceType={decode_flags(int(m.group(1)))}",
            line,
        )
        for line in lines
    )


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else "-"
    text = sys.stdin.read() if source == "-" else open(source, encoding="utf-8", errors="replace").read()
    summary = summarise(text)
    if not summary.strip():
        print("(no components parsed from the manifest dump)", file=sys.stderr)
        return 1
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
