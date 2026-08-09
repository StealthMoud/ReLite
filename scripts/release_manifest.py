#!/usr/bin/env python3
"""Verify an APK's signature cryptographically (via `apksigner verify`,
never inferred from whether signing credentials were merely present at
build time) and write dist/release-manifest.json — sections 31-34 of the
v0.3.0 plan.

Usage:
    release_manifest.py <version> <apk_path> [--wheel <wheel_path>]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

_DEBUG_CERT_DN = "CN=Android Debug"
_CERT_DN_RE = re.compile(r"Signer #1 certificate DN:\s*(.+)")
_CERT_SHA256_RE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def find_apksigner() -> str:
    result = subprocess.run(
        [str(REPO_ROOT / "scripts" / "find_apksigner.sh")], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise RuntimeError(f"apksigner not found: {result.stderr.strip()}")
    return result.stdout.strip()


def verify_apk_signature(apk_path: Path) -> tuple[bool, str | None, str | None]:
    """Returns (verified_ok, certificate_dn, certificate_sha256).

    Section 31: never infers "signed" from whether credentials were
    present at build time — this actually runs `apksigner verify`
    against the built artifact and parses its own report.
    """
    apksigner = find_apksigner()
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk_path)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        return False, None, None

    dn_match = _CERT_DN_RE.search(result.stdout)
    sha_match = _CERT_SHA256_RE.search(result.stdout)
    return True, (dn_match.group(1).strip() if dn_match else None), (
        sha_match.group(1).lower() if sha_match else None
    )


def classify_signing(verified_ok: bool, cert_dn: str | None) -> str:
    if not verified_ok:
        return "unsigned"
    if cert_dn and _DEBUG_CERT_DN in cert_dn:
        return "debug"
    return "release"


def check_pinned_certificate(cert_sha256: str | None) -> str | None:
    """Section 33: if a real ReLite release certificate digest has been
    committed (docs/release-signing-cert.sha256 — the public digest
    only, never a private key), verify a "release"-classified APK's
    certificate actually matches it. Returns an error message, or None
    if there's nothing to check (no pin file yet) or it matches.
    """
    pin_path = REPO_ROOT / "docs" / "release-signing-cert.sha256"
    if not pin_path.exists():
        return None
    expected = pin_path.read_text().strip().lower()
    if cert_sha256 != expected:
        return f"certificate SHA-256 {cert_sha256} does not match pinned {expected}"
    return None


def build_manifest(version: str, apk_path: Path, wheel_path: Path | None) -> dict:
    verified_ok, cert_dn, cert_sha256 = verify_apk_signature(apk_path)
    signed_status = classify_signing(verified_ok, cert_dn)

    if signed_status == "release":
        pin_error = check_pinned_certificate(cert_sha256)
        if pin_error:
            raise SystemExit(f"error: {pin_error} — refusing to publish a release artifact with an unexpected key")

    git_commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    ).stdout.strip()

    manifest = {
        "version": version,
        "git_commit": git_commit,
        "build_timestamp": datetime.now(UTC).isoformat(),
        "apk": {
            "name": apk_path.name,
            "sha256": sha256_of(apk_path),
            "signed_status": signed_status,
            "certificate_dn": cert_dn,
            "certificate_sha256": cert_sha256 if signed_status == "release" else None,
        },
    }
    if wheel_path and wheel_path.exists():
        manifest["wheel"] = {"name": wheel_path.name, "sha256": sha256_of(wheel_path)}
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("apk_path", type=Path)
    parser.add_argument("--wheel", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "dist" / "release-manifest.json")
    args = parser.parse_args()

    manifest = build_manifest(args.version, args.apk_path, args.wheel)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"Wrote {args.out}")
    print(f"  APK signed_status: {manifest['apk']['signed_status']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
