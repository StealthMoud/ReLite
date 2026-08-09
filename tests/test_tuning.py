from __future__ import annotations

import pytest

from relite.tuning import restore_managed_setting, set_private_dns
from relite.validate import ValidationError


def test_set_private_dns_off_when_hostname_none(fake_client, fake_runner):
    assert set_private_dns(fake_client, None) is True
    called = [" ".join(c) for c in fake_runner.calls]
    assert any("settings put global private_dns_mode off" in c for c in called)


def test_set_private_dns_writes_mode_and_hostname(fake_client, fake_runner):
    assert set_private_dns(fake_client, "dns.example.com") is True
    called = [" ".join(c) for c in fake_runner.calls]
    assert any("private_dns_mode hostname" in c for c in called)
    assert any("private_dns_specifier dns.example.com" in c for c in called)


def test_set_private_dns_rejects_shell_injection_attempt(fake_client, fake_runner):
    with pytest.raises(ValidationError):
        set_private_dns(fake_client, "dns.example.com; rm -rf /")
    assert fake_runner.calls == []


def test_restore_managed_setting_deletes_when_originally_absent(fake_client, fake_runner):
    restore_managed_setting(fake_client, "global", "private_dns_mode", None)
    called = [" ".join(c) for c in fake_runner.calls]
    assert any("settings delete global private_dns_mode" in c for c in called)


def test_restore_managed_setting_restores_original_value(fake_client, fake_runner):
    restore_managed_setting(fake_client, "global", "window_animation_scale", "1.0")
    called = [" ".join(c) for c in fake_runner.calls]
    assert any("settings put global window_animation_scale 1.0" in c for c in called)


def test_restore_managed_setting_dry_run_makes_no_calls(fake_client, fake_runner):
    restore_managed_setting(fake_client, "global", "window_animation_scale", "1.0", dry_run=True)
    assert fake_runner.calls == []


def test_restore_managed_setting_rejects_malformed_animation_scale(fake_client, fake_runner):
    # A value that doesn't look like a plausible scale is not blindly written.
    restore_managed_setting(fake_client, "global", "window_animation_scale", "1.0; rm -rf /")
    assert fake_runner.calls == []
