"""Baseline-aware desired-state profile planner (sections 2-3 of the
v0.3.0 plan).

The v0.2.0 planner only ever considered packages currently installed for
user 0, so a package `pm uninstall --user 0`'d by `maximum` was simply
absent from `list_packages()` and never reconsidered when moving back to
`performance` or `safe` — profile transitions only worked in the
"loosen from stock" direction, never truly bidirectionally.

This planner instead computes an explicit *desired state* for every
classified package from two independent inputs — the package's
pre-ReLite baseline state (from the recorded baseline snapshot) and the
target profile's action — and asks `relite.package_state.plan_transition`
for the minimal live transition from wherever the package actually is
right now to that desired state. `keep` means "whatever it was before
ReLite ever touched it", not "must be enabled" — a user who had already
disabled or uninstalled something before running ReLite keeps that
choice under `keep`.
"""

from __future__ import annotations

from dataclasses import dataclass

from relite.classifier import Action, ClassificationDatabase, Profile
from relite.package_state import PackageState, StateTransition, plan_transition


def desired_state_for(baseline: PackageState, action: Action) -> PackageState:
    """The state `action` should produce for a package with the given
    pre-ReLite baseline state.

    - keep: the baseline itself — never resurrects something the user
      had already disabled/removed, never force-enables something that
      merely defaults to "keep" because it's unclassified-for-this-field.
    - disable: PRESENT_DISABLED, unless the package was already absent
      at baseline — disable cannot resurrect an absent package just to
      immediately disable it.
    - uninstall-user: always ABSENT_FOR_USER, regardless of baseline —
      this is the one action allowed to remove something that was
      present at baseline.
    """
    if action == "keep":
        return baseline
    if action == "disable":
        if baseline == PackageState.ABSENT_FOR_USER:
            return PackageState.ABSENT_FOR_USER
        return PackageState.PRESENT_DISABLED
    if action == "uninstall-user":
        return PackageState.ABSENT_FOR_USER
    raise ValueError(f"unknown action {action!r}")


@dataclass
class DesiredTransition:
    package: str
    action: Action
    reason: str
    baseline_state: PackageState
    current_state: PackageState
    desired_state: PackageState
    transition: StateTransition

    @property
    def is_noop(self) -> bool:
        return self.transition.is_noop


def plan_profile_transition(
    baseline_states: dict[str, PackageState],
    current_states: dict[str, PackageState],
    db: ClassificationDatabase,
    profile: Profile,
) -> list[DesiredTransition]:
    """The full set of live transitions needed to move every classified,
    unprotected package from its current state to what `profile` wants,
    given each package's recorded pre-ReLite baseline.

    `baseline_states`/`current_states` map package name -> PackageState;
    a package missing from either is treated as PRESENT_ENABLED for
    baseline (the ordinary default) or ABSENT_FOR_USER for current (it
    wasn't in the live `list_packages()` result at all).
    """
    plan: list[DesiredTransition] = []
    for name in db.entries:
        if db.is_protected(name):
            continue
        baseline = baseline_states.get(name, PackageState.PRESENT_ENABLED)
        current = current_states.get(name, PackageState.ABSENT_FOR_USER)
        action = db.decide(name, profile)
        desired = desired_state_for(baseline, action)
        transition = plan_transition(name, current, desired)
        if transition.is_noop:
            continue
        classification = db.classify(name)
        plan.append(
            DesiredTransition(
                package=name,
                action=action,
                reason=classification.reason or "no reason recorded",
                baseline_state=baseline,
                current_state=current,
                desired_state=desired,
                transition=transition,
            )
        )
    return plan
