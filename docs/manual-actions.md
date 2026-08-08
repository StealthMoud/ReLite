# Manual actions

Steps ReLite will never execute automatically, because they are
destructive, irreversible, or require a decision only the device owner
can make. Each entry states the exact procedure and its risks so a human
can execute it deliberately, and what ReLite does instead (or in
addition).

There is currently **no pending manual action for the RMX5303** — no
destructive step has been found necessary yet, because no physical device
has been available in the environment that produced this scaffolding to
even reach the point of needing one. This file is here so the pattern
exists before it's needed; entries get added as investigation actually
requires them (see `research/bootloader.md`, `research/treble-gsi.md`).

## Template for adding an entry

```markdown
## <short title>

**Status:** pending human execution / not yet required / completed on <date>

**Why ReLite doesn't do this automatically:** <reason — irreversibility,
data loss risk, security posture change, etc.>

**Prerequisites:**
- <backup requirement>
- <recovery path confirmed available>

**Procedure:**
```bash
<exact commands, in order>
```

**What ReLite does before/after:** <e.g. "relite snapshot --name
pre-unlock records full state first">
```

## Anticipated future entries (not yet actioned)

These are named here because the master plan calls them out explicitly as
things that would need this treatment *if* pursued — not because a
decision to pursue them has been made.

### Bootloader unlock (if GSI/OS work is ever pursued)

**Status:** not yet required — `research/bootloader.md`'s feasibility
verdict is still `UNKNOWN` pending device access. Unlocking is a
prerequisite for GSI/OS work only, never for Stage 1 (stock-ROM CLI +
ReLite Home) functionality.

**Why ReLite doesn't do this automatically:** `fastboot flashing unlock`
triggers a factory data reset (irreversible without a prior backup) and
flips Verified Boot to an unlocked state, which some banking/DRM apps
detect and refuse to run under.

**Prerequisites (once pursued):**
- Full `relite snapshot --name pre-unlock` and an off-device backup of
  anything not reproducible from that snapshot (personal files, app
  data ReLite doesn't track).
- Confirmed OEM unlock allowance (`fastboot flashing get_unlock_ability`,
  see `research/bootloader.md`) and "OEM unlocking" enabled in Developer
  Options — a manual, reversible Settings toggle, not scripted by ReLite.

**Procedure:** to be written here, exactly, once bootloader research
concludes the device is actually unlockable. Not written speculatively —
an untested procedure documented as if verified would be worse than no
procedure at all.

### GSI flash (if Treble/GSI feasibility is positive)

**Status:** not yet required — gated on `research/treble-gsi.md`
returning `SUPPORTED` or `POSSIBLY SUPPORTED`, and on the bootloader
unlock above.

Same treatment as above: exact `fastboot flash system <gsi>.img` / DSU
install procedure written here only once feasibility is confirmed, with
the reboot-to-stock recovery path validated first.
