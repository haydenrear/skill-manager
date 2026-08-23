package dev.skillmanager.bindings;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A unit's scaffolded, re-derivable trees are not its content — HIS-18, and
 * HIS-4's slice (1), which HIS-4 did not deliver.
 *
 * <h2>The defect</h2>
 *
 * <p>The test-graph scaffolder writes {@code test_graph/build-logic},
 * {@code test_graph/sdk} and {@code test_graph/standard-nodes} into a consuming
 * unit as symlinks into the provider's store copy, and in the same pass writes
 * the {@code .gitignore} block that declares all three generated.
 * {@link ChildHomeMaterializer} dereferences those links into real directories
 * so the child home is independent (CHM-5) — correct — but they were still
 * <b>hashed as content</b>, so from that moment the child copy's digest
 * diverges from the store's and every later read reports the difference. That
 * is the INPUT to the drift report: HIS-2 bounded the rendering, and on the
 * committed baseline {@code spec-double-compiler} still contributed 776 of 889
 * file lines.
 *
 * <h2>Three cases, deliberately, and not one</h2>
 *
 * <p>The rule is that excluding a path makes it <b>invisible</b>, not
 * <b>disposable</b> — not hashed, not copied, not deleted. One assertion over
 * all three cannot say which half broke, and the third half has a delivered
 * sibling ({@code carryOverUnownedTrees}, HIS-4) that could carry it while the
 * digest half is still absent. So each is its own case.
 *
 * <h2>The negative control is the exclusion's own falsifier</h2>
 *
 * <p>"A unit that legitimately contains a directory named {@code sdk} is not
 * excluded" and "the fixture fails when the exclusion is removed" are the same
 * assertion read from two ends, so they are one case: the SAME fixture with the
 * scaffolder's ignore block absent keeps the dereferenced trees AND its digest
 * moves. Without that case the first three could all pass over a fixture whose
 * digest never moved in the first place, which is the shape this epic's vacuity
 * ledger records five times.
 *
 * <h2>The ignore block is READ FROM THE SCAFFOLDER, not typed here</h2>
 *
 * <p>Those lines are GENERATED at bind time and are not checked in: the repo's
 * own {@code test_graph/.gitignore} names none of the three, and the installed
 * copy in a consuming unit names all three. A fixture that hand-wrote a
 * plausible block would be testing the fixture. {@link #scaffoldedIgnorePaths}
 * scrapes them out of {@code ensure_provider_binding_ignores} so that renaming
 * them in the scaffolder moves this fixture with it.
 */
public final class ScaffoldedTreeIsNotContentTest {

    private static final String UNIT = "graph-consumer";
    private static final String PROVIDER = "graph-provider";

    public static int run() throws Exception {
        List<String> scaffolded = scaffoldedIgnorePaths();
        return Tests.suite("ScaffoldedTreeIsNotContentTest")

                // Vacuity mechanism B: assert the fixture's own inputs. If the
                // scrape silently returned nothing, every case below would
                // build a unit with an empty .gitignore and pass for the wrong
                // reason -- which is exactly how HIS-1's assertion passed.
                .test("the ignore block under test is the one the scaffolder emits", () -> {
                    assertEquals(List.of("/build-logic", "/sdk", "/standard-nodes"), scaffolded,
                            "ensure_provider_binding_ignores still declares these three paths; "
                                    + "if it changed, this fixture changed with it and the "
                                    + "expectation here is what needs updating");
                })

                // ------------------------------------------------------ (1) not hashed
                .test("an excluded path is NOT HASHED — a dereference leaves the child copy's "
                        + "digest equal to the store's", () -> {
                    try (Fixture f = Fixture.scaffolded(scaffolded)) {
                        // Preconditions, asserted rather than assumed.
                        for (String name : f.treeNames) {
                            Path link = f.sourceUnit.resolve("test_graph").resolve(name);
                            assertTrue(Files.isSymbolicLink(link),
                                    "precondition: " + name + " is a symlink in the store");
                            assertTrue(Files.isRegularFile(
                                            link.toRealPath().resolve("generated.txt")),
                                    "precondition: " + name + " resolves to a real tree with "
                                            + "content in it");
                        }

                        ChildHomeMaterializer.UnitOutcome outcome = f.materialize();
                        assertFalse(outcome.heldBack(),
                                "precondition: the unit was materialized, not held back");
                        assertTrue(Files.isRegularFile(
                                        f.childUnit.resolve("test_graph/build.gradle.kts")),
                                "precondition: the copy really happened");

                        // The claim, and NOTHING between it and the
                        // preconditions above. "The tree is not in the child
                        // copy" is case (2)'s claim, and asserting it here as a
                        // precondition made this case redden on it instead of
                        // on the digest -- vacuity mechanism A, caught by
                        // probe V1 on the first run and moved out.
                        // Asked at the DRIFT REPORT's own scope -- entryDigests
                        // with .git excluded -- because that is the record this
                        // ticket exists to shrink, and because a whole-tree
                        // digest over two freshly copied .git directories would
                        // be asserting git's bookkeeping instead of the claim.
                        assertEquals(
                                ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git")),
                                ChildHomeMaterializer.entryDigests(f.childUnit,
                                        java.util.Set.of(".git")),
                                "the child copy's per-entry digests match the store's across the "
                                        + "dereference, so nothing downstream reports drift");
                    }
                })

                // ------------------------------------------------------ (2) not copied
                .test("an excluded path is NOT COPIED", () -> {
                    try (Fixture f = Fixture.scaffolded(scaffolded)) {
                        f.materialize();
                        Path childGraph = f.childUnit.resolve("test_graph");
                        for (String name : f.treeNames) {
                            assertFalse(Files.exists(childGraph.resolve(name),
                                            LinkOption.NOFOLLOW_LINKS),
                                    "the scaffolded tree " + name + " is not written into the "
                                            + "child home, as a directory or as a link back into "
                                            + "the parent store");
                        }
                        // And the exclusion is not "nothing was copied": the
                        // unit's real test_graph content still arrives.
                        assertTrue(Files.isRegularFile(childGraph.resolve("build.gradle.kts")),
                                "the unit's own test_graph content is still copied");
                        assertTrue(Files.isRegularFile(childGraph.resolve(".gitignore")),
                                "and so is the declaration itself — a child home that lost it "
                                        + "would stop agreeing with the store on the next pass");
                    }
                })

                // ----------------------------------------------------- (3) not deleted
                .test("an excluded path is NOT DELETED — a wholesale refresh carries it across "
                        + "the swap", () -> {
                    try (Fixture f = Fixture.scaffolded(scaffolded)) {
                        f.materialize();

                        // The child home binds its own copy of the generated
                        // tree, at the path the exclusion covers. Not a
                        // Rederivable name -- `sdk` is not in that list -- so
                        // only the new rule can save it, and the delivered
                        // sibling cannot mask the result.
                        Path bound = f.childUnit.resolve("test_graph/sdk");
                        Fs.ensureDir(bound);
                        Files.writeString(bound.resolve("bound-here.txt"), "THE CHILD'S BINDING\n");

                        Files.writeString(f.sourceUnit.resolve("SKILL.md"), "STORE v2\n");
                        ChildHomeMaterializer.UnitOutcome outcome = f.materialize();

                        assertEquals("STORE v2\n",
                                Files.readString(f.childUnit.resolve("SKILL.md")),
                                "precondition: the refresh really replaced the tree, so the "
                                        + "swap this claim is about actually happened");
                        assertFalse(outcome.heldBack(),
                                "and writing into an excluded path is not a new reason to hold "
                                        + "the unit back (GOAL-no-spurious-holdback)");
                        // Two assertions, because a missing file throws
                        // NoSuchFileException out of readString and the record
                        // then names a path instead of a claim. Probe V3
                        // reddened exactly that way before this split.
                        assertTrue(Files.isRegularFile(bound.resolve("bound-here.txt")),
                                "the excluded tree survives the wholesale replace — excluding a "
                                        + "path makes it invisible, not disposable");
                        assertEquals("THE CHILD'S BINDING\n",
                                Files.readString(bound.resolve("bound-here.txt")),
                                "and with its bytes, not as an empty shell");
                    }
                })

                // ------------------------------- the falsifier, from both ends at once
                .test("a unit that legitimately contains sdk and standard-nodes keeps them — and "
                        + "the same fixture without the exclusion moves its digest", () -> {
                    try (Fixture f = Fixture.authored()) {
                        f.materialize();
                        for (String name : f.treeNames) {
                            Path materialized = f.childUnit.resolve("test_graph").resolve(name);
                            assertTrue(Files.isDirectory(materialized, LinkOption.NOFOLLOW_LINKS),
                                    "a unit that never declared " + name + " generated keeps it, "
                                            + "dereferenced into the child home as content");
                            assertTrue(Files.isRegularFile(materialized.resolve("generated.txt")),
                                    "with its bytes: a global name list would have hidden these");
                        }
                        assertFalse(ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git"))
                                        .equals(ChildHomeMaterializer.entryDigests(f.childUnit,
                                                java.util.Set.of(".git"))),
                                "and the digests DIVERGE — which is what the exclusion prevents "
                                        + "in the case above, so that case is not passing over a "
                                        + "fixture whose digest never moved");
                    }
                })

                // -------------------------------------- the clause that keeps it honest
                .test("a path the unit TRACKS is content even when its own .gitignore matches it",
                        () -> {
                    try (Fixture f = Fixture.trackedButIgnored()) {
                        f.materialize();
                        assertTrue(Files.isRegularFile(
                                        f.childUnit.resolve("generated/committed.txt")),
                                "a committed file its own .gitignore matches still reaches the "
                                        + "child home — measured on the operator's store, one "
                                        + "unit tracks 14 such files");
                        assertFalse(Files.exists(f.childUnit.resolve("generated/scratch.txt"),
                                        LinkOption.NOFOLLOW_LINKS),
                                "and its untracked neighbour under the same ignored directory "
                                        + "is still excluded, so the clause is narrow");
                    }
                })

                // Found by the sync-settles fixture's own vacuity guard, on the
                // first graph run of this change, and pinned here so the graph
                // is not the only thing that can catch it again.
                .test("a scaffolded tree the unit COMMITTED is still materialized, and both sides "
                        + "name it", () -> {
                    try (Fixture f = Fixture.committedLinks(scaffoldedIgnorePaths())) {
                        f.materialize();
                        for (String name : f.treeNames) {
                            Path at = f.childUnit.resolve("test_graph").resolve(name);
                            assertTrue(Files.isRegularFile(at.resolve("generated.txt")),
                                    "a link the unit's own history holds at mode 120000 is "
                                            + "content: it is dereferenced into the child home, "
                                            + "not dropped, and HIS-4's DereferencedStoreLinks "
                                            + "owns what git then makes of it");
                        }
                        // The failure that actually happened: the source walk
                        // emitted nothing for the subtree while the store's own
                        // walk still emitted the tracked LINK, so the two sides
                        // disagreed about one path forever.
                        String rel = "test_graph/" + f.treeNames.get(0);
                        assertTrue(ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git")).containsKey(rel),
                                "precondition: the store's own view names " + rel);
                        assertTrue(ChildHomeMaterializer.entryDigests(f.childUnit,
                                        java.util.Set.of(".git")).containsKey(rel),
                                "and so does the child's — a path one side hashes and the other "
                                        + "does not have is the divergence this change removes, "
                                        + "not a new place to create it");
                    }
                })

                // ---------------------------------------- the DELETION surface
                // Review of #240, BLOCKER 1. `treeDigest` is what
                // `isLocallyModified` reads, and that predicate's own javadoc
                // calls it "the predicate a prune, a teardown and a close-out
                // consult before destroying it". Excluding a path from the
                // digest therefore made an agent's authored file DISPOSABLE —
                // `home sync` reported UNCHANGED, close-out found no blocker,
                // and `wt close` / `project remove` deleted the child home with
                // an empty preserve set. `carryOverUnownedTrees` guards the
                // SWAP; nothing guarded the TEARDOWN.
                //
                // Not hypothetical: acp-cdc-ai-python/scripts/sources/logs/*.jsonl
                // in the operator's root store are ACP session transcripts, and
                // they are one of only three genuinely-new exclusions this
                // change adds.
                .test("an agent's file under an excluded path is NOT DISPOSABLE — the teardown "
                        + "predicate still sees it", () -> {
                    try (Fixture f = Fixture.declaring("results/\n")) {
                        f.materialize();
                        assertFalse(f.locallyModified(),
                                "precondition: a pristine child copy is disposable, so the "
                                        + "assertion below is about the file and not about the "
                                        + "predicate refusing everything");

                        Path authored = f.childUnit.resolve("results/findings.md");
                        Fs.ensureDir(authored.getParent());
                        Files.writeString(authored, "WHAT THE TICKET FOUND\n");

                        assertTrue(f.locallyModified(),
                                "a file the child home wrote under an excluded path is work the "
                                        + "parent store cannot be shown to have — invisible to "
                                        + "the digest must not mean disposable by the teardown");
                        assertEquals(1, f.locallyModifiedCount(),
                                "and `locallyModifiedUnits()` names the unit, so close-out and "
                                        + "`project remove` both see it");
                    }
                })
                .test("an excluded path the SOURCE also holds is still disposable — the guard is "
                        + "narrow", () -> {
                    try (Fixture f = Fixture.declaring("results/\n")) {
                        Path inStore = f.sourceUnit.resolve("results/generated.txt");
                        Fs.ensureDir(inStore.getParent());
                        Files.writeString(inStore, "REGENERATED\n");
                        f.materialize();

                        Path inChild = f.childUnit.resolve("results/generated.txt");
                        Fs.ensureDir(inChild.getParent());
                        Files.writeString(inChild, "REGENERATED\n");

                        assertFalse(f.locallyModified(),
                                "byte-identical to what the parent store holds at the same "
                                        + "excluded path, so there is nothing here that exists "
                                        + "nowhere else and the teardown is not blocked");
                    }
                })

                // ------------------------------------- the two walkers agreeing
                // Review of #240, BLOCKER 2. `walk` asked the question at the
                // SYMLINK frame (directory=false) and again at the DEREFERENCED
                // frame (directory=true). A dir-only rule matches the second and
                // not the first, so `walk` emitted neither the link nor its
                // target while `walkPlain` still emitted the LINK: a permanent
                // one-sided entry in every drift report. The fixture the
                // scaffolder's own block produces cannot reach it, because
                // `ensure_provider_binding_ignores` emits `/sdk` with no
                // trailing slash — and `build/`, `dist/`, `.venv/` are the
                // dominant convention everywhere else.
                .test("a DIRECTORY-ONLY rule over an in-unit store link leaves the two walkers "
                        + "agreeing", () -> {
                    try (Fixture f = Fixture.dirOnlyRuleOverStoreLink()) {
                        assertTrue(Files.isSymbolicLink(f.sourceUnit.resolve("test_graph/sdk")),
                                "precondition: the store holds a symlink there");
                        f.materialize();

                        boolean inSource = ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                java.util.Set.of(".git")).containsKey("test_graph/sdk");
                        boolean inChild = ChildHomeMaterializer.entryDigests(f.childUnit,
                                java.util.Set.of(".git")).containsKey("test_graph/sdk");
                        assertEquals(inSource, inChild,
                                "one side hashing a path the other does not have is a permanent "
                                        + "entry in every later drift report — the exact failure "
                                        + "the case above names, arriving through the `directory` "
                                        + "argument instead");
                        assertFalse(inSource,
                                "and both sides EXCLUDE it. Directory-ness is read as the "
                                        + "MATERIALIZED view will have it, so the answer cannot "
                                        + "move when the dereference turns the link into the "
                                        + "directory the rule names — a narrow, deliberate "
                                        + "divergence from `git check-ignore`, which reads a "
                                        + "symlink as a file");
                        assertFalse(Files.exists(f.childUnit.resolve("test_graph/sdk"),
                                        LinkOption.NOFOLLOW_LINKS),
                                "so it is not copied either, the same as every other excluded "
                                        + "path");
                    }
                })
                .test("a directory-only rule over a link to a FILE is not excluded — the "
                        + "divergence is narrow", () -> {
                    try (Fixture f = Fixture.dirOnlyRuleOverStoreLinkToFile()) {
                        f.materialize();
                        assertTrue(ChildHomeMaterializer.entryDigests(f.childUnit,
                                        java.util.Set.of(".git")).containsKey("test_graph/sdk"),
                                "`sdk/` names a directory, and a link that resolves to a FILE is "
                                        + "not one on either side of the dereference");
                    }
                })

                // ------------------------------ GOAL-no-spurious-holdback, clause 2
                // The review is right that clause 1 was asserted here and clause
                // 2 was not, and a guard that only ever says "do not hold back"
                // is not a guard.
                .test("a unit that GENUINELY differs is still held back, excluded paths or not",
                        () -> {
                    try (Fixture f = Fixture.scaffolded(scaffolded)) {
                        f.materialize();
                        Files.writeString(f.childUnit.resolve("SKILL.md"), "AN AGENT WROTE THIS\n");
                        Files.writeString(f.sourceUnit.resolve("SKILL.md"), "STORE v2\n");

                        ChildHomeMaterializer.UnitOutcome outcome = f.materialize();
                        assertTrue(outcome.heldBack(),
                                "an ordinary edit is still an edit — narrowing the question must "
                                        + "not answer 'clean' whenever an excluded path exists");
                        assertEquals("AN AGENT WROTE THIS\n",
                                Files.readString(f.childUnit.resolve("SKILL.md")),
                                "and the edit is still there");
                    }
                })

                // ------------------------------------------- no index, no ignoring
                // Review of #240, MED-3. `readIndex` returned an EMPTY set for a
                // MISSING index, so the class's own "fails towards visibility"
                // was true for a corrupt index and false for an absent one: the
                // declaration became authoritative on the strength of a file
                // that is not there. Measured in the review — a committed file
                // became IGNORED after `rm .git/index`.
                .test("no readable index means NOTHING is ignored — one rule for all three ways "
                        + "of not having one", () -> {
                    try (Fixture f = Fixture.declaring("results/\n")) {
                        Path generated = f.sourceUnit.resolve("results/generated.txt");
                        Fs.ensureDir(generated.getParent());
                        Files.writeString(generated, "REGENERATED\n");
                        assertFalse(ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git"))
                                        .containsKey("results/generated.txt"),
                                "precondition: with an index, the declaration is honoured");

                        Files.delete(f.sourceUnit.resolve(".git/index"));
                        assertTrue(ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git"))
                                        .containsKey("results/generated.txt"),
                                "with the index gone there is nothing that could rescue a "
                                        + "committed path from the declaration, so the "
                                        + "declaration is not read at all");

                        Fs.deleteRecursive(f.sourceUnit.resolve(".git"));
                        assertTrue(ChildHomeMaterializer.entryDigests(f.sourceUnit,
                                        java.util.Set.of(".git"))
                                        .containsKey("results/generated.txt"),
                                "and a unit with no repository at all is the same situation, not "
                                        + "a different one");
                    }
                })

                // ------------------ the carry-over branch nothing had reached
                // Review of #240: case (3)'s fixture never reaches
                // `carryOverUnownedTrees`' `if (Files.exists(to)) continue;`,
                // where the destination's copy is abandoned and then destroyed
                // by the swap. This reaches it: upstream STOPS declaring the
                // path generated and puts its own content there, while the
                // child home already has its own.
                .test("when upstream stops declaring a path generated, the child's copy of it is "
                        + "not lost silently", () -> {
                    try (Fixture f = Fixture.declaring("results/\n")) {
                        f.materialize();
                        Path mine = f.childUnit.resolve("results/notes.md");
                        Fs.ensureDir(mine.getParent());
                        Files.writeString(mine, "THE CHILD'S NOTES\n");

                        // Upstream re-classifies the path as content.
                        Files.writeString(f.sourceUnit.resolve(".gitignore"), "\n");
                        Path theirs = f.sourceUnit.resolve("results/notes.md");
                        Fs.ensureDir(theirs.getParent());
                        Files.writeString(theirs, "UPSTREAM'S NOTES\n");
                        Files.writeString(f.sourceUnit.resolve("SKILL.md"), "STORE v2\n");

                        f.materialize();
                        assertEquals("STORE v2\n",
                                Files.readString(f.childUnit.resolve("SKILL.md")),
                                "precondition: the refresh landed, so the swap this is about "
                                        + "happened");
                        assertTrue(Files.isRegularFile(f.childUnit.resolve("results/notes.md")),
                                "upstream's copy arrives, which is the branch under test — the "
                                        + "staged tree already holds the path, so the child's own "
                                        + "copy cannot be carried across");
                        assertEquals("UPSTREAM'S NOTES\n",
                                Files.readString(f.childUnit.resolve("results/notes.md")),
                                "and upstream's content is what stands there. THE CHILD'S COPY IS "
                                        + "GONE. This case pins the loss so it is a known one, "
                                        + "and the swap now WARNS by name instead of taking it "
                                        + "silently — see DEF-056, which owns preserving it");
                    }
                })
                .runAll();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A parent store holding a provider unit with three generated trees, a
     * consumer unit linking to them from {@code test_graph/}, and an empty
     * child home to materialize into.
     *
     * <p>The links are absolute and point INSIDE the parent store and OUTSIDE
     * the unit, because {@code walk} dereferences only those — a fixture whose
     * links point anywhere else is inert, which is the mistake HIS-4 recorded
     * costing it two reproductions.
     */
    private static final class Fixture implements AutoCloseable {

        private final Path base;
        private final SkillStore parent;
        private final SkillStore child;
        final Path sourceUnit;
        final Path childUnit;
        final List<String> treeNames;

        private Fixture(Path base, SkillStore parent, SkillStore child, List<String> treeNames) {
            this.base = base;
            this.parent = parent;
            this.child = child;
            this.sourceUnit = parent.unitDir(UNIT, UnitKind.SKILL);
            this.childUnit = child.unitDir(UNIT, UnitKind.SKILL);
            this.treeNames = treeNames;
        }

        /** The real shape: the scaffolder's links AND the scaffolder's ignore block. */
        static Fixture scaffolded(List<String> ignorePaths) throws Exception {
            return build(ignorePaths, true, false);
        }

        /** The same shape with no declaration — a unit that authored those names. */
        static Fixture authored() throws Exception {
            return build(scaffoldedIgnorePaths(), false, false);
        }

        /** The declaration AND a repository that committed the links anyway. */
        static Fixture committedLinks(List<String> ignorePaths) throws Exception {
            return build(ignorePaths, true, true);
        }

        private static Fixture build(List<String> ignorePaths, boolean declare, boolean commit)
                throws Exception {
            List<String> names = new ArrayList<>();
            for (String p : ignorePaths) names.add(p.startsWith("/") ? p.substring(1) : p);

            Path base = Files.createTempDirectory("his18-");
            SkillStore parent = store(base.resolve("parent/.skill-manager"));
            SkillStore child = store(base.resolve("child/.skill-manager"));

            Path providerUnit = parent.unitDir(PROVIDER, UnitKind.SKILL);
            Fs.ensureDir(providerUnit);
            Files.writeString(providerUnit.resolve("SKILL.md"), "PROVIDER\n");

            Path consumerUnit = parent.unitDir(UNIT, UnitKind.SKILL);
            Path graph = consumerUnit.resolve("test_graph");
            Fs.ensureDir(graph);
            Files.writeString(consumerUnit.resolve("SKILL.md"), "STORE v1\n");
            Files.writeString(graph.resolve("build.gradle.kts"), "// the unit's own content\n");

            StringBuilder ignore = new StringBuilder("# Gradle\nbuild/\n");
            if (declare) {
                ignore.append("\n# TEST-GRAPH-MANAGED-BINDINGS-BEGIN\n");
                for (String p : ignorePaths) ignore.append(p).append('\n');
                ignore.append("# TEST-GRAPH-MANAGED-BINDINGS-END\n");
            }
            Files.writeString(graph.resolve(".gitignore"), ignore.toString());

            for (String name : names) {
                Path target = providerUnit.resolve("project_sdk_sources").resolve(name);
                Fs.ensureDir(target);
                Files.writeString(target.resolve("generated.txt"),
                        "regenerated by the scaffolder: " + name + "\n");
                Files.createSymbolicLink(graph.resolve(name), target.toAbsolutePath());
            }
            initRepo(consumerUnit);
            if (commit) {
                // -f, because the generated block already covers all three and
                // the real units carry them TRACKED from before the
                // managed-bindings migration — the measured index stages.
                git(consumerUnit, "add", "-A", "-f");
                git(consumerUnit, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                        "commit", "--quiet", "-m", "fixture: the links are in history");
            }
            return new Fixture(base, parent, child, List.copyOf(names));
        }

        /**
         * A git-backed unit that COMMITTED a file its own {@code .gitignore}
         * matches, alongside an untracked neighbour under the same rule.
         */
        static Fixture trackedButIgnored() throws Exception {
            Path base = Files.createTempDirectory("his18-tracked-");
            SkillStore parent = store(base.resolve("parent/.skill-manager"));
            SkillStore child = store(base.resolve("child/.skill-manager"));

            Path unit = parent.unitDir(UNIT, UnitKind.SKILL);
            Fs.ensureDir(unit);
            Files.writeString(unit.resolve("SKILL.md"), "STORE v1\n");
            Files.writeString(unit.resolve(".gitignore"), "generated/\n");
            Path generated = unit.resolve("generated");
            Fs.ensureDir(generated);
            Files.writeString(generated.resolve("committed.txt"), "COMMITTED ANYWAY\n");
            Files.writeString(generated.resolve("scratch.txt"), "NOT COMMITTED\n");

            git(unit, "init", "-b", "main", "--quiet");
            git(unit, "add", "-A");
            git(unit, "add", "-f", "generated/committed.txt");
            git(unit, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "fixture: commit an ignored path on purpose");
            return new Fixture(base, parent, child, List.of());
        }

        /**
         * The same dir-only rule over a link that resolves to a FILE. The
         * divergence taken in {@code asMaterializedDirectory} must not reach
         * this one, or "directory-only" would have stopped meaning anything.
         */
        static Fixture dirOnlyRuleOverStoreLinkToFile() throws Exception {
            Path base = Files.createTempDirectory("his18-dironly-file-");
            SkillStore parent = store(base.resolve("parent/.skill-manager"));
            SkillStore child = store(base.resolve("child/.skill-manager"));

            Path providerUnit = parent.unitDir(PROVIDER, UnitKind.SKILL);
            Fs.ensureDir(providerUnit.resolve("project_sdk_sources"));
            Files.writeString(providerUnit.resolve("SKILL.md"), "PROVIDER\n");
            Path target = providerUnit.resolve("project_sdk_sources/sdk");
            Files.writeString(target, "a generated FILE, not a tree\n");

            Path unit = parent.unitDir(UNIT, UnitKind.SKILL);
            Path graph = unit.resolve("test_graph");
            Fs.ensureDir(graph);
            Files.writeString(unit.resolve("SKILL.md"), "STORE v1\n");
            Files.writeString(graph.resolve(".gitignore"), "sdk/\n");
            Files.createSymbolicLink(graph.resolve("sdk"), target.toAbsolutePath());
            initRepo(unit);
            return new Fixture(base, parent, child, List.of("sdk"));
        }

        /**
         * A unit with nothing scaffolded in it, declaring one ordinary path
         * generated. The shape a worktree agent actually meets: a unit whose
         * `.gitignore` says `results/`, and an agent that writes a finding
         * there.
         */
        static Fixture declaring(String unitGitignore) throws Exception {
            Path base = Files.createTempDirectory("his18-declaring-");
            SkillStore parent = store(base.resolve("parent/.skill-manager"));
            SkillStore child = store(base.resolve("child/.skill-manager"));
            Path unit = parent.unitDir(UNIT, UnitKind.SKILL);
            Fs.ensureDir(unit);
            Files.writeString(unit.resolve("SKILL.md"), "STORE v1\n");
            Files.writeString(unit.resolve(".gitignore"), unitGitignore);
            initRepo(unit);
            return new Fixture(base, parent, child, List.of());
        }

        /**
         * A scaffolded store link under a DIRECTORY-ONLY rule ({@code sdk/}
         * rather than {@code /sdk}). The scaffolder emits the second form, so
         * no other fixture here reaches the first — and trailing-slash rules
         * are the dominant convention everywhere else.
         */
        static Fixture dirOnlyRuleOverStoreLink() throws Exception {
            Path base = Files.createTempDirectory("his18-dironly-");
            SkillStore parent = store(base.resolve("parent/.skill-manager"));
            SkillStore child = store(base.resolve("child/.skill-manager"));

            Path providerUnit = parent.unitDir(PROVIDER, UnitKind.SKILL);
            Path target = providerUnit.resolve("project_sdk_sources/sdk");
            Fs.ensureDir(target);
            Files.writeString(providerUnit.resolve("SKILL.md"), "PROVIDER\n");
            Files.writeString(target.resolve("generated.txt"), "regenerated\n");

            Path unit = parent.unitDir(UNIT, UnitKind.SKILL);
            Path graph = unit.resolve("test_graph");
            Fs.ensureDir(graph);
            Files.writeString(unit.resolve("SKILL.md"), "STORE v1\n");
            Files.writeString(graph.resolve(".gitignore"), "sdk/\n");
            Files.createSymbolicLink(graph.resolve("sdk"), target.toAbsolutePath());
            initRepo(unit);
            return new Fixture(base, parent, child, List.of("sdk"));
        }

        boolean locallyModified() throws IOException {
            return new ChildHomeMaterializer(parent, child)
                    .isLocallyModified(UNIT, UnitKind.SKILL);
        }

        int locallyModifiedCount() throws IOException {
            return new ChildHomeMaterializer(parent, child).locallyModifiedUnits().size();
        }

        ChildHomeMaterializer.UnitOutcome materialize() throws IOException {
            return new ChildHomeMaterializer(parent, child)
                    .materializeUnit(UNIT, UnitKind.SKILL, MaterializationMode.COPY);
        }

        @Override
        public void close() throws IOException {
            Fs.deleteRecursive(base);
        }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    // ------------------------------------------------------------ the scaffolder

    /**
     * The ignore lines {@code ensure_provider_binding_ignores} writes, read out
     * of the scaffolder's own source.
     *
     * <p>Scraped rather than typed because the block is generated at bind time
     * and appears in no checked-in file: the repository's own
     * {@code test_graph/.gitignore} names none of these paths, and only an
     * installed consuming unit carries them. Typing them here would make the
     * fixture agree with itself for as long as nobody changed the scaffolder.
     */
    private static List<String> scaffoldedIgnorePaths() throws IOException {
        Path common = locate("skills/test_graph/scripts/_common.py");
        String source = Files.readString(common);
        int at = source.indexOf("def ensure_provider_binding_ignores");
        if (at < 0) {
            throw new AssertionError("ensure_provider_binding_ignores is gone from " + common
                    + " — the scaffolder this fixture mirrors has moved");
        }
        int end = source.indexOf("\ndef ", at + 1);
        String body = end < 0 ? source.substring(at) : source.substring(at, end);
        List<String> paths = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("\"/")) continue;
            int close = trimmed.indexOf('"', 1);
            if (close > 1) paths.add(trimmed.substring(1, close));
        }
        return List.copyOf(paths);
    }

    /** {@code rel} under the repository root, found by walking up from the cwd. */
    private static Path locate(String rel) {
        Path here = Path.of("").toAbsolutePath();
        for (Path dir = here; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(rel);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("could not find " + rel + " from " + here
                + " — this fixture reads the scaffolder's own source");
    }

    // ------------------------------------------------------------------- git

    /**
     * Give a fixture unit an index, because {@link GitIgnoreRules} refuses to
     * read a declaration without one — no readable index means nothing is
     * ignored, deliberately, so a fixture with no repository would test nothing.
     *
     * <p>Only {@code SKILL.md} is added. Nothing under {@code test_graph/} or
     * {@code results/} is tracked in any fixture here except
     * {@link Fixture#committedLinks}, so every case below turns on the
     * DECLARATION rather than on what happens to be in the index — which is
     * what those cases claim to be about.
     */
    private static void initRepo(Path unit) throws Exception {
        git(unit, "init", "-b", "main", "--quiet");
        git(unit, "add", "SKILL.md");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out;
        try (InputStream in = process.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir
                    + " (rc=" + exit + "): " + out);
        }
    }
}
