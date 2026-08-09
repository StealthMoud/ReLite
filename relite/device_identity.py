"""Per-physical-device local state isolation.

Section 7 of the v0.2.0 plan: `.local/state.json`, `.local/actions.jsonl`,
and `.local/<model>/snapshots/` used to be keyed mainly by device *model*,
which is unsafe once two devices are in play — two RMX5303 units, or a
second ReLite-supported model connected at a different time would silently
share (and corrupt) each other's state and rollback data.

The raw ADB serial is never written to any committed or shared file: the
on-disk directory name is `<model>-<8 hex chars of sha256(serial)>`, which
is stable per physical unit but not reversible to the serial from the
directory name alone.
"""

from __future__ import annotations

import hashlib
import re
import shutil
from pathlib import Path

_SANITIZE_RE = re.compile(r"[^A-Za-z0-9_.-]+")


def default_local_root() -> Path:
    return Path(".local")


def device_key(model: str, serial: str) -> str:
    """A stable, non-reversible identifier for one physical device."""
    safe_model = _SANITIZE_RE.sub("_", model.strip()) or "unknown"
    serial_hash = hashlib.sha256(serial.encode("utf-8")).hexdigest()[:8]
    return f"{safe_model}-{serial_hash}"


def device_local_dir(root: Path, model: str, serial: str) -> Path:
    """`.local/<device_key>/` — the isolated root for one physical device's
    state, journal, and snapshots."""
    return root / device_key(model, serial)


def migrate_legacy_layout(root: Path, model: str, serial: str) -> Path:
    """One-time migration from the pre-v0.2.0 layout (state/journal keyed
    only by model, shared across every device of that model) into the new
    per-device directory. Never destroys old data: legacy files are moved,
    not copied-and-deleted-elsewhere, and migration is skipped entirely if
    the destination already has its own state (a second device of the same
    model must never inherit the first device's history).
    """
    new_dir = device_local_dir(root, model, serial)
    if new_dir.exists():
        return new_dir  # already migrated (or already a fresh per-device dir)

    legacy_state = root / "state.json"
    legacy_journal = root / "actions.jsonl"
    legacy_snapshots = root / model / "snapshots"

    if not (legacy_state.exists() or legacy_journal.exists() or legacy_snapshots.exists()):
        return new_dir  # nothing to migrate

    new_dir.mkdir(parents=True, exist_ok=True)
    if legacy_state.exists():
        shutil.move(str(legacy_state), str(new_dir / "state.json"))
    if legacy_journal.exists():
        shutil.move(str(legacy_journal), str(new_dir / "actions.jsonl"))
    if legacy_snapshots.exists():
        shutil.move(str(legacy_snapshots), str(new_dir / "snapshots"))
    return new_dir
