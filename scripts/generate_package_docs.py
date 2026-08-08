#!/usr/bin/env python3
"""Generate a human-readable package table from a device's machine-readable
packages.yaml / protected-packages.yaml, so the two never drift apart.

Usage:
    python scripts/generate_package_docs.py devices/realme/RMX5303 > devices/realme/RMX5303/PACKAGES.md

Run this after editing packages.yaml/protected-packages.yaml — don't hand
-edit the generated file, it's regenerated wholesale.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from relite.classifier import load_database  # noqa: E402

PROFILES = ["safe", "performance", "maximum"]


def _first_sentence(reason: str) -> str:
    text = " ".join(reason.split())
    for sep in (". ", " — "):
        if sep in text:
            return text.split(sep, 1)[0] + ("." if sep == ". " else "")
    return text[:140] + ("…" if len(text) > 140 else "")


def render(device_dir: Path) -> str:
    db = load_database(device_dir)
    device_name = device_dir.name

    lines = [
        f"# {device_name} package reference (generated)",
        "",
        "**Generated from `packages.yaml` / `protected-packages.yaml` by "
        "`scripts/generate_package_docs.py` — do not hand-edit.** Regenerate "
        "after changing either source file.",
        "",
        "## Profile inheritance",
        "",
        "```text",
        "safe",
        "  ↓  (+ optional OEM services, duplicate apps, diagnostics)",
        "performance",
        "  ↓  (+ remaining nonessential validated components)",
        "maximum",
        "```",
        "",
        "Each level is a strict superset of the one above it: nothing `safe` "
        "disables is ever re-enabled by `performance` or `maximum`, and "
        "`maximum` never removes anything `safe`/`performance` leave alone "
        "without it being listed as an additional action below. See "
        "`docs/profiles.md` for what qualifies a package for each level.",
        "",
        "## Classified packages",
        "",
        f"{len(db.entries)} packages have a classification entry; "
        f"{len(db.protected)} packages are hard-protected and never appear "
        "in any profile's change list regardless of classification.",
        "",
        "| Package | Category | Confidence | safe | performance | maximum | Notes |",
        "|---|---|---|---|---|---|---|",
    ]

    for name in sorted(db.entries):
        entry = db.entries[name]
        actions = [entry.action_for(p) for p in PROFILES]  # type: ignore[arg-type]
        notes = []
        if entry.platform_limitation:
            notes.append("⚠ known platform limitation")
        if not entry.rollback_supported:
            notes.append("no rollback")
        note_str = "; ".join(notes) if notes else ""
        lines.append(
            f"| `{name}` | {', '.join(entry.category)} | {entry.confidence} | "
            f"{actions[0]} | {actions[1]} | {actions[2]} | {note_str} |"
        )

    lines += [
        "",
        "## Protected packages (never touched by any profile)",
        "",
        "| Package | Reason |",
        "|---|---|",
    ]
    for name in sorted(db.protected):
        reason = _first_sentence(db.protected[name].reason)
        lines.append(f"| `{name}` | {reason} |")

    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    print(render(Path(sys.argv[1])))
