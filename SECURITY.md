# Security Policy

ReLite operates on user-owned Android devices over ADB, without requiring
root or an unlocked bootloader. That still means it touches a device's
package and settings state, so we take security and safety issues
seriously.

## Reporting a vulnerability

Please **do not** open a public issue for security-sensitive reports
(e.g. a ReLite action that could brick a device, corrupt user data, expose
personal information, or bypass a protected-package safeguard).

Instead, open a private security advisory on the repository's GitHub
"Security" tab, or contact the maintainers directly through the contact
listed in the repository profile. Include:

- ReLite version / commit hash
- Device model and firmware (sanitized — no IMEI/serial/etc.)
- Exact command run and observed vs. expected behavior
- Logs with `relite sanitize` already applied

## Scope

In scope:

- Any ReLite command that could remove/disable a package outside the
  documented protected-package policy (`docs/safety.md`).
- Any code path that could commit personally identifiable device data
  (see `relite/sanitize.py`) into a public snapshot, benchmark result, or
  fixture.
- Any destructive command ReLite would execute automatically without
  being listed in `docs/manual-actions.md` first (bootloader unlock,
  `fastboot flash`/`erase`, `dd` to a block device — these must never be
  automatic).

Out of scope: vulnerabilities in stock realme/UNISOC firmware itself —
report those to realme, not this project.

## Supported versions

Only the latest `main` branch and the most recent tagged release receive
security fixes pre-1.0.
