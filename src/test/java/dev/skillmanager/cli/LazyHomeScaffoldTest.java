package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.launch.LaunchEnv;
import dev.skillmanager.store.HomeDescriptor;
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
 *   <li><b>It proves writers still work.</b> One case runs a writing
 *       command against a fresh decoy and asserts the home IS created — so the
 *       read-only cases cannot pass by the CLI having stopped working.</li>
 *   <li><b>It proves the declaration does not outlive the invocation.</b> The
 *       access mode is a process global, and as shipped nothing ever put it
 *       back: one embedded {@code list} left the whole JVM pinned
 *       {@link HomeScaffold.Access#READ_ONLY} and every later
 *       {@link SkillStore#init()} a silent no-op. That case reads the mode in
 *       force the instant the CLI returned — before this harness resets
 *       anything, because a probe after the cleanup would be measuring the
 *       cleanup — and then, in the same window, {@code init()}s a second decoy
 *       and counts its entries. Two independent detectors, one of them a byte
 *       counter.</li>
 * </ul>
 *
 * <h2>What else is pinned here</h2>
 *
 * <ul>
 *   <li>A null {@code ParseResult} is WRITES_HOME, the same answer an unknown
 *       command path gets. Unreachable through picocli; asserted so the one
 *       fail-closed branch in a fail-open design cannot come back.</li>
 *   <li>"Is a home" has one definition. {@link SkillStore#isHome()} and
 *       {@link dev.skillmanager.launch.LaunchEnv#looksLikeStoreRoot} are
 *       asserted to agree on a descriptor-only home and on a partial
 *       {@code installed/}-only layout — the two cases where the two former
 *       spellings disagreed.</li>
 *   <li>The refusals name the way out. {@code exec} against a non-home
 *       <em>ambient</em> home must name {@code --init} and must not blame a
 *       {@code --home} the operator never passed; {@code exec --home} against
 *       a non-home must still blame {@code --home}.</li>
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
                assertTrue(!store.isHome(), "an empty directory is not a home");
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
            assertTrue(new SkillStore(decoy).isHome(), "the home now exists");
        });

        // ------------------------------------- the mode is scoped, not sticky
        suite.test("a read-only invocation does not pin the JVM read-only", () -> {
            // Instrument floor #1: the reader can tell the two modes apart. If
            // `declared()` always answered WRITES_HOME, the assertion below
            // that it was PUT BACK to WRITES_HOME would be vacuous.
            HomeScaffold.declare(HomeScaffold.Access.READ_ONLY);
            assertEquals(HomeScaffold.Access.READ_ONLY, HomeScaffold.declared(),
                    "the mode reader distinguishes the two modes");
            HomeScaffold.reset();

            // Instrument floor #2, the one that counts bytes: `witness` is
            // init()ed after the CLI has returned and BEFORE anything resets
            // the global, so a leaked READ_ONLY turns it into a silent no-op
            // and this reads 0. That is the failure the leak actually causes.
            Path witness = freshDecoy();
            Path decoy = freshDecoy();
            Run r = runCli(decoy, () -> new SkillStore(witness).init(), "list");

            assertEquals(HomeScaffold.Access.READ_ONLY, r.declared,
                    "`list` was classified read-only");
            assertEquals(HomeScaffold.Access.WRITES_HOME, r.modeAfterRun,
                    "the CLI put back the mode it displaced — leaving it READ_ONLY "
                            + "pins every later SkillStore.init() in this JVM to a no-op");
            assertEquals(HOME_LAYOUT_ENTRIES, countEntries(witness),
                    "a direct init() after a read-only CLI run still writes a home; got "
                            + listEntries(witness));
            assertEquals(0, countEntries(decoy), "the read-only run itself wrote nothing");
        });

        suite.test("a writing invocation also puts the previous mode back", () -> {
            Path decoy = freshDecoy();
            Run r = runCli(decoy, "policy", "init");
            assertEquals(HomeScaffold.Access.WRITES_HOME, r.declared, "policy init writes");
            assertEquals(HomeScaffold.Access.WRITES_HOME, r.modeAfterRun,
                    "the mode outside an invocation is always the permissive default");
        });

        suite.test("an unparsed invocation is permissive, like an unknown path", () -> {
            // Fail-open, in the one branch that used to fail closed. Picocli
            // never hands the execution strategy a null ParseResult today, so
            // this is a statement about the design rather than a reachable
            // path: unknown means "keep the old eager behaviour", never
            // "silently starve a writer".
            assertEquals(HomeScaffold.Access.WRITES_HOME,
                    CommandHomeAccess.of((CommandLine.ParseResult) null),
                    "a null parse result is WRITES_HOME, like an unknown command path");
            assertEquals(CommandHomeAccess.of("no-such-command"),
                    CommandHomeAccess.of((CommandLine.ParseResult) null),
                    "the two unknown-input answers agree");
        });

        // -------------------------------------- one predicate for 'is a home'
        suite.test("`is a home` has one definition, not two", () -> {
            // A descriptor-only home is where the two old spellings disagreed:
            // SkillStore said "not materialized" (so reconcile was skipped)
            // while NotAHomeException.require said "is a home" (so exec
            // accepted it). They now ask LaunchEnv.looksLikeStoreRoot, once.
            Path descriptorOnly = freshDecoy();
            Files.writeString(descriptorOnly.resolve(HomeDescriptor.FILENAME), "{}");
            assertTrue(new SkillStore(descriptorOnly).isHome(),
                    "a descriptor alone makes it a home");
            assertEquals(LaunchEnv.looksLikeStoreRoot(descriptorOnly),
                    new SkillStore(descriptorOnly).isHome(),
                    "SkillStore and the refusal predicate agree about a descriptor-only home");

            // And in the other direction: a partial layout is not a home under
            // either spelling. It no longer self-heals on a read command, which
            // is intended — the first writing command completes it.
            Path partial = freshDecoy();
            Files.createDirectory(partial.resolve("installed"));
            assertTrue(!new SkillStore(partial).isHome(),
                    "installed/ without skills/ or a descriptor is a partial layout");
            assertEquals(LaunchEnv.looksLikeStoreRoot(partial),
                    new SkillStore(partial).isHome(),
                    "SkillStore and the refusal predicate agree about a partial layout");

            // Floor: the predicate is capable of saying yes.
            Path real = freshDecoy();
            HomeScaffold.declare(HomeScaffold.Access.WRITES_HOME);
            try {
                new SkillStore(real).init();
            } finally {
                HomeScaffold.reset();
            }
            assertTrue(new SkillStore(real).isHome(), "a real home is a home");
        });

        // ------------------------------------- the refusal names the way out
        suite.test("`exec` against a non-home ambient home names --init, not --home", () -> {
            Path decoy = freshDecoy();
            Run r = runCli(decoy, "exec", "--print-env");
            assertEquals(2, r.exitCode, "NotAHomeException.EXIT_CODE");
            assertContains(r.output, "is not a Skill Manager home", "the refusal fired");
            assertContains(r.output, "exec --init", "the refusal names its opt-in");
            assertContains(r.output, "$" + SkillStore.HOME_ENV,
                    "it names where the path actually came from");
            assertTrue(!r.output.contains("exec --home:"),
                    "it does not blame an option that was never passed; got ["
                            + r.output.strip() + "]");
            assertEquals(0, countEntries(decoy), "the refusal wrote nothing");
        });

        suite.test("`exec --home <not a home>` still blames --home", () -> {
            Path decoy = freshDecoy();
            Path elsewhere = freshDecoy();
            Run r = runCli(decoy, "exec", "--home", elsewhere.toString(), "--print-env");
            assertEquals(2, r.exitCode, "NotAHomeException.EXIT_CODE");
            assertContains(r.output, "exec --home:",
                    "the operator did pass --home, so that is the argument to fix");
            assertContains(r.output, "exec --init", "the opt-in is still named");
            assertEquals(0, countEntries(elsewhere), "the refusal wrote nothing");
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
        m.put("artifacts", new String[]{"artifacts"});
        m.put("artifacts list", new String[]{"artifacts", "list"});
        m.put("artifacts show", new String[]{"artifacts", "show", "no-such-artifact"});
        m.put("artifacts stale", new String[]{"artifacts", "stale"});
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
        // READ since #241's H1. The decoy probe is the point: `home describe`
        // against a home that is not there must print a refusal and create
        // NOTHING. It was WRITE, and tryReconcile ran ahead of it and wrote
        // installed-unit records into whatever home it was pointed at.
        m.put("home describe", new String[]{"home", "describe"});
        m.put("sandbox", new String[]{"sandbox"});
        m.put("sandbox status", new String[]{"sandbox", "status"});
        m.put("search", new String[]{"search", "no-such-unit"});
        m.put("show", new String[]{"show", "no-such-unit"});
        m.put("unit", new String[]{"unit"});
        // Narrowed from WRITES_HOME by HIS-14 (review of #234, HIGH-2): both
        // inspect a home and neither declares --init, and while they were
        // classified as writers `tryReconcile` ran ahead of them and mutated
        // the home they were asked to report on. Probed with no arguments,
        // which is the refusal path for each -- the point of the probe is that
        // the CLI reached the classification, not that the command succeeded.
        m.put("home verify", new String[]{"home", "verify"});
        m.put("home close-out", new String[]{"home", "close-out"});
        // HIS-13. The sharpest case for this guard in the CLI, because the
        // command's contract IS that it writes nothing: DEF-067 is that an
        // observer which repairs stops being an observer, and a `home repair`
        // classified WRITE would have `tryReconcile` scaffold the home it was
        // asked to inspect BEFORE detection ever ran -- so the bare command
        // would mutate a home for the crime of being asked about it. Probed
        // with no arguments, the refusal path, because what the probe asserts
        // is that the CLI reached the classification and the decoy stayed
        // empty. `--fix` is deliberately NOT probed here: it is a writer, it
        // is classified WRITE by the INIT_GATED row, and asserting that it
        // scaffolds nothing would be asserting the opposite of its contract.
        m.put("home repair", new String[]{"home", "repair"});
        return m;
    }

    // ------------------------------------------------------------- machinery

    /**
     * @param declared     what the CLI itself declared for this invocation,
     *                     read from {@link HomeScaffold#lastDeclared()} after
     *                     the run. The run starts from
     *                     {@link HomeScaffold.Access#WRITES_HOME}, so a
     *                     {@code READ_ONLY} here can only have come from the
     *                     execution strategy classifying this argv.
     * @param modeAfterRun the mode actually in force the instant the CLI
     *                     returned, before this harness resets anything. This
     *                     is the leak detector: the declaration is supposed to
     *                     be scoped to the invocation, so this must be the
     *                     permissive default no matter what the command was.
     */
    private record Run(int exitCode, String output, HomeScaffold.Access declared,
                       HomeScaffold.Access modeAfterRun) {}

    /** Something to measure while the process is still as the CLI left it. */
    @FunctionalInterface
    private interface AfterRun { void run() throws Exception; }

    private static Run runCli(Path decoy, String... argv) throws Exception {
        return runCli(decoy, null, argv);
    }

    /**
     * Run the CLI against {@code decoy} as the home, with the agent config
     * roots pointed at a sibling sandbox so nothing can reach the operator's
     * real {@code ~/.claude}, {@code ~/.codex} or {@code ~/.gemini}.
     *
     * <p>{@code afterRun} runs after the CLI returns and <em>before</em> this
     * harness restores anything, which is the only window in which "what did
     * the invocation leave behind in this process" can be observed at all. A
     * probe that ran after the {@code finally} would be measuring the
     * harness's own cleanup.
     */
    private static Run runCli(Path decoy, AfterRun afterRun, String... argv) throws Exception {
        Path sandbox = decoy.resolveSibling(decoy.getFileName() + "-agent-home");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        Map<String, String> otel = quietExporters();
        int rc;
        HomeScaffold.Access declared;
        HomeScaffold.Access modeAfterRun;
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
            declared = HomeScaffold.lastDeclared();
            modeAfterRun = HomeScaffold.declared();
            if (afterRun != null) afterRun.run();
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            AgentHomes.clearOverrides();
            HomeScaffold.reset();
            restore(otel);
        }
        return new Run(rc, captured.toString(StandardCharsets.UTF_8), declared, modeAfterRun);
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
