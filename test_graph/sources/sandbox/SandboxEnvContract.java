///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The oracle for issue #30: the sandbox env contract is written down in exactly
 * one place, and a node added without it FAILS.
 *
 * <h2>Why a check and not a convention</h2>
 *
 * #18's production fix made sandboxing possible. It did not make it automatic,
 * and by the time #30 was filed <b>50 of the 105</b> CLI env sites in
 * {@code test_graph/sources} passed neither the agent-home variables nor
 * {@code HOME} — so their fallback resolved to the inherited real {@code $HOME}
 * and they projected into the operator's live {@code ~/.claude},
 * {@code ~/.codex} and {@code ~/.gemini}. One of those projections turned up in
 * a live agent's available-skills list.
 *
 * <p>Fifty sites did not drift because fifty authors were careless. They drifted
 * because the contract lived in prose and in four disagreeing copies, and
 * <b>nothing enforced it</b>. A convention nobody enforces is how this happens.
 * So the contract is now a choke point ({@link SmEnv}) and this node is the
 * thing that keeps it one.
 *
 * <h2>The two invariants, and what each one alone would miss</h2>
 *
 * <ol>
 *   <li><b>One writer.</b> No file except {@code sources/lib/SmEnv.java} writes
 *       any of the six managed variables into a child environment. This catches
 *       "a new node spelled the recipe itself", which is how the four copies got
 *       out of step. It would not catch a node that sets nothing at all.</li>
 *   <li><b>One route.</b> Every file that resolves this repository's
 *       {@code skill-manager} entrypoint for execution reaches {@link SmEnv}
 *       through its own {@code //SOURCES} closure. This catches "a new node
 *       spawns the CLI and forgets the env entirely" — the shape
 *       {@code ProjectDependenciesResolved} had. It would not catch a node that
 *       routes through {@code SmEnv} <em>and</em> then overrides a variable, so
 *       it needs the first.</li>
 * </ol>
 *
 * <p>Neither invariant can see a node that spawns the CLI by a path spelling
 * nobody has used yet. That case is covered from the other side by
 * {@code home-tripwire}, which watches the four real homes for the write itself
 * rather than for the code that would make it. Two mechanisms, one rule —
 * stated here once.
 *
 * <h2>The self-test is the reason to believe the scan</h2>
 *
 * A scan that reports "no violations" is worth nothing until it has been shown
 * to report one. Epic #1 shipped three mutation proofs that were no-ops and a
 * filesystem invariant that explored 2,357 states while a record was being
 * destroyed — every one of them a zero that meant "could not look" reported as
 * "looked and found nothing".
 *
 * <p>So this node plants five synthetic nodes in a temp directory and runs the
 * SAME detector over them: one that writes the variable itself, one that spells
 * the recipe in a {@code Map.of} literal rather than a {@code put} (the shape a
 * line-at-a-time detector missed, and which was live in
 * {@code SkillDevSmoke.java}), one that spawns the CLI with no env at all, one
 * that routes through the helper correctly, and one that merely READS a variable
 * name. The first three must be flagged, the last two must not. An over-eager
 * detector and a blind one both fail here.
 *
 * <p>The real-tree counts are asserted to be non-trivial for the same reason: a
 * detector pointed at the wrong directory finds zero violations and looks
 * healthy.
 *
 * <h2>And the helper's behaviour, not just its shape</h2>
 *
 * The last group of assertions runs {@link SmEnv} and reads the environment it
 * produced: all six variables present, none of them naming the operator's home,
 * a sandbox even when no upstream published one, and — deliberately —
 * {@code HOME} still untouched unless asked. That last one is an assertion
 * because redirecting {@code HOME} across the graphs was the tempting fix and
 * the wrong one: it also relocates the jbang, uv, npm and git caches these
 * graphs depend on.
 */
public class SandboxEnvContract {

    static final NodeSpec SPEC = NodeSpec.of("sandbox.env.contract")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("sandbox", "oracle", "home")
            .timeout("120s");

    /** The variables whose value decides which home a child process writes. */
    static final List<String> MANAGED = List.of(
            "SKILL_MANAGER_HOME", "SKILL_MANAGER_INSTALL_DIR",
            "CLAUDE_HOME", "CLAUDE_CONFIG_DIR", "CODEX_HOME", "GEMINI_HOME");

    /** Tokens that make a statement a WRITE into a child environment. */
    static final List<String> WRITE_TOKENS = List.of(
            "environment()", ".put(", ".putAll(", "Map.of(", "Map.entry(");

    /** The one file allowed to contain a write, as a tree-relative path. */
    static final String HELPER_REL = "lib/SmEnv.java";

    /** The helper's file name, for matching inside a {@code //SOURCES} closure. */
    static final String HELPER_FILE = "SmEnv.java";

    /**
     * The idioms this repository uses to name its own CLI for execution.
     *
     * <p>Deliberately narrow. {@code skillsDir.resolve("skill-manager")} is a
     * store directory that happens to share the name, and
     * {@code home.resolve("bin/cli/skill-manager")} is a generated shim under
     * test rather than this repo's entrypoint — neither is a CLI invocation this
     * contract governs, and a broader pattern would flag both.
     */
    static final List<String> CLI_IDIOMS = List.of(
            "repoRoot.resolve(\"skill-manager\")",
            "repoRoot().resolve(\"skill-manager\")",
            "SmEnv.repoRoot().resolve(\"skill-manager\")",
            "skillManager()",
            "SmEnv.cli()");

    /** What a scan of one tree found. */
    record Findings(int filesScanned, int writeStatements, int cliResolvers,
                    Set<String> writers, Set<String> unrouted) {}

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path sources = SmEnv.repoRoot().resolve("test_graph/sources");

            // ---------------------------------------------------- real tree
            Findings real = scan(sources);
            boolean onlyTheHelperWrites =
                    real.writers().size() == 1 && real.writers().iterator().next().endsWith(HELPER_REL);
            boolean everyCliResolverIsRouted = real.unrouted().isEmpty();

            // A detector pointed at an empty or wrong directory finds nothing
            // and looks healthy. These are the floor the measurement stands on.
            boolean theScanReadTheTree = real.filesScanned() >= 190
                    && real.cliResolvers() >= 90
                    && real.writeStatements() >= 6;

            // ------------------------------------------- sensitivity self-test
            Path fixtures = Files.createTempDirectory("sandbox-env-contract-");
            Findings leakyPut = scan(plant(fixtures, "leaky-put", LEAKY_PUT));
            Findings leakyMap = scan(plant(fixtures, "leaky-map", LEAKY_MAP));
            Findings noEnv = scan(plant(fixtures, "no-env", NO_ENV));
            Findings tidy = scan(plant(fixtures, "tidy", TIDY));
            Findings reader = scan(plant(fixtures, "reader", READER));

            boolean aSelfSpelledWriteIsDetected = !leakyPut.writers().isEmpty();
            boolean aMapLiteralRecipeIsDetected = !leakyMap.writers().isEmpty();
            boolean aCliSpawnWithoutTheHelperIsDetected = !noEnv.unrouted().isEmpty();
            boolean aNodeUsingTheHelperIsNotFlagged =
                    tidy.writers().isEmpty() && tidy.unrouted().isEmpty()
                            && tidy.cliResolvers() == 1;
            boolean aMereReaderIsNotFlagged =
                    reader.writers().isEmpty() && reader.unrouted().isEmpty();

            // ------------------------------------------- the helper's behaviour
            Path probeHome = fixtures.resolve("probe-home");
            ProcessBuilder pb = new ProcessBuilder("true");
            String inheritedHome = pb.environment().get("HOME");
            String inheritedJavaToolOptions = pb.environment().get("JAVA_TOOL_OPTIONS");
            SmEnv.apply(ctx, pb, probeHome.toString());
            Map<String, String> childEnv = pb.environment();
            List<String> missing = new ArrayList<>();
            for (String v : MANAGED) {
                String value = childEnv.get(v);
                if (value == null || value.isBlank()) missing.add(v);
            }
            boolean allSixVariablesAreSet = missing.isEmpty();

            Path operatorHome = operatorHome();
            List<String> namingTheOperatorsHome = new ArrayList<>();
            for (String v : MANAGED) {
                if (v.equals("SKILL_MANAGER_INSTALL_DIR")) continue;   // the repo, not a home
                String value = childEnv.get(v);
                if (value != null && Path.of(value).toAbsolutePath().normalize()
                        .startsWith(operatorHome)) {
                    namingTheOperatorsHome.add(v + "=" + value);
                }
            }
            boolean noVariableNamesTheOperatorsHome = namingTheOperatorsHome.isEmpty();

            // The pre-existing spellings set the agent variables only
            // `ifPresent`, so a graph without env.prepared silently got #18's
            // state back. The fallback has to be a sandbox, not an absence.
            SmEnv.Sandbox withoutUpstream = SmEnv.sandboxOf(null, probeHome.toString());
            boolean thereIsASandboxEvenWithNoUpstream =
                    withoutUpstream.avoids(operatorHome)
                            && withoutUpstream.claudeRoot().startsWith(probeHome.toString());

            // Redirecting HOME across the graphs was the tempting fix and the
            // wrong one: it also relocates the jbang/uv/npm/git caches.
            boolean posixHomeIsLeftAloneUntilAsked =
                    java.util.Objects.equals(inheritedHome, childEnv.get("HOME"))
                            && java.util.Objects.equals(inheritedJavaToolOptions,
                                    childEnv.get("JAVA_TOOL_OPTIONS"));
            SmEnv.alsoRedirectPosixHome(pb, probeHome.toString());
            boolean posixHomeIsRedirectedWhenAsked =
                    probeHome.toString().equals(childEnv.get("HOME"))
                            && ("-Duser.home=" + probeHome).equals(childEnv.get("JAVA_TOOL_OPTIONS"));

            boolean pass = onlyTheHelperWrites && everyCliResolverIsRouted && theScanReadTheTree
                    && aSelfSpelledWriteIsDetected && aMapLiteralRecipeIsDetected
                    && aCliSpawnWithoutTheHelperIsDetected && aNodeUsingTheHelperIsNotFlagged
                    && aMereReaderIsNotFlagged
                    && allSixVariablesAreSet && noVariableNamesTheOperatorsHome
                    && thereIsASandboxEvenWithNoUpstream
                    && posixHomeIsLeftAloneUntilAsked && posixHomeIsRedirectedWhenAsked;

            return (pass
                    ? NodeResult.pass("sandbox.env.contract")
                    : NodeResult.fail("sandbox.env.contract",
                            "writers=" + real.writers()
                                    + " unrouted=" + real.unrouted()
                                    + " filesScanned=" + real.filesScanned()
                                    + " cliResolvers=" + real.cliResolvers()
                                    + " writeStatements=" + real.writeStatements()
                                    + " missingVars=" + missing
                                    + " namingOperatorHome=" + namingTheOperatorsHome))
                    .assertion("only_the_shared_helper_writes_a_managed_home_variable",
                            onlyTheHelperWrites)
                    .assertion("every_file_that_resolves_the_cli_routes_through_the_helper",
                            everyCliResolverIsRouted)
                    .assertion("the_scan_read_the_tree_it_claims_to_have_read", theScanReadTheTree)
                    .assertion("a_node_that_writes_a_managed_variable_itself_is_detected",
                            aSelfSpelledWriteIsDetected)
                    .assertion("a_node_that_spells_the_recipe_in_a_map_literal_is_detected",
                            aMapLiteralRecipeIsDetected)
                    .assertion("a_node_that_spawns_the_cli_without_the_helper_is_detected",
                            aCliSpawnWithoutTheHelperIsDetected)
                    .assertion("a_node_that_routes_through_the_helper_is_not_flagged",
                            aNodeUsingTheHelperIsNotFlagged)
                    .assertion("a_node_that_merely_reads_a_variable_name_is_not_flagged",
                            aMereReaderIsNotFlagged)
                    .assertion("the_helper_sets_all_six_managed_variables",
                            allSixVariablesAreSet)
                    .assertion("no_variable_the_helper_sets_names_the_operators_home",
                            noVariableNamesTheOperatorsHome)
                    .assertion("the_helper_still_sandboxes_with_no_upstream_context",
                            thereIsASandboxEvenWithNoUpstream)
                    .assertion("the_helper_leaves_posix_home_alone_until_asked",
                            posixHomeIsLeftAloneUntilAsked)
                    .assertion("the_helper_redirects_posix_home_when_asked",
                            posixHomeIsRedirectedWhenAsked)
                    .metric("filesScanned", real.filesScanned())
                    .metric("writeStatements", real.writeStatements())
                    .metric("cliResolvers", real.cliResolvers())
                    .metric("violations", real.writers().size() - 1 + real.unrouted().size());
        });
    }

    // ------------------------------------------------------------- the scan

    /** Scan one tree of {@code .java} files for both invariants. */
    static Findings scan(Path root) throws IOException {
        Set<String> writers = new TreeSet<>();
        Set<String> unrouted = new TreeSet<>();
        int files = 0;
        int writes = 0;
        int cliResolvers = 0;
        for (Path p : javaFiles(root)) {
            files++;
            String text = Files.readString(p);
            int here = 0;
            for (String statement : statements(text)) {
                if (isManagedWrite(statement)) here++;
            }
            if (here > 0) {
                writes += here;
                writers.add(rel(root, p));
            }
            if (resolvesTheCli(text)) {
                cliResolvers++;
                if (!reachesTheHelper(p)) unrouted.add(rel(root, p));
            }
        }
        return new Findings(files, writes, cliResolvers, writers, unrouted);
    }

    /** Every {@code .java} file at or under {@code root}, sorted. */
    static List<Path> javaFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString();
    }

    /**
     * Java source split into statements, with comments dropped.
     *
     * <p>Statement granularity rather than line granularity on purpose: the
     * spelling that a per-line detector missed, and that was live in
     * {@code SkillDevSmoke.java}, put {@code Map.of(} on one line and
     * {@code "SKILL_MANAGER_HOME", home} on the next. A fixed lookback window
     * instead produced a false positive on
     * {@code CheckoutHomeLaunchIsolated.java}, where an unrelated
     * {@code environment().put("PATH", ...)} sat six lines above a READ of
     * {@code SKILL_MANAGER_HOME}. A statement is the unit that makes both
     * correct.
     */
    static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inTextBlock = false;
        for (String raw : text.split("\n", -1)) {
            String s = raw.strip();
            // A text block is DATA, not code. This node's own synthetic
            // fixtures are text blocks containing writes, and without this it
            // would report itself as a second writer.
            int fences = s.split("\"\"\"", -1).length - 1;
            if (inTextBlock) {
                if (fences % 2 == 1) inTextBlock = false;
                continue;
            }
            if (fences % 2 == 1) { inTextBlock = true; continue; }
            if (s.contains("\"\"\"")) continue;
            if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*")) continue;
            if (s.isEmpty()) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
            if (s.endsWith(";") || s.endsWith("{") || s.endsWith("}")) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** A statement that names a managed variable AND writes it into an env. */
    static boolean isManagedWrite(String statement) {
        boolean names = false;
        for (String v : MANAGED) {
            if (statement.contains(v)) { names = true; break; }
        }
        if (!names) return false;
        for (String token : WRITE_TOKENS) {
            if (statement.contains(token)) return true;
        }
        return false;
    }

    /** Whether {@code text} names this repository's CLI for execution. */
    static boolean resolvesTheCli(String text) {
        String code = String.join("\n", statements(text));
        for (String idiom : CLI_IDIOMS) {
            if (code.contains(idiom)) return true;
        }
        return false;
    }

    /**
     * Whether {@code entry} reaches {@link SmEnv} through its jbang
     * {@code //SOURCES} closure — the same graph the compiler sees, so a file
     * that "uses the helper" in a comment does not count and a file that
     * delegates to a support class that uses it does.
     */
    static boolean reachesTheHelper(Path entry) throws IOException {
        Set<Path> seen = new LinkedHashSet<>();
        collectSources(entry, seen);
        for (Path p : seen) {
            if (p.getFileName() != null
                    && HELPER_FILE.equals(p.getFileName().toString())) return true;
        }
        return false;
    }

    private static void collectSources(Path file, Set<Path> seen) throws IOException {
        Path norm = file.toAbsolutePath().normalize();
        if (!seen.add(norm) || !Files.isRegularFile(norm)) return;
        Path base = norm.getParent();
        for (String raw : Files.readString(norm).split("\n", -1)) {
            String s = raw.strip();
            if (!s.startsWith("//SOURCES")) continue;
            String spec = s.substring("//SOURCES".length()).strip();
            if (spec.isEmpty()) continue;
            for (Path resolved : expand(base, spec)) collectSources(resolved, seen);
        }
    }

    /** Resolve one {@code //SOURCES} spec, which may end in a glob. */
    private static List<Path> expand(Path base, String spec) throws IOException {
        List<Path> out = new ArrayList<>();
        Path candidate = base.resolve(spec).normalize();
        if (!spec.contains("*")) {
            out.add(candidate);
            return out;
        }
        Path dir = candidate.getParent();
        String glob = candidate.getFileName().toString();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, glob)) {
            entries.forEach(out::add);
        }
        return out;
    }

    // ---------------------------------------------------------- the fixtures

    /**
     * Write one synthetic node into its own directory and return that dir.
     *
     * <p>A stub {@code SmEnv.java} goes in beside it when the body includes one,
     * so the {@code //SOURCES} closure the routing check walks is a real one —
     * a fixture whose include cannot resolve would be "routed" for the wrong
     * reason, or "unrouted" for it.
     */
    static Path plant(Path root, String name, String body) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SyntheticNode.java"), body);
        if (body.contains("//SOURCES " + HELPER_FILE)) {
            Files.writeString(dir.resolve(HELPER_FILE), HELPER_STUB);
        }
        return dir;
    }

    /** A helper the routing check can resolve, holding no managed write. */
    static final String HELPER_STUB =
            "final class SmEnv {\n"
                    + "    static void apply(Object ctx, Object pb, Object home) {}\n"
                    + "}\n";

    /** Violates invariant 1: spells the recipe itself, with puts. */
    static final String LEAKY_PUT = """
            ///usr/bin/env jbang "$0" "$@" ; exit $?
            public class SyntheticNode {
                public static void main(String[] args) throws Exception {
                    ProcessBuilder pb = new ProcessBuilder(repoRoot.resolve("skill-manager").toString());
                    pb.environment().put("SKILL_MANAGER_HOME", home);
                    pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
                }
            }
            """;

    /**
     * Violates invariant 1 in the spelling a per-line detector missed: the
     * variable name is on a different line from the call that writes it.
     */
    static final String LEAKY_MAP = """
            ///usr/bin/env jbang "$0" "$@" ; exit $?
            public class SyntheticNode {
                public static void main(String[] args) throws Exception {
                    java.util.Map<String, String> env = java.util.Map.of(
                            "SKILL_MANAGER_HOME", home,
                            "CLAUDE_HOME", agentHome);
                }
            }
            """;

    /** Violates invariant 2: spawns the CLI, sets nothing at all. */
    static final String NO_ENV = """
            ///usr/bin/env jbang "$0" "$@" ; exit $?
            public class SyntheticNode {
                public static void main(String[] args) throws Exception {
                    ProcessBuilder pb = new ProcessBuilder(
                            repoRoot.resolve("skill-manager").toString(), "install", "--yes");
                    pb.start();
                }
            }
            """;

    /** Satisfies both: resolves the CLI and routes through the helper. */
    static final String TIDY = """
            ///usr/bin/env jbang "$0" "$@" ; exit $?
            //SOURCES SmEnv.java
            public class SyntheticNode {
                public static void main(String[] args) throws Exception {
                    ProcessBuilder pb = new ProcessBuilder(
                            repoRoot.resolve("skill-manager").toString(), "install", "--yes");
                    SmEnv.apply(ctx, pb, home);
                }
            }
            """;

    /** Names the variables but only READS them; must not be flagged. */
    static final String READER = """
            ///usr/bin/env jbang "$0" "$@" ; exit $?
            public class SyntheticNode {
                public static void main(String[] args) throws Exception {
                    ProcessBuilder pb = new ProcessBuilder("echo");
                    pb.environment().put("PATH", somePath);

                    String smHome = envValue(out, "SKILL_MANAGER_HOME");
                    String claudeConfigDir = envValue(out, "CLAUDE_CONFIG_DIR");
                }
            }
            """;

    /**
     * The operator's home directory, {@code $HOME} first — the same precedence
     * {@code AgentHomes.userHome()} uses, and for the same reason: on macOS the
     * JVM's {@code user.home} comes from the OS and ignores {@code $HOME}, so
     * reading only the property would compare against the wrong home in exactly
     * the sandboxed run where this assertion matters most.
     */
    static Path operatorHome() {
        String fromEnv = System.getenv("HOME");
        String raw = fromEnv != null && !fromEnv.isBlank()
                ? fromEnv
                : System.getProperty("user.home");
        return Path.of(raw).toAbsolutePath().normalize();
    }
}
