package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.store.HomeScaffold;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The oracle for the eager-home-scaffold defect: a command that only reads
 * must leave a home that does not exist alone.
 *
 * <h2>What was measured</h2>
 *
 * <pre>{@code
 * $ mkdir -p decoy
 * $ SKILL_MANAGER_HOME="$PWD/decoy" skill-manager --version
 * skill-manager 0.19.2
 * $ find decoy | wc -l
 * 13
 * }</pre>
 *
 * <p>Twelve directories from {@code --version}: the command an onboarding
 * checklist tells an agent to run to <em>prove</em> a home works. Unset,
 * {@code SKILL_MANAGER_HOME} is the operator's global home. The cause was not
 * {@code --version} — the CLI's execution strategy called
 * {@code SkillStore.defaultStore().init()} on every invocation before the
 * parsed command ran, so the layout was a side effect of starting the process.
 *
 * <h2>Why this test is shaped the way it is</h2>
 *
 * <ul>
 *   <li><b>It drives the real entry point.</b> {@link SkillManagerCli#run} with
 *       the same argv a shell would pass, not {@code SkillStore.init} in
 *       isolation — the defect lived in the wiring between them, so testing
 *       either end alone would have missed it.</li>
 *   <li><b>The decoy path is resolved.</b> {@code /tmp} on macOS is a symlink
 *       to {@code /private/tmp}, and this epic has already had a check report
 *       zero hits because it counted the wrong side of one. Every count below
 *       goes through {@link Path#toRealPath}.</li>
 *   <li><b>It has a floor.</b> The first case writes a home into the decoy with
 *       the SAME counter and asserts it sees twelve entries. A "zero entries"
 *       result means nothing until the instrument has been shown to produce a
 *       non-zero one, and this epic has shipped several zeros that meant
 *       "could not look".</li>
 *   <li><b>It cannot silently stop covering a command.</b> Every read-only path
 *       in {@link CommandHomeAccess} must appear in {@link #invocations()}, and
 *       every path in {@link CliMetadata} must be classified. A new
 *       informational command therefore fails this test until someone
 *       classifies it and gives it a probe.</li>
 *   <li><b>It proves writers still work.</b> The last case runs a writing
 *       command against a fresh decoy and asserts the home IS created — so the
 *       read-only cases cannot pass by the CLI having stopped working.</li>
 * </ul>
 *
 * <p>The home root is redirected through {@link AgentHomes#setOverride}
 * because a JVM cannot mutate its own environment: without an interception
 * point, an in-process test of "what does this command write to
 * {@code $SKILL_MANAGER_HOME}" would aim at the developer's real home.
 */
public final class LazyHomeScaffoldTest {

    /** The directories {@link SkillStore#init()} lays out under a home root. */
    private static final int HOME_LAYOUT_ENTRIES = 12;

    public static int run() {
        Tests.Suite suite = Tests.suite("LazyHomeScaffoldTest");

        // ------------------------------------------------------------- floor
        suite.test("the counter sees a scaffold when one happens", () -> {
            Path decoy = freshDecoy();
            HomeScaffold.declare(HomeScaffold.Access.WRITES_HOME);
            try {
                new SkillStore(decoy).init();
            } finally {
                HomeScaffold.reset();
            }
            assertEquals(HOME_LAYOUT_ENTRIES, countEntries(decoy),
                    "a written home is twelve entries — if this ever reads 0, "
                            + "every 'clean' assertion below is vacuous");
        });

        suite.test("a read-only store leaves the decoy untouched", () -> {
            Path decoy = freshDecoy();
            HomeScaffold.declare(HomeScaffold.Access.READ_ONLY);
            try {
                SkillStore store = new SkillStore(decoy);
                store.init();
                assertTrue(!store.isMaterialized(), "an empty directory is not a home");
                assertEquals(0, store.listInstalledUnits().units().size(),
                        "reading a home that does not exist yields nothing");
            } finally {
                HomeScaffold.reset();
            }
            assertEquals(0, countEntries(decoy), "read-only init wrote nothing");
        });

        // --------------------------------------------------- classification
        suite.test("every command path is classified", () -> {
            Set<String> commands = new TreeSet<>(CliMetadata.commandPaths());
            assertTrue(commands.size() >= 50,
                    "the command catalog is populated (found " + commands.size() + ")");
            Set<String> classified = new TreeSet<>(CommandHomeAccess.classifiedPaths());
            Set<String> unclassified = new TreeSet<>(commands);
            unclassified.removeAll(classified);
            assertTrue(unclassified.isEmpty(),
                    "unclassified commands (add a row to CommandHomeAccess): " + unclassified);
            Set<String> stale = new TreeSet<>(classified);
            stale.removeAll(commands);
            assertTrue(stale.isEmpty(), "classified but no such command: " + stale);
        });

        suite.test("help and version outrank the command's own row", () -> {
            assertEquals(HomeScaffold.Access.WRITES_HOME, CommandHomeAccess.of("install"),
                    "install writes");
            assertEquals(HomeScaffold.Access.READ_ONLY, access("install", "--help"),
                    "install --help asks for text, not an install");
            assertEquals(HomeScaffold.Access.READ_ONLY, access("--version"),
                    "the reported defect");
            assertEquals(HomeScaffold.Access.READ_ONLY, access("--help"), "root help");
            assertEquals(HomeScaffold.Access.READ_ONLY, access("home", "clone", "--help"),
                    "help at a nested level");
            assertEquals(HomeScaffold.Access.WRITES_HOME, access("install", "demo"),
                    "a real install is still allowed to create the home");
            assertEquals(HomeScaffold.Access.WRITES_HOME, CommandHomeAccess.of("no-such-command"),
                    "an unknown path keeps the old eager behaviour rather than "
                            + "silently starving a writer");
        });

        suite.test("every read-only command has a probe below", () -> {
            Set<String> probed = new TreeSet<>(invocations().keySet());
            Set<String> missing = new TreeSet<>(CommandHomeAccess.readOnlyPaths());
            missing.removeAll(probed);
            assertTrue(missing.isEmpty(),
                    "read-only commands with no decoy probe: " + missing);
        });

        // ----------------------------------------------------- the oracle
        for (Map.Entry<String, String[]> probe : invocations().entrySet()) {
            String path = probe.getKey();
            String[] argv = probe.getValue();
            suite.test("`" + String.join(" ", argv) + "` scaffolds nothing", () -> {
                Path decoy = freshDecoy();
                Run r = runCli(decoy, argv);
                // The per-command floor. An empty decoy proves nothing unless
                // the CLI actually got as far as deciding what this argv was:
                // either it reached the classification point and called this
                // invocation read-only, or picocli rejected the argv and said
                // so. `deps` against an empty home exits 0 printing nothing,
                // so "produced output" is not the right floor; "was classified"
                // is, because that is the mechanism under test.
                assertTrue(r.declared == HomeScaffold.Access.READ_ONLY
                                || (r.exitCode != 0 && !r.output.isBlank()),
                        "the CLI reached this command (" + path + "): declared="
                                + r.declared + " rc=" + r.exitCode
                                + " output=[" + r.output.strip() + "]");
                assertEquals(0, countEntries(decoy),
                        "decoy stayed empty; got " + listEntries(decoy)
                                + " (rc=" + r.exitCode + ")");
            });
        }

        suite.test("--version still prints a version and exits 0", () -> {
            Path decoy = freshDecoy();
            Run r = runCli(decoy, "--version");
            assertEquals(0, r.exitCode, "version exit code");
            assertContains(r.output, "skill-manager ", "version text");
            assertEquals(0, countEntries(decoy), "decoy stayed empty");
        });

        suite.test("--help still prints usage and exits 0", () -> {
            Path decoy = freshDecoy();
            Run r = runCli(decoy, "--help");
            assertEquals(0, r.exitCode, "help exit code");
            assertContains(r.output, "Usage: skill-manager", "usage text");
            assertEquals(0, countEntries(decoy), "decoy stayed empty");
        });

        // ------------------------------------------------- writers still work
        suite.test("a writing command still creates the home on first run", () -> {
            Path decoy = freshDecoy();
            assertEquals(0, countEntries(decoy), "decoy starts empty");
            Run r = runCli(decoy, "policy", "init");
            assertEquals(0, r.exitCode, "policy init exit code");
            assertEquals(HomeScaffold.Access.WRITES_HOME, r.declared,
                    "the CLI classified this invocation as a writer");
            assertEquals(HOME_LAYOUT_ENTRIES + 1, countEntries(decoy),
                    "the full home layout plus policy.toml; got " + listEntries(decoy));
            assertTrue(Files.isRegularFile(decoy.resolve("policy.toml")),
                    "policy.toml written");
            assertTrue(new SkillStore(decoy).isMaterialized(), "the home now exists");
        });

        return suite.runAll();
    }

    /**
     * One in-process invocation per read-only command path.
     *
     * <p>Registry-backed commands ({@code search}, {@code ads list},
     * {@code registry status}) are included deliberately: they fail fast when
     * no registry is up, and "fails to reach the registry" must still not be a
     * reason to materialize a home.
     */
    private static Map<String, String[]> invocations() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("skill-manager", new String[]{});
        m.put("ads", new String[]{"ads"});
        m.put("ads list", new String[]{"ads", "list"});
        m.put("bindings", new String[]{"bindings"});
        m.put("bindings list", new String[]{"bindings", "list"});
        m.put("bindings show", new String[]{"bindings", "show", "no-such-binding"});
        m.put("cli", new String[]{"cli"});
        m.put("cli list", new String[]{"cli", "list"});
        m.put("cli show", new String[]{"cli", "show", "no-such-tool"});
        m.put("cli path", new String[]{"cli", "path"});
        m.put("deps", new String[]{"deps"});
        m.put("env", new String[]{"env"});
        m.put("gateway", new String[]{"gateway"});
        m.put("gateway status", new String[]{"gateway", "status"});
        m.put("harness", new String[]{"harness"});
        m.put("harness list", new String[]{"harness", "list"});
        m.put("harness show", new String[]{"harness", "show", "no-such-harness"});
        m.put("list", new String[]{"list"});
        m.put("lock", new String[]{"lock"});
        m.put("lock status", new String[]{"lock", "status"});
        m.put("login show", new String[]{"login", "show"});
        m.put("pm", new String[]{"pm"});
        m.put("pm list", new String[]{"pm", "list"});
        m.put("pm which", new String[]{"pm", "which", "uv"});
        m.put("policy", new String[]{"policy"});
        m.put("policy show", new String[]{"policy", "show"});
        m.put("policy path", new String[]{"policy", "path"});
        m.put("project", new String[]{"project"});
        m.put("project list", new String[]{"project", "list"});
        m.put("project show", new String[]{"project", "show", "no-such-project"});
        m.put("project profiles", new String[]{"project", "profiles"});
        m.put("project profiles list", new String[]{"project", "profiles", "list"});
        m.put("registry", new String[]{"registry"});
        m.put("registry status", new String[]{"registry", "status"});
        m.put("search", new String[]{"search", "no-such-unit"});
        m.put("show", new String[]{"show", "no-such-unit"});
        m.put("unit", new String[]{"unit"});
        return m;
    }

    // ------------------------------------------------------------- machinery

    /**
     * @param declared what the CLI itself declared for this invocation, read
     *                 back after the run. The run starts from
     *                 {@link HomeScaffold.Access#WRITES_HOME}, so a
     *                 {@code READ_ONLY} here can only have come from the
     *                 execution strategy classifying this argv.
     */
    private record Run(int exitCode, String output, HomeScaffold.Access declared) {}

    /**
     * Run the CLI against {@code decoy} as the home, with the agent config
     * roots pointed at a sibling sandbox so nothing can reach the operator's
     * real {@code ~/.claude}, {@code ~/.codex} or {@code ~/.gemini}.
     */
    private static Run runCli(Path decoy, String... argv) throws Exception {
        Path sandbox = decoy.resolveSibling(decoy.getFileName() + "-agent-home");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        Map<String, String> otel = quietExporters();
        int rc;
        HomeScaffold.Access declared;
        try {
            // Start permissive so a READ_ONLY reading afterwards can only be
            // the CLI's own decision about this argv, never leftover state.
            HomeScaffold.declare(HomeScaffold.Access.WRITES_HOME);
            AgentHomes.setOverride(SkillStore.HOME_ENV, decoy);
            AgentHomes.setOverride(AgentHomes.HOME, sandbox);
            AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, sandbox);
            AgentHomes.setOverride(AgentHomes.CLAUDE_CONFIG_DIR, sandbox.resolve(".claude"));
            AgentHomes.setOverride(AgentHomes.CODEX_HOME, sandbox.resolve(".codex"));
            AgentHomes.setOverride(AgentHomes.GEMINI_HOME, sandbox.resolve(".gemini"));
            System.setOut(sink);
            System.setErr(sink);
            rc = SkillManagerCli.run(argv);
            declared = HomeScaffold.declared();
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            AgentHomes.clearOverrides();
            HomeScaffold.reset();
            restore(otel);
        }
        return new Run(rc, captured.toString(StandardCharsets.UTF_8), declared);
    }

    /** Silence the OTLP exporters so no invocation waits on a collector. */
    private static Map<String, String> quietExporters() {
        Map<String, String> previous = new LinkedHashMap<>();
        for (String key : List.of("otel.traces.exporter", "otel.metrics.exporter",
                "otel.logs.exporter")) {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, "none");
        }
        return previous;
    }

    private static void restore(Map<String, String> properties) {
        properties.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
    }

    private static HomeScaffold.Access access(String... argv) {
        CommandLine cmd = new CommandLine(new SkillManagerCli());
        return CommandHomeAccess.of(cmd.parseArgs(argv));
    }

    /**
     * A brand-new, EMPTY decoy directory, resolved through
     * {@link Path#toRealPath}. The resolution is the point: {@code /tmp} on
     * macOS is a symlink to {@code /private/tmp}, and a check that watched the
     * wrong side of one already reported a false clean in this epic.
     */
    private static Path freshDecoy() throws Exception {
        Path decoy = Files.createTempDirectory("sm-lazy-home-decoy-").toRealPath();
        if (countEntries(decoy) != 0) {
            throw new IllegalStateException("decoy is not empty: " + decoy);
        }
        return decoy;
    }

    /**
     * Entries under {@code root}, recursively, excluding {@code root} itself.
     *
     * <p>Throws rather than returning 0 when the directory cannot be walked —
     * "could not look" must never be reported as "looked and found nothing".
     */
    private static int countEntries(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("decoy is missing or not a directory: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return (int) walk.filter(p -> !p.equals(root)).count();
        }
    }

    private static List<String> listEntries(Path root) throws Exception {
        if (!Files.isDirectory(root)) return List.of("<decoy missing: " + root + ">");
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root)).map(root::relativize).map(Path::toString)
                    .sorted().forEach(out::add);
        }
        return out;
    }
}
