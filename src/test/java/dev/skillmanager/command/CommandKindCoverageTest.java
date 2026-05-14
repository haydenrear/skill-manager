package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.DocRepoBinder;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.LockCommand;
import dev.skillmanager.commands.RebindCommand;
import dev.skillmanager.commands.UpgradeCommand;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.lock.UnitsLock;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class CommandKindCoverageTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CommandKindCoverageTest");

        suite.test("lock live state includes skill + plugin + doc + harness", () -> {
            SkillStore store = newStore();
            installSkill(store, "alpha");
            installPlugin(store, "beta-plugin");
            installDocRepo(store, "team-prompts");
            installHarness(store, "learning-app-coordinator");

            UnitsLock live = LockCommand.readLiveState(store);
            assertEquals(4, live.units().size(), "four kinds in lock state");
            assertEquals(UnitKind.SKILL, live.get("alpha").orElseThrow().kind(), "skill row");
            assertEquals(UnitKind.PLUGIN, live.get("beta-plugin").orElseThrow().kind(), "plugin row");
            assertEquals(UnitKind.DOC, live.get("team-prompts").orElseThrow().kind(), "doc row");
            assertEquals(UnitKind.HARNESS, live.get("learning-app-coordinator").orElseThrow().kind(), "harness row");
        });

        suite.test("deps renders harness references and doc nodes", () -> {
            SkillStore store = newStore();
            installSkill(store, "planner");
            installDocRepo(store, "team-prompts");
            installHarness(store, "learning-app-coordinator");

            Result result = captureOut(() -> new CommandLine(new DepsCommand(store))
                    .execute("learning-app-coordinator"));
            assertEquals(0, result.rc, "deps rc");
            assertContains(result.out, "learning-app-coordinator (harness)", "harness rendered");
            assertContains(result.out, "planner (skill)", "skill child rendered");
            assertContains(result.out, "team-prompts (doc)", "doc child rendered");
        });

        suite.test("upgrade rejects doc and harness explicitly", () -> {
            SkillStore store = newStore();
            installDocRepo(store, "team-prompts");
            installHarness(store, "learning-app-coordinator");

            Result doc = captureErr(() -> new CommandLine(new UpgradeCommand(store))
                    .execute("team-prompts"));
            assertEquals(5, doc.rc, "doc upgrade rc");
            assertContains(doc.err, "doc units do not support `upgrade`", "doc unsupported message");

            Result harness = captureErr(() -> new CommandLine(new UpgradeCommand(store))
                    .execute("learning-app-coordinator"));
            assertEquals(5, harness.rc, "harness upgrade rc");
            assertContains(harness.err, "harness units do not support `upgrade`", "harness unsupported message");
        });

        suite.test("rebind supports doc source bindings", () -> {
            SkillStore store = newStore();
            installDocRepo(store, "team-prompts");
            Path oldTarget = Files.createTempDirectory("doc-old-target-");
            Path newTarget = Files.createTempDirectory("doc-new-target-");

            DocUnit doc = DocRepoParser.load(store.unitDir("team-prompts", UnitKind.DOC));
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    doc, oldTarget, "review-stance", ConflictPolicy.RENAME_EXISTING,
                    BindingSource.EXPLICIT, ignored -> "doc-bind-1");
            Binding original = plan.bindings().get(0);
            java.util.List<SkillEffect> effects = new java.util.ArrayList<>();
            for (Projection p : original.projections()) {
                effects.add(new SkillEffect.MaterializeProjection(p, original.conflictPolicy()));
            }
            effects.add(new SkillEffect.CreateBinding(original));
            new Executor(store, null).run(new Program<>("bind-doc-fixture", effects, receipts -> null));

            int rc = new CommandLine(new RebindCommand(store))
                    .execute("doc-bind-1", "--to", newTarget.toString());
            assertEquals(0, rc, "rebind rc");
            Binding rebound = new BindingStore(store).read("team-prompts")
                    .findById("doc-bind-1").orElseThrow();
            assertEquals(newTarget.toAbsolutePath().normalize(), rebound.targetRoot(), "target updated");
            assertTrue(Files.exists(newTarget.resolve("docs/agents/review-stance.md")),
                    "doc copied to new target");
        });

        return suite.runAll();
    }

    private static SkillStore newStore() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("kind-coverage-"));
        store.init();
        return store;
    }

    private static void installSkill(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("kind-skill-");
        var u = UnitFixtures.scaffoldSkill(tmp, name, DepSpec.empty());
        Fs.copyRecursive(u.sourcePath(), store.unitDir(name, UnitKind.SKILL));
        seedRecord(store, name, UnitKind.SKILL);
    }

    private static void installPlugin(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("kind-plugin-");
        UnitFixtures.scaffoldPlugin(tmp, name, DepSpec.empty(),
                new ContainedSkillSpec(name + "-impl", DepSpec.empty()));
        Fs.copyRecursive(tmp.resolve(name), store.unitDir(name, UnitKind.PLUGIN));
        seedRecord(store, name, UnitKind.PLUGIN);
    }

    private static void installDocRepo(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("kind-doc-");
        Path md = tmp.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), "review\n");
        Files.writeString(tmp.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n");
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.DOC));
        seedRecord(store, name, UnitKind.DOC);
    }

    private static void installHarness(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("kind-harness-");
        Files.writeString(tmp.resolve("harness.toml"),
                "[harness]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "units = [\"skill:planner\"]\n"
                        + "docs = [\"doc:team-prompts/review-stance\"]\n");
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.HARNESS));
        seedRecord(store, name, UnitKind.HARNESS);
    }

    private static void seedRecord(SkillStore store, String name, UnitKind kind) throws Exception {
        new UnitStore(store).write(new InstalledUnit(
                name, "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE,
                "fixture", null, null,
                UnitStore.nowIso(),
                List.of(), kind));
    }

    private static Result captureOut(ThrowingInt op) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            int rc = op.run();
            return new Result(rc, out.toString(), "");
        } finally {
            System.setOut(original);
        }
    }

    private static Result captureErr(ThrowingInt op) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err));
            int rc = op.run();
            return new Result(rc, "", err.toString());
        } finally {
            System.setErr(original);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String out, String err) {}
}
