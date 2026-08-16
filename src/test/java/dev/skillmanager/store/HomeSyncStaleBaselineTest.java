package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.SyncGitHandler;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A home that moved AFTER it was cloned, and a home cloned from it AFTER that
 * move — issue #210, measured on a real project home.
 *
 * <h2>The defect</h2>
 *
 * <p>A project home is cloned from the root home; {@code home clone} writes a
 * per-unit baseline naming the tree it started with, and names no source (it
 * is the home's own clone-time state, and therefore also the state of the home
 * it came from). Then {@code skill-manager sync <unit>} pulls newer upstream
 * commits into the project home's store copy — three times, over two weeks —
 * and refreshes {@code installed/<unit>.json} each time and the materialization
 * record never. Ticket worktree homes are cloned from the project home in the
 * middle of that sequence, and restate their own baselines correctly against
 * what they were handed.
 *
 * <p>When a ticket comes back through {@code home close-out}, the project
 * home's stale clone-time baseline is accepted as "the state these two homes
 * share" ({@code describesSource}, first showing) and becomes the merge base.
 * The ticket is on v2, the project on v3, the base says v1 — so every file that
 * moved in v1..v2 as well as v2..v3 reads as "changed on both sides". Four units
 * nobody had edited reported 11, 8, 9 and 45 conflicts, six of them under
 * {@code .git/} ({@code index}, {@code FETCH_HEAD}, reflogs), and the worktree
 * could not be closed.
 *
 * <h2>What these cases pin down</h2>
 *
 * <ol>
 *   <li>The reported shape end to end: an unedited ticket home cloned after an
 *       upstream sync closes clean against a project home that synced again
 *       since — through git's own answer (every ref of the ticket is already
 *       in the project's history), before any record is consulted.</li>
 *   <li>The same shape with a working-tree edit in the ticket, so git cannot
 *       vouch for it and the record rules must: the edit merges up, the
 *       project's newer files stay, and {@code git status} on both sides —
 *       which rewrites {@code .git/index} and authors nothing — is not a
 *       conflict. And the pass AFTER that merge still clears the gate.</li>
 *   <li>Without git at all, the base preference on its own: a destination
 *       that edited a unit in place after its clone and was then cloned into
 *       a source that edited a different file.</li>
 *   <li>The upstream sync itself now restates the baseline of a pristine copy
 *       and leaves an edited copy's record alone.</li>
 * </ol>
 */
public final class HomeSyncStaleBaselineTest {

    private static final String UNIT = "stale-baseline-skill";

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== HomeSyncStaleBaselineTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("HomeSyncStaleBaselineTest");

        suite.test("an unedited ticket cloned after an upstream sync closes clean against a project "
                + "that synced again since", () -> {
            Tiers t = Tiers.create("clean");
            // v1 -> project cloned -> v2 pulled (record NOT refreshed, the
            // pre-fix state) -> ticket cloned -> v3 pulled.
            t.upstreamCommit("v2", Map.of("charts/values.yaml", "replicas: 2\n",
                    "src/main.py", "print('v2')\n"));
            t.pullIntoWithoutRestating(t.projectUnit());
            assertFalse(t.projectRecordMatchesTree(),
                    "the project home's record is stale after the sync — that is the state this "
                            + "suite exists for, and if it were fresh the case proves nothing");
            t.cloneTicket();
            t.upstreamCommit("v3", Map.of("charts/values.yaml", "replicas: 3\n",
                    "docs/notes.md", "v3 notes\n"));
            t.pullIntoWithoutRestating(t.projectUnit());
            // The bookkeeping churn the real homes carried: skt check runs
            // `git status` in every store copy.
            git(t.ticketUnit(), "status", "--porcelain");
            git(t.projectUnit(), "status", "--porcelain");
            assertFalse(t.ticketRecordEntry(".git/index")
                            .equals(currentEntry(t.ticketUnit(), ".git/index")),
                    "the ticket's .git/index really moved off its own record — without this the "
                            + "bookkeeping half of the defect is not on the table");

            // Untracked build output in the ticket's copy: not content, and not
            // a reason for git's answer to be withheld (issue #41's rule).
            Files.createDirectories(t.ticketUnit().resolve("build/classes"));
            Files.writeString(t.ticketUnit().resolve("build/classes/Out.class"), "bytes");
            String ticketBefore = ChildHomeMaterializer.treeDigest(t.ticketUnit());
            String projectBefore = ChildHomeMaterializer.treeDigest(t.projectUnit());

            UnitSync outcome = only(HomeSync.run(t.ticket, t.project,
                    new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.UNCHANGED, outcome.status(),
                    "the ticket holds nothing the project lacks: " + outcome.detail());
            assertEquals(ticketBefore, ChildHomeMaterializer.treeDigest(t.ticketUnit()),
                    "asking changed not one byte of the ticket's copy -- a `git status` that "
                            + "refreshes the index would have");
            assertEquals(projectBefore, ChildHomeMaterializer.treeDigest(t.projectUnit()),
                    "nor of the project's");
            assertContains(outcome.detail(), "ahead of the source",
                    "and the sentence says which way round it is: " + outcome.detail());
            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(t.ticket, t.project);
            assertTrue(verdict.safe(),
                    "and the teardown gate clears: " + HomeCloseOut.render(verdict));
            assertEquals(t.head(t.projectUnit()), t.upstreamHead(),
                    "the project is still on v3 — nothing rewound it");
        });

        suite.test("a ticket edit on top of a superseded baseline merges up without a conflict on "
                + "git's own bookkeeping, and the next pass clears the gate", () -> {
            Tiers t = Tiers.create("edit");
            t.upstreamCommit("v2", Map.of("charts/values.yaml", "replicas: 2\n",
                    "src/main.py", "print('v2')\n"));
            t.pullIntoWithoutRestating(t.projectUnit());
            t.cloneTicket();
            t.upstreamCommit("v3", Map.of("charts/values.yaml", "replicas: 3\n",
                    "docs/notes.md", "v3 notes\n"));
            t.pullIntoWithoutRestating(t.projectUnit());
            // The agent's actual work: one file, uncommitted, in the ticket home.
            Files.writeString(t.ticketUnit().resolve("references/agent-notes.md"),
                    "written in the ticket\n");
            git(t.ticketUnit(), "status", "--porcelain");
            git(t.projectUnit(), "status", "--porcelain");

            UnitSync plan = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.MERGED, plan.status(),
                    "the edit is the only thing the ticket has to offer: " + plan.detail()
                            + " conflicts=" + plan.conflicts());
            assertEquals(List.of("references/agent-notes.md"), plan.files(),
                    "and it is exactly the edit that travels: " + plan.files());
            assertTrue(plan.conflicts().isEmpty(),
                    "no conflict — least of all on .git/index: " + plan.conflicts());

            UnitSync applied = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, false)));
            assertEquals(SyncStatus.MERGED, applied.status(), applied.detail());
            assertEquals("written in the ticket\n",
                    Files.readString(t.projectUnit().resolve("references/agent-notes.md")),
                    "the edit reached the project home");
            assertEquals("replicas: 3\n",
                    Files.readString(t.projectUnit().resolve("charts/values.yaml")),
                    "and the project's newer upstream files were kept");
            assertEquals(t.upstreamHead(), t.head(t.projectUnit()),
                    "and its history was neither rewound nor replaced");

            // The pass after `skt publish`: the ticket still carries its
            // (now-published) edit, so git cannot vouch for it and the record
            // written by the merge above is what decides. It must not conflict
            // on the paths that merge could not claim as shared.
            UnitSync after = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.UNCHANGED, after.status(),
                    "the second pass has nothing left to take: " + after.detail()
                            + " conflicts=" + after.conflicts());
            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(t.ticket, t.project);
            assertTrue(verdict.safe(),
                    "and the gate clears once the edit is home: " + HomeCloseOut.render(verdict));
        });

        suite.test("the source's own record is preferred over a clone-time baseline the destination "
                + "moved off — with no git in the picture", () -> {
            Path root = Files.createTempDirectory("stale-baseline-plain-");
            SkillStore project = store(root.resolve("project"));
            UnitFixtures.scaffoldSkill(project.skillsDir(), UNIT, DepSpec.empty());
            Path projectUnit = project.skillDir(UNIT);
            Files.writeString(projectUnit.resolve("f.md"), "f v1\n");
            Files.writeString(projectUnit.resolve("g.md"), "g v1\n");
            ChildHomeMaterializer.recordCloneBaselines(project);
            // The project moves f in place after its clone-time baseline was written.
            Files.writeString(projectUnit.resolve("f.md"), "f v2 (project)\n");
            // A ticket cloned from THAT state restates its own baseline (f v2, g v1).
            SkillStore ticket = new SkillStore(root.resolve("ticket"));
            HomeCloner.cloneHome(project.root(), ticket.root());
            ChildHomeMaterializer.recordCloneBaselines(ticket);
            Path ticketUnit = ticket.skillDir(UNIT);
            // The ticket edits the SAME file the project had edited before the
            // clone; the project meanwhile moves g, so the ticket's clone-time
            // bytes are no longer what the project holds and the merge path is
            // the only way home.
            Files.writeString(ticketUnit.resolve("f.md"), "f v3 (ticket)\n");
            Files.writeString(projectUnit.resolve("g.md"), "g v2 (project)\n");

            UnitSync plan = only(HomeSync.run(ticket, project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.MERGED, plan.status(),
                    "the ticket moved f off the f it was HANDED, and the project has not touched "
                            + "f since; against the project's clone-time f both sides look moved: "
                            + plan.detail() + " conflicts=" + plan.conflicts());
            assertEquals(List.of("f.md"), plan.files(), "f travels: " + plan.files());
            assertTrue(plan.conflicts().isEmpty(),
                    "f is not a conflict — the ticket was handed the project's f: " + plan.conflicts());
        });

        suite.test("an upstream sync restates the baseline of a pristine copy and leaves an edited "
                + "copy's record alone", () -> {
            try (TestHarness h = TestHarness.create()) {
                Path remote = Files.createTempDirectory("stale-baseline-remote-");
                git(remote, "init", "-b", "main", "--quiet");
                Files.writeString(remote.resolve("SKILL.md"),
                        "---\nname: " + UNIT + "\ndescription: x\n---\nv1\n");
                git(remote, "add", "-A");
                commit(remote, "v1");
                String v1 = head(remote);

                Path unit = h.store().unitDir(UNIT, UnitKind.SKILL);
                Files.createDirectories(unit.getParent());
                git(unit.getParent(), "clone", "--quiet", remote.toString(), unit.toString());
                Files.createDirectories(unit.resolve("references"));
                new UnitStore(h.store()).write(new InstalledUnit(UNIT, "0.1.0",
                        InstalledUnit.Kind.GIT, InstalledUnit.InstallSource.GIT,
                        remote.toString(), v1, "main", UnitStore.nowIso(), List.of(),
                        UnitKind.SKILL));
                // The home's own baseline, as `home clone` writes it.
                ChildHomeMaterializer.recordCloneBaselines(h.store());
                assertTrue(ChildHomeMaterializer.standsOnItsCopyRecord(h.store(), UNIT, UnitKind.SKILL),
                        "a freshly baselined copy stands on its record");

                Files.writeString(remote.resolve("SKILL.md"),
                        "---\nname: " + UNIT + "\ndescription: x\n---\nv2\n");
                git(remote, "add", "-A");
                commit(remote, "v2");
                String v2 = head(remote);

                EffectReceipt receipt = SyncGitHandler.run(new SkillEffect.SyncGit(UNIT,
                        UnitKind.SKILL, InstalledUnit.InstallSource.GIT, true, false),
                        new EffectContext(h.store(), null));
                assertEquals(EffectStatus.OK, receipt.status(),
                        "the sync itself succeeded: " + receipt.errorMessage());
                assertEquals(v2, head(unit), "and moved the store copy to v2");
                assertTrue(ChildHomeMaterializer.standsOnItsCopyRecord(h.store(), UNIT, UnitKind.SKILL),
                        "THE RECORD FOLLOWED THE SYNC: the copy still stands on its record, so no "
                                + "prune, refresh or close-out reads upstream's bytes as a local edit");
                assertFalse(new ChildHomeMaterializer(h.store(), h.store())
                                .isLocallyModified(UNIT, UnitKind.SKILL),
                        "and it is not 'locally modified'");

                // Now an edit, then another upstream move: the record must NOT
                // be restated over the edit.
                Files.writeString(unit.resolve("references/agent-notes.md"), "local work\n");
                assertFalse(ChildHomeMaterializer.standsOnItsCopyRecord(h.store(), UNIT, UnitKind.SKILL),
                        "an edited copy does not stand on its record");
                Files.writeString(remote.resolve("upstream.md"), "v3\n");
                git(remote, "add", "-A");
                commit(remote, "v3");
                EffectReceipt merged = SyncGitHandler.run(new SkillEffect.SyncGit(UNIT,
                        UnitKind.SKILL, InstalledUnit.InstallSource.GIT, true, true),
                        new EffectContext(h.store(), null));
                assertEquals(EffectStatus.OK, merged.status(),
                        "the --merge sync succeeded: " + merged.errorMessage());
                assertEquals("local work\n", Files.readString(unit.resolve("references/agent-notes.md")),
                        "the edit survived the sync");
                assertTrue(new ChildHomeMaterializer(h.store(), h.store())
                                .isLocallyModified(UNIT, UnitKind.SKILL),
                        "and the copy still reads as locally modified — the sync did not launder "
                                + "the edit into a pristine baseline");
            }
        });

        suite.test("a ticket that pulled newer upstream fast-forwards a clean project home and "
                + "refuses -- with the sync as the remedy -- an edited one", () -> {
            Tiers t = Tiers.create("ahead");
            t.cloneTicket();
            t.upstreamCommit("v2", Map.of("charts/values.yaml", "replicas: 2\n"));
            // The TICKET syncs; the project does not. Records restated the way
            // the fixed sync leaves them.
            t.pullIntoWithoutRestating(t.ticketUnit());
            ChildHomeMaterializer.restateBaseline(t.ticket, UNIT, UnitKind.SKILL);

            UnitSync plan = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.UPDATED, plan.status(),
                    "a clean project whose history the ticket contains is fast-forwarded: "
                            + plan.detail());
            assertTrue(plan.files().contains("charts/values.yaml"),
                    "and the files those commits carry travel with .git: " + plan.files());
            UnitSync applied = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, false)));
            assertEquals(SyncStatus.UPDATED, applied.status(), applied.detail());
            assertEquals(t.upstreamHead(), t.head(t.projectUnit()),
                    "the project's HEAD is now v2 -- history and files moved together");
            assertEquals("replicas: 2\n",
                    Files.readString(t.projectUnit().resolve("charts/values.yaml")),
                    "and its files match its HEAD");
            assertTrue(HomeCloseOut.inspect(t.ticket, t.project).safe(),
                    "so the gate clears: " + HomeCloseOut.render(HomeCloseOut.inspect(t.ticket, t.project)));

            // Now the project has an uncommitted edit and the ticket pulls
            // further: .git cannot travel without leaving the project's files
            // and HEAD disagreeing, and the answer says what to run instead.
            Files.writeString(t.projectUnit().resolve("references/project-notes.md"),
                    "edited in the project\n");
            t.upstreamCommit("v3", Map.of("charts/values.yaml", "replicas: 3\n"));
            t.pullIntoWithoutRestating(t.ticketUnit());
            ChildHomeMaterializer.restateBaseline(t.ticket, UNIT, UnitKind.SKILL);
            UnitSync blocked = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.CONFLICTED, blocked.status(),
                    "an edited project cannot take the ticket's .git wholesale: " + blocked.detail());
            assertTrue(blocked.conflicts().contains(ChildHomeMaterializer.GIT_HISTORY_CONFLICT),
                    "the conflict is named as the history, not as index files: " + blocked.conflicts());
            assertContains(blocked.detail(), "skill-manager sync " + UNIT,
                    "and the remedy is to bring the project up to date, not to publish: "
                            + blocked.detail());
            assertEquals("edited in the project\n",
                    Files.readString(t.projectUnit().resolve("references/project-notes.md")),
                    "nothing was written over the project's edit");
            // The project catches up (what `skill-manager sync` does); the
            // ticket then holds nothing unique and the gate clears.
            t.pullIntoWithoutRestating(t.projectUnit());
            UnitSync after = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.UNCHANGED, after.status(), after.detail());
            assertTrue(HomeCloseOut.inspect(t.ticket, t.project).safe(), "gate clears once the project is current");
        });

        suite.test("a local commit in the ticket's store copy is still unique work: neither history "
                + "contains the other once the project has moved too", () -> {
            Tiers t = Tiers.create("localcommit");
            t.cloneTicket();
            Files.writeString(t.ticketUnit().resolve("references/agent-notes.md"), "committed here\n");
            git(t.ticketUnit(), "add", "-A");
            commit(t.ticketUnit(), "agent: local commit in the store copy");
            String agentCommit = t.head(t.ticketUnit());
            t.upstreamCommit("v2", Map.of("charts/values.yaml", "replicas: 2\n"));
            t.pullIntoWithoutRestating(t.projectUnit());

            UnitSync plan = only(HomeSync.run(t.ticket, t.project, new HomeSync.Options(true, true)));
            assertEquals(SyncStatus.CONFLICTED, plan.status(),
                    "diverged histories are a conflict, never a silent skip: " + plan.detail());
            assertTrue(plan.conflicts().contains(ChildHomeMaterializer.GIT_HISTORY_CONFLICT),
                    "named as the history: " + plan.conflicts());
            assertContains(plan.detail(), "unit publish", "and the remedy is unit publish");
            assertFalse(HomeCloseOut.inspect(t.ticket, t.project).safe(),
                    "the gate refuses while that commit exists in one home only");
            assertEquals(agentCommit, t.head(t.ticketUnit()), "and nothing touched the ticket");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * Upstream, a root home cloned from it, a project home cloned from root by
     * {@code home clone} (clone-time baselines, no source), and later a ticket
     * home cloned from the project the same way.
     */
    private static final class Tiers {
        final Path upstream;
        final SkillStore rootHome;
        final SkillStore project;
        SkillStore ticket;

        private Tiers(Path upstream, SkillStore rootHome, SkillStore project) {
            this.upstream = upstream;
            this.rootHome = rootHome;
            this.project = project;
        }

        static Tiers create(String label) throws Exception {
            Path root = Files.createTempDirectory("stale-baseline-" + label + "-");
            Path upstream = root.resolve("upstream");
            Files.createDirectories(upstream);
            git(upstream, "init", "-b", "main", "--quiet");
            UnitFixtures.scaffoldSkill(root.resolve("scaffold"), UNIT, DepSpec.empty());
            copyTree(root.resolve("scaffold").resolve(UNIT), upstream);
            Files.createDirectories(upstream.resolve("charts"));
            Files.createDirectories(upstream.resolve("src"));
            Files.createDirectories(upstream.resolve("docs"));
            Files.createDirectories(upstream.resolve("references"));
            Files.writeString(upstream.resolve("charts/values.yaml"), "replicas: 1\n");
            Files.writeString(upstream.resolve("src/main.py"), "print('v1')\n");
            Files.writeString(upstream.resolve("docs/notes.md"), "v1 notes\n");
            Files.writeString(upstream.resolve("references/.keep"), "");
            git(upstream, "add", "-A");
            commit(upstream, "v1");

            SkillStore rootHome = store(root.resolve("root-home"));
            Path rootUnit = rootHome.skillDir(UNIT);
            git(rootHome.skillsDir(), "clone", "--quiet", upstream.toString(), rootUnit.toString());

            SkillStore project = new SkillStore(root.resolve("project-home"));
            HomeCloner.cloneHome(rootHome.root(), project.root());
            ChildHomeMaterializer.recordCloneBaselines(project);
            return new Tiers(upstream, rootHome, project);
        }

        void cloneTicket() throws Exception {
            ticket = new SkillStore(project.root().getParent().resolve("ticket-home"));
            HomeCloner.cloneHome(project.root(), ticket.root());
            ChildHomeMaterializer.recordCloneBaselines(ticket);
        }

        Path projectUnit() { return project.skillDir(UNIT); }

        Path ticketUnit() { return ticket.skillDir(UNIT); }

        void upstreamCommit(String message, Map<String, String> files) throws Exception {
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path f = upstream.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue());
            }
            git(upstream, "add", "-A");
            commit(upstream, message);
        }

        /** What `skill-manager sync` did before #210: fetch + fast-forward, record untouched. */
        void pullIntoWithoutRestating(Path unit) throws Exception {
            git(unit, "fetch", "--quiet", upstream.toString(), "main");
            git(unit, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                    "merge", "--quiet", "--ff-only", "FETCH_HEAD");
        }

        String upstreamHead() { return head(upstream); }

        String head(Path unit) { return HomeSyncStaleBaselineTest.head(unit); }

        boolean projectRecordMatchesTree() throws IOException {
            return ChildHomeMaterializer.standsOnItsCopyRecord(project, UNIT, UnitKind.SKILL);
        }

        String ticketRecordEntry(String rel) throws IOException {
            String json = Files.readString(ticket.root().resolve(".materialization/skill/" + UNIT + ".json"));
            int at = json.indexOf("\"" + rel + "\"");
            if (at < 0) throw new AssertionError("no entry for " + rel + " in the ticket's record");
            int colon = json.indexOf(':', at);
            int q1 = json.indexOf('"', colon);
            int q2 = json.indexOf('"', q1 + 1);
            return json.substring(q1 + 1, q2);
        }
    }

    private static String currentEntry(Path unit, String rel) throws IOException {
        Map<String, String> entries = ChildHomeMaterializer.entryDigests(unit, Set.of());
        String d = entries.get(rel);
        if (d == null) throw new AssertionError("no current entry for " + rel);
        return d;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(p);
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static UnitSync only(HomeSync.Report report) {
        return report.units().stream()
                .filter(unit -> unit.unitName().equals(UNIT))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome reported for " + UNIT));
    }

    private static String head(Path unit) {
        String head = GitOps.headHash(unit);
        if (head == null) throw new AssertionError("no HEAD in " + unit);
        return head;
    }

    // -------------------------------------------------------------- git glue

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Result r = run(dir, argv);
        if (r.exit != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir
                    + " (rc=" + r.exit + "): " + r.out);
        }
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", message);
    }

    private record Result(int exit, String out) {}

    private static Result run(Path workdir, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) pb.directory(workdir.toFile());
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(p.waitFor(), out.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private HomeSyncStaleBaselineTest() {}
}
