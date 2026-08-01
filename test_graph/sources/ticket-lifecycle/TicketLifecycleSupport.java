//SOURCES ../lib/SmEnv.java
//SOURCES ../home-sync/HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared machinery for the {@code ticket-lifecycle} graph: the whole
 * per-checkout-home ticket workflow, driven end to end with the model replaced
 * by scripted CLI calls.
 *
 * <h2>What this graph drives that no other one does</h2>
 *
 * <p>{@code home-clone} proves a home is a pure function of its root,
 * {@code home-sync} proves all four directions between the three tiers, and
 * {@code checkout-home} proves a provisioned checkout is isolated. None of them
 * provisions a worktree through {@code new-change.sh}, edits a unit the way an
 * agent would, and tears it down through {@code close-change.sh} — and both P0s
 * of epic #2 lived in exactly that composition, because two repositories'
 * individually correct changes composed into a hang.
 *
 * <h2>The oracles are borrowed, not restated</h2>
 *
 * <p>{@link HomeSyncSupport} already owns "did these bytes move"
 * ({@code difference}, {@code entryDigests}), "what did the report say"
 * ({@code json}, {@code status}, {@code blocker}) and the sandboxed CLI
 * invocation ({@code sm}). Re-implementing any of them here would produce a
 * second thing to keep in step — issue #24's recurring shape — and would break
 * {@code home.sync.sensitive}'s claim to be the reason to believe them, since
 * that node plants defects against those functions and not against copies of
 * them. So this class adds only what is genuinely new: locating and running the
 * {@code git-integration-repo} scripts, and reading the home lock.
 *
 * <h2>Where the scripts come from</h2>
 *
 * <p>{@code new-change.sh}, {@code close-change.sh} and {@code bootstrap-home.sh}
 * live in the {@code git-integration-repo} skill, which is a different
 * repository from this one. {@link #scripts} resolves them and says how — and
 * when it cannot, the fixture node FAILS with the environment variable that
 * fixes it rather than skipping. A graph that skips when its subject is absent
 * reports green on a machine where it measured nothing, and this issue exists
 * because that has already happened twice in this epic.
 */
final class TicketLifecycleSupport {

    private TicketLifecycleSupport() {}

    /** Escape hatch for a checkout laid out differently from this machine's. */
    static final String SCRIPTS_ENV = "TICKET_LIFECYCLE_SCRIPTS";

    /** The three units: one shared between both tickets, one per ticket. */
    static final String SHARED = "tl-shared";
    static final String UNIT_A = "tl-x";
    static final String UNIT_B = "tl-y";

    /**
     * What each simulated agent appends. The two shared-unit lines differ,
     * because identical edits on both sides reconcile silently and a conflict
     * fixture that quietly stopped conflicting would take every downstream
     * assertion with it while still passing them.
     */
    static final String A_SHARED = "TICKET-A improved the shared skill\n";
    static final String B_SHARED = "TICKET-B improved the shared skill, differently\n";
    static final String A_ONLY = "a page only ticket A wrote\n";
    static final String B_ONLY = "a page only ticket B wrote\n";

    static final String TICKET_A = "TICKET-A";
    static final String TICKET_B = "TICKET-B";
    static final String TICKET_C = "TICKET-C";

    // ------------------------------------------------------ script location

    /** Where the {@code git-integration-repo} scripts were found, and how. */
    record Scripts(Path dir, String how) {
        boolean found() { return dir != null; }

        Path of(String name) { return dir.resolve(name); }
    }

    /**
     * Locate {@code git-integration-repo/scripts}.
     *
     * <p>Four routes, in decreasing order of "this machine was told" over "this
     * machine was guessed at":
     *
     * <ol>
     *   <li>{@code $TICKET_LIFECYCLE_SCRIPTS}.</li>
     *   <li>Up from the repository's <b>git common directory</b> to the nearest
     *       {@code integration.toml}. The common dir rather than the working
     *       tree, because this repository is normally worked on from a linked
     *       worktree created OUTSIDE the integration repo — deliberately, so the
     *       parent's tracked tree stays clean — and from there no ancestor of
     *       the working tree holds {@code integration.toml} at all.</li>
     *   <li>Up from the working tree, for a plain constituent checkout.</li>
     *   <li>A home that has the skill installed, for a machine where the
     *       integration repo is not present but the skill is.</li>
     * </ol>
     */
    static Scripts scripts(NodeContext ctx) {
        String declared = System.getenv(SCRIPTS_ENV);
        if (declared != null && !declared.isBlank()) {
            Path dir = Path.of(declared).toAbsolutePath().normalize();
            if (isScriptsDir(dir)) return new Scripts(dir, "$" + SCRIPTS_ENV);
            return new Scripts(null, "$" + SCRIPTS_ENV + " names " + dir
                    + ", which has no new-change.sh/close-change.sh/bootstrap-home.sh");
        }

        Path repo = SmEnv.repoRoot();
        HomeSyncSupport.Capture common = HomeSyncSupport.git(repo,
                "rev-parse", "--path-format=absolute", "--git-common-dir");
        if (common.ok() && !common.trimmed().isEmpty()) {
            Scripts found = fromIntegrationAbove(Path.of(common.trimmed()),
                    "the integration repo above this checkout's git common dir");
            if (found.found()) return found;
        }
        Scripts found = fromIntegrationAbove(repo, "the integration repo above this checkout");
        if (found.found()) return found;

        for (String homeRaw : List.of(
                String.valueOf(ctx == null ? "" : ctx.get("env.prepared", "home").orElse("")),
                System.getProperty("user.home", "") + "/.skill-manager")) {
            if (homeRaw == null || homeRaw.isBlank()) continue;
            Path candidate = Path.of(homeRaw).resolve("skills")
                    .resolve("git-integration-repo").resolve("scripts");
            if (isScriptsDir(candidate)) {
                return new Scripts(candidate, "the git-integration-repo skill installed in "
                        + homeRaw);
            }
        }
        return new Scripts(null, "no integration.toml above " + repo + " (or its git common dir)"
                + " and no git-integration-repo skill in any home on this machine");
    }

    private static Scripts fromIntegrationAbove(Path start, String how) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("integration.toml"))) {
                Path candidate = dir.resolve("constituents")
                        .resolve("git-integration-repo").resolve("scripts");
                if (isScriptsDir(candidate)) return new Scripts(candidate, how + " (" + dir + ")");
            }
            dir = dir.getParent();
        }
        return new Scripts(null, how + ": none found");
    }

    private static boolean isScriptsDir(Path dir) {
        return Files.isRegularFile(dir.resolve("new-change.sh"))
                && Files.isRegularFile(dir.resolve("close-change.sh"))
                && Files.isRegularFile(dir.resolve("bootstrap-home.sh"));
    }

    // ------------------------------------------------------------- processes

    /**
     * Run one of the integration scripts, in {@code cwd}, with the same
     * sandboxed environment every skill-manager child in this graph gets.
     *
     * <p>Two additions over {@link HomeSyncSupport#sm}, both load-bearing:
     *
     * <ul>
     *   <li>{@code SKILL_MANAGER_CLI} names this checkout's launcher. Without
     *       it {@code bootstrap-home.sh}'s {@code pick_cli} prefers PATH, and
     *       PATH on a developer machine is the released build — measured here:
     *       {@code new-change.sh} then exits 3 with "no skill-manager CLI with a
     *       `home` subcommand was found", which is a true statement about the
     *       machine and a useless one about the code under test.</li>
     *   <li>{@code HOME} is redirected to the run's sandbox, so
     *       {@code bootstrap-home.sh}'s {@code GLOBAL_HOME=$HOME/.skill-manager}
     *       — the home it refuses to write, and the one this graph asserts is
     *       never written — is a path inside the sandbox rather than the
     *       operator's. That makes the central invariant checkable from two
     *       sides: the sandbox path must never come into existence, and the
     *       operator's real homes must not move.</li>
     * </ul>
     *
     * <p>{@code SKILL_MANAGER_HOME} is pointed at the ambient home for the same
     * reason {@code home-sync} does it: every skill-manager command reconciles
     * against {@code $SKILL_MANAGER_HOME} before doing anything else, and that
     * pass writes. Aiming it at a home that is neither end of any reconcile is
     * what keeps the assertions about the tiers under test.
     */
    static ProcessRecord script(NodeContext ctx, String label, Path cwd, Path script,
                                String ambientHome, String... args) {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        SmEnv.apply(ctx, pb, ambientHome);
        sandboxRoot(ctx).ifPresent(root -> SmEnv.alsoRedirectPosixHome(pb, root));
        pb.environment().put("SKILL_MANAGER_CLI", SmEnv.cli().toString());
        return Procs.run(ctx, label, pb);
    }

    /** Start a script without waiting for it — the concurrent close-out pair. */
    static Process spawnScript(NodeContext ctx, Path logFile, Path cwd, Path script,
                               String ambientHome, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
        SmEnv.apply(ctx, pb, ambientHome);
        sandboxRoot(ctx).ifPresent(root -> SmEnv.alsoRedirectPosixHome(pb, root));
        pb.environment().put("SKILL_MANAGER_CLI", SmEnv.cli().toString());
        Files.createDirectories(logFile.getParent());
        pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
        return pb.start();
    }

    /** {@link HomeSyncSupport#sm}, re-exported so nodes have one import. */
    static ProcessRecord sm(NodeContext ctx, String label, String home, String... args) {
        return HomeSyncSupport.sm(ctx, label, home, args);
    }

    /** The run's sandbox root — the {@code $HOME} every child in this graph gets. */
    static java.util.Optional<String> sandboxRoot(NodeContext ctx) {
        return ctx == null ? java.util.Optional.empty() : ctx.get("env.prepared", "home");
    }

    /**
     * Run a plain command with the graph's sandbox environment, capturing
     * stdout+stderr into the node's log.
     *
     * <p>Used for {@code python3} probes and for the shims, neither of which is
     * skill-manager but both of which must not be allowed to resolve the
     * operator's agent homes.
     */
    static ProcessRecord plain(NodeContext ctx, String label, Path cwd, String ambientHome,
                               List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) pb.directory(cwd.toFile());
        SmEnv.apply(ctx, pb, ambientHome);
        sandboxRoot(ctx).ifPresent(root -> SmEnv.alsoRedirectPosixHome(pb, root));
        pinCli(pb, command);
        return Procs.run(ctx, label, pb);
    }

    /** Start a command without waiting for it, for the concurrent experiments. */
    static Process spawn(NodeContext ctx, Path logFile, String ambientHome, List<String> command)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(ctx, pb, ambientHome);
        sandboxRoot(ctx).ifPresent(root -> SmEnv.alsoRedirectPosixHome(pb, root));
        pinCli(pb, command);
        Files.createDirectories(logFile.getParent());
        pb.redirectErrorStream(true).redirectOutput(logFile.toFile());
        return pb.start();
    }

    /**
     * Name this checkout's launcher in {@code SKILL_MANAGER_CLI} — unless the
     * command IS a home's own pin, in which case scrub the variable instead.
     *
     * <p>This is {@code close-change.sh}'s {@code run_cli} rule, restated for
     * the same measured reason. Since skill-manager #61 the pin at
     * {@code <home>/bin/cli/skill-manager} resolves its own target as
     * {@code cli="${SKILL_MANAGER_CLI:-<absolute path>}"}, so naming the pin in
     * that variable makes it exec ITSELF, forever: 7:03 of CPU over 13:06 of
     * wall clock from one teardown, silent throughout. A node that spawns a
     * pinned CLI with an inherited value set would reproduce it, and a hang is
     * not a failure — on a graph run it is indistinguishable from slow work.
     */
    private static void pinCli(ProcessBuilder pb, List<String> command) {
        String head = command.isEmpty() ? "" : command.get(0);
        if (head.endsWith("/bin/cli/skill-manager")) {
            pb.environment().remove("SKILL_MANAGER_CLI");
            return;
        }
        pb.environment().put("SKILL_MANAGER_CLI", SmEnv.cli().toString());
    }

    /** The last JSON object line of an arbitrary log file. */
    static Map<String, Object> jsonOf(Path logFile) {
        Object parsed = HomeSyncSupport.MiniJson.parse(
                HomeSyncSupport.jsonLine(HomeSyncSupport.read(logFile)));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        return map;
    }

    // ------------------------------------------------------------- the lock

    /** The lock file {@code HomeLock} uses for one home. */
    static Path lockFile(Path home) {
        return home.resolve(".materialization").resolve(".home.lock");
    }

    static Path lockProbe() {
        return SmEnv.repoRoot()
                .resolve("test_graph").resolve("sources").resolve("ticket-lifecycle")
                .resolve("lockprobe.py");
    }

    /** One {@code F_GETLK} sample: milliseconds since the sampler started, and the holder. */
    record Sample(long millis, long holder) {}

    static List<Sample> readSamples(Path file) {
        List<Sample> out = new ArrayList<>();
        for (String line : HomeSyncSupport.read(file).split("\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 2) continue;
            try {
                out.add(new Sample(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
            } catch (NumberFormatException ignored) {
                // a partially written line at the tail is not a finding
            }
        }
        return out;
    }

    /**
     * The interval each pid was observed holding the lock, in sample order.
     *
     * <p>Deliberately keyed on the holder pid rather than on "held / free".
     * A held/free timeline cannot tell one long critical section from two
     * adjacent ones, and "two adjacent ones" is precisely the observation this
     * graph needs to make. {@code F_GETLK} hands the holder's pid over, so the
     * attribution is the kernel's rather than the test's.
     */
    record Held(long pid, long firstMillis, long lastMillis) {
        long durationMillis() { return lastMillis - firstMillis; }
    }

    /**
     * Maximal held intervals, one per contiguous run of samples naming the same
     * pid. Sentinels ({@code 0} free, {@code -1} absent, {@code -2} unreadable)
     * are not holders and never produce an interval.
     */
    static List<Held> heldIntervals(List<Sample> samples, Set<Long> ignoredPids) {
        List<Held> out = new ArrayList<>();
        long current = 0;
        long from = 0;
        long to = 0;
        for (Sample s : samples) {
            long pid = s.holder() > 0 && !ignoredPids.contains(s.holder()) ? s.holder() : 0;
            if (pid == current) {
                if (pid != 0) to = s.millis();
                continue;
            }
            if (current != 0) out.add(new Held(current, from, to));
            current = pid;
            from = s.millis();
            to = s.millis();
        }
        if (current != 0) out.add(new Held(current, from, to));
        return out;
    }

    /** True when no two intervals overlap in time. */
    static boolean disjoint(List<Held> intervals) {
        List<Held> sorted = new ArrayList<>(intervals);
        sorted.sort((a, b) -> Long.compare(a.firstMillis(), b.firstMillis()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).firstMillis() < sorted.get(i - 1).lastMillis()) return false;
        }
        return true;
    }

    /** Distinct pids observed holding, in first-seen order. */
    static List<Long> holders(List<Held> intervals) {
        Set<Long> seen = new LinkedHashSet<>();
        for (Held h : intervals) seen.add(h.pid());
        return new ArrayList<>(seen);
    }

    /**
     * The serialization verdict for a pair of concurrent writers.
     *
     * <p>This is the whole oracle, in one function, so that the negative
     * control in {@code ticket.lifecycle.concurrent.close.out} is answered by
     * the SAME code that answers the real measurement. An oracle re-spelled for
     * its own sensitivity test proves nothing about the spelling that matters.
     */
    record Verdict(boolean serialised, int distinctHolders, boolean disjoint,
                   long heldSamples, String detail) {}

    static Verdict verdict(List<Sample> samples, Set<Long> ignoredPids) {
        List<Held> intervals = heldIntervals(samples, ignoredPids);
        List<Long> pids = holders(intervals);
        long heldSamples = samples.stream()
                .filter(s -> s.holder() > 0 && !ignoredPids.contains(s.holder())).count();
        boolean isDisjoint = disjoint(intervals);
        boolean serialised = pids.size() == 2 && isDisjoint && heldSamples > 0;
        return new Verdict(serialised, pids.size(), isDisjoint, heldSamples,
                "holders=" + pids + " intervals=" + intervals + " heldSamples=" + heldSamples);
    }

    // ------------------------------------------------------------- fixtures

    /** Inode of a path, or {@code -1}. Two homes must never share one. */
    static long inode(Path path) {
        try {
            Object key = Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
            return key instanceof Number n ? n.longValue() : -1;
        } catch (IOException | UnsupportedOperationException e) {
            return -1;
        }
    }

    /** What a reference walk found, and how much of the tree it actually read. */
    record Scan(List<String> hits, int entriesRead) {}

    /**
     * Every path under {@code root} whose bytes — or symlink target — name
     * {@code needle}, together with the number of entries the walk read.
     *
     * <p>The "no path resolving back to the project or root home" claim, made
     * against the tree rather than against a report. {@code home verify}'s own
     * output would be a second thing this graph was trusting, and #133 is
     * actively changing what its default mode reports and fails on; walking the
     * tree answers the same question the same way before and after that lands.
     *
     * <p>{@code entriesRead} is carried out of the walk rather than left
     * implicit because a walk that reached nothing reports "no references"
     * exactly as loudly as a clean home does. That zero has already been
     * mistaken for a measurement four times in this epic, so the count is
     * asserted against a floor and reported as a metric either way.
     */
    static Scan filesNaming(Path root, String needle, int limit) {
        List<String> out = new ArrayList<>();
        int read = 0;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return new Scan(out, 0);
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (out.size() >= limit) break;
                if (Files.isSymbolicLink(p)) {
                    read++;
                    if (Files.readSymbolicLink(p).toString().contains(needle)) {
                        out.add("link " + root.relativize(p));
                    }
                    continue;
                }
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.size(p) > 2_000_000) continue;
                read++;
                if (Files.readString(p).contains(needle)) out.add("file " + root.relativize(p));
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable entry is not evidence of a leak; the walk continues
        }
        return new Scan(out, read);
    }

    /** Unit directory inside a home. */
    static Path unitDir(Path home, String unit) {
        return home.resolve("skills").resolve(unit);
    }

    /** A minimal installable skill unit as a source directory. */
    static void mkSource(Path parent, String name, String body) throws IOException {
        Path dir = parent.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: ticket-lifecycle graph fixture
                ---
                %s
                """.formatted(name, body));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "ticket-lifecycle graph fixture"
                """.formatted(name));
    }

    /** The path {@code new-change.sh} puts a ticket worktree at. */
    static Path worktreeFor(Path checkout, String ticket) {
        return checkout.getParent().resolve(checkout.getFileName() + "-" + ticket);
    }

    static Path homeOf(Path worktree) {
        return worktree.resolve(".skill-manager");
    }

    /** Digest map of a whole home, for the "wrote nothing" comparisons. */
    static LinkedHashMap<String, String> digests(Path root) throws IOException {
        return HomeSyncSupport.entryDigests(root);
    }
}
