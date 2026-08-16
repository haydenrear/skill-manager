package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.commands.BuildCommand;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-06: the per-artifact repair, and the two attributions it refuses to act
 * on.
 *
 * <p>The headline case is the ticket's acceptance demonstration, run as a test
 * rather than only as a transcript: <b>hide a shim's output and
 * {@code build --stale} names it, rebuilds it, and the home holds it
 * again</b> — with no other artifact touched.
 *
 * <p>The rest of this suite is about what {@code build} declines to do, which
 * is where ARTI-05's review put the risk. A verb that rebuilt everything it
 * could find a plausible producer for would act on a containment inference at
 * the wrong granularity, and would report a class of artifact as fresh that
 * nothing in the home can check. Both refusals are asserted here as behaviour,
 * not left to a comment.
 */
public final class ArtifactBuildTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactBuildTest");

        // ------------------------------------------------- the headline case

        suite.test("hiding a shim's output makes `build --stale` name it", () -> {
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            ArtifactBuild.Plan plan = staleplan(store);

            List<String> ids = plan.rebuilds().stream().map(ArtifactBuild.Step::id).toList();
            assertTrue(ids.contains(ArtifactIds.cliShim("skill-script", "alpha-script")),
                    "the hidden shim is in the plan: " + ids);
            ArtifactBuild.Step step = plan.rebuilds().stream()
                    .filter(s -> s.id().equals(ArtifactIds.cliShim("skill-script", "alpha-script")))
                    .findFirst().orElseThrow();
            assertEquals("alpha", step.unitName(), "and it resolved to the unit that declares it");
            assertEquals("skill-script", step.dep().backend(), "through the right backend");
            assertTrue(step.unverifiableAfterBuild() == false,
                    "a skill-script rebuild CAN be verified afterwards");
        });

        suite.test("`build <artifact>` really rebuilds it, and the home holds it again", () -> {
            SkillStore store = fingerprinted();
            Path shim = store.root().resolve("bin/cli/alpha-script");
            Files.move(shim, store.root().resolve("bin/cli/.hidden"));
            assertFalse(Files.exists(shim), "precondition: the artifact is gone");

            Result r = build(store, ArtifactIds.cliShim("skill-script", "alpha-script"));

            assertEquals(0, r.rc, "the repair succeeded: " + r.err + r.out);
            assertTrue(Files.exists(shim), "and the home holds the artifact again");
            assertContains(r.out, "now: current",
                    "and the run reports the verdict it MEASURED afterwards");
            var after = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, after.freshness(),
                    "independently re-derived: " + after.reason());
        });

        suite.test("COMPANION: building one artifact does not build the others", () -> {
            // "Its stale prerequisites and nothing else." Without this, a
            // `build <one>` that quietly did the whole home would pass every
            // assertion above and be exactly the command this ticket replaces.
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));
            Path dangler = store.root().resolve("bin/cli/dangler");
            assertTrue(Files.isSymbolicLink(dangler), "precondition: the pip shim dangles");

            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactBuild.Plan plan = ArtifactBuild.of(index,
                    ArtifactFreshness.of(index, store), store, ArtifactBuild.Scope.NAMED,
                    List.of(ArtifactIds.cliShim("skill-script", "alpha-script")), false);

            assertEquals(1, plan.rebuilds().size(),
                    "only the named artifact: " + plan.rebuilds().stream()
                            .map(ArtifactBuild.Step::id).toList());
            // And the other stale one really was stale, so this is a narrowing
            // and not an empty home.
            assertTrue(staleplan(store).rebuilds().size() > 1,
                    "--stale would have taken more than one");
        });

        // ------------------------------------------ the refusals that matter

        suite.test("a provisioned tree is never a build target, whatever it inherited", () -> {
            // ARTI-05's review, measured: the tree inherits the claiming shim's
            // backend/tool/fingerprint through containment, so a lookup keyed on
            // what it RECORDS would resolve a producer for it. The rule is about
            // the KIND, and this asserts the kind is what decides.
            SkillStore store = fingerprinted();
            String treeId = ArtifactIds.provisionedTree("cache",
                    "skill-script-alpha-alpha-script");
            ArtifactIndex index = ArtifactIndex.of(store);
            Artifact tree = index.byId(treeId).orElseThrow();
            assertEquals("skill-script", tree.recorded().get("backend"),
                    "precondition: the tree DOES carry the shim's install, by containment");

            ArtifactBuild.Plan plan = ArtifactBuild.of(index,
                    ArtifactFreshness.of(index, store), store, ArtifactBuild.Scope.NAMED,
                    List.of(treeId), false);

            ArtifactBuild.Step step = plan.steps().stream()
                    .filter(s -> s.id().equals(treeId)).findFirst().orElseThrow();
            assertEquals(ArtifactBuild.Action.NOT_BUILDABLE, step.action(),
                    "an inferred owner is not a build trigger");
            assertContains(step.reason(), "inferred",
                    "and the refusal says why rather than reading as unimplemented");
            assertContains(step.reason(), "granularity",
                    "naming the failure mode the review measured");
        });

        suite.test("a missing tree still gets repaired — through the shim, not the tree", () -> {
            // The whole reason the refusal above costs nothing. A clone skips
            // cache/; the tree goes; the shim that execs into it inherits STALE
            // through the graph; the shim IS buildable; its install is what
            // rewrites the tree. The sound action is reached without acting on
            // the unsound attribution.
            SkillStore store = fingerprinted();
            deleteRecursive(store.root().resolve("cache/skill-script-alpha-alpha-script"));

            ArtifactBuild.Plan plan = staleplan(store);

            List<String> ids = plan.rebuilds().stream().map(ArtifactBuild.Step::id).toList();
            assertTrue(ids.contains(ArtifactIds.cliShim("skill-script", "alpha-script")),
                    "the shim that runs out of the missing tree is planned: " + ids);
            for (ArtifactBuild.Step step : plan.rebuilds()) {
                assertFalse(step.kind() == ArtifactKind.PROVISIONED_TREE,
                        "and no tree is a target: " + step.id());
            }
        });

        suite.test("a backend that records nothing is planned as unverifiable, up front", () -> {
            // #120. `build` may rebuild these unconditionally; what it may not
            // do is report them as fresh afterwards. The claim is made at PLAN
            // time so it cannot be an excuse invented after the fact.
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactBuild.Plan plan = ArtifactBuild.of(index,
                    ArtifactFreshness.of(index, store), store, ArtifactBuild.Scope.NAMED,
                    List.of(ArtifactIds.cliShim("pip", "alpha-pkg")), false);

            ArtifactBuild.Step step = plan.steps().get(0);
            assertEquals(ArtifactBuild.Action.REBUILD, step.action(), "it IS buildable");
            assertTrue(step.unverifiableAfterBuild(),
                    "and this home cannot check what the rebuild produced");
            assertContains(step.reason(), "#120",
                    "with the ticket that fixes it named in the reason");
        });

        suite.test("every unbuildable kind names the command that DOES rebuild it", () -> {
            // A refusal with no remedy is the #142 class. Asserted over the
            // whole home rather than one kind, so a kind added later cannot
            // quietly ship a dead end.
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactBuild.Plan plan = ArtifactBuild.of(index,
                    ArtifactFreshness.of(index, store), store, ArtifactBuild.Scope.NAMED,
                    index.artifacts().stream().map(Artifact::id).toList(), false);

            int checked = 0;
            for (ArtifactBuild.Step step : plan.notBuildable()) {
                if (step.kind() == ArtifactKind.CLI_SHIM
                        || step.kind() == ArtifactKind.PROVISIONED_TREE) continue;
                assertContains(step.reason(), "skill-manager ",
                        step.id() + " names no command that rebuilds it");
                checked++;
            }
            assertTrue(checked >= 4, "the fixture holds enough kinds to mean something: " + checked);
        });

        // --------------------------------------------------- the command shell

        suite.test("a clean home builds nothing and says so", () -> {
            SkillStore store = fingerprinted();
            // The pip shim in the fixture dangles from the start, so build it
            // out of the way first — this case is about the EMPTY answer.
            Path target = store.root().resolve("cache/uv-tools/alpha/bin/dangler");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "#!/bin/sh\n");
            target.toFile().setExecutable(true);

            Result r = build(store);

            assertEquals(0, r.rc, "nothing stale is not a failure: " + r.err);
            assertContains(r.err + r.out, "nothing to build",
                    "and it is a sentence, not an empty report");
        });

        suite.test("an artifact id this home does not hold is a usage error with near-misses", () -> {
            SkillStore store = fingerprinted();

            Result r = build(store, "cli-shim:skill-script/alpha-scrip");

            assertEquals(2, r.rc, "not a silent no-op");
            assertContains(r.err, "no artifact with id", "the id is named");
            assertContains(r.err, "alpha-script", "and the one that was meant is offered");
        });

        suite.test("--dry-run changes nothing and still names what it would do", () -> {
            SkillStore store = fingerprinted();
            Path shim = store.root().resolve("bin/cli/alpha-script");
            Files.move(shim, store.root().resolve("bin/cli/.hidden"));

            Result r = build(store, "--stale", "--dry-run");

            assertEquals(0, r.rc, "dry run rc: " + r.err);
            assertContains(r.out, "would build", "it says what it would do");
            assertContains(r.out, ArtifactIds.cliShim("skill-script", "alpha-script"),
                    "naming the artifact");
            assertFalse(Files.exists(shim), "and rebuilt nothing");
            assertFalse(Files.exists(store.root().resolve("audit.log")),
                    "a dry run writes no audit line either");
        });

        // ------------------------------------------ the remedy home verify prints

        suite.test("`home verify`'s remedy NAMES the artifacts that own its findings", () -> {
            // The remedy is printed under a per-instance diagnosis, so it has
            // to be about those instances. `build --stale` beneath them is a
            // whole-home action under a per-instance finding — the exact
            // asymmetry this ticket exists to remove, restated one verb later —
            // and its exit code answers a question nobody asked.
            SkillStore store = fingerprinted();
            deleteRecursive(store.root().resolve("cache/skill-script-alpha-alpha-script"));

            Result r = verify(store);

            assertEquals(1, r.rc, "an unresolved reference still refuses");
            assertContains(r.err, "complete it with:", "and prints a remedy");
            assertContains(r.err, ArtifactIds.cliShim("skill-script", "alpha-script"),
                    "which names the artifact that owns the failing reference");
            assertTrue(!r.err.contains("build --stale"),
                    "and is NOT the whole-home command: " + r.err);
        });

        suite.test("COMPANION: an artifact id that reaches a shell is quoted", () -> {
            // `cli-shim:pip/jinja2-cli[yaml]` is a real id in a real home and
            // `[yaml]` is a glob. This line is pasted into shells and is run
            // through `sh -c` by HomeFixpointLaw, so an unquoted id is a remedy
            // that addresses a different artifact, or none.
            SkillStore store = fingerprinted();
            // The fixture's pip shim is a dangling symlink from the start.
            Result r = verify(store);

            assertEquals(1, r.rc, "the dangling pip shim refuses");
            assertContains(r.err, "'" + ArtifactIds.cliShim("pip", "alpha-pkg") + "'",
                    "the id is a single shell word: " + r.err);
        });

        suite.test("COMPANION: with nothing buildable behind them it falls back, not silent", () -> {
            // A remedy line with no command in it is the #142 class. A home
            // whose unresolved path belongs to no artifact with a producer
            // still gets the general command.
            Path root = Files.createTempDirectory("build-remedy-bare-");
            SkillStore store = new SkillStore(root.resolve("home"));
            store.init();
            Files.createDirectories(store.root().resolve("bin/cli"));
            Files.createSymbolicLink(store.root().resolve("bin/cli/ob-shim"),
                    store.root().resolve("venvs/ob/bin/ob-shim"));

            Result r = verify(store);

            assertEquals(1, r.rc, "still refuses");
            assertContains(r.err, "build --stale",
                    "and still prints a command rather than a bare sentence");
        });

        suite.test("an artifact this command never attempted does not decide its exit code", () -> {
            // The coupling created by BECOMING the remedy: something `build`
            // cannot repair, elsewhere in the home, must not make the printed
            // remedy fail after it did its job. `not-buildable` rows are named
            // loudly and leave the exit code alone.
            SkillStore store = scriptOnly(fingerprinted());
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));
            // A stale artifact with no producer, beside the one that has one.
            Files.delete(store.root().resolve(".claude/skills/alpha"));

            Result whole = build(store, "--stale");

            assertTrue(whole.out.contains("not rebuilt here"),
                    "the home holds a stale artifact build cannot produce: " + whole.out);
            assertContains(whole.out, "built ", "and one it can, which it repaired");
            assertEquals(0, whole.rc,
                    "the one it never attempted does not fail the run: " + whole.err);
        });

        // ----------------------------------------- a producer that wrote nothing

        suite.test("a producer that ran and wrote nothing is a no-op, never `built`", () -> {
            // `runCliInstall` discards InstallOutcome and emits CliInstalled
            // unconditionally, so a short-circuited backend prints
            // `✓ cli: <dep> installed`. This is the one verb whose whole thesis
            // is that a successful exit is not evidence the artifact exists, so
            // it reads the outcome. Here SkillScriptBackend skips: its
            // fingerprint matches and its declared binary is present.
            SkillStore store = scriptOnly(fingerprinted());

            Result r = build(store, "--all");

            assertEquals(0, r.rc, "a no-op over a current home is not a failure: " + r.err);
            assertContains(r.out, "no-op", "the row says what happened");
            assertTrue(!r.out.contains("1 built"),
                    "and the summary does not count it as work: " + r.out);
            assertContains(r.err, "wrote nothing", "with the count called out");
        });

        suite.test("COMPANION: a producer that DID write is still reported as built", () -> {
            // Without this, "never reports built" could pass by the outcome
            // never being read as an event at all.
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            Result r = build(store, ArtifactIds.cliShim("skill-script", "alpha-script"));

            assertEquals(0, r.rc, "rc: " + r.err);
            assertContains(r.out, "built ", "an install that ran says built");
            assertTrue(!r.out.contains("no-op"), "and is not a no-op: " + r.out);
        });

        suite.test("an unowned tree is not sent after an artifact that does not exist", () -> {
            // A refusal whose remedy has no referent is a dead end dressed as a
            // remedy. `cache/uv-tools` in the fixture is claimed by nobody —
            // its only shim reaches it through a broken link.
            SkillStore store = ArtifactsFixture.seed();
            String treeId = ArtifactIds.provisionedTree("cache", "uv-tools");
            ArtifactIndex index = ArtifactIndex.of(store);
            assertTrue(index.byId(treeId).orElseThrow().owner() == null,
                    "precondition: nothing claims this tree");

            ArtifactBuild.Plan plan = ArtifactBuild.of(index,
                    ArtifactFreshness.of(index, store), store,
                    ArtifactBuild.Scope.NAMED, List.of(treeId), false);
            ArtifactBuild.Step step = plan.steps().get(0);

            assertEquals(ArtifactBuild.Action.NOT_BUILDABLE, step.action(), "not buildable");
            assertTrue(!step.reason().contains("Build the artifact that runs out of it"),
                    "and is not sent after an artifact that does not exist: " + step.reason());
            assertContains(step.reason(), "nothing in this home claims",
                    "it names the condition instead");
        });

        suite.test("a real build writes an audit line naming the artifact", () -> {
            // #45/T44: a mutation absent from audit.log is the gap that ticket
            // was filed on, and a new verb is a new chance to reintroduce it.
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            build(store, ArtifactIds.cliShim("skill-script", "alpha-script"));

            Path audit = store.root().resolve("audit.log");
            assertTrue(Files.exists(audit), "the run is in the audit log");
            String text = Files.readString(audit);
            assertContains(text, "build", "under its own verb");
            assertContains(text, ArtifactIds.cliShim("skill-script", "alpha-script"),
                    "naming the artifact it rebuilt, not just the unit");
        });

        return suite.runAll();
    }

    // --------------------------------------------------------------- plumbing

    private static ArtifactBuild.Plan staleplan(SkillStore store) throws Exception {
        ArtifactIndex index = ArtifactIndex.of(store);
        return ArtifactBuild.of(index, ArtifactFreshness.of(index, store), store,
                ArtifactBuild.Scope.STALE, List.of(), false);
    }

    private record Result(int rc, String out, String err) {}

    /**
     * The fixture with its {@code pip} lock row removed.
     *
     * <p>The fixture's pip dep names a package that does not exist, on purpose
     * — it is there so a row with no install fingerprint is representable. A
     * case about SELECTION ({@code --all}, {@code --stale}) would otherwise
     * reach the real network through {@code uv} and fail for a reason that has
     * nothing to do with what it is asserting. Removing the row, rather than
     * stubbing the backend, keeps every remaining artifact a real one.
     */
    private static SkillStore scriptOnly(SkillStore store) throws Exception {
        CliLock lock = CliLock.load(store);
        lock.remove("pip", "alpha-pkg");
        lock.save(store);
        Files.deleteIfExists(store.root().resolve("bin/cli/dangler"));
        return store;
    }

    /** {@code home verify} against a fixture home, capturing what it printed. */
    private static Result verify(SkillStore store) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new dev.skillmanager.commands.HomeCommand.VerifyCmd())
                    .execute("--home", store.root().toString());
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    private static Result build(SkillStore store, String... args) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            BuildCommand cmd = new BuildCommand();
            cmd.injectedStore = store;
            int rc = new CommandLine(cmd).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    /** {@link ArtifactsFixture#seed} plus a real, fingerprinted skill-script install. */
    private static SkillStore fingerprinted() throws Exception {
        SkillStore store = ArtifactsFixture.seed();
        Path scripts = store.root().resolve("skills/alpha/skill-scripts");
        Files.createDirectories(scripts);
        // A script that really produces the artifact, because this suite runs
        // the real backend: a fixture whose install writes nothing would make
        // "build repaired it" unprovable.
        Files.writeString(scripts.resolve("install.sh"), """
                #!/bin/sh
                set -e
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                printf '#!/bin/sh\\necho ok\\n' > "$SKILL_MANAGER_BIN_DIR/alpha-script"
                chmod +x "$SKILL_MANAGER_BIN_DIR/alpha-script"
                """);
        scripts.resolve("install.sh").toFile().setExecutable(true);

        CliDependency dep = declaredScriptDep(store);
        var fingerprint = new InstallerRegistry().fingerprintFor(dep, store, "alpha");
        assertTrue(fingerprint.present(),
                "the fixture must produce a real digest, not a gap: " + fingerprint.gap());

        CliLock lock = CliLock.load(store);
        lock.recordInstall("skill-script", "alpha-script", "1.0.0", "skill-script:alpha-script",
                null, "alpha", fingerprint, "alpha-script");
        lock.save(store);
        return store;
    }

    private static CliDependency declaredScriptDep(SkillStore store) throws Exception {
        for (var unit : store.listInstalledUnits().units()) {
            for (CliDependency dep : unit.cliDependencies()) {
                if ("skill-script".equals(dep.backend())) return dep;
            }
        }
        throw new IllegalStateException("the fixture declares a skill-script dep");
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
