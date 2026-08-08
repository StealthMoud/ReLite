from __future__ import annotations

from relite.sanitize import find_leaks, sanitize_dict, sanitize_key_value, sanitize_text


def test_sanitize_text_redacts_imei_like_number():
    text = "Device IMEI: 123456789012345 reporting in"
    result = sanitize_text(text)
    assert "123456789012345" not in result
    assert "[REDACTED]" in result


def test_sanitize_text_redacts_mac_address():
    text = "BSSID: AA:BB:CC:DD:EE:FF connected"
    assert "AA:BB:CC:DD:EE:FF" not in sanitize_text(text)


def test_sanitize_text_redacts_email():
    text = "account_name=someone@example.com"
    assert "someone@example.com" not in sanitize_text(text)


def test_sanitize_key_value_redacts_known_identifying_keys():
    assert sanitize_key_value("android_id", "abc123abcdef1234") == "[REDACTED]"
    assert sanitize_key_value("ro.serialno", "ANYVALUE") == "[REDACTED]"


def test_sanitize_dict_recurses_and_redacts():
    data = {
        "device": {"android_id": "abc123abcdef1234", "model": "RMX5303"},
        "notes": ["contact me at someone@example.com"],
    }
    result = sanitize_dict(data)
    assert result["device"]["android_id"] == "[REDACTED]"
    assert result["device"]["model"] == "RMX5303"
    assert "someone@example.com" not in result["notes"][0]


def test_find_leaks_detects_unredacted_identifiers():
    assert "email" in find_leaks("contact: someone@example.com")
    assert find_leaks("nothing sensitive here") == []
