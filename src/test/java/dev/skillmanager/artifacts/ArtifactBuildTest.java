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
