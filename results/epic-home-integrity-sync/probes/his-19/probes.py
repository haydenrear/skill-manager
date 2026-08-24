#!/usr/bin/env python3
"""
HIS-19 vacuity harness.

Every probe: copy the production file ASIDE, apply one mutation, run
RunHis19.java, record which assertions reddened, then restore BY COPYING THE
SAVED FILE BACK.

DEF-035, which has now bitten four agents in this epic: never `git checkout --`,
`git restore`, `git stash` or `git clean` to undo a mutation-test edit. It eats
uncommitted work.

Mechanism C, mechanised: each mutation asserts its pattern matched EXACTLY ONCE
before it is applied. A mutation that matched nothing, or matched twice,
produces a green run that reads exactly like a passing check.

Mechanism D, which this ticket is most exposed to: a PIN has several branches
that production decides separately and in an order the author does not control
-- already versionless, a linked prefix alias, a keg alias, a PATH alias, a
candidate that is a different build, a candidate that is versioned too, a build
that does not resolve. Every probe below names the ONE branch it moves, and
there is one probe per branch. The version-gate probe of the suite itself
(`GATE: an alias that ALSO names a version`) was written aimed at the wrong
branch on the first attempt -- PATH pointed at the keg's own `bin/`, whose
entry IS the located path, so the IDENTITY gate refused it before the version
gate ran. It passed while testing nothing. Caught by running it and reading
which rejection came back, not by reading the code.
"""
import os, re, shutil, subprocess, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
PIN = ROOT / "src/main/java/dev/skillmanager/launch/DurableCliPin.java"
SHIMS = ROOT / "src/main/java/dev/skillmanager/launch/LauncherShims.java"
CLONER = ROOT / "src/main/java/dev/skillmanager/store/HomeCloner.java"
CMD = ROOT / "src/main/java/dev/skillmanager/commands/HomeCommand.java"
REPAIR = ROOT / "src/main/java/dev/skillmanager/store/HomeRepair.java"

# (id, file, old, new, "which BRANCH this moves")
PROBES = [
    ("V1", SHIMS,
     "        DurableCliPin.Choice choice = DurableCliPin.choose(pinnedCli);\n"
     "        Path pin = choice.pin();",
     "        DurableCliPin.Choice choice = DurableCliPin.choose(pinnedCli);\n"
     "        Path pin = pinnedCli.toAbsolutePath().normalize();",
     "THE WRITER'S PIN STRING -- the whole ticket, reverted to the pre-HIS-19 line. "
     "`choose` is still CALLED, so the mutated path is demonstrably reached and only "
     "its answer is discarded."),
    ("V2", PIN,
     "            out.add(new Candidate(keg.prefix().resolve(\"bin\").resolve(name),\n"
     "                    Source.HOMEBREW_LINKED));",
     "            out.add(new Candidate(keg.prefix().resolve(\"bin\").resolve(name),\n"
     "                    Source.HOMEBREW_LINKED)); if (true) { out.remove(out.size() - 1); }",
     "the HOMEBREW_LINKED candidate alone. The keg alias below still fires, so a home "
     "still survives an upgrade -- which is the point: these are separate branches and "
     "only the one named here should redden."),
    ("V3", PIN,
     "            out.add(new Candidate(keg.prefix().resolve(\"opt\").resolve(keg.formula())\n"
     "                    .resolve(keg.rest()), Source.HOMEBREW_KEG));",
     "            out.add(new Candidate(keg.prefix().resolve(\"opt\").resolve(keg.formula())\n"
     "                    .resolve(keg.rest()), Source.HOMEBREW_KEG));"
     " if (true) { out.remove(out.size() - 1); }",
     "the HOMEBREW_KEG candidate alone -- the keg-only arm."),
    # V4 targeted the PATH_ALIAS arm. THE ARM WAS REMOVED by the review of #250
    # (MAJOR 2), so the probe is removed with it rather than left pointing at a
    # branch that no longer exists -- a probe naming a deleted branch is a record
    # that reads as coverage and is not. Its slot is taken by the two branches
    # that review added assertions for.
    ("V4", CMD,
     "            pin = result.pin();",
     "            // pin = result.pin();",
     "MAJOR 1 -- `home shims`'s REPORTER. The pin the command prints and puts in "
     "the --json `cli` field, versus the pin it wrote. Reverting this reddened "
     "NOTHING in 1402 cases before the review; the two assertions it now reddens "
     "are the whole of that finding."),
    ("V5", PIN,
     "        if (!candidateReal.equals(real)) {",
     "        if (false) {",
     "the SAME-FILE gate. This is the one branch whose failure is WORSE than the defect "
     "being fixed: without it this becomes the PATH search issue #61 removed, and a home "
     "gets pinned to somebody else's build."),
    ("V6", PIN,
     "        List<String> versions = versionSegmentsIn(candidate);\n"
     "        if (!versions.isEmpty()) {",
     "        List<String> versions = versionSegmentsIn(candidate);\n"
     "        if (false) {",
     "the VERSIONLESSNESS gate on a candidate -- what makes 'more durable' checkable "
     "rather than hopeful."),
    ("V7", PIN,
     "        List<String> versions = versionSegmentsIn(abs);\n"
     "        if (versions.isEmpty()) {",
     "        List<String> versions = versionSegmentsIn(abs);\n"
     "        if (false) {",
     "the NO_VERSION_TO_LOSE short-circuit -- the branch a source checkout and every "
     "test fixture takes, and the reason this class touches nothing it need not."),
    ("V8", PIN,
     "        if (!Files.isRegularFile(candidate)) return \"skipped: no such file\";\n"
     "        if (!Files.isExecutable(candidate)) return \"skipped: not executable\";",
     "        if (false) return \"skipped: no such file\";\n"
     "        if (false) return \"skipped: not executable\";",
     "the EXISTENCE / EXECUTABILITY gates. DECLARED EXPECTED-GREEN, and the javadoc now "
     "says VERDICT-NEUTRAL rather than claiming them as gates with controls (review of "
     "#250, m4): every input they refuse is refused again by the same-file gate below "
     "them -- a dangling link does not resolve, a directory does not resolve to the "
     "located FILE, a symlink takes its target's mode. They are ordering and message "
     "quality, not semantics."),
    ("V9", PIN,
     "    private static final Pattern VERSIONED_SEGMENT =\n"
     "            Pattern.compile(\"(?:.*[-_])?\" + VERSION.pattern());",
     "    private static final Pattern VERSIONED_SEGMENT =\n"
     "            Pattern.compile(\".*\");",
     "the VERSION PREDICATE itself, widened so that every path segment is a version. "
     "The instrument the whole class keys on -- ledger row 14's lesson applied to a "
     "predicate rather than to a grep."),
    ("V10", CMD,
     "            if (!result.clean() || !unresolved.isEmpty() || !deadPins.isEmpty()) return 1;",
     "            if (!result.clean() || !unresolved.isEmpty()) return 1;",
     "`home verify`'s VERDICT DISJUNCTION -- exactly the line whose absence made the "
     "0.24.0 incident exit 0. Note it is not the same branch as V11: the finding can be "
     "reported and still not counted, which is what this restores."),
    ("V11", CLONER,
     "        List<String> danglingCliPins = danglingCliPinIn(dstRoot);",
     "        List<String> danglingCliPins = List.of(); danglingCliPinIn(dstRoot);",
     "the DETECTOR in verifyRoots -- the question itself, still CALLED so the path is "
     "reached and only its answer is dropped."),
    ("V12", REPAIR,
     "        Path durable = live == null ? null : dev.skillmanager.launch.DurableCliPin.forPin(live);",
     "        Path durable = live;",
     "the REPAIRER'S TARGET. Only the remedy's honesty moves: `apply` re-derives the "
     "durable pin through LauncherShims.write regardless, so the migration still works "
     "and only the one-answer assertion reddens."),
    ("V13", PIN,
     "        Path real = realOrNull(abs);\n"
     "        if (real == null) {",
     "        Path real = realOrNull(abs);\n"
     "        if (false) {",
     "the UNRESOLVABLE-LOCATED message branch. Review of #250, m4: this branch is "
     "VERDICT-neutral -- with `real` null every candidate fails the same-file gate "
     "anyway -- so the probe named after it used to pass with it deleted. What it "
     "decides is the RECORDED REASON, and that is now what the case asserts."),
    ("V14", PIN,
     "            out.add(new Candidate(keg.prefix().resolve(\"bin\").resolve(name),\n"
     "                    Source.HOMEBREW_LINKED));\n"
     "            out.add(new Candidate(keg.prefix().resolve(\"opt\").resolve(keg.formula())\n"
     "                    .resolve(keg.rest()), Source.HOMEBREW_KEG));",
     "            out.add(new Candidate(java.nio.file.Path.of(\"/usr/bin\").resolve(name),\n"
     "                    Source.HOMEBREW_LINKED));",
     "MAJOR 2's INVARIANT: every candidate is DERIVED FROM THE LOCATED PATH. This "
     "plants a candidate that is not -- an absolute path chosen without looking at "
     "`abs`, which is what the removed PATH arm amounted to. The same-file gate "
     "still refuses it, so nothing unsafe is pinned; what reddens is every arm that "
     "depended on a derived candidate existing."),
]

# Declared UP FRONT rather than discovered: see V8's branch note. Ledger row 12
# is the rule that an all-green probe is a FAILED probe unless it was declared.
EXPECTED_GREEN = {"V8"}

PRECONDITION_MARKERS = ("precondition:", "precondition ")

LABELS_FILE = pathlib.Path(__file__).parent / "labels.txt"


def label_of(line, known=()):
    """This line's assertion label.

    A [PASS] line IS its label. A [FAIL] line is `<label>: <message>`, and the
    label may itself contain a colon -- three of this suite's do -- so splitting
    on the first colon merges distinct tests (it silently merged the two M3
    probes into one). The label is therefore the longest KNOWN label the body
    starts with, and only when that fails does it fall back to a split.
    """
    body = line.split("] ", 1)[1].strip() if "] " in line else line.strip()
    if line.startswith("[PASS]"):
        return body
    best = ""
    for candidate in known:
        if body.startswith(candidate) and len(candidate) > len(best):
            best = candidate
    return best or body.split(":", 1)[0].strip()


def known_labels():
    """The labels THIS suite declares, from a clean baseline run.

    Review of PR #244, M6: V7.out carried 28 copies of an assertion that lives
    in HomeDescriptorCliRemedyTest, which RunHis13.java does not run. They were
    lines INSIDE a multi-line failure message -- the byte-snapshot, redirected
    by V7 at the operator's real agent trees, read files whose contents happen
    to contain that text -- and a collector that keys on the "[FAIL]" prefix
    cannot tell those from assertions. So the label set is established once,
    from a clean run, and anything outside it is marked as what it is.
    """
    if LABELS_FILE.is_file():
        return set(LABELS_FILE.read_text().split("\n")) - {""}
    return set()


def is_precondition(line):
    """True when this red failed on SETUP rather than on the claim."""
    return any(m in line for m in PRECONDITION_MARKERS)


def collect(log):
    """Every assertion line, truncated, each red labelled claim or precondition.

    Truncated at 240 characters: V7 redirects the agent axis at the operator's
    REAL ~/.claude, so its untruncated output is ~900 KB of their skill trees'
    contents. See V7.out's header.
    """
    known = known_labels()
    out, seen = [], set()
    for raw in log.splitlines():
        line = raw.strip()
        if not (line.startswith("[FAIL]") or line.startswith("[PASS]")):
            continue
        label = label_of(line, known)
        if known and label not in known:
            key = ("foreign", label)
            if key in seen:
                continue
            seen.add(key)
            out.append("[NOTE] not an assertion of this suite, matched inside a failure "
                       "message: " + label[:120])
            continue
        if label in seen:
            continue
        seen.add(label)
        tag = "  (precondition)" if line.startswith("[FAIL]") and is_precondition(line) else ""
        if len(line) > 240:
            line = line[:240] + "  \u2026[truncated]"
        out.append(line + tag)
    return out


def apply(path, old, new, label):
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        sys.exit(f"{label}: pattern matched {n} times, expected exactly 1 (mechanism C)")
    path.write_text(text.replace(old, new))


# DEF-074: the suite needs an UNPINNED environment. SKILL_MANAGER_HOME alone
# reddens ~10 unit cases, and a probe read against a baseline that already has
# reds is a probe read against noise. Stripped here rather than left to whoever
# invokes this file, so the harness cannot be run the wrong way.
UNPIN = ("SKILL_MANAGER_HOME", "SKILL_MANAGER_CLI", "CLAUDE_CONFIG_DIR",
         "CLAUDE_HOME", "CODEX_HOME", "GEMINI_HOME")


def run():
    env = {k: v for k, v in os.environ.items() if k not in UNPIN}
    p = subprocess.run(["jbang", "RunHis19.java"], cwd=ROOT, env=env,
                       capture_output=True, text=True, timeout=1800)
    return p.stdout + p.stderr


def baseline(out_dir):
    """A clean run: the label set every probe is read against, plus the record."""
    log = run()
    lines = [l.strip() for l in log.splitlines() if l.strip().startswith("[PASS]")]
    labels = sorted({label_of(l) for l in lines})
    LABELS_FILE.write_text("\n".join(labels) + "\n")
    verdict = next((l.strip() for l in reversed(log.splitlines())
                    if "passed," in l or "ALL PASSED" in l), "(no verdict line)")
    (out_dir / "BASELINE.out").write_text(
        "# BASELINE -- no mutation. The colour every probe below is a departure from,\n"
        "# and the source of labels.txt, which is how a real assertion of this suite is\n"
        "# told from text matched inside a failure message (M6).\n"
        f"# generated by: probes.py --baseline\n# verdict: {verdict}\n\n"
        + "\n".join(collect(log)) + "\n")
    print(f"baseline: {verdict}  ({len(labels)} assertions)")


def main():
    args = sys.argv[1:]
    out_dir = pathlib.Path(__file__).parent
    if "--baseline" in args or not LABELS_FILE.is_file():
        baseline(out_dir)
        args = [a for a in args if a != "--baseline"]
        if not args:
            return
    only = args or [p[0] for p in PROBES]
    for pid, path, old, new, branch in PROBES:
        if pid not in only:
            continue
        backup = path.with_suffix(".java.probe-backup")
        shutil.copy2(path, backup)
        try:
            apply(path, old, new, pid)
            log = run()
        finally:
            shutil.copy2(backup, path)   # NOT git checkout -- DEF-035
            backup.unlink()
        red = collect(log)
        verdict = next((l.strip() for l in reversed(log.splitlines())
                        if "passed," in l or "ALL PASSED" in l or "FAILURES" in l),
                       "(no verdict line)")
        # MECHANISM A, MECHANISED. A red is only a probe's evidence when the
        # assertion that reddened is the CLAIM. The review of PR #244 found my
        # table crediting probes whose red was `precondition: ...` -- eight
        # such events across six assertions, five of them undeclared. So the
        # harness now classifies every red rather than leaving it to the author
        # to read the transcript honestly.
        claims = [l for l in red if l.startswith("[FAIL]") and not is_precondition(l)]
        pres = [l for l in red if l.startswith("[FAIL]") and is_precondition(l)]
        (out_dir / f"{pid}.out").write_text(
            f"# {pid} -- branch moved: {branch}\n"
            f"# mutation:\n#   {old.strip()[:140]}\n# ->\n#   {new.strip()[:140]}\n"
            f"# file: {path.relative_to(ROOT)}\n"
            f"# generated by: probes.py {pid}\n"
            f"# verdict: {verdict}\n"
            f"# red on a CLAIM: {len(claims)}   red on a PRECONDITION only: {len(pres)}\n"
            "#\n# A red is this probe's evidence only when the reddened assertion is the\n"
            "# CLAIM. Lines marked (precondition) failed on setup and prove nothing about\n"
            "# the branch -- mechanism A of the vacuity ledger.\n\n"
            + "\n".join(red) + "\n")
        flag = ""
        if not claims and pid not in EXPECTED_GREEN:
            flag = "   <-- NO CLAIM REDDENED: treat as a VACUOUS probe (ledger row 12)"
        if claims and pid in EXPECTED_GREEN:
            flag = "   <-- expected green and was not; re-read the probe"
        print(f"{pid}: {verdict}  claims={len(claims)} preconditions={len(pres)}{flag}")


if __name__ == "__main__":
    main()
