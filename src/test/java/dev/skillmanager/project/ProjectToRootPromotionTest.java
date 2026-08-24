package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.source.InstalledUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>project home to root home</b> — the promotion direction the owner asked to
 * enforce, and the one nothing exercised until it was run by hand and failed.
 *
 * <h2>What was measured, and what the issue got wrong</h2>
 *
 * <p>On 2026-08-24, four units were synced into the operator's root home. All
 * four failed with {@code PROJECT_SYNC_FAILED: meta-harness} — one of six
 * registered projects, carrying three absolutely-spelled vendored symlinks.
 *
 * <p>Issue #254 records that as "one project's durability defect refuses every
 * unit sync … none of which names it or is named by it", and prescribes
 * scoping the refusal to the units the failing project actually claims.
 * <b>Both halves of that are wrong, and this suite is where it is written
 * down.</b> Read from the operator's root home the same day:
 *
 * <pre>
 *   meta-harness/project-lock.toml claims git-issue-workflow,
 *   git-epic-workflow, skill-manager and skt — ALL FOUR of the units that
 *   failed.
 * </pre>
 *
 * <p>And the prescribed fix has shipped since #144:
 * {@code LiveInterpreter.syncClaimingProjects} already stamps failures only on
 * {@code projectClaimers.get(projectName)}, guarded by
 * {@code ProjectDependencyResolverTest}'s "project sync failures are recorded
 * only on units claiming the failed project". A node that registered two
 * projects, broke one, and synced a unit the broken one <em>does not claim</em>
 * would therefore have passed against the unfixed build — a vacuous assertion
 * of the kind the epic's own ledger is 20 rows deep in. It is still written
 * below, as {@code CASE 3}, precisely so the claim is testable rather than
 * asserted; it is labelled as the control it is, not as the fix.
 *
 * <h2>So what the defect actually is</h2>
 *
 * <p>The scope was never units-per-project. It is <b>kind of failure</b>. A
 * project's {@code [[vendored]]} durability finding is a statement about that
 * project's checkout: it was true before the unit sync and it is true after,
 * and no byte the sync moved can cause or cure it. Recording it as
 * {@code PROJECT_SYNC_FAILED} on the unit — and counting the receipt's PARTIAL
 * toward sync's exit code — makes a correct check about project A into a
 * refusal of change management for every unit A claims, at the tier above.
 *
 * <p><b>The check is not weakened.</b> {@code CASE 2} is the guard: the same
 * broken project's own {@code project resolve} still refuses, with the same
 * message, at the same seam. Delete the check to make {@code CASE 1} pass and
 * {@code CASE 2} goes red.
 *
 * <h2>The owner's second sentence is CASE 4</h2>
 *
 * <blockquote>"We need to be enforcing change management of the root skill
 * manager from project home. <b>However, we should still be able to update the
 * root.</b>"</blockquote>
 *
 * <p>A design that makes the first half work by forbidding root writes fails
 * the second. {@code CASE 4} drives the real {@code SyncCommand} at the root
 * tier with a non-durable project registered and asserts exit 0 <em>and</em>
 * that the unit's bytes actually moved — an exit code alone cannot distinguish
 * "succeeded" from "did nothing", which is vacuity-ledger row 19's shape.
 */
public final class ProjectToRootPromotionTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectToRootPromotionTest")

                // ---------------------------------------------------- CASE 1: the fix

                .test("a unit sync into the root home survives a non-durable claiming project", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "promotion-claimed");
                        f.breakVendoredDurably();

                        // PRECONDITION, asserted rather than assumed (mechanism B).
                        // Two things must be true or CASE 1 is about nothing:
                        // the project must CLAIM the unit, and the breakage must
                        // be the durability finding rather than some other IO
                        // failure that happens to abort the resolve.
                        assertTrue(new SkillProjectLockStore(h.store())
                                        .projectsClaiming(f.unitName).contains(f.projectName),
                                "fixture precondition: the broken project claims the synced unit — "
                                        + "without this CASE 3's shape is being tested, not CASE 1's");
                        ProjectVendoredResolver.Report broken =
                                ProjectVendoredResolver.check(f.reload(), false);
                        assertEquals(ProjectVendoredResolver.Status.ABSOLUTE_IN_PROJECT,
                                broken.entries().get(0).status(),
                                "fixture precondition: the breakage is the meta-harness shape — "
                                        + "an absolute symlink into the project's own tree");
                        assertEquals(1, broken.fatalProblems().size(),
                                "fixture precondition: and it is fatal to a project resolve");

                        EffectReceipt receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                List.of(f.unitName), null, false));

                        // THE CLAIM. Three independent readings of it, because
                        // the symptom the operator hit was the error record and
                        // the symptom the exit code reads is the receipt status.
                        assertFalse(h.sourceOf(f.unitName).orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "a project's own non-durable vendored paths are not the unit's fault, "
                                        + "and are not recorded as its error");
                        assertEquals(EffectStatus.OK, receipt.status(),
                                "and do not turn the parent-home sync PARTIAL — PARTIAL on this "
                                        + "effect is counted by SyncUseCase.report() and is exactly "
                                        + "what made `skt sync <unit>` exit non-zero");

                        // ...and it is NOT silent. A fix that made the finding
                        // disappear would pass the three assertions above and be
                        // worse than the defect.
                        ContextFact.ProjectSyncSkippedNonDurable skipped = receipt.facts().stream()
                                .filter(ContextFact.ProjectSyncSkippedNonDurable.class::isInstance)
                                .map(ContextFact.ProjectSyncSkippedNonDurable.class::cast)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                        "the skipped project must still be named; facts were "
                                                + receipt.facts()));
                        assertEquals(f.projectName, skipped.projectName(),
                                "the fact names the project whose realization was skipped");
                        assertEquals(1, skipped.findings(), "and how many declared paths are at risk");
                        assertContains(skipped.message(), "--repair-vendored",
                                "and the exact command that clears it");
                        // MAJOR 2 of PR #255's review: this used to assert the
                        // string "NOT refreshed", which was measurably FALSE --
                        // register(), installMissing() and scaffold() all run
                        // before checkVendored. The claim now is that the message
                        // REPORTS what happened rather than asserting a negative.
                        assertContains(skipped.message(), "PARTIALLY REFRESHED",
                                "and states the cost plainly rather than implying success");
                        assertFalse(skipped.message().contains("was NOT refreshed"),
                                "never the false negative it used to print: " + skipped.message());
                        assertContains(skipped.message(), "no units needed installing",
                                "and in THIS fixture nothing was installed, so it must not claim "
                                        + "the units-lock was rewritten either -- the conditional "
                                        + "half is the part that keeps the new sentence true");
                    }
                })

                // ------------------------------------- CASE 2: the guard. Scoped, not weakened

                .test("the project that owns the non-durable paths is still refused its own resolve", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "promotion-guard");
                        f.breakVendoredDurably();

                        try {
                            new ProjectDependencyResolver(h.store(), null).resolve(
                                    f.reload(), new ProjectDependencyResolver.Options(true, false));
                            throw new AssertionError(
                                    "CASE 1 must not be satisfiable by deleting the check: the "
                                            + "project whose declaration is non-durable still has "
                                            + "its own resolve refused");
                        } catch (ProjectVendoredDurabilityException refused) {
                            assertContains(refused.getMessage(), "project vendored paths are invalid",
                                    "with the finding, not a generic error");
                            assertContains(refused.getMessage(), "ABSOLUTE_IN_PROJECT",
                                    "naming the mode");
                            assertContains(refused.getMessage(), "--copy-sdk",
                                    "and the remedy");
                            assertEquals(f.projectName, refused.projectName(),
                                    "attributed to the project it is about");
                        }
                    }
                })

                // ------------------------- CASE 3: the control #254 prescribed as the fix

                .test("a non-durable project that does not claim the unit was never in the way", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture broken = Fixture.build(h, "promotion-unrelated-broken");
                        broken.breakVendoredDurably();
                        Fixture healthy = Fixture.build(h, "promotion-unrelated-healthy");

                        assertFalse(new SkillProjectLockStore(h.store())
                                        .projectsClaiming(healthy.unitName)
                                        .contains(broken.projectName),
                                "fixture precondition: the broken project does not claim this unit");

                        EffectReceipt receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                List.of(healthy.unitName), null, false));

                        assertEquals(EffectStatus.OK, receipt.status(),
                                "an unrelated project's condition never reached this sync");
                        assertFalse(h.sourceOf(healthy.unitName).orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "and stamps nothing on it");
                        // Stated as a control, not as evidence for the fix: this
                        // case passes against the unfixed build too, because
                        // projectsClaiming() has scoped this since #144.
                        assertTrue(receipt.facts().stream()
                                        .noneMatch(ContextFact.ProjectSyncSkippedNonDurable.class::isInstance),
                                "the broken project is not even visited, so it is not reported here "
                                        + "either — a skip notice about a project the operator did "
                                        + "not touch would be the new spurious hold-back");
                    }
                })

                // ------------------- CASE 4: "we should still be able to update the root"

                .test("a direct root update still works while a claiming project is non-durable", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "promotion-direct-root");
                        f.breakVendoredDurably();

                        // The bytes have to actually move, or exit 0 proves
                        // nothing (vacuity-ledger row 19: an arm that never ran
                        // while its controls were green).
                        Files.writeString(f.sourceDir.resolve("NEW-UPSTREAM.md"),
                                "content published after the unit was installed\n");
                        f.commitSource("publish new content");
                        Path landed = h.store().skillDir(f.unitName).resolve("NEW-UPSTREAM.md");
                        assertFalse(Files.exists(landed),
                                "fixture precondition: the root home does not hold the new content yet");

                        SyncCommand cmd = new SyncCommand(h.store());
                        cmd.skipMcp = true;
                        cmd.skipAgents = true;
                        int rc = cmd.call();

                        assertEquals(0, rc,
                                "the root home is still directly updatable — the enforced direction "
                                        + "must not be implemented by forbidding root writes");
                        assertTrue(Files.exists(landed),
                                "and it actually updated: exit 0 over a sync that moved nothing "
                                        + "would satisfy the exit code and not the sentence");
                        assertFalse(h.sourceOf(f.unitName).orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "with no project-sync error left on the unit");
                    }
                })

                // ------- CASE 5: MAJOR 2 of PR #255. The advisory must REPORT, not assert.

                .test("the advisory names what was already written, and its remedy is runnable", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "promotion-advisory");
                        f.breakVendoredDurably();

                        // A SECOND unit, declared but not yet installed, so the
                        // refusal happens with real work already committed behind
                        // it. Without this the case could pass while the advisory
                        // said "nothing happened" and nothing had.
                        Path second = UnitFixtures.scaffoldSkill(
                                f.repoRoot.resolve("src2"), f.unitName + "-b", DepSpec.empty())
                                .sourcePath();
                        gitInitCommit(second);
                        f.declareAlso("demo_b", second);
                        assertFalse(h.store().containsUnit(f.unitName + "-b"),
                                "fixture precondition: the second unit is not installed yet");

                        ProjectVendoredDurabilityException refused = null;
                        try {
                            new ProjectDependencyResolver(h.store(), null).resolve(
                                    f.reload(), new ProjectDependencyResolver.Options(true, false));
                        } catch (ProjectVendoredDurabilityException e) {
                            refused = e;
                        }
                        if (refused == null) throw new AssertionError("expected the refusal");

                        // THE MEASUREMENT. checkVendored runs AFTER register(),
                        // installMissing() and scaffold() -- by its own javadoc,
                        // because the declared vendored source lives inside the
                        // child home the scaffolder creates. So work HAS landed.
                        assertTrue(h.store().containsUnit(f.unitName + "-b"),
                                "the second unit really was installed before the refusal -- this "
                                        + "is what makes \"NOT refreshed\" a false sentence");

                        String advisory = refused.advisory();
                        assertFalse(advisory.contains("NOT refreshed"),
                                "the advisory must not claim nothing happened when four things "
                                        + "did, behind exit 0. Pre-PR the operator saw exit 1 and "
                                        + "went looking; a false 'nothing happened' at exit 0 is "
                                        + "strictly worse. Got: " + advisory);
                        assertContains(advisory, "PARTIALLY REFRESHED",
                                "it says what actually happened");
                        assertContains(advisory, "unit(s) installed into the store",
                                "naming the work that landed");
                        assertContains(advisory, "bindings NOT materialized",
                                "and the work that did not");

                        // MINOR 1: the remedy has to be pasteable. It used to
                        // interpolate the project NAME into a `--project-dir <...>`
                        // placeholder; angle brackets are shell redirection and the
                        // name is not the directory.
                        assertFalse(advisory.contains("<" + f.projectName + ">"),
                                "the remedy no longer puts the project NAME in angle brackets: "
                                        + advisory);
                        assertContains(refused.repairCommand(), f.repoRoot.toString(),
                                "it names the project's real directory");
                        assertEquals(f.repoRoot.toAbsolutePath().normalize(),
                                refused.projectRoot().toAbsolutePath().normalize(),
                                "which the exception carries from the SkillProject it refused");
                    }
                })

                .runAll();
    }

    // ----------------------------------------------------------------- fixture

    /**
     * One registered project, in its own directory, with its own child home, a
     * git-backed skill it claims, and one declared vendored path that starts
     * <em>valid</em> — a {@code --copy-sdk} style snapshot directory — so that
     * the resolve which registers it succeeds.
     *
     * <p>{@link #breakVendoredDurably()} then replaces that snapshot with the
     * exact shape measured on {@code meta-harness}: an absolute symlink landing
     * inside the project's own tree, at the declared source. It resolves. It is
     * still wrong, for the reason HIS-19 shipped for the CLI pin.
     */
    private static final class Fixture {
        final TestHarness h;
        final Path repoRoot;
        final Path sourceDir;
        final String projectName;
        final String unitName;

        private Fixture(TestHarness h, Path repoRoot, Path sourceDir,
                        String projectName, String unitName) {
            this.h = h;
            this.repoRoot = repoRoot;
            this.sourceDir = sourceDir;
            this.projectName = projectName;
            this.unitName = unitName;
        }

        static Fixture build(TestHarness h, String slug) throws Exception {
            Path repoRoot = Files.createTempDirectory(slug + "-");
            String unitName = slug + "-unit";
            Path sourceDir = UnitFixtures.scaffoldSkill(
                    repoRoot.resolve("src"), unitName, DepSpec.empty()).sourcePath();
            gitInitCommit(sourceDir);

            // The declared vendored source has to exist inside the project's own
            // home for the finding to be ABSOLUTE_IN_PROJECT rather than
            // MISPOINTED — the classifier judges "absolute" against "lands at
            // the declared source", and conflating the two would test a
            // different status than the one measured.
            Path declaredSource = repoRoot.resolve(
                    ".skill-manager/skills/vendor-unit/vendor_sources/sdk");
            Files.createDirectories(declaredSource);
            Files.writeString(declaredSource.resolve("marker.txt"), "vendored sdk content\n");

            // Start legal: a real directory with content is Status.COPY.
            Path declaredPath = repoRoot.resolve("vendor/sdk");
            Files.createDirectories(declaredPath);
            Files.writeString(declaredPath.resolve("marker.txt"), "vendored sdk content\n");

            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "%s"

                    [skills.demo]
                    source = "git+file://%s#main"

                    [[vendored]]
                    name = "vendor-sdk"
                    paths = ["vendor/sdk"]
                    from_unit = "vendor-unit"
                    from_subpath = "vendor_sources"
                    on_invalid = "error"
                    """.formatted(slug, sourceDir));

            SkillProject project = SkillProjectParser.load(repoRoot);
            ProjectDependencyResolver.Result result =
                    new ProjectDependencyResolver(h.store(), null)
                            .resolve(project, new ProjectDependencyResolver.Options(true, false));
            assertTrue(result.vendored().clean(),
                    "fixture precondition: the project registers CLEAN, so the breakage below is "
                            + "the only thing under test — got " + result.vendored().render());
            assertTrue(h.store().containsUnit(unitName),
                    "fixture precondition: the unit really landed in the root home");
            return new Fixture(h, repoRoot, sourceDir, slug, unitName);
        }

        /** Add a second declared unit to the manifest, leaving everything else. */
        void declareAlso(String alias, Path source) throws IOException {
            Path manifest = repoRoot.resolve("skill-project.toml");
            Files.writeString(manifest, Files.readString(manifest)
                    + "\n[skills." + alias + "]\nsource = \"git+file://" + source + "#main\"\n");
        }

        SkillProject reload() throws IOException {
            return SkillProjectParser.load(repoRoot);
        }

        /** meta-harness's shape: absolute link text, landing at the declared source. */
        void breakVendoredDurably() throws IOException {
            Path declaredPath = repoRoot.resolve("vendor/sdk");
            deleteTree(declaredPath);
            Path absoluteTarget = repoRoot.resolve(
                    ".skill-manager/skills/vendor-unit/vendor_sources/sdk")
                    .toAbsolutePath().normalize();
            Files.createSymbolicLink(declaredPath, absoluteTarget);
            // It RESOLVES. That is the whole point: link existence is not the
            // test, and a check that passed here would be the defect.
            assertTrue(Files.isDirectory(declaredPath),
                    "fixture precondition: the non-durable link resolves on this machine");
        }

        void commitSource(String message) throws Exception {
            git(sourceDir, "add", ".");
            git(sourceDir, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                    "commit", "-m", message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void gitInitCommit(Path repo) throws Exception {
        git(repo, "init", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                "commit", "-m", "initial");
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
