package dev.skillmanager.commands;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Operations on a Skill Manager home as a whole, as distinct from the
 * units inside it.
 */
@Command(name = "home",
        description = "Inspect, copy, describe, and set the policy of Skill Manager homes.",
        subcommands = {
                HomeCommand.CloneCmd.class,
                HomeCommand.VerifyCmd.class,
                HomeCommand.DescribeCmd.class,
                HomeCommand.PolicyCmd.class,
                HomeCommand.ShimsCmd.class,
                HomeCommand.DriftCmd.class
        })
public final class HomeCommand {

    /**
     * Produce a per-project home. The point of the copy is that
     * {@code SKILL_MANAGER_HOME} can be pointed at it and nothing reaches
     * back into the original — so the copy is not trusted, it is verified.
     */
    @Command(name = "clone",
            description = "Copy this home to a new root (skipping cache/) and verify "
                    + "that nothing in the copy still points at the original.")
    public static final class CloneCmd implements Callable<Integer> {

        @Option(names = "--to", required = true,
                description = "Destination home directory. Must not exist, or be empty.")
        Path to;

        @Option(names = "--from",
                description = "Source home. Defaults to $SKILL_MANAGER_HOME.")
        Path from;

        @Option(names = "--strict",
                description = "Also fail when authored unit content mentions the source "
                        + "home. Such references are historical records (spec .history, "
                        + "effect evidence) and rewriting them corrupts the unit, so they "
                        + "are reported but tolerated by default.")
        boolean strict;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--own-gateway",
                description = "Let the copy claim ownership of the gateway URL it inherited. "
                        + "Off by default: two homes cannot both own one port.")
        boolean ownGateway;

        @Override
        public Integer call() throws Exception {
            Path source = from != null ? from : SkillStore.defaultStore().root();
            HomeCloner.Report report = HomeCloner.cloneHome(source, to, strict);
            print(report, json);
            if (!report.clean()) return 1;
            SkillStore cloned = new SkillStore(report.dest());
            // A gateway is one process on one port, and the copy inherited
            // the source's `gateway.properties` verbatim — including its
            // ownership. Left alone, the copy would believe it runs the
            // gateway the source actually runs, then collide with it on
            // `gateway up` and kill it on `gateway down`. Attaching is the
            // only inheritance that is true of a copy.
            if (!ownGateway) {
                GatewayConfig inherited = GatewayConfig.resolve(cloned, null);
                if (inherited.owned()) {
                    GatewayConfig.attach(cloned, inherited.baseUrl().toString());
                    if (!json) {
                        Log.info("  gateway:     attached to %s (the source home owns it; "
                                + "pass --own-gateway to claim it instead)", inherited.baseUrl());
                    }
                }
            }
            // Descriptor last, so it reports the ownership decision above.
            HomeDescriptor descriptor = describe(cloned, null,
                    HomeDescriptor.read(cloned.root())
                            .map(HomeDescriptor::envContributions)
                            .orElse(Map.of()));
            descriptor.write(cloned.root());
            if (!json) Log.info("  descriptor:  %s", HomeDescriptor.file(cloned.root()));
            return 0;
        }
    }

    /**
     * Re-run only the check. Useful against a home cloned by other means
     * (an {@code rsync}, a container image build) before trusting it.
     */
    @Command(name = "verify",
            description = "Check that a home holds no absolute reference back to another home.")
    public static final class VerifyCmd implements Callable<Integer> {

        @Option(names = "--home", required = true, description = "Home to check.")
        Path home;

        @Option(names = "--against", required = true,
                description = "The home it must not reference — normally the one it was copied from.")
        Path against;

        @Option(names = "--strict", description = "Also fail on authored unit content references.")
        boolean strict;

        @Override
        public Integer call() throws Exception {
            HomeCloner.Verification result = HomeCloner.verify(against, home, strict);
            // Tolerated references are still references. Reporting only the
            // leak list would let "0 leaks" read as "nothing survives" while
            // authored content still names the other home.
            if (!result.contentReferences().isEmpty()) {
                Log.info("%d unit-content file(s) mention %s — historical records, tolerated%s",
                        result.contentReferences().size(), against,
                        strict ? " (counted as failures under --strict)" : "; re-run with --strict to fail on them");
                for (String ref : result.contentReferences()) Log.info("    %s", ref);
            }
            if (!result.danglingLinks().isEmpty()) {
                Log.warn("%d symlink(s) do not resolve in %s", result.danglingLinks().size(), home);
                for (String dangling : result.danglingLinks()) Log.warn("    %s", dangling);
            }
            if (result.clean()) {
                Log.ok("no %sreference to %s survives in %s",
                        result.contentReferences().isEmpty() ? "" : "repairable ", against, home);
                return 0;
            }
            Log.error("%d reference(s) to %s survive in %s",
                    result.leaks().size(), against, home);
            for (HomeCloner.Leak leak : result.leaks()) Log.error("  %s", leak);
            return 1;
        }
    }

    /**
     * {@code home describe} — compute (and optionally persist) the
     * {@code home.runtime.json} interop contract for a home.
     *
     * <p>The descriptor is derived from the home on every call rather than
     * read back: {@code units} is a snapshot, so a stale one is worse than
     * none. {@code --write} persists the freshly computed value.
     */
    @Command(name = "describe",
            description = "Print the home.runtime.json interop descriptor for a home: the env to "
                    + "export, the resolved CLI, the gateway, the installed-unit snapshot, and "
                    + "the home policy.")
    public static final class DescribeCmd implements Callable<Integer> {

        @Option(names = "--home",
                description = "Skill Manager home to describe. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--home-root",
                description = "The directory holding .claude/.codex/.gemini beside the store. "
                        + "Defaults to the store's parent when the store is named "
                        + ".skill-manager, else the store itself.")
        Path homeRoot;

        @Option(names = "--set-env", paramLabel = "NAME=VALUE",
                description = "Declare an extra env contribution (repeatable). Replaces the "
                        + "recorded set; omit to keep whatever the existing descriptor declared.")
        List<String> setEnv = new ArrayList<>();

        @Option(names = "--write",
                description = "Persist the descriptor to <home>/" + HomeDescriptor.FILENAME + ".")
        boolean write;

        @Option(names = "--json", description = "Emit machine-readable JSON (the descriptor itself).")
        boolean json;

        private final SkillStore injectedStore;

        public DescribeCmd() { this(null); }

        public DescribeCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = resolveStore(injectedStore, home);
            store.init();
            Map<String, String> contributions = setEnv.isEmpty()
                    ? HomeDescriptor.read(store.root())
                            .map(HomeDescriptor::envContributions)
                            .orElse(Map.of())
                    : parseEnv(setEnv);
            HomeDescriptor descriptor = describe(store, homeRoot, contributions);
            if (write) descriptor.write(store.root());
            if (json) {
                System.out.println(descriptor.toJson());
                return 0;
            }
            renderHuman(descriptor, store, write);
            return 0;
        }
    }

    /**
     * {@code home policy [live|frozen]} — read or declare whether a home
     * may be mutated in place.
     */
    @Command(name = "policy",
            description = "Show or set this home's policy: live (mutable) or frozen (sync, "
                    + "upgrade, and push-back are refused).")
    public static final class PolicyCmd implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1",
                description = "New policy: live or frozen. Omit to show the current one.")
        String policy;

        @Option(names = "--home",
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        private final SkillStore injectedStore;

        public PolicyCmd() { this(null); }

        public PolicyCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = resolveStore(injectedStore, home);
            store.init();
            if (policy == null || policy.isBlank()) {
                HomePolicy current = HomePolicy.load(store);
                System.out.println("policy: " + current.wire());
                System.out.println("file:   " + HomePolicy.file(store)
                        + (java.nio.file.Files.isRegularFile(HomePolicy.file(store))
                            ? "" : "  (absent — live by default)"));
                return 0;
            }
            HomePolicy next = HomePolicy.parse(policy, null);
            HomePolicy.write(store, next);
            if (next.frozen()) {
                Log.ok("%s is now frozen — sync, upgrade, and project sync will refuse", store.root());
            } else {
                Log.ok("%s is now live", store.root());
            }
            return 0;
        }
    }

    /**
     * {@code home shims} — write the {@code claude}/{@code codex}/{@code
     * gemini} launchers into {@code <home>/bin/launch}.
     *
     * <p>Putting that directory on {@code PATH} for a worktree is what makes
     * the correct launch environment the default: nobody exports anything, and
     * an agent needs no knowledge of the mechanism.
     */
    @Command(name = "shims",
            description = "Generate the claude/codex/gemini launchers for a home under "
                    + "bin/launch. Put that directory on PATH for a worktree and every launch "
                    + "binds to that home automatically.")
    public static final class ShimsCmd implements Callable<Integer> {

        @Option(names = "--home",
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        public ShimsCmd() { this(null); }

        public ShimsCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = resolveStore(injectedStore, home);
            store.init();
            LauncherShims.Result result = LauncherShims.write(store);
            if (json) {
                System.out.println("""
                        {"dir":"%s","shims":[%s]}"""
                        .formatted(esc(result.dir().toString()),
                                result.written().stream()
                                        .map(p -> "\"" + esc(p.toString()) + "\"")
                                        .collect(java.util.stream.Collectors.joining(","))));
                return 0;
            }
            Log.ok("wrote %d launcher(s) to %s", result.written().size(), result.dir());
            for (Path shim : result.written()) Log.info("  %s", shim);
            Log.info("  put %s first on PATH to launch against this home by default", result.dir());
            return 0;
        }
    }

    /**
     * {@code home drift} — what changed in this home, and the acknowledgement
     * that lets a launch proceed.
     *
     * <p>{@code --record} takes a fresh digest and reports the difference from the
     * last one; {@code --show} prints the pending change; {@code --ack} marks it
     * read. The gate itself lives in {@link dev.skillmanager.store.DriftGate};
     * see its javadoc for why an acknowledgement is required rather than a
     * refreshed digest being enough.
     */
    @Command(name = "drift",
            description = "Show, record, or acknowledge changes to this home's units. A launch "
                    + "refuses while a change is unacknowledged, so an agent cannot keep acting "
                    + "on a skill that moved underneath it.")
    public static final class DriftCmd implements Callable<Integer> {

        @Option(names = "--home",
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--record",
                description = "Compare the home against its recorded digest, record any change "
                        + "as pending, and refresh the digest.")
        boolean record;

        @Option(names = "--ack", description = "Mark the pending change read, clearing the gate.")
        boolean ack;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        public DriftCmd() { this(null); }

        public DriftCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = resolveStore(injectedStore, home);
            store.init();
            if (record) {
                HomeDigest baseline = HomeDigest.read(store).orElse(null);
                DriftGate recorded = DriftGate.recordSince(store, baseline, "home drift --record")
                        .orElse(null);
                if (baseline == null && recorded == null) {
                    Log.ok("recorded the first digest for %s — nothing to compare against yet",
                            store.root());
                    return 0;
                }
            }
            if (ack) {
                DriftGate acked = DriftGate.acknowledge(store).orElse(null);
                if (acked == null) {
                    Log.ok("no unread change in %s", store.root());
                    return 0;
                }
                Log.ok("acknowledged %d changed unit(s) in %s",
                        acked.report().units().size(), store.root());
                for (String line : acked.report().render()) Log.info("  %s", line);
                return 0;
            }
            DriftGate pending = DriftGate.pending(store).orElse(null);
            if (json) {
                System.out.println(pending == null
                        ? "{\"pending\":false,\"units\":[]}"
                        : "{\"pending\":true,\"operation\":\"" + esc(pending.operation())
                                + "\",\"detectedAt\":\"" + esc(pending.detectedAt())
                                + "\",\"units\":" + driftJson(pending) + "}");
                return pending == null ? 0 : DriftGate.EXIT_CODE;
            }
            if (pending == null) {
                Log.ok("no unread change in %s", store.root());
                return 0;
            }
            Log.warn("%d unit(s) changed in %s (%s) and have not been read:",
                    pending.report().units().size(), store.root(), pending.operation());
            for (String line : pending.report().render()) Log.info("  %s", line);
            Log.warn("  run `skill-manager home drift --ack` once you have taken it in");
            return DriftGate.EXIT_CODE;
        }

        private static String driftJson(DriftGate gate) {
            return gate.report().units().stream()
                    .map(unit -> "{\"unit\":\"" + esc(unit.label())
                            + "\",\"change\":\"" + unit.change().name().toLowerCase()
                            + "\",\"files\":[" + unit.allFiles().stream()
                                    .map(f -> "\"" + esc(f) + "\"")
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]}")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }

    // -------------------------------------------------------- descriptor

    /**
     * Assemble the descriptor for {@code store}.
     *
     * <p>Everything in it is read from the home, not from the ambient
     * process: the env block is derived from the home's own layout
     * ({@link HomeDescriptor#envFor}), the gateway comes from the home's
     * {@code gateway.properties}, the policy from its
     * {@code home.policy.toml}, and {@code units} from
     * {@link ListCommand#rows} so the descriptor and {@code list --json}
     * cannot disagree.
     */
    public static HomeDescriptor describe(SkillStore store, Path homeRootOverride,
                                          Map<String, String> envContributions)
            throws IOException {
        Path root = homeRootOverride != null
                ? homeRootOverride.toAbsolutePath().normalize()
                : HomeDescriptor.homeRootFor(store.root());
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        var listed = store.listInstalledUnits();
        List<HomeDescriptor.Unit> units = new ArrayList<>();
        for (ListCommand.Row row : ListCommand.rows(
                listed.units(), new UnitStore(store), new BindingStore(store))) {
            units.add(new HomeDescriptor.Unit(
                    row.name(), row.kind(), row.version(), row.source(), row.sha()));
        }
        return new HomeDescriptor(
                root,
                HomePolicy.load(store).wire(),
                HomeDescriptor.envFor(root, store.root()),
                new HomeDescriptor.Cli(HomeDescriptor.resolveCli(store.root())),
                new HomeDescriptor.Gateway(gw.baseUrl().toString(), gw.owned()),
                units,
                envContributions);
    }

    private static SkillStore resolveStore(SkillStore injected, Path home) {
        if (injected != null) return injected;
        if (home != null) return new SkillStore(home.toAbsolutePath().normalize());
        return SkillStore.defaultStore();
    }

    private static Map<String, String> parseEnv(List<String> assignments) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String assignment : assignments) {
            int eq = assignment.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(
                        "--set-env expects NAME=VALUE, got: " + assignment);
            }
            out.put(assignment.substring(0, eq), assignment.substring(eq + 1));
        }
        return out;
    }

    private static void renderHuman(HomeDescriptor d, SkillStore store, boolean wrote) {
        Log.info("  home root:   %s", d.homeRoot());
        Log.info("  policy:      %s", d.policy());
        Log.info("  cli:         %s", d.cli() == null || d.cli().skillManager() == null
                ? "(unresolved — set SKILL_MANAGER_CLI or put skill-manager on PATH)"
                : d.cli().skillManager());
        Log.info("  gateway:     %s (%s)", d.gateway().url(),
                d.gateway().owned() ? "owned" : "attached to a shared gateway");
        Log.info("  env:");
        d.env().asMap().forEach((k, v) -> Log.info("    %-18s %s", k, v));
        if (!d.envContributions().isEmpty()) {
            Log.info("  env contributions:");
            d.envContributions().forEach((k, v) -> Log.info("    %-18s %s", k, v));
        }
        Log.info("  units:       %d", d.units().size());
        if (wrote) Log.ok("wrote %s", HomeDescriptor.file(store.root()));
    }

    private static void print(HomeCloner.Report report, boolean json) {
        if (json) {
            System.out.println("""
                    {"source":"%s","dest":"%s","directories":%d,"files":%d,"symlinks":%d,\
                    "bytes":%d,"linksRelativized":%d,"stateReanchored":%d,\
                    "provisionedRewritten":%d,"leaks":%d,"contentReferences":%d,\
                    "danglingLinks":%d,"danglingReferences":%d,"clean":%b}"""
                    .formatted(esc(report.source().toString()), esc(report.dest().toString()),
                            report.directories(), report.files(), report.symlinks(),
                            report.bytes(), report.linksRelativized(), report.stateReanchored(),
                            report.provisionedRewritten(), report.leaks().size(),
                            report.contentReferences().size(), report.danglingLinks().size(),
                            report.danglingReferences().size(), report.clean()));
            return;
        }
        Log.info("  source:      %s", report.source());
        Log.info("  destination: %s", report.dest());
        Log.info("  copied:      %d dirs, %d files, %d links (%d bytes; %s skipped)",
                report.directories(), report.files(), report.symlinks(), report.bytes(),
                String.join(", ", HomeCloner.SKIPPED_DIRS.stream().sorted().toList()));
        Log.info("  re-anchored: %d links relativized, %d records, %d provisioned files",
                report.linksRelativized(), report.stateReanchored(), report.provisionedRewritten());
        if (!report.contentReferences().isEmpty()) {
            Log.info("  %d unit-content file(s) mention the source home — historical records, "
                    + "left as written", report.contentReferences().size());
        }
        int unresolved = report.danglingLinks().size() + report.danglingReferences().size();
        if (unresolved > 0) {
            // `skill-manager cli` has only read-only subcommands, so the old
            // hint sent the reader somewhere that could not fix anything.
            Log.warn("  %d reference(s) do not resolve in the copy (targets under a skipped "
                    + "directory); re-provision with `skill-manager sync --force-scripts`", unresolved);
            for (String dangling : report.danglingLinks()) Log.warn("    link   %s", dangling);
            for (String dangling : report.danglingReferences()) Log.warn("    script %s", dangling);
        }
        if (report.clean()) {
            Log.ok("cloned home to %s — no path in it resolves back to %s",
                    report.dest(), report.source());
        } else {
            Log.error("clone verification FAILED — %d path(s) still point at %s",
                    report.leaks().size(), report.source());
            for (HomeCloner.Leak leak : report.leaks()) Log.error("    %s", leak);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
