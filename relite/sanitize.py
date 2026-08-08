"""Strip personally identifying data before anything is written to disk
under `devices/`, `research/`, or `benchmarks/results/`.

This module is deliberately conservative: it is better to over-redact a
benchmark log than to leak an IMEI into a public git history.
"""

from __future__ import annotations

import re
from typing import Any

# Property/setting keys whose values are considered identifying outright,
# regardless of what they contain.
IDENTIFYING_KEYS = {
    "ro.serialno",
    "ro.boot.serialno",
    "ril.serialnumber",
    "android_id",
    "advertising_id",
    "bluetooth_address",
    "wifi_mac_address",
    "device_name",
    "sim_operator",
    "sim_serial",
    "imei",
    "imsi",
    "phone_number",
    "line1number",
    "account_name",
    "gaia_id",
}

REDACTED = "[REDACTED]"

_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("imei", re.compile(r"\b\d{15}\b")),
    ("imsi", re.compile(r"\b\d{14,15}\b")),
    ("mac_or_bt", re.compile(r"\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")),
    ("email", re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")),
    # Requires actual group separators (space/dash) between digit runs, so
    # unformatted 10+ digit blobs — build timestamps embedded in a
    # firmware fingerprint, version codes, etc. — don't false-positive.
    # Real phone numbers are essentially never written as one unbroken
    # digit string in documentation/logs; when they are, IMEI/IMSI-length
    # bare digit runs are already covered by those patterns above.
    (
        "phone_number",
        re.compile(
            r"(?<!\d)(?!\d{4}-\d{2}-\d{2}(?!\d))(?:\+\d{1,3}[\s-])?(?:\d{2,4}[\s-]){2,4}\d{2,4}(?!\d)"
        ),
    ),
    (
        "bearer_token",
        re.compile(
            r"\b(?:(?=[A-Za-z0-9]*\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{20,}|ya29\.[\w\-.]+|AIza[\w\-]{35})\b"
        ),
    ),
    ("android_id_hex", re.compile(r"\b[0-9a-fA-F]{16}\b")),
    # Only match when /Users/ or /home/ starts a path token (preceded by
    # whitespace, a quote, or start of string) — otherwise this false
    # -positives on package/import paths that merely contain "home" as a
    # path segment, e.g. `io/relite/home/util/IconCache.kt`.
    ("home_path", re.compile(r"(?:^|(?<=[\s\"'`]))/(Users|home)/[^/\s]+")),
    ("ssid_quoted", re.compile(r'SSID:\s*"[^"]*"')),
]


def sanitize_text(text: str) -> str:
    """Redact identifying substrings from free-form text (e.g. logcat, dumpsys)."""
    result = text
    for _name, pattern in _PATTERNS:
        result = pattern.sub(REDACTED, result)
    return result


def sanitize_key_value(key: str, value: str) -> str:
    if key.lower() in IDENTIFYING_KEYS:
        return REDACTED
    return sanitize_text(value)


def _sanitize_list_item(item: Any) -> Any:
    if isinstance(item, dict):
        return sanitize_dict(item)
    if isinstance(item, str):
        return sanitize_text(item)
    return item


def sanitize_dict(data: dict[str, Any]) -> dict[str, Any]:
    """Recursively sanitize a JSON-like structure (snapshots, reports)."""
    out: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, dict):
            out[key] = sanitize_dict(value)
        elif isinstance(value, list):
            out[key] = [_sanitize_list_item(v) for v in value]
        elif isinstance(value, str):
            out[key] = sanitize_key_value(key, value)
        else:
            out[key] = value
    return out


def find_leaks(text: str) -> list[str]:
    """Return a list of pattern names that matched unredacted text — used by CI."""
    hits = []
    for name, pattern in _PATTERNS:
        if pattern.search(text):
            hits.append(name)
    return hits
