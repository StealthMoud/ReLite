"""Per-physical-device local state isolation.

Section 7 of the v0.2.0 plan: `.local/state.json`, `.local/actions.jsonl`,
and `.local/<model>/snapshots/` used to be keyed mainly by device *model*,
which is unsafe once two devices are in play — two RMX5303 units, or a
second ReLite-supported model connected at a different time would silently
share (and corrupt) each other's state and rollback data.

The on-disk directory name is `<model>-<hex>`, a *pseudonymous* identifier
(section 18 of the v0.3.0 plan) — HMAC-SHA256 of the serial keyed by a
random, install-local salt, not a bare unsalted hash. A bare
`sha256(serial)[:8]` is not "non-reversible": ADB serials commonly come
from a small, guessable character set at a fixed length, making an
unsalted hash of one brute-forceable offline. The salt (`.local/.device_salt`,
gitignored, generated on first use) means the key can't be recomputed
without it, even by someone who knows or guesses the serial.
"""

from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import shutil
from pathlib import Path

_SANITIZE_RE = re.compile(r"[^A-Za-z0-9_.-]+")
_SALT_FILENAME = ".device_salt"
_KEY_HEX_LENGTH = 20


def default_local_root() -> Path:
    return Path(".local")


def _load_or_create_salt(root: Path) -> bytes:
    salt_path = root / _SALT_FILENAME
    if salt_path.exists():
        try:
            salt = bytes.fromhex(salt_path.read_text().strip())
            if salt:
                return salt
        except ValueError:
            pass  # corrupt salt file — fall through and regenerate
    salt = secrets.token_bytes(32)
    root.mkdir(parents=True, exist_ok=True)
    salt_path.write_text(salt.hex() + "\n")
    return salt


def _safe_model(model: str) -> str:
    return _SANITIZE_RE.sub("_", model.strip()) or "unknown"


def device_key(root: Path, model: str, serial: str) -> str:
    """A stable, pseudonymous identifier for one physical device."""
    salt = _load_or_create_salt(root)
    digest = hmac.new(salt, serial.encode("utf-8"), hashlib.sha256).hexdigest()[:_KEY_HEX_LENGTH]
    return f"{_safe_model(model)}-{digest}"


def _legacy_v02_device_key(model: str, serial: str) -> str:
    """The unsalted `sha256(serial)[:8]` key v0.2.0 used, needed only to
    locate a v0.2.0 directory for migration (section 19) — never used for
    anything else, since it's exactly the weak, guessable scheme being
    replaced."""
    digest = hashlib.sha256(serial.encode("utf-8")).hexdigest()[:8]
    return f"{_safe_model(model)}-{digest}"


def device_local_dir(root: Path, model: str, serial: str) -> Path:
    """`.local/<device_key>/` — the isolated root for one physical device's
    state, journal, and snapshots."""
    return root / device_key(root, model, serial)


def migrate_legacy_layout(root: Path, model: str, serial: str) -> Path:
    """One-time migration into the current per-device directory scheme.
    Never destroys old data, and never guesses ownership of ambiguous
    data (section 19):

    1. A v0.2.0 directory (keyed by the old unsalted hash) CAN be safely
       identified for the device currently connected, because that
       device's old key is deterministically recomputable from its real
       serial right now. If found, it's moved to the new salted-key
       directory.
    2. A pre-v0.2.0 flat layout (`.local/state.json`,
       `.local/<model>/snapshots/`, shared by every device of that
       model) is ambiguous — it could belong to *this* device or a
       different unit of the same model that was used before per-device
       isolation existed. It's moved into `.local/legacy-unassigned/`
       rather than either being silently claimed by whichever device
       happens to connect first, or left in a place a future ambiguous
       migration might trip over again.
    """
    new_dir = device_local_dir(root, model, serial)
    if new_dir.exists():
        return new_dir  # already migrated (or already a fresh per-device dir)

    legacy_v02_dir = root / _legacy_v02_device_key(model, serial)
    if legacy_v02_dir.exists() and legacy_v02_dir != new_dir:
        legacy_v02_dir.rename(new_dir)
        return new_dir

    legacy_state = root / "state.json"
    legacy_journal = root / "actions.jsonl"
    legacy_snapshots = root / model / "snapshots"

    if legacy_state.exists() or legacy_journal.exists() or legacy_snapshots.exists():
        unassigned_dir = root / "legacy-unassigned"
        unassigned_dir.mkdir(parents=True, exist_ok=True)
        if legacy_state.exists():
            shutil.move(str(legacy_state), str(unassigned_dir / "state.json"))
        if legacy_journal.exists():
            shutil.move(str(legacy_journal), str(unassigned_dir / "actions.jsonl"))
        if legacy_snapshots.exists():
            shutil.move(str(legacy_snapshots), str(unassigned_dir / f"{model}-snapshots"))

    return new_dir
