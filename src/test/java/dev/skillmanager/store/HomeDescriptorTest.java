package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.agent.ClaudeAgent;
import dev.skillmanager.agent.CodexAgent;
import dev.skillmanager.agent.GeminiAgent;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code home.runtime.json} — the interop contract a consumer reads to
 * launch an agent against a home.
 *
 * <p>Two properties matter more than the field list, and both are asserted
 * end-to-end rather than by inspecting the JSON:
 *
 * <ol>
 *   <li><b>The published env actually isolates the home.</b> Exporting the
 *       descriptor's env block has to move Claude's skills directory into
 *       the home — not to {@code <homeRoot>/.claude/.claude}, and not to
 *       the developer's real {@code ~/.claude}. Both failures are silent,
 *       and the second is the one this epic keeps finding.</li>
 *   <li><b>The descriptor survives relocation.</b> A per-project home is
 *       made by copying a home, so a descriptor that named its absolute
 *       location would describe the original from inside the copy.</li>
 * </ol>
 */
public final class HomeDescriptorTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeDescriptorTest");

        // ------------------------------------------------------------ layout

        suite.test("homeRoot is the parent of a .skill-manager store", () -> {
            Path root = Files.createTempDirectory("descriptor-root-");
            assertEquals(root, HomeDescriptor.homeRootFor(root.resolve(".skill-manager")),
                    "conventional store resolves to its parent");
            Path bare = root.resolve("odd-store");
            assertEquals(bare, HomeDescriptor.homeRootFor(bare),
                    "a store not named .skill-manager is its own root");
        });

        suite.test("CLAUDE_HOME and CLAUDE_CONFIG_DIR are one value", () -> {
            Path homeRoot = Files.createTempDirectory("descriptor-one-value-");
            HomeDescriptor.Env env = HomeDescriptor.envFor(
                    homeRoot, homeRoot.resolve(".skill-manager"));

            assertEquals(homeRoot.resolve(".claude"), env.claudeConfigDir(), "config dir");
            assertEquals(env.claudeConfigDir(), env.claudeHome(),
                    "CLAUDE_HOME carries the same value as CLAUDE_CONFIG_DIR — they cannot diverge");
            assertEquals(homeRoot.resolve(".skill-manager"), env.skillManagerHome(), "store");
            assertEquals(homeRoot.resolve(".codex"), env.codexHome(), "codex home");
            assertEquals(homeRoot.resolve(".gemini"), env.geminiHome(), "gemini home");
            assertEquals(List.of("SKILL_MANAGER_HOME", "CLAUDE_CONFIG_DIR", "CLAUDE_HOME",
                            "CODEX_HOME", "GEMINI_HOME"),
                    List.copyOf(env.asMap().keySet()), "every env key is emitted, in order");
        });

        // -------------------------------------------- the isolation property

        suite.test("exporting the descriptor env moves every agent home into the home", () -> {
            Path homeRoot = Files.createTempDirectory("descriptor-isolation-");
            HomeDescriptor.Env env = HomeDescriptor.envFor(
                    homeRoot, homeRoot.resolve(".skill-manager"));
            AgentHomes.clearOverrides();
            try {
                // Stand in for `export`: the descriptor's own values, read
                // back through the same lookup a real process would use.
                applyAsOverrides(env);

                assertEquals(homeRoot.resolve(".claude/skills"), new ClaudeAgent().skillsDir(),
                        "Claude skills land in the home, with exactly one .claude segment");
                assertEquals(homeRoot.resolve(".claude/plugins"), new ClaudeAgent().pluginsDir(),
                        "Claude plugins land in the home");
                // This used to read "Claude MCP config sits beside .claude/,
                // matching Claude's own layout". It matches Claude's layout
                // only when CLAUDE_CONFIG_DIR is UNSET; the descriptor sets it,
                // and Claude Code then reads $CLAUDE_CONFIG_DIR/.claude.json.
                // The old answer put the gateway entry in a file the launched
                // agent never opens — reported as ADDED — and left an untracked
                // <project>/.claude.json that the documented `/.claude/`
                // gitignore rule does not match.
                assertEquals(homeRoot.resolve(".claude/.claude.json"),
                        new ClaudeAgent().mcpConfigPath(),
                        "Claude MCP config moves WITH the config dir the descriptor exports");
                assertEquals(homeRoot.resolve(".codex/skills"), new CodexAgent().skillsDir(),
                        "Codex skills land in the home");
                assertEquals(homeRoot.resolve(".gemini/skills"), new GeminiAgent().skillsDir(),
                        "Gemini skills land in the home");

                String realHome = System.getProperty("user.home");
                assertFalse(new ClaudeAgent().skillsDir().startsWith(realHome),
                        "nothing resolves back into the developer's real home");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("a consumer that sets only CLAUDE_CONFIG_DIR still moves skill projection", () -> {
            // The divergence bug: CLAUDE_CONFIG_DIR is the variable the
            // Claude CLI honours, so it is the one a per-project home
            // actually sets — and skill-manager used to ignore it here and
            // symlink into ~/.claude/skills instead.
            Path configDir = Files.createTempDirectory("descriptor-config-only-")
                    .resolve("proj/.claude");
            AgentHomes.clearOverrides();
            try {
                AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, configDir);
                assertEquals(configDir.resolve("skills"), new ClaudeAgent().skillsDir(),
                        "skills follow CLAUDE_CONFIG_DIR alone");
                assertEquals(configDir, AgentHomes.claude().configDir(), "single lookup config dir");
                assertEquals(configDir.getParent(), AgentHomes.claude().root(),
                        "root is the config dir's parent");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("CLAUDE_HOME alone keeps its parent-of-.claude meaning", () -> {
            Path claudeHome = Files.createTempDirectory("descriptor-home-only-");
            AgentHomes.clearOverrides();
            try {
                AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, claudeHome);
                assertEquals(claudeHome.resolve(".claude"), AgentHomes.claude().configDir(),
                        "CLAUDE_HOME is still the parent, so .claude is appended");
                assertEquals(claudeHome, AgentHomes.claude().root(), "root is CLAUDE_HOME itself");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        // ------------------------------------------------------- relocation

        suite.test("a descriptor written into a home describes the clone after `home clone`", () -> {
            Path source = seededHome("descriptor-clone-src-");
            HomeCommand.describe(new SkillStore(source), null, Map.of("SLM_AGENT", "coder-1"))
                    .write(source);

            Path dest = Files.createTempDirectory("descriptor-clone-dst-").resolve(".skill-manager");
            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);
            assertTrue(report.clean(), "clone clean: " + report.leaks());

            HomeDescriptor copied = HomeDescriptor.read(dest).orElseThrow();
            assertEquals(dest, copied.env().skillManagerHome(),
                    "SKILL_MANAGER_HOME follows the copy");
            assertEquals(HomeDescriptor.homeRootFor(dest), copied.homeRoot(),
                    "homeRoot follows the copy");
            assertEquals(HomeDescriptor.homeRootFor(dest).resolve(".claude"),
                    copied.env().claudeConfigDir(), "CLAUDE_CONFIG_DIR follows the copy");
            assertEquals("coder-1", copied.envContributions().get("SLM_AGENT"),
                    "declared env contributions survive the copy verbatim");
            assertFalse(Files.readString(HomeDescriptor.file(dest)).contains(source.toString()),
                    "no absolute path to the source home survives in the descriptor");
            assertContains(Files.readString(HomeDescriptor.file(dest)),
                    HomePaths.TOKEN, "self-references are stored tokenized");
        });

        suite.test("a plain `cp -R` of the home yields a correct descriptor too", () -> {
            // Not everything that relocates a home is `home clone`: an rsync,
            // a container image layer, a `git worktree` bootstrap. The stored
            // form has to carry the relocation on its own, or the descriptor
            // silently keeps naming the home it was written in.
            Path source = seededHome("descriptor-cp-src-");
            HomeCommand.describe(new SkillStore(source), null, Map.of()).write(source);
            Path dest = Files.createTempDirectory("descriptor-cp-dst-").resolve(".skill-manager");
            Fs.copyRecursive(source, dest);

            HomeDescriptor copied = HomeDescriptor.read(dest).orElseThrow();
            Path expectedRoot = HomeDescriptor.homeRootFor(dest);
            assertEquals(expectedRoot, copied.homeRoot(), "homeRoot follows raw bytes");
            assertEquals(expectedRoot.resolve(".claude"), copied.env().claudeConfigDir(),
                    "the Claude config dir beside the store follows too");
            assertEquals(expectedRoot.resolve(".gemini"), copied.env().geminiHome(),
                    "and Gemini's");
            assertEquals(dest, copied.env().skillManagerHome(), "and the store itself");
        });

        suite.test("an agent home outside the home's own directory stays absolute", () -> {
            // The relative encoding is bounded to paths beside the store on
            // purpose. Walking further up would repoint an unrelated
            // directory at whatever sits at that offset from the copy — the
            // failure mode HomePaths refuses to risk. An operator who put an
            // agent home elsewhere meant elsewhere.
            Path root = seededHome("descriptor-external-");
            Path elsewhere = Files.createTempDirectory("descriptor-elsewhere-");

            HomeDescriptor d = HomeCommand.describe(new SkillStore(root), elsewhere, Map.of());
            d.write(root);

            assertContains(Files.readString(HomeDescriptor.file(root)), elsewhere.toString(),
                    "an out-of-tree home root is stored verbatim, not walked up to");
            assertEquals(elsewhere, HomeDescriptor.read(root).orElseThrow().homeRoot(),
                    "and reads back unchanged");
        });

        // ------------------------------------------------------- assembly

        suite.test("units mirror the `list --json` snapshot exactly", () -> {
            Path root = seededHome("descriptor-units-");
            SkillStore store = new SkillStore(root);

            HomeDescriptor d = HomeCommand.describe(store, null, Map.of());

            List<ListCommand.Row> rows = ListCommand.rows(
                    store.listInstalledUnits().units(),
                    new UnitStore(store), new BindingStore(store));
            assertEquals(rows.size(), d.units().size(), "same row count as list --json");
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(rows.get(i).name(), d.units().get(i).name(), "unit name " + i);
                assertEquals(rows.get(i).kind(), d.units().get(i).kind(), "unit kind " + i);
                assertEquals(rows.get(i).sha(), d.units().get(i).sha(), "unit sha " + i);
                assertEquals(rows.get(i).source(), d.units().get(i).source(), "unit source " + i);
            }
            assertEquals("alpha", d.units().get(0).name(), "the seeded unit is in the snapshot");
        });

        suite.test("describe reflects the home's policy and gateway rather than defaults", () -> {
            Path root = seededHome("descriptor-state-");
            SkillStore store = new SkillStore(root);
            HomePolicy.write(store, HomePolicy.FROZEN);
            GatewayConfig.attach(store, "http://127.0.0.1:9999");

            HomeDescriptor d = HomeCommand.describe(store, null, Map.of());

            assertEquals("frozen", d.policy(), "declared policy is reported");
            assertEquals("http://127.0.0.1:9999", d.gateway().url(), "configured gateway URL");
            assertFalse(d.gateway().owned(), "attached gateway is reported as not owned");
        });

        suite.test("cli.skillManager is resolved, and prefers the home's own shim", () -> {
            Path root = seededHome("descriptor-cli-");
            Path shim = root.resolve("bin/cli/skill-manager");
            Fs.ensureDir(shim.getParent());
            Files.writeString(shim, "#!/bin/sh\nexit 0\n");
            Fs.makeExecutable(shim);

            assertEquals(shim, HomeDescriptor.resolveCli(root),
                    "the home's own CLI wins over anything global");
            HomeDescriptor d = HomeCommand.describe(new SkillStore(root), null, Map.of());
            assertEquals(shim, d.cli().skillManager(), "and it is what the descriptor publishes");
        });

        suite.test("describe keeps recorded env contributions when none are supplied", () -> {
            Path root = seededHome("descriptor-contrib-");
            SkillStore store = new SkillStore(root);
            HomeCommand.describe(store, null, Map.of("SLM_AGENT", "reviewer-2")).write(root);

            HomeDescriptor again = HomeCommand.describe(store, null,
                    HomeDescriptor.read(root).orElseThrow().envContributions());
            assertEquals("reviewer-2", again.envContributions().get("SLM_AGENT"),
                    "a re-describe does not silently drop a consumer's declared env");
        });

        // ------------------------------------------------------------ command

        suite.test("`home describe --write --json` emits the descriptor and persists it", () -> {
            Path root = seededHome("descriptor-cmd-");
            SkillStore store = new SkillStore(root);

            PrintStream original = System.out;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int rc;
            try {
                System.setOut(new PrintStream(buf));
                rc = new CommandLine(new HomeCommand.DescribeCmd(store))
                        .execute("--write", "--json", "--set-env", "SLM_AGENT=coder-9");
            } finally {
                System.setOut(original);
            }

            String out = buf.toString();
            assertEquals(0, rc, "describe rc");
            assertContains(out, "\"SKILL_MANAGER_HOME\"", "env block emitted");
            assertContains(out, "\"CLAUDE_CONFIG_DIR\"", "operative Claude var emitted");
            assertContains(out, "\"CLAUDE_HOME\"", "legacy Claude var emitted too");
            assertContains(out, "\"GEMINI_HOME\"", "Gemini home emitted");
            assertContains(out, "\"SLM_AGENT\" : \"coder-9\"", "declared contribution emitted");
            assertTrue(Files.isRegularFile(HomeDescriptor.file(root)),
                    "--write persisted " + HomeDescriptor.FILENAME);
            assertEquals("coder-9",
                    HomeDescriptor.read(root).orElseThrow().envContributions().get("SLM_AGENT"),
                    "persisted descriptor round-trips");
        });

        suite.test("describe/drift/policy/shims refuse a path that is not a home, and build none", () -> {
            // Issue #33. All four opened with store.init(), which lays out a
            // whole home at whatever path it was given, so a mistyped --home
            // did not fail — it quietly created a second, empty home and then
            // answered questions about that one. Every answer was true of the
            // thing it had just built and false of the thing the operator
            // meant: the fail-open shape this epic keeps finding.
            //
            // Asserted on BYTES, not on the exit code alone. A command that
            // crashed after scaffolding would satisfy an exit-code test
            // perfectly while leaving the mess the fix exists to prevent.
            Path notAHome = Files.createTempDirectory("descriptor-not-a-home-");
            Files.writeString(notAHome.resolve("README.md"), "a checkout, not a home\n");

            for (String[] argv : List.of(
                    new String[]{"describe"}, new String[]{"drift"},
                    new String[]{"policy"}, new String[]{"shims"})) {
                int rc = switch (argv[0]) {
                    case "describe" -> new CommandLine(new HomeCommand.DescribeCmd())
                            .execute("--home", notAHome.toString());
                    case "drift" -> new CommandLine(new HomeCommand.DriftCmd())
                            .execute("--home", notAHome.toString());
                    case "policy" -> new CommandLine(new HomeCommand.PolicyCmd())
                            .execute("--home", notAHome.toString());
                    default -> new CommandLine(new HomeCommand.ShimsCmd())
                            .execute("--home", notAHome.toString());
                };
                assertEquals(NotAHomeException.EXIT_CODE, rc,
                        "home " + argv[0] + " refuses a path that is not a home");
            }
            assertFalse(Files.exists(notAHome.resolve("installed")),
                    "no home was laid out at the path that was refused");
            assertFalse(Files.exists(notAHome.resolve("skills")),
                    "not even skills/, which is half the home test");
            assertFalse(Files.exists(HomeDescriptor.file(notAHome)),
                    "and no descriptor was written for a directory that is not a home");
            assertFalse(Files.exists(notAHome.resolve("bin/launch")),
                    "and no launcher shims either");
            try (var entries = Files.list(notAHome)) {
                assertEquals(1, (int) entries.count(),
                        "the directory holds exactly what it held before");
            }

            // --init keeps the one gesture the old behaviour covered:
            // declaring a policy on a home as it is being created.
            int initRc = new CommandLine(new HomeCommand.PolicyCmd())
                    .execute("--home", notAHome.toString(), "--init", "frozen");
            assertEquals(0, initRc, "--init scaffolds deliberately");
            assertTrue(Files.isDirectory(notAHome.resolve("skills")),
                    "and the home really is laid out when it was asked for");
        });

        return suite.runAll();
    }

    /**
     * Apply an env block the way a launcher would, but through
     * {@link AgentHomes}'s thread-local override map — a JVM cannot mutate
     * its own {@code getenv()}, and the override is the same lookup the env
     * vars feed into.
     */
    private static void applyAsOverrides(HomeDescriptor.Env env) {
        Map<String, String> vars = env.asMap();
        for (String key : List.of(AgentHomes.CLAUDE_CONFIG_DIR, AgentHomes.CLAUDE_HOME,
                AgentHomes.CODEX_HOME, AgentHomes.GEMINI_HOME)) {
            String value = vars.get(key);
            if (value != null) AgentHomes.setOverride(key, Path.of(value));
        }
    }

    /** A home with one installed unit, one binding ledger row, and a store layout. */
    private static Path seededHome(String prefix) throws Exception {
        Path root = Files.createTempDirectory(prefix).resolve(".skill-manager");
        SkillStore store = new SkillStore(root);
        store.init();
        Path unit = store.skillDir("alpha");
        Fs.ensureDir(unit);
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: alpha\ndescription: descriptor fixture\n---\nbody\n");
        new UnitStore(store).write(new InstalledUnit(
                "alpha", "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "fixture", "abc1234def", null,
                UnitStore.nowIso(), List.of(), UnitKind.SKILL));
        return root;
    }
}
