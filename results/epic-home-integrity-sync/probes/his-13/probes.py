#!/usr/bin/env python3
"""
HIS-13 vacuity harness.

Every probe: copy the production file ASIDE, apply one mutation, run
RunHis13.java, record which assertions reddened, then restore BY COPYING THE
SAVED FILE BACK.

DEF-035, which has now bitten four agents in this epic: never `git checkout --`,
`git restore`, `git stash` or `git clean` to undo a mutation-test edit. It eats
uncommitted work.

Mechanism C of the vacuity ledger, mechanised: each mutation asserts its pattern
matched EXACTLY ONCE before it is applied. A mutation that matched nothing, or
matched twice, produces a green run that reads exactly like a passing check.

And the four suppression probes (V1-V4) still CALL the branch they disable --
`f(root, new ArrayList<>())` rather than `0 * f(...)` -- so the mutated path is
demonstrably reached and only its findings are dropped. The first draft used an
arithmetic form that did not compile (the methods return `int`), and jbang's
failure reads exactly like a suite of reds. That is mechanism C catching this
harness on its own first run, and it is recorded rather than quietly fixed.
"""
import re, shutil, subprocess, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
REPAIR = ROOT / "src/main/java/dev/skillmanager/store/HomeRepair.java"
CLONER = ROOT / "src/main/java/dev/skillmanager/store/HomeCloner.java"

# (id, file, old, new, "which BRANCH this moves")
PROBES = [
    ("V1", REPAIR,
     "        examined += misanchoredAgentLinks(root, findings);",
     "        examined += misanchoredAgentLinks(root, new ArrayList<>());",
     "shape 1 -- the agent-axis walk. The pre-HIS-13 world for that surface."),
    ("V2", REPAIR,
     "        examined += foreignPathsInShims(root, findings);",
     "        examined += foreignPathsInShims(root, new ArrayList<>());",
     "shape 2 -- the regular-file shim TEXT scan."),
    ("V3", REPAIR,
     "        examined += prunedInheritedEntries(root, findings);",
     "        examined += prunedInheritedEntries(root, new ArrayList<>());",
     "shape 3 -- ledger x descent record."),
    ("V4", REPAIR,
     "        examined += danglingCliPin(root, cliPin, findings);",
     "        examined += danglingCliPin(root, cliPin, new ArrayList<>());",
     "shape 4 -- DEF-012's pinned-build reader."),
    ("V5", REPAIR,
     "    public static Report detect(Path store, Path cliPin) {\n        Path root = store.toAbsolutePath().normalize();",
     "    public static Report detect(Path store, Path cliPin) {\n"
     "        Path root = store.toAbsolutePath().normalize();\n"
     "        try { if (!DETECT_REENTRY.get()) { DETECT_REENTRY.set(true);"
     " try { repair(root, cliPin); } finally { DETECT_REENTRY.set(false); } } }"
     " catch (Exception ignored) {}",
     "DEF-067's hazard, PLANTED: the observer repairs what it finds."),
    ("V6", REPAIR,
     "        int cap = Math.max(8, before.findings().size() * 4);",
     "        int cap = 1;",
     "repair's CONVERGENCE: one repair per invocation instead of all of them."),
    ("V7", REPAIR,
     "        return AgentHomes.agentDirsUnder(AgentHomes.homeRootFor(store.toAbsolutePath().normalize()));",
     "        return AgentHomes.agentDirsUnder(AgentHomes.agentHomeRoot());",
     "the AGENT-AXIS DERIVATION: HIS-14's defect, planted in the repairer."),
    ("V8", REPAIR,
     "        List<Path> roots = new ArrayList<>();\n"
     "        roots.add(Fs.realOrNormalized(store));\n"
     "        for (Path agentDir : agentDirsOf(store)) roots.add(Fs.realOrNormalized(agentDir));\n"
     "        return new WriteConfinement.Scope(store, List.copyOf(roots), WHAT);",
     "        return WriteConfinement.forHome(AgentHomes.homeRootFor(store), WHAT);",
     "the CONFINEMENT ROOTS: the enclosing home root instead of the two axes."),
    ("V9", REPAIR,
     "            if (dev.skillmanager.bindings.ChildHomeLink.isChildOf(store, recorded)\n"
     "                    || HomeProvenance.sanctions(store, recorded)) {\n"
     "                parents.add(recorded);\n"
     "            }",
     "            parents.add(recorded);",
     "shape 3's SANCTION GATE: the recorded snapshot trusted as a grant (#228)."),
    ("V10", CLONER,
     "        return sanctionedParentShim(rel, link, foreign, root, null,\n"
     "                new java.util.HashMap<>(), new java.util.HashMap<>())\n"
     "                ? null : foreign;",
     "        return foreign;",
     "the CANONICAL READER's sanction arm -- HomeCloner.unsanctionedForeignHome."),
    ("V11", REPAIR,
     "            if (Files.exists(store.resolve(rel), LinkOption.NOFOLLOW_LINKS)) continue;\n"
     "            for (Path parent : parents) {\n"
     "                Path theirs = parent.resolve(rel);\n"
     "                if (!Files.exists(theirs, LinkOption.NOFOLLOW_LINKS)) continue;",
     "            if (Files.exists(store.resolve(rel), LinkOption.NOFOLLOW_LINKS)) continue;\n"
     "            for (Path parent : parents) {\n"
     "                Path theirs = parent.resolve(rel);",
     "shape 3's CONJUNCTION: 'declared and missing' alone, without 'the parent holds it'."),
    ("V12", REPAIR,
     "            Path outward = shimDirLinkedOutOfHome(shimDir, store, dir);\n"
     "            if (outward != null) {",
     "            Path outward = shimDirLinkedOutOfHome(shimDir, store, dir);\n"
     "            if (false) {",
     "the bin/cli-IS-A-LINK guard -- HIS-9's disclosed limitation."),

    # ---- added after the review of PR #244; one per branch the fixes moved ----
    ("V13", REPAIR,
     "            if (HomePolicy.lazyArtifacts(new SkillStore(store))) return 0;",
     "            if (false && HomePolicy.lazyArtifacts(new SkillStore(store))) return 0;",
     "BLOCKER 1's fix: the lazy_artifacts gate on shape 3."),
    ("V14", REPAIR,
     "        String replaced = replaceWholePath(text, candidate.toString(),\n"
     "                finding.target().toString());",
     "        String replaced = text.replace(candidate.toString(), finding.target().toString());",
     "BLOCKER 2's fix, LAYER 1: whole-path replacement. The postcondition below "
     "is left in place, so this shows the second layer catching the first's failure."),
    ("V15", REPAIR,
     "        if (!brokenAfter.isEmpty()) {",
     "        if (false) {",
     "BLOCKER 2's fix, LAYER 2 alone: the postcondition. Expected to redden "
     "NOTHING on its own -- layer 1 is correct -- and recorded as such."),
    ("V16", REPAIR,
     "                        boolean held = Files.exists(mine, LinkOption.NOFOLLOW_LINKS);",
     "                        boolean held = true;",
     "M3: the branch deciding whether a MISANCHORED_AGENT_LINK is repairable."),
    ("V17", REPAIR,
     "                live != null, live));",
     "                true, live));",
     "M3: DANGLING_CLI_PIN's `live == null` arm -- 'cannot tell' reported as repairable."),
    ("V18", REPAIR,
     "                current = detect(root, cliPin);",
     "                current = current;",
     "BLOCKER 2's fix, LAYER 3: the RE-SCAN between repairs. Acts on a list "
     "taken before the home was changed."),
    ("V19", REPAIR,
     "            boolean whole = (hit == 0 || !pathChar(text.charAt(hit - 1)))\n"
     "                    && (end == text.length() || !pathChar(text.charAt(end)));",
     "            boolean whole = true;",
     "replaceWholePath's BOUNDARY PREDICATE itself -- substring semantics restored "
     "inside the function, rather than at its call site (which is V14)."),
]

# V15 is expected to be GREEN. A probe that reddens nothing is normally a
# vacuity finding (row 12 of the ledger); this one is a defence-in-depth
# statement -- layer 2 has nothing to catch while layer 1 is correct -- so it
# is declared here rather than discovered later, and V14 is what shows it live.
EXPECTED_GREEN = {"V15"}

REENTRY = ("public final class HomeRepair {",
           "public final class HomeRepair {\n\n    private static final ThreadLocal<Boolean>"
           " DETECT_REENTRY = ThreadLocal.withInitial(() -> false);")


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


def run():
    p = subprocess.run(["jbang", "RunHis13.java"], cwd=ROOT,
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
            if pid == "V5":
                apply(path, *REENTRY, pid + " (helper)")
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
