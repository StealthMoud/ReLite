"""Input validation for anything that ends up interpolated into an `adb
shell` command line. adb shell ultimately runs through a shell on the
device, so any external value (a user-supplied DNS hostname, a package or
component name pulled from a config file) must be checked against the
identifier syntax it's supposed to have *before* it's ever placed in a
command string — never passed through as arbitrary text.

Section 8 of the v0.2.0 plan: reject anything containing shell metacharacters
by construction, rather than trying to escape them.
"""

from __future__ import annotations

import re


class ValidationError(ValueError):
    """Raised when an external value does not match the syntax ReLite expects."""


# Android package name: dot-separated identifiers, each starting with a
# letter. Real-device finding (RMX5303, 2026-08-09): the platform framework
# package itself is literally named "android" — a single segment, no dot —
# so a dot cannot be required, even though every third-party/OEM package
# ReLite otherwise deals with has at least one.
_PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*$")

# Android component name: "package/Class", where Class may be a
# shorthand-relative name (".Class") or fully qualified, with optional
# inner-class '$' separators.
_COMPONENT_RE = re.compile(
    r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*/\.?[A-Za-z][A-Za-z0-9_.$]*$"
)

_HOSTNAME_LABEL_RE = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

_SETTING_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# User-controlled local artifact names (snapshot names, benchmark labels):
# conservative, no path separators, no leading dot (rules out "." and
# "..", both of which are filesystem-meaningful rather than a name).
_LOCAL_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_LOCAL_NAME_MAX_LENGTH = 100


def validate_package_name(name: str) -> str:
    if not name or len(name) > 255 or not _PACKAGE_RE.match(name):
        raise ValidationError(f"not a valid Android package name: {name!r}")
    return name


def validate_component_name(component: str) -> str:
    if not component or len(component) > 300 or not _COMPONENT_RE.match(component):
        raise ValidationError(f"not a valid Android component name: {component!r}")
    return component


def validate_dns_hostname(hostname: str) -> str:
    """Validate a Private DNS provider hostname (RFC 1123 syntax, conservative)."""
    if not hostname or len(hostname) > 253:
        raise ValidationError(f"not a valid DNS hostname: {hostname!r}")
    labels = hostname.split(".")
    if len(labels) < 2:
        raise ValidationError(f"not a valid DNS hostname: {hostname!r}")
    for label in labels:
        if not _HOSTNAME_LABEL_RE.match(label):
            raise ValidationError(f"not a valid DNS hostname: {hostname!r}")
    return hostname


def validate_setting_key(key: str) -> str:
    if not key or not _SETTING_KEY_RE.match(key):
        raise ValidationError(f"not a valid settings key: {key!r}")
    return key


def validate_local_name(name: str) -> str:
    """Validate a user-controlled local artifact name — a snapshot name
    (`relite snapshot --name ...`), a benchmark label, or similar — used
    to build a filesystem path under `.local/`. Rejects `.`, `..`, path
    separators, and control characters by construction, so a name like
    `../../something` can never escape the intended ReLite directory.
    """
    if not name or len(name) > _LOCAL_NAME_MAX_LENGTH or not _LOCAL_NAME_RE.match(name):
        raise ValidationError(f"not a valid local artifact name: {name!r}")
    if name in (".", ".."):
        raise ValidationError(f"not a valid local artifact name: {name!r}")
    return name
