"""The manifest's expected-violation table, executed.

WHY THIS FILE EXISTS.  ``planning_rules.regression_config_rule`` says every
property carries a configuration that must keep FAILING, and that a regression
configuration which starts passing is a defect in the invariant rather than good
news.  Until this file nothing anywhere ran them, and nothing even READ the
table: ``run_tlc.sh`` has no caller in ``test_graph``, in pytest or in CI, the
graphs are suspended on push and pull request, and ``grep -rn
expected_violations`` over ``*.py *.sh *.kts *.java *.yml`` returned nothing at
all.  The table in ``spec_manifest.yaml`` was prose.

HIS-5 (#217) filed that as DEF-087 against its own pull request, because the
goal it was delivering says *"so the defect cannot come back unnoticed"* and
nine invariants with zero automatic enforcement do not meet it.  Review folded
the fix back into the ticket.  This is it.

WHAT IT ASSERTS, AND WHY THE WORDING MATTERS.  Not "TLC failed" -- **the
invariant TLC names is the one the manifest declared**.  TLC reports the FIRST
invariant it finds violated, in an order the author does not control, so a
configuration written to refute invariant X can be refuting invariant Y for
years while every transcript reads identically.  A check that accepted any
failure would pass in exactly that case, which is the thing HIS-5 spent its
whole ticket proving does not happen here.  Asserting the *name* is what makes
this a regression test rather than a smoke test.

TWO HALVES, AND THE SPLIT IS DELIBERATE.

*Structural* checks need no model checker and therefore run EVERYWHERE,
including on the CI runner, which has no ``tlc2``: every configuration the
manifest names exists on disk, every invariant it names is defined in the
module, every configuration on disk is declared in the manifest, and the
healthy configuration lists exactly the invariants the manifest says it does.
Those catch a table that has drifted from the files beside it -- which is the
shape ARTI-22 already shipped once, five ``.tla``/``.cfg`` copies landing
undeclared.

*Model-checking* checks shell ``run_tlc.sh`` and need ``tlc2``.  Where it is
absent they skip, and **a skip here is a gap, not a pass** -- see
``test_the_model_checking_half_is_not_silently_skipped``, which makes the
environment state it out loud instead of letting an empty run read as green.

IT MUST NOT REPAIR ANYTHING IT FINDS (DEF-067).  It shells TLC, reads stdout and
asserts.  It writes nothing, and it never edits a configuration to make a run
agree with the table.

NO YAML PARSER.  ``uv run --with pytest pytest`` supplies pytest and nothing
else, so ``import yaml`` would fail at collection and take the whole file with
it.  The neighbouring contract suites read the manifest as text for the same
reason.  The reader below is therefore hand-rolled -- and because a
hand-rolled reader that silently returns nothing is itself a check that cannot
fail, ``test_the_manifest_declares_a_table_to_execute`` asserts the table's
SIZE before anything is run, and the parser is exercised against a known-bad
input in ``test_the_reader_notices_a_table_that_disagrees_with_the_module``.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

MODEL_DIR = Path(__file__).resolve().parent.parent
MANIFEST = MODEL_DIR / "spec_manifest.yaml"
RUN_TLC = MODEL_DIR / "run_tlc.sh"
MODULE = MODEL_DIR / "HomeIntegrityInternal.tla"

VIOLATION_RE = re.compile(r"^Error: Invariant (\S+) is violated\.", re.MULTILINE)
CLEAN = "Model checking completed. No error has been found."


# ------------------------------------------------------------------ the reader


def _block(text: str, key: str) -> list[str]:
    """Lines under ``key:`` that are indented more deeply than it."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped == f"{key}:":
            indent = len(line) - len(line.lstrip())
            out = []
            for nxt in lines[i + 1:]:
                if not nxt.strip():
                    out.append(nxt)
                    continue
                if len(nxt) - len(nxt.lstrip()) <= indent:
                    break
                out.append(nxt)
            return out
    return []


def _cfg_table(key: str) -> dict[str, str]:
    """``<something>.cfg: SomeInvariant`` pairs under ``key:``."""
    pairs = {}
    for line in _block(MANIFEST.read_text(encoding="utf-8"), key):
        m = re.match(r"^\s+(\S+\.cfg):\s*(\S+)\s*$", line)
        if m:
            pairs[m.group(1)] = m.group(2)
    return pairs


def _guard_configs() -> dict[str, str]:
    """invariant -> guard cfg, from ``witness_dependence.witness_dependent``."""
    guards = {}
    invariant = None
    for line in _block(MANIFEST.read_text(encoding="utf-8"), "witness_dependent"):
        name = re.match(r"^\s+([A-Za-z]\w*):\s*$", line)
        if name:
            invariant = name.group(1)
            continue
        g = re.match(r"^\s+guard_config:\s*(\S+\.cfg)\s*$", line)
        if g and invariant:
            guards[invariant] = g.group(1)
    return guards


def _declared_invariants() -> list[str]:
    """Invariant names listed under the manifest's ``invariants:`` block."""
    return [
        m.group(1)
        for line in _block(MANIFEST.read_text(encoding="utf-8"), "invariants")
        if (m := re.match(r"^\s+-\s+([A-Za-z]\w*)\s*$", line))
    ]


EXPECTED_VIOLATIONS = _cfg_table("expected_violations")
PROBES = _cfg_table("reachability_probes")
GUARDS = _guard_configs()


# ------------------------------------------------------- structural (no tlc2)


def test_the_manifest_declares_a_table_to_execute():
    """A guard on this suite itself.

    Parametrised tests over an EMPTY table collect zero cases and report green,
    which is the shape of a check that cannot fail -- the defect this whole
    ticket is about, one level up in the harness rather than in the model.  So
    the table's size is asserted before anything is run, and the hand-rolled
    reader is the reason this assertion is not paranoia.
    """
    assert len(EXPECTED_VIOLATIONS) >= 14, EXPECTED_VIOLATIONS
    assert len(PROBES) >= 2, PROBES
    assert len(GUARDS) >= 1, GUARDS


def test_every_declared_configuration_exists():
    for cfg in list(EXPECTED_VIOLATIONS) + list(PROBES) + list(GUARDS.values()):
        assert (MODEL_DIR / cfg).is_file(), f"{cfg} is declared in the manifest and is not on disk"


def test_every_configuration_on_disk_is_declared():
    """No undeclared configurations.

    ARTI-22 shipped five ``.tla``/``.cfg`` copies into this directory undeclared
    and a review caught it; #103 spent a whole ticket repairing that shape.  A
    configuration nobody declares is a configuration nobody runs.
    """
    declared = set(EXPECTED_VIOLATIONS) | set(PROBES) | set(GUARDS.values()) | {
        "HomeIntegrityInternal.cfg"
    }
    on_disk = {p.name for p in MODEL_DIR.glob("HomeIntegrityInternal*.cfg")}
    assert on_disk - declared == set(), (
        f"configurations on disk that the manifest does not declare: "
        f"{sorted(on_disk - declared)}"
    )


def test_every_named_invariant_is_defined_in_the_module():
    """The table names invariants; the module must define them.

    This is what the reader is validated against -- point the table at an
    invariant that does not exist and this test is what says so.
    """
    tla = MODULE.read_text(encoding="utf-8")
    named = set(EXPECTED_VIOLATIONS.values()) | set(PROBES.values()) | set(GUARDS)
    for invariant in sorted(named):
        assert re.search(rf"^{re.escape(invariant)} ==", tla, re.MULTILINE), (
            f"{invariant} is named in spec_manifest.yaml and is not defined in "
            f"{MODULE.name}"
        )


def test_the_healthy_configuration_lists_every_declared_invariant():
    """``expected_violations`` and the healthy cfg must not drift apart.

    An invariant declared in the manifest but missing from the correct-behaviour
    configuration is never checked against a healthy home, which is half of
    what makes it an invariant at all.
    """
    cfg = (MODEL_DIR / "HomeIntegrityInternal.cfg").read_text(encoding="utf-8")
    listed = set(re.findall(r"^\s+([A-Za-z]\w*)\s*$", cfg.split("INVARIANTS")[1], re.MULTILINE))
    missing = set(_declared_invariants()) - listed
    assert not missing, f"declared in the manifest, absent from the healthy cfg: {sorted(missing)}"


def test_the_reader_notices_a_table_that_disagrees_with_the_module():
    """Validate the instrument against an input it MUST flag.

    HIS-8's sweep returned a confident all-zeros because its ``grep -E``
    pattern carried a literal pipe, and the reviewer of this pull request had
    its first cross-check show fourteen reds because zsh does not word-split an
    unquoted variable.  A tool is not believed here until it has been shown
    failing on something known-bad.
    """
    tla = MODULE.read_text(encoding="utf-8")
    assert not re.search(r"^ThisInvariantDoesNotExist ==", tla, re.MULTILINE)
    assert re.search(r"^ADivergentTreeIsNeverSilentlyReplaced ==", tla, re.MULTILINE)


# ---------------------------------------------------------- model checking


def _tlc_available() -> bool:
    if shutil.which("tlc2") or os.environ.get("TLA2TOOLS_JAR"):
        return True
    home = os.environ.get("SKILL_MANAGER_HOME") or str(Path.home() / ".skill-manager")
    return os.access(Path(home) / "bin" / "cli" / "tlc2", os.X_OK)


TLC = _tlc_available()
requires_tlc = pytest.mark.skipif(
    not TLC,
    reason="tlc2 not resolvable; install spec-double-compiler or set TLA2TOOLS_JAR",
)


def test_the_model_checking_half_is_not_silently_skipped(record_property):
    """A SKIP IS A GAP, NOT A PASS -- so the environment has to say which it is.

    Where ``tlc2`` is absent the model-checking half below does not run, and a
    suite that reports all-green having executed none of it is the exact defect
    this file exists to prevent.  This records the fact as a property on the
    run, so ``-rs`` or a report reader sees "the table was NOT executed here"
    rather than an unbroken row of dots.

    Set ``SPEC_REQUIRE_TLC=1`` -- as the epic's local pre-merge signal does --
    to turn the absence into a FAILURE instead of a note.
    """
    record_property("tlc_available", TLC)
    record_property("expected_violations_declared", len(EXPECTED_VIOLATIONS))
    if os.environ.get("SPEC_REQUIRE_TLC") == "1":
        assert TLC, (
            "SPEC_REQUIRE_TLC=1 and tlc2 is not resolvable, so the "
            "expected-violation table would not be executed at all."
        )


def run_tlc(cfg_name: str) -> str:
    """Run one configuration and return TLC's combined output.

    Invoked through ``bash`` rather than executed directly: ``run_tlc.sh`` is
    committed mode 100644, so relying on the exec bit would make this suite
    depend on a file mode nobody asserts.
    """
    cfg = MODEL_DIR / cfg_name
    proc = subprocess.run(
        ["bash", str(RUN_TLC), str(MODULE), str(cfg)],
        capture_output=True,
        text=True,
        timeout=600,
    )
    return proc.stdout + proc.stderr


def violated(output: str) -> str | None:
    match = VIOLATION_RE.search(output)
    return match.group(1) if match else None


@requires_tlc
@pytest.mark.parametrize("cfg,invariant", sorted(EXPECTED_VIOLATIONS.items()))
def test_expected_violation_reports_the_declared_invariant(cfg, invariant):
    """Each regression configuration fails, ON THE INVARIANT THE MANIFEST NAMES.

    The second assertion is the point.  ``assert reported is not None`` alone
    would pass when a configuration fails on a NEIGHBOUR, which is exactly the
    mechanism-A error this ticket measured its way out of.
    """
    reported = violated(run_tlc(cfg))
    assert reported is not None, (
        f"{cfg} reported NO violation. Per regression_config_rule a regression "
        f"configuration that starts passing is a defect in the invariant "
        f"{invariant!r}, not good news."
    )
    assert reported == invariant, (
        f"{cfg} failed on {reported!r}, but the manifest declares it as the "
        f"expected violation of {invariant!r}. A configuration that fails for a "
        f"reason other than the invariant under test is vacuity-ledger "
        f"mechanism A: the probe is pointed at a different branch than the claim."
    )


@requires_tlc
@pytest.mark.parametrize("cfg,invariant", sorted(PROBES.items()))
def test_reachability_probe_still_finds_its_witness(cfg, invariant):
    """Each probe fails, by name -- its counterexample IS the reachability.

    A probe reporting "No error has been found" means the healthy model has
    stopped reaching the states the real invariants are about, and every green
    run over them is measuring nothing.
    """
    reported = violated(run_tlc(cfg))
    assert reported == invariant, (
        f"{cfg} was expected to violate {invariant!r} and reported {reported!r}. "
        f"These probes MUST fail: their counterexamples are the evidence that "
        f"the antecedents of the real invariants are reachable at all."
    )


@requires_tlc
def test_the_healthy_configuration_holds():
    output = run_tlc("HomeIntegrityInternal.cfg")
    assert CLEAN in output, f"the correct-behaviour configuration failed:\n{output[-4000:]}"


@requires_tlc
@pytest.mark.parametrize("invariant,guard", sorted(GUARDS.items()))
def test_guard_config_stays_clean(invariant, guard):
    """guard_config_rule: the same defect with the witness-reading invariant
    removed must keep reporting no error.

    A guard that starts FAILING means the remaining invariants have been
    strengthened into the witness-reading one and the record of why the witness
    exists is stale.
    """
    output = run_tlc(guard)
    assert CLEAN in output, (
        f"the guard for {invariant!r} ({guard}) is no longer clean:\n{output[-4000:]}"
    )
