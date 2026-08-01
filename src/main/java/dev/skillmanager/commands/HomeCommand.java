package dev.skillmanager.commands;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.policy.FrozenHomeException;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.HomeCloseOut;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.HomeSync;
import dev.skillmanager.store.NotAHomeException;
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
                HomeCommand.DriftCmd.class,
                HomeCommand.SyncCmd.class,
                HomeCommand.CloseOutCmd.class
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
            // Write down what this copy started from, per unit, while that is
            // still knowable. It is the only witness to the two homes' common
            // ancestor, and without it the first `home sync` back into the
            // original can only report the whole unit as conflicted — even when
            // one side never moved. That covers units with no record AND units
            // whose inherited record describes content this copy does not hold,
            // which is what the source home editing a unit in place leaves
            // behind. See ChildHomeMaterializer#recordCloneBaselines.
            List<ChildHomeMaterializer.UnitRef> recorded =
                    ChildHomeMaterializer.recordCloneBaselines(cloned);
            if (!json && !recorded.isEmpty()) {
                Log.info("  baseline:    recorded for %d unit(s), so edits made here can be "
                        + "merged back with `skill-manager home sync`", recorded.size());
            }
            if (!json) {
                // Written by HomeCloner.rebaselineDrift, reported here because
                // it is the difference an operator notices: before it, the copy
                // inherited the source's baseline and its first launch could be
                // refused over a change made in another home.
                Log.info("  drift:       baselined against this copy's own content (%d unit(s)); "
                                + "a clone is not drifted",
                        HomeDigest.read(cloned).map(d -> d.units().size()).orElse(0));
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
     *
     * <h2>It refuses a path that is not a home, and that is the whole point</h2>
     *
     * <p>This command is the ORACLE the onboarding acceptance criterion rests
     * on — "the copy holds no reference back" is what a person runs it to
     * learn. And {@code home verify --home <a path that does not exist>} used
     * to print {@code ✓ no reference to … survives in …} and exit 0, because
     * {@link HomeCloner#verify} walks the destination and a destination with no
     * files contributes no leaks. Zero leaks, exit 0, ✓.
     *
     * <p>That is {@link dev.skillmanager.store.NotAHomeException}'s exact
     * shape — a zero that means "could not look" reported as "looked and found
     * nothing" — in a command #33 did not cover, and it is worse here than in
     * {@code close-out} because nothing else in the onboarding flow asks the
     * question again. Both sides are checked: {@code --against} too, since a
     * mistyped source is a verification against a home that was never the
     * origin, which also finds nothing and also says ✓.
     *
     * <h2>The two failure classes are reported apart, loudest last</h2>
     *
     * <p>Measured on a constituent checkout: 169 findings, of which 163 were
     * append-only history files that merely quote a path and 6 were live
     * symlinks resolving into the operator's global home. The old report
     * printed the 163 first, in full, then the 6 in one alphabetically sorted
     * list with the 163 interleaved. Both numbers were correct and the reader
     * came away with the wrong one, which is the same defect as reporting
     * nothing: an instrument whose signal is 96% noise is not read.
     *
     * <p>So: isolation failures print last and alone, the authored mentions
     * are summarised rather than enumerated, and the count line names both
     * kinds separately. {@code --strict} keeps its meaning — <em>also</em>
     * fail on the mentions — and changes which of the two lists is fatal,
     * never which of them is shown. Issue #133.
     */
    @Command(name = "verify",
            description = "Check that a home holds no absolute reference back to another home.")
    public static final class VerifyCmd implements Callable<Integer> {

        /**
         * How many tolerated mentions to name before summarising. Enough to
         * show what they look like, few enough that they cannot bury the
         * findings below them.
         */
        private static final int TOLERATED_SAMPLE = 3;

        @Option(names = "--home", required = true, description = "Home to check.")
        Path home;

        @Option(names = "--against", required = true,
                description = "The home it must not reference — normally the one it was copied from.")
        Path against;

        @Option(names = "--strict", description = "Also fail on authored unit content references.")
        boolean strict;

        @Override
        public Integer call() throws Exception {
            try {
                NotAHomeException.require(home, "home verify --home");
                NotAHomeException.require(against, "home verify --against");
            } catch (NotAHomeException notAHome) {
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
            HomeCloner.Verification result = HomeCloner.verify(against, home, strict);
            // Tolerated references are still references. Reporting only the
            // leak list would let "0 leaks" read as "nothing survives" while
            // authored content still names the other home. Summarised, not
            // enumerated: 163 lines of history file is how the six that matter
            // went unread.
            List<String> mentions = result.contentReferences();
            if (!mentions.isEmpty()) {
                Log.info("%d unit-content file(s) mention %s — historical records, %s",
                        mentions.size(), against,
                        strict ? "counted as failures under --strict"
                                : "tolerated; re-run with --strict to fail on them");
                for (String ref : mentions.subList(0, Math.min(TOLERATED_SAMPLE, mentions.size()))) {
                    Log.info("    %s", ref);
                }
                if (mentions.size() > TOLERATED_SAMPLE) {
                    Log.info("    … %d more", mentions.size() - TOLERATED_SAMPLE);
                }
            }
            // Provisioning that never completed. A message printed once by the
            // clone was not enough: nobody ran the remedy, and nothing asked
            // again. This is the command that asks again — issue #133 item 2.
            List<String> unresolved = result.unresolved();
            if (!unresolved.isEmpty()) {
                Log.error("%d reference(s) in %s do not resolve — provisioning was never "
                                + "completed, so the tools they name will fail at exec time",
                        unresolved.size(), home);
                for (String ref : unresolved) Log.error("    %s", ref);
                Log.error("  complete it with `skill-manager sync --force-scripts` "
                        + "(SKILL_MANAGER_HOME=%s), then re-run this check", home);
            }
            // Last, because it is the verdict, and because a terminal keeps
            // the tail. Never gated on --strict: a path that RESOLVES into
            // another home is not a historical record under any reading.
            List<HomeCloner.Leak> isolation = result.isolationFailures();
            int tolerated = result.toleratedFailures().size();
            if (!isolation.isEmpty()) {
                Log.error("%d path(s) in %s resolve into another Skill Manager home%s",
                        isolation.size(), home,
                        tolerated == 0 ? ""
                                : " (plus " + tolerated
                                        + " authored mention(s), fatal under --strict)");
                for (HomeCloner.Leak leak : isolation) Log.error("  %s", leak);
            } else if (tolerated > 0) {
                Log.error("%d authored mention(s) of %s, fatal under --strict; no path in %s "
                        + "resolves into another Skill Manager home", tolerated, against, home);
            }
            if (!result.clean() || !unresolved.isEmpty()) return 1;
            Log.ok("no %sreference to %s survives in %s, and no path in it reaches any "
                            + "other Skill Manager home",
                    mentions.isEmpty() ? "" : "repairable ", against, home);
            return 0;
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

        @Option(names = "--init",
                description = "Lay out the home first if it is not one yet. Without this a "
                        + "path that is not a home is refused rather than created.")
        boolean init;

        private final SkillStore injectedStore;

        public DescribeCmd() { this(null); }

        public DescribeCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, init,
                        home != null
                                ? "home describe --home"
                                : "home describe (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        "home describe --init");
            } catch (NotAHomeException notAHome) {
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
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

        @Option(names = "--init",
                description = "Lay out the home first if it is not one yet — the case of "
                        + "declaring a policy on a home as it is being created. Without this a "
                        + "path that is not a home is refused rather than created.")
        boolean init;

        private final SkillStore injectedStore;

        public PolicyCmd() { this(null); }

        public PolicyCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, init,
                        home != null
                                ? "home policy --home"
                                : "home policy (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        "home policy --init");
            } catch (NotAHomeException notAHome) {
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
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

        @Option(names = "--init",
                description = "Lay out the home first if it is not one yet. Without this a "
                        + "path that is not a home is refused rather than created.")
        boolean init;

        private final SkillStore injectedStore;

        public ShimsCmd() { this(null); }

        public ShimsCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, init,
                        home != null
                                ? "home shims --home"
                                : "home shims (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        "home shims --init");
            } catch (NotAHomeException notAHome) {
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
            Path pin;
            try {
                pin = dev.skillmanager.launch.RunningCli.locate();
            } catch (dev.skillmanager.launch.RunningCli.UnknownLocationException e) {
                // Reported rather than thrown: the operator has to act on this,
                // and the alternative the code refused to take (resolve the CLI
                // from PATH at launch time) is precisely the defect. A home with
                // no launch surface is visible; one with a silently downgraded
                // launch surface is not.
                Log.error("%s", "home shims: " + e.getMessage());
                return 127;
            }
            LauncherShims.Result result = LauncherShims.write(store, pin);
            if (json) {
                System.out.println("""
                        {"dir":"%s","cli":"%s","shims":[%s]}"""
                        .formatted(esc(result.dir().toString()), esc(pin.toString()),
                                result.written().stream()
                                        .map(p -> "\"" + esc(p.toString()) + "\"")
                                        .collect(java.util.stream.Collectors.joining(","))));
                return 0;
            }
            Log.ok("wrote %d launcher(s) to %s", result.written().size(), result.dir());
            for (Path shim : result.written()) Log.info("  %s", shim);
            Log.info("  pinned CLI: %s", pin);
            Log.info("  put %s first on PATH to launch against this home by default", result.dir());
            return 0;
        }
    }

    /**
     * {@code home drift} — what changed in this home, and the acknowledgement
     * that lets a launch proceed.
     *
     * <p>{@code --record} takes a fresh digest and reports the difference from the
     * last one; the bare command prints the pending change; {@code --ack} marks
     * it read. (This javadoc said {@code --show} for the printing spelling, and
     * so did the refusal {@code exec} prints. There has never been such an
     * option.) The gate itself lives in {@link dev.skillmanager.store.DriftGate};
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

        @Option(names = "--init",
                description = "Lay out the home first if it is not one yet. Without this a "
                        + "path that is not a home is refused rather than created.")
        boolean init;

        private final SkillStore injectedStore;

        public DriftCmd() { this(null); }

        public DriftCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, init,
                        home != null
                                ? "home drift --home"
                                : "home drift (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        "home drift --init");
            } catch (NotAHomeException notAHome) {
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
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

    /**
     * {@code home sync} — reconcile one home against another, in either
     * direction, by copy.
     *
     * <p>The mechanism and the reasoning live in {@link HomeSync}. What belongs
     * here is the surface: two homes named explicitly (never inferred — this
     * command writes into one of them, and guessing which is not a thing to be
     * clever about), and a report that is printed whatever happened.
     */
    @Command(name = "sync",
            description = "Reconcile one Skill Manager home against another by copy. Units the "
                    + "destination has edited are held back and reported, not overwritten; "
                    + "--merge three-way merges them and reports any conflict.")
    public static final class SyncCmd implements Callable<Integer> {

        @Option(names = "--from", required = true, description = "Home to read from. Never written.")
        Path from;

        @Option(names = "--to", required = true, description = "Home to reconcile. Written unless --dry-run.")
        Path to;

        @Option(names = "--merge",
                description = "Three-way merge a destination unit that carries local work, using "
                        + "its recorded per-file baseline. Conflicts are reported, never resolved; "
                        + "local work is kept either way.")
        boolean merge;

        @Option(names = "--dry-run",
                description = "Compute and print the whole report, write nothing.")
        boolean dryRun;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore source = new SkillStore(from.toAbsolutePath().normalize());
            SkillStore dest = new SkillStore(to.toAbsolutePath().normalize());
            HomeSync.Report report;
            try {
                report = HomeSync.run(source, dest, new HomeSync.Options(merge, dryRun));
            } catch (NotAHomeException notAHome) {
                // Same contract as the frozen case below: a refusal is the
                // answer, so it is an exit code and a sentence, not a stack
                // trace. --json callers get a payload rather than nothing,
                // because a script that parses stdout must be able to tell
                // "refused" from "crashed".
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            } catch (FrozenHomeException frozen) {
                // The refusal is the answer, not a crash: FrozenHomeException's
                // whole contract is an exit code a caller can branch on, and
                // `sync`/`upgrade` already return it. Letting picocli print a
                // stack trace instead made a deliberate policy look like a bug.
                Log.error("%s", frozen.getMessage());
                return FrozenHomeException.EXIT_CODE;
            }
            if (json) {
                System.out.println(syncJson(report));
            } else {
                renderSync(report);
            }
            // A dry run against a frozen destination is a report AND a refusal:
            // the whole plan is printed above, and the exit code is the one a
            // real run would have produced, so nothing branching on it changes.
            // See HomeSync#run, issue #51.
            if (report.destinationFrozen()) return FrozenHomeException.EXIT_CODE;
            // Held-back units are the documented default outcome of a plain
            // sync, so they are reported and do not fail the command. A
            // conflict is different: it is a decision nothing here is allowed
            // to make, and it has to be visible to a script. A LINKED unit is
            // the same kind of thing one step earlier — the pass could not even
            // decide whose bytes they were — and a script that reads exit 0 as
            // "reconciled" would be reading it wrong.
            return report.conflicted().isEmpty()
                    && report.with(ChildHomeMaterializer.SyncStatus.LINKED).isEmpty()
                    ? 0 : 1;
        }
    }

    /**
     * {@code home close-out} — the refusal a worktree teardown runs before it
     * deletes a home.
     *
     * <p>See {@link HomeCloseOut} for why this reports and refuses rather than
     * quietly doing the merge itself.
     */
    @Command(name = "close-out",
            description = "Refuse (non-zero) while a worktree home still holds work that "
                    + "removing it would destroy, naming every unit and what to run for each. "
                    + "Writes nothing; safe to run repeatedly.")
    public static final class CloseOutCmd implements Callable<Integer> {

        @Option(names = "--home", required = true,
                description = "The worktree's Skill Manager home, about to be removed.")
        Path home;

        @Option(names = "--into", required = true,
                description = "The project home its work has to reach first.")
        Path into;

        @Option(names = "--json", description = "Emit a machine-readable verdict.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore worktree = new SkillStore(home.toAbsolutePath().normalize());
            SkillStore project = new SkillStore(into.toAbsolutePath().normalize());
            HomeCloseOut.Verdict verdict;
            try {
                verdict = HomeCloseOut.inspect(worktree, project);
            } catch (NotAHomeException notAHome) {
                // The defect this refuses used to look like success: safe=true,
                // blockers=[], exit 0, for a --home that was the worktree
                // DIRECTORY rather than the home inside it. A JSON consumer
                // therefore gets safe=false explicitly rather than an absent
                // field it might default the wrong way.
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            } catch (FrozenHomeException frozen) {
                // The gate is a dry-run sync into the project home, so a frozen
                // project refuses it. Same contract as `home sync` above: an
                // exit code, not a stack trace — a teardown script branches on
                // this, and 9 ("refused, nothing attempted") is not 1 ("this
                // worktree still holds work").
                Log.error("%s", frozen.getMessage());
                return FrozenHomeException.EXIT_CODE;
            }
            if (json) {
                System.out.println(closeOutJson(verdict));
                return verdict.exitCode();
            }
            for (String line : HomeCloseOut.render(verdict)) Log.info("%s", line);
            if (verdict.safe()) {
                Log.ok("%s holds nothing that removing it would destroy", verdict.home());
                return 0;
            }
            Log.error("%d unit(s) in %s would be lost if it were removed now",
                    verdict.blockers().size(), verdict.home());
            return verdict.exitCode();
        }
    }

    // --------------------------------------------------------- home sync IO

    private static void renderSync(HomeSync.Report report) {
        Log.info("  from:        %s", report.from());
        Log.info("  to:          %s%s", report.to(), report.dryRun() ? "  (dry run — nothing written)" : "");
        if (report.destinationFrozen()) {
            Log.warn("  the destination is frozen (%s declares policy = \"frozen\"), so this is "
                            + "what a run WOULD do and no run will be allowed to do it — thaw it "
                            + "with `skill-manager home policy live --home %s`, or clone it",
                    report.to().resolve(dev.skillmanager.policy.HomePolicy.FILENAME), report.to());
        }
        for (ChildHomeMaterializer.UnitSync unit : report.units()) {
            String status = unit.status().name().toLowerCase().replace('_', '-');
            if (unit.status() == ChildHomeMaterializer.SyncStatus.UNCHANGED) {
                Log.info("  %-16s %s", status, unit.label());
                continue;
            }
            Log.info("  %-16s %s — %s", status, unit.label(), unit.detail());
            for (String conflict : unit.conflicts()) Log.warn("      conflict  %s", conflict);
        }
        Log.info("  %d unchanged, %d updated, %d new, %d merged, %d held back, %d conflicted, "
                        + "%d removed upstream, %d linked",
                report.count(ChildHomeMaterializer.SyncStatus.UNCHANGED),
                report.count(ChildHomeMaterializer.SyncStatus.UPDATED),
                report.count(ChildHomeMaterializer.SyncStatus.NEW),
                report.count(ChildHomeMaterializer.SyncStatus.MERGED),
                report.count(ChildHomeMaterializer.SyncStatus.HELD_BACK),
                report.count(ChildHomeMaterializer.SyncStatus.CONFLICTED),
                report.count(ChildHomeMaterializer.SyncStatus.REMOVED_UPSTREAM),
                report.count(ChildHomeMaterializer.SyncStatus.LINKED));
        if (report.clean()) {
            Log.ok("%s%s", report.dryRun() ? "would reconcile " : "reconciled ", report.to());
        } else {
            Log.warn("%d unit(s) were not reconciled and were left exactly as they were",
                    report.unresolved().size());
        }
    }

    private static String syncJson(HomeSync.Report report) {
        return "{\"from\":\"" + esc(report.from().toString())
                + "\",\"to\":\"" + esc(report.to().toString())
                + "\",\"merge\":" + report.merge()
                + ",\"dryRun\":" + report.dryRun()
                + ",\"destinationFrozen\":" + report.destinationFrozen()
                + ",\"clean\":" + report.clean()
                + ",\"units\":" + unitsJson(report.units()) + "}";
    }

    private static String unitsJson(List<ChildHomeMaterializer.UnitSync> units) {
        return units.stream()
                .map(unit -> "{\"unit\":\"" + esc(unit.label())
                        + "\",\"name\":\"" + esc(unit.unitName())
                        + "\",\"kind\":\"" + unit.unitKind().name().toLowerCase()
                        + "\",\"status\":\"" + unit.status().name().toLowerCase().replace('_', '-')
                        + "\",\"detail\":\"" + esc(unit.detail())
                        + "\",\"files\":" + strings(unit.files())
                        + ",\"conflicts\":" + strings(unit.conflicts()) + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String closeOutJson(HomeCloseOut.Verdict verdict) {
        String blockers = verdict.blockers().stream()
                .map(blocker -> "{\"unit\":\"" + esc(blocker.label())
                        + "\",\"status\":\""
                        + blocker.unit().status().name().toLowerCase().replace('_', '-')
                        + "\",\"detail\":\"" + esc(blocker.unit().detail())
                        + "\",\"conflicts\":" + strings(blocker.unit().conflicts())
                        + ",\"remedy\":\"" + esc(blocker.remedy()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"home\":\"" + esc(verdict.home().toString())
                + "\",\"into\":\"" + esc(verdict.into().toString())
                + "\",\"safe\":" + verdict.safe()
                + ",\"exitCode\":" + verdict.exitCode()
                + ",\"blockers\":" + blockers
                + ",\"units\":" + unitsJson(verdict.units()) + "}";
    }

    /**
     * The refusal, as a payload a script can branch on.
     *
     * <p>{@code safe} and {@code clean} are both present and both false. The
     * defect being refused printed {@code "safe": true} for a path that was not
     * a home at all, so a consumer that reads only one of those fields — or
     * that treats an absent field as its cheerful default — must not be able to
     * read this as approval.
     */
    private static String errorJson(NotAHomeException error) {
        return "{\"error\":\"not_a_home\",\"path\":\"" + esc(String.valueOf(error.path()))
                + "\",\"message\":\"" + esc(error.getMessage())
                + "\",\"safe\":false,\"clean\":false,\"blockers\":[],\"units\":[],\"exitCode\":"
                + NotAHomeException.EXIT_CODE + "}";
    }

    private static String strings(List<String> values) {
        return values.stream()
                .map(value -> "\"" + esc(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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

    /**
     * The store {@code --home} names, once it has been established that a home
     * is what it is.
     *
     * <h2>Why these three commands stopped calling {@code init()}</h2>
     *
     * <p>{@code home describe}, {@code home drift} and {@code home policy} each
     * opened with {@code store.init()}, which lays out a full home — {@code
     * installed/}, {@code skills/}, the rest — at whatever path it was given.
     * So a mistyped {@code --home} did not fail; it silently created a second,
     * empty home next to the real one and then answered questions about it.
     * Every answer was true of the thing it had just built and false of the
     * thing the operator meant, which is the exact shape of the fail-open class
     * this epic keeps finding: <em>a zero that means "could not look", reported
     * as "looked and found nothing"</em>. Issue #33.
     *
     * <p>{@link NotAHomeException} previously recorded these three as
     * "reported, not patched", on the reasoning that laying out an empty home
     * at a mistyped path is a mess rather than a data loss. That reasoning
     * missed the second half: {@code home describe --json} is what the launch
     * shims and {@code bootstrap-home.sh} read to decide where an agent's
     * config lives, so a descriptor computed for the wrong directory is acted
     * on by a machine, not just read by a person.
     *
     * <p>{@code --init} keeps the one legitimate gesture the old behaviour
     * covered — declaring a policy on a home as it is being created — but makes
     * it a thing the operator asked for rather than a side effect of a typo.
     *
     * <h2>The refusal names the argument that was actually used</h2>
     *
     * <p>All four callers used to pass {@code role} as {@code "home <x>
     * --home"} unconditionally, which is a lie whenever no {@code --home} was
     * given: the path came from {@code $SKILL_MANAGER_HOME}, and telling
     * someone to fix an option they never typed sends them hunting for a typo
     * that is not there. It also never mentioned the {@code --init} each of
     * these commands already declares, so the refusal was a dead end.
     *
     * <p>Both were survivable while these refusals were unreachable for the
     * ambient home — the eager scaffold in {@code SkillManagerCli.tryReconcile}
     * created it before the command ran, so the path was always a home by the
     * time it was checked. Removing that scaffold flipped {@code home
     * describe}, {@code home policy}, {@code home shims} and {@code home drift}
     * from exit 0 to exit 2 against an ambient non-home, which makes the
     * no-{@code --home} branch the COMMON one. Same shape, same fix and same
     * wording as {@code ExecCommand}.
     *
     * @param role     how the caller named the path — the ternary belongs at
     *                 the call site because only it knows whether
     *                 {@code --home} was passed
     * @param initHint the opt-in this command declares, e.g.
     *                 {@code "home policy --init"}
     */
    private static SkillStore requireHome(
            SkillStore injected, Path home, boolean init, String role, String initHint)
            throws IOException {
        SkillStore store = resolveStore(injected, home);
        if (init) {
            store.init();
            return store;
        }
        NotAHomeException.require(store.root(), role, initHint);
        return store;
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
            //
            // The remedy is still printed here, but it is no longer only
            // printed: `home verify` re-derives this same set from the copy
            // and refuses while it is non-empty (#133 item 2). A step named
            // once, in the middle of a successful clone, across a 24-repo
            // fan-out, is a step nobody performs — and the failure it prevents
            // does not surface until some later tool execs a shim that points
            // at nothing.
            Log.warn("  %d reference(s) do not resolve in the copy (targets under a skipped "
                    + "directory); re-provision with `SKILL_MANAGER_HOME=%s skill-manager sync "
                    + "--force-scripts` — `home verify` refuses this home until you do",
                    unresolved, report.dest());
            for (String dangling : report.danglingLinks()) Log.warn("    link   %s", dangling);
            for (String dangling : report.danglingReferences()) Log.warn("    script %s", dangling);
        }
        if (report.clean()) {
            // Says what was actually checked. The old wording — "no path in it
            // resolves back to <source>" — was true and useless: it left links
            // resolving into a THIRD home (the operator's live one) reported as
            // independence. See HomeCloner#foreignHomeReachedBy, issue #49.
            Log.ok("cloned home to %s — nothing in it resolves back to %s, and no path in it "
                    + "reaches any other Skill Manager home", report.dest(), report.source());
        } else {
            Log.error("clone verification FAILED — %d path(s) reach outside this copy",
                    report.leaks().size());
            for (HomeCloner.Leak leak : report.leaks()) Log.error("    %s", leak);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
