#!/usr/bin/env python3
"""Resolve the GhostLock build target matrix and optionally prune the params
tables so a build covers exactly the selected device / SoC / kernel line.

Used by .github/workflows/build.yml (manual workflow_dispatch runs). It keeps
the two authoritative tables - exploit/src/params_table.c and the Kotlin mirror
app/src/main/java/indev/ghostlock/s26/ParamsTable.kt - in sync.

Usage (prints a JSON matrix to stdout):
  tailor.py --device all --processor all --kernel-line auto [--tailor]

  --device      device codename: all | m1q | m2q | m3q | m1s | m2s
  --processor   SoC family:      all | snapdragon | exynos
  --kernel-line kernel line:     auto | cn | intl | exynos
  --tailor      rewrite the tables in place (no-op without this flag)

Without --tailor the matrix is only reported; with --tailor the tables are
pruned to the resolved lines/devices before the native + APK build runs.
"""

import argparse
import json
import re
import sys

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

PARAMS_C = ROOT / "exploit" / "src" / "params_table.c"
PARAMS_KT = ROOT / "app/src/main/java/indev/ghostlock/s26/ParamsTable.kt"

DEVICE_LINES = {
    "m1q": ("cn", "intl"),
    "m2q": ("cn", "intl"),
    "m3q": ("cn", "intl"),
    "m1s": ("exynos",),
    "m2s": ("exynos",),
}

PROC_LINES = {
    "snapdragon": ("cn", "intl"),
    "exynos": ("exynos",),
    "all": ("cn", "intl", "exynos"),
}

PROC_DEVICES = {
    "snapdragon": ("m1q", "m2q", "m3q"),
    "exynos": ("m1s", "m2s"),
    "all": ("m1q", "m2q", "m3q", "m1s", "m2s"),
}

LINES_ORDER = ("cn", "intl", "exynos")


def resolve_lines(device: str, processor: str, kernel_line: str) -> list[str]:
    if kernel_line != "auto":
        return [kernel_line]
    if device != "all":
        lines = set()
        for dev in device.split(","):
            lines.update(DEVICE_LINES[dev])
        return [ln for ln in LINES_ORDER if ln in lines]
    return [ln for ln in LINES_ORDER if ln in PROC_LINES[processor]]


def resolve_devices(device: str, processor: str, lines: list[str]) -> list[str]:
    candidates = [device] if device != "all" else list(PROC_DEVICES[processor])
    line_set = set(lines)
    kept = [dev for dev in candidates if set(DEVICE_LINES[dev]) & line_set]
    return kept or candidates


# --------------------------------------------------------------------------
# exploit/src/params_table.c
# --------------------------------------------------------------------------

def find_array(text: str, name: str) -> tuple[int, int]:
    m = re.search(r"\b" + re.escape(name) + r"\s*\[\]\s*=\s*\{", text)
    if not m:
        raise SystemExit(f"error: C array '{name}' not found in {PARAMS_C}")
    start = text.index("{", m.start())
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i
    raise SystemExit(f"error: unbalanced braces for '{name}' in {PARAMS_C}")


def split_entries(body: str) -> list[str]:
    entries, start, depth = [], None, 0
    for i, ch in enumerate(body):
        if ch == "{":
            if depth == 0 and start is None:
                start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and start is not None:
                entries.append(body[start : i + 1])
                start = None
    return entries


def entry_fields(entry: str) -> dict[str, str]:
    return {k: v for k, v in re.findall(r"\.(\w+)\s*=\s*\"([^\"]+)\"", entry)}


def render_entries(entries: list[str], indent: str = "    ") -> str:
    """Indent each entry block and join with ',\\n'. The wrapping ''{}'
    braces + trailing ';' stay in the caller's text."""
    if not entries:
        return ""
    out = [indent + e.strip() for e in entries]
    return ",\n".join(out) + "\n"


def prune_c(keep_lines: set[str], keep_devices: set[str]) -> bool:
    if not PARAMS_C.exists():
        return False
    text = PARAMS_C.read_text(encoding="utf-8")

    start, close = find_array(text, "kernel_lines")
    entries = split_entries(text[start + 1 : close])
    kept = [e for e in entries if entry_fields(e).get("line_id") in keep_lines]
    text = text[: start + 1] + "\n" + render_entries(kept) + text[close:]

    start, close = find_array(text, "device_map")
    entries = split_entries(text[start + 1 : close])
    kept = []
    for e in entries:
        f = entry_fields(e)
        if f.get("line_id") in keep_lines and (
            not keep_devices or f.get("device") in keep_devices
        ):
            kept.append(e)
    text = text[: start + 1] + "\n" + render_entries(kept) + text[close:]

    PARAMS_C.write_text(text, encoding="utf-8")
    return True


# --------------------------------------------------------------------------
# ParamsTable.kt (Kotlin mirror, UI verdict only)
# --------------------------------------------------------------------------

def replace_list_block(text: str, marker: str, keep_line) -> str:
    """Replace the single list block beginning with 'marker(' up to its
    closing '\n    )' line, keeping only entries for which keep_line() is True."""
    i = text.find(marker)
    if i < 0:
        raise SystemExit(f"error: '{marker}' not found in {PARAMS_KT}")
    open_paren = text.find("(", i)
    rest = text[open_paren:]
    m = re.match(r"\((.*?)\n\s*\)", rest, re.S)
    if not m:
        raise SystemExit(f"error: cannot parse list block '{marker}' in {PARAMS_KT}")
    kept = [ln for ln in m.group(1).splitlines() if keep_line(ln)]
    rebuilt = "(\n" + "\n".join(kept) + "\n    )"
    return text[:open_paren] + rebuilt + text[open_paren + m.end() :]


def prune_kt(keep_lines: set[str], keep_devices: set[str]) -> bool:
    if not PARAMS_KT.exists():
        return False
    text = PARAMS_KT.read_text(encoding="utf-8")

    def keep_family(line: str) -> bool:
        mm = re.match(r'\s*FamilyMember\("([^"]+)"', line)
        return bool(mm) and mm.group(1) in keep_devices

    def keep_entry(line: str) -> bool:
        mm = re.match(r'\s*DeviceEntry\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)', line)
        return bool(mm) and mm.group(2) in keep_devices and mm.group(3) in keep_lines

    new_lines = ", ".join(f'"{ln}"' for ln in LINES_ORDER if ln in keep_lines)
    text = re.sub(
        r"val lines = setOf\([^)]*\)",
        f"val lines = setOf({new_lines})",
        text,
    )
    text = replace_list_block(text, "val family: List<FamilyMember> = listOf", keep_family)
    text = replace_list_block(text, "val deviceMap: List<DeviceEntry> = listOf", keep_entry)

    PARAMS_KT.write_text(text, encoding="utf-8")
    return True


# --------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="all")
    ap.add_argument("--processor", default="all")
    ap.add_argument("--kernel-line", default="auto")
    ap.add_argument("--tailor", action="store_true")
    args = ap.parse_args()

    if args.device not in ("all", *DEVICE_LINES):
        raise SystemExit(f"error: unknown device '{args.device}'")
    if args.processor not in PROC_LINES:
        raise SystemExit(f"error: unknown processor '{args.processor}'")
    if args.kernel_line not in ("auto", *LINES_ORDER):
        raise SystemExit(f"error: unknown kernel line '{args.kernel_line}'")

    lines = resolve_lines(args.device, args.processor, args.kernel_line)
    devices = resolve_devices(args.device, args.processor, lines)

    matrix = {
        "device": args.device,
        "processor": args.processor,
        "kernel_line": args.kernel_line,
        "lines": lines,
        "devices": devices,
        "tailored": False,
    }

    if args.tailor:
        c_ok = prune_c(set(lines), set(devices))
        kt_ok = prune_kt(set(lines), set(devices))
        matrix["tailored"] = c_ok or kt_ok
        sys.stderr.write(f"tailored params_table.c={c_ok} ParamsTable.kt={kt_ok}\n")

    sys.stderr.write(f"target: lines={','.join(lines)} devices={','.join(devices)}\n")
    print(json.dumps(matrix))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())