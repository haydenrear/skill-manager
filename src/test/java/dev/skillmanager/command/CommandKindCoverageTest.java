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
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.cli.installer.SkillScriptBackend;
import dev.skillmanager.commands.DepsCommand;
import dev.skillmanager.commands.InstallCommand;
import dev.skillmanager.commands.LockCommand;
import dev.skillmanager.commands.RebindCommand;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.commands.UpgradeCommand;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.lock.UnitsLock;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
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

            UnitsLock live = LockCommand.readLiveState(store).lock();
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

        suite.test("sync --from doc preserves doc-repo reconciliation", () -> {
            SkillStore store = newStore();
            installDocRepo(store, "team-prompts");
            Path from = scaffoldDocRepoSource("team-prompts", "review v2\n");

            Result result = captureOut(() -> new CommandLine(new SyncCommand(store))
                    .execute("team-prompts", "--from", from.toString(), "--dry-run",
                            "--skip-mcp", "--skip-agents", "--yes"));

            assertEquals(0, result.rc, "sync doc --from rc");
            assertContains(result.out, "sync team-prompts from " + from, "from-dir effect present");
            assertContains(result.out, "doc-repo sync team-prompts", "doc sync effect preserved");
        });

        suite.test("install and sync parse --force-scripts", () -> {
            InstallCommand install = new InstallCommand();
            int installRc = new CommandLine(install)
                    .parseArgs("--force-scripts", "--dry-run", "--yes", "file:/tmp/unit")
                    .errors().size();
            assertEquals(0, installRc, "install parse errors");
            assertTrue(install.forceScripts, "install forceScripts flag");
            assertContains(new CommandLine(install).getUsageMessage(),
                    "--force-scripts", "install help lists force scripts");

            SyncCommand sync = new SyncCommand(newStore());
            int syncRc = new CommandLine(sync)
                    .parseArgs("--force-scripts", "--dry-run", "--skip-mcp", "--skip-agents")
                    .errors().size();
            assertEquals(0, syncRc, "sync parse errors");
            assertTrue(sync.forceScripts, "sync forceScripts flag");
            assertContains(new CommandLine(sync).getUsageMessage(),
                    "--force-scripts", "sync help lists force scripts");
        });

        suite.test("sync named --force-scripts does not force unrelated installed scripts", () -> {
            SkillStore store = newStore();
            Path targetSource = installSkillScriptFixture(store, "target-script-skill", "target-script-bin");
            installSkillScriptFixture(store, "other-script-skill", "other-script-bin");

            assertEquals(1, readRunCount(store, "target-script-bin"), "target initial run count");
            assertEquals(1, readRunCount(store, "other-script-bin"), "other initial run count");

            int rc = new CommandLine(new SyncCommand(store))
                    .execute("target-script-skill",
                            "--from", targetSource.toString(),
                            "--yes",
                            "--force-scripts",
                            "--skip-mcp",
                            "--skip-agents");

            assertEquals(0, rc, "sync target force rc");
            assertEquals(2, readRunCount(store, "target-script-bin"), "target reran");
            assertEquals(1, readRunCount(store, "other-script-bin"), "other did not rerun");
        });

        suite.test("sync --from harness preserves live instance reconciliation", () -> {
            SkillStore store = newStore();
            installHarness(store, "learning-app-coordinator");
            Path target = Files.createTempDirectory("kind-harness-target-");
            new BindingStore(store).write(new ProjectionLedger("learning-app-coordinator", List.of(
                    new Binding("harness:test-instance:planner",
                            "planner", UnitKind.SKILL, null, target,
                            ConflictPolicy.ERROR, BindingStore.nowIso(),
                            BindingSource.HARNESS, List.of()))));
            Path from = scaffoldHarnessSource("learning-app-coordinator");

            Result result = captureOut(() -> new CommandLine(new SyncCommand(store))
                    .execute("learning-app-coordinator", "--from", from.toString(), "--dry-run",
                            "--skip-mcp", "--skip-agents", "--yes"));

            assertEquals(0, result.rc, "sync harness --from rc");
            assertContains(result.out, "sync learning-app-coordinator from " + from, "from-dir effect present");
            assertContains(result.out, "harness sync learning-app-coordinator instance=test-instance",
                    "harness sync effect preserved");
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
        Path tmp = scaffoldDocRepoSource(name, "review\n");
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.DOC));
        seedRecord(store, name, UnitKind.DOC);
    }

    private static Path scaffoldDocRepoSource(String name, String content) throws Exception {
        Path tmp = Files.createTempDirectory("kind-doc-");
        Path md = tmp.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), content);
        Files.writeString(tmp.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n");
        return tmp;
    }

    private static void installHarness(SkillStore store, String name) throws Exception {
        Path tmp = scaffoldHarnessSource(name);
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.HARNESS));
        seedRecord(store, name, UnitKind.HARNESS);
    }

    private static Path scaffoldHarnessSource(String name) throws Exception {
        Path tmp = Files.createTempDirectory("kind-harness-");
        Files.writeString(tmp.resolve("harness.toml"),
                "[harness]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "units = [\"skill:planner\"]\n"
                        + "docs = [\"doc:team-prompts/review-stance\"]\n");
        return tmp;
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

    private static Path installSkillScriptFixture(SkillStore store, String name, String binary)
            throws Exception {
        Path source = scaffoldSkillScriptSource(name, binary);
        Fs.copyRecursive(source, store.unitDir(name, UnitKind.SKILL));
        seedRecord(store, name, UnitKind.SKILL);

        Skill skill = SkillParser.load(store.skillDir(name));
        CliDependency dep = skill.cliDependencies().get(0);
        new InstallerRegistry().installOne(dep, store, name);

        CliLock lock = CliLock.load(store);
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        lock.recordInstall(dep.backend(), req.tool(), req.version(),
                dep.spec(), null, name,
                SkillScriptBackend.fingerprintFor(store, name, dep));
        lock.save(store);
        return source;
    }

    private static Path scaffoldSkillScriptSource(String name, String binary) throws Exception {
        Path dir = Files.createTempDirectory("kind-script-skill-").resolve(name);
        Files.createDirectories(dir.resolve("skill-scripts"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: script fixture
                ---
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"

                [[cli_dependencies]]
                spec = "skill-script:%s"
                on_path = "__zzz_nope_%s"

                [cli_dependencies.install.any]
                script = "install.sh"
                binary = "%s"
                """.formatted(name, binary, binary, binary));
        Files.writeString(dir.resolve("skill-scripts/install.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                count="$SKILL_MANAGER_BIN_DIR/%s.count"
                n=0
                if [[ -f "$count" ]]; then
                  n="$(cat "$count")"
                fi
                n=$((n + 1))
                printf '%%s\\n' "$n" > "$count"
                touch "$SKILL_MANAGER_BIN_DIR/%s"
                chmod +x "$SKILL_MANAGER_BIN_DIR/%s"
                echo "%s run $n"
                """.formatted(binary, binary, binary, binary));
        return dir;
    }

    private static int readRunCount(SkillStore store, String binary) throws Exception {
        Path count = store.cliBinDir().resolve(binary + ".count");
        if (!Files.isRegularFile(count)) return 0;
        return Integer.parseInt(Files.readString(count).trim());
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
