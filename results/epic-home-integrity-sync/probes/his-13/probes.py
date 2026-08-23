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
     "            for (Finding finding : before.repairable()) {",
     "            for (Finding finding : before.repairable().subList(0,"
     " Math.min(1, before.repairable().size()))) {",
     "repair's CONVERGENCE: one finding per pass instead of all of them."),
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
]

REENTRY = ("public final class HomeRepair {",
           "public final class HomeRepair {\n\n    private static final ThreadLocal<Boolean>"
           " DETECT_REENTRY = ThreadLocal.withInitial(() -> false);")


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


def main():
    only = sys.argv[1:] or [p[0] for p in PROBES]
    out_dir = pathlib.Path(__file__).parent
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
        # Truncated at 240 characters. V7 redirects the agent axis at the
        # operator's REAL ~/.claude, so its untruncated output was 900 KB of
        # their skill trees' contents. See V7.out's header.
        red = [(l.strip()[:240] + "  \u2026[truncated]") if len(l.strip()) > 240 else l.strip()
               for l in log.splitlines()
               if l.strip().startswith("[FAIL]") or l.strip().startswith("[PASS]")]
        verdict = next((l.strip() for l in reversed(log.splitlines())
                        if "passed," in l or "ALL PASSED" in l or "FAILURES" in l),
                       "(no verdict line)")
        (out_dir / f"{pid}.out").write_text(
            f"# {pid} -- branch moved: {branch}\n# mutation:\n#   {old.strip()[:110]}\n"
            f"# ->\n#   {new.strip()[:110]}\n\n" + "\n".join(red) + f"\n\n{verdict}\n")
        print(f"{pid}: {verdict}")


if __name__ == "__main__":
    main()
