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
import dev.skillmanager.artifacts.ArtifactBuild;
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
                HomeCommand.CloseOutCmd.class,
                HomeCommand.RefreshPluginsCmd.class
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
            // Always <dest>/home.runtime.json — derivable from the destination
            // the verdict above already names.
            if (!json) Log.detail("  descriptor:  %s", HomeDescriptor.file(cloned.root()));
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

        /**
         * {@code --root} is a synonym, not a second concept.
         *
         * <p>Every remedy that names this command names a home and nothing
         * else, and {@code bootstrap-home.sh} — the script that prints most of
         * them — spells its own home argument {@code --root}. An agent holding
         * only the home path typed {@code home verify --root <home>} and got a
         * usage error, which is the whole of D11.
         */
        @Option(names = {"--home", "--root"}, description = "Home to check.")
        Path home;

        @Option(names = "--against",
                description = "The home it must not reference — normally the one it was copied "
                        + "from. Optional: without it the source-reference half of the check is "
                        + "reported as NOT CHECKED and the rest still runs.")
        Path against;

        @Option(names = "--strict", description = "Also fail on authored unit content references.")
        boolean strict;

        @Override
        public Integer call() throws Exception {
            if (home == null) {
                // Still required — but as a refusal that names the home it
                // wants, not as picocli's "Missing required options" over two
                // flags of which only one was ever knowable.
                Log.error("home verify needs a home to check: "
                        + "`skill-manager home verify --home <home>` "
                        + "(or --root <home>). Add `--against <source home>` to also check that "
                        + "no reference back to the home it was copied from survives.");
                return 2;
            }
            try {
                NotAHomeException.require(home, "home verify --home");
                if (against != null) NotAHomeException.require(against, "home verify --against");
            } catch (NotAHomeException notAHome) {
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
            HomeCloner.Verification result = against == null
                    ? HomeCloner.verify(home, strict)
                    : HomeCloner.verify(against, home, strict);
            if (against == null) {
                // Said BEFORE the findings, because a reader who stops at the
                // verdict must not be able to hear a guarantee this run did not
                // make. Same discipline as `home clone`'s "NOT checked:" clause.
                Log.info("NOT CHECKED: whether a reference back to the home this one was copied "
                        + "from survives — pass `--against <source home>` for that half. "
                        + "Checked below: every link and generated script in %s resolves, and "
                        + "no path in it reaches any other Skill Manager home.", home);
            }
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
                sample(mentions);
            }
            // Persisted error MESSAGES that quote a path into the other home.
            // Reported here, separately, and never in the isolation verdict:
            // ten of these were filed as "paths that resolve into another
            // Skill Manager home" and sent an operator chasing an isolation
            // problem after a sentence. Issue #144.
            List<String> diagnostics = result.diagnosticReferences();
            if (!diagnostics.isEmpty()) {
                Log.info("%d unit record(s) quote %s inside a persisted error message — a "
                                + "description of that home, not a path into it; %s",
                        diagnostics.size(), against,
                        strict ? "counted as failures under --strict"
                                : "tolerated; they go when the error does");
                sample(diagnostics);
            }
            // Sanctioned links at a PARENT store — reported, never counted.
            //
            // A child home's bin/cli entry is a symlink at its parent's entry
            // on purpose (ChildHomeMaterializer.mirrorExistingShim), so the
            // child shares the toolchain the parent provisioned instead of
            // installing a second copy of it. The isolation rule predates child
            // homes and refused every one of them: measured on harness-smoke,
            // `✗ FOREIGN_HOME bin/cli/pycowsay … resolves into the home at
            // <parent>`, with project-child-home passing only because its
            // fixtures declare no CLI deps.
            //
            // Not folded into the tolerated categories, which are fatal under
            // --strict: this is not a defect at any strictness. Printed anyway,
            // because a home that is not self-contained is a fact a reader
            // deciding whether to move or delete something needs.
            List<String> parentShims = result.parentStoreShims();
            if (!parentShims.isEmpty()) {
                Log.info("%d shim(s) in %s link at its parent store — a child home shares the "
                                + "parent's provisioned tools by design; the parent must outlive "
                                + "this home",
                        parentShims.size(), home);
                sample(parentShims);
            }
            // Provisioning that never completed. A message printed once by the
            // clone was not enough: nobody ran the remedy, and nothing asked
            // again. This is the command that asks again — issue #133 item 2.
            List<String> unresolved = result.unresolved();
            if (!unresolved.isEmpty()) {
                Log.error("%d reference(s) in %s do not resolve — provisioning was never "
                                + "completed, so the tools they name will fail at exec time",
                        unresolved.size(), home);
                Log.errorList("    ", unresolved);
                // BOTH axes, derived from this home. `SKILL_MANAGER_HOME=<home>
                // skill-manager sync --force-scripts` — the spelling this line
                // used to carry, and the one `home clone` printed too — pins
                // where the units live and NOT where the agent configs live,
                // so it reports `ADDED claude (~/.claude.json)` and writes the
                // operator's global config instead of this home's
                // (skill-manager#145). This is the one place the remedy is
                // printed now, so it is printed runnable.
                //
                // ARTI-06: and the VERB is now `build`, not
                // `sync --force-scripts`. The diagnosis above is per instance;
                // the remedy was total — every skill-script in the home rerun,
                // three deploy-helm venvs at ~530 MB each, to repair one shim.
                //
                // The artifacts are NAMED, not left to `--stale`. This remedy
                // is printed for THESE references, so its exit code has to
                // answer THIS question. Measured: `build --stale` on the
                // operator's project home selects 55 artifacts and plans 18
                // rebuilds, 11 of them lock rows recording a `binary` their
                // install never produced — so it exits 1 over artifacts that
                // have nothing to do with the reference it was printed under,
                // and HomeFixpointLaw (which parses this exact string and runs
                // it through sh -c) then fails on a home whose reference WAS
                // repaired. A whole-home remedy beneath a per-instance finding
                // is also the asymmetry this ticket exists to remove, restated
                // one verb later.
                Log.error("  complete it with: %s, then re-run this check",
                        reprovisionRemedy(home, unresolved));
            }
            // Last, because it is the verdict, and because a terminal keeps
            // the tail. Never gated on --strict: a path that RESOLVES into
            // another home is not a historical record under any reading.
            List<HomeCloner.Leak> isolation = result.isolationFailures();
            int tolerated = result.toleratedFailures().size();
            // Split, because "authored" is a claim about unit content and a
            // persisted error message is not that. Both are mentions; neither
            // is a path that resolves.
            String toleratedPhrase = toleratedPhrase(result);
            if (!isolation.isEmpty()) {
                Log.error("%d path(s) in %s resolve into another Skill Manager home%s",
                        isolation.size(), home,
                        tolerated == 0 ? ""
                                : " (plus " + toleratedPhrase + ", fatal under --strict)");
                List<String> rows = new java.util.ArrayList<>();
                for (HomeCloner.Leak leak : isolation) rows.add(leak.toString());
                Log.errorList("  ", rows);
                // A refusal with no remedy is the #142 class this release
                // exists to close, and until now this — the branch that carries
                // the VERDICT — was the one refusal in this command that
                // printed none. The reader was told which paths leak and
                // nothing about how to stop them leaking.
                Log.error("  %s", isolationRemedy(isolation, home));
            } else if (tolerated > 0) {
                Log.error("%s of %s, fatal under --strict; no path in %s "
                        + "resolves into another Skill Manager home",
                        toleratedPhrase, against, home);
                // The one refusal in this command with no command behind it,
                // and it says so rather than staying silent. There is nothing
                // to run: an authored references page that quotes another home,
                // and a persisted error message that describes one, are TEXT
                // inside content this program does not author. Naming a command
                // here would be inventing a remedy, which is the failure mode
                // one worse than having none. So it names the two real exits
                // instead, and both of them work.
                Log.error("  no command clears these: they are authored unit content and "
                        + "persisted error text, not paths. Edit the unit content (listed above) "
                        + "and re-run, or drop --strict — without it this home passes.");
            }
            if (!result.clean() || !unresolved.isEmpty()) return 1;
            // "no path reaches any other home" is FALSE of a child home, and a
            // verdict that says it anyway is the same defect as the three
            // earlier versions of this line: a guarantee wider than the run.
            // The exception is named in the verdict, not left in a line above
            // it that a reader who stops at the ✓ never sees.
            String except = parentShims.isEmpty() ? ""
                    : " except the " + parentShims.size() + " sanctioned parent-store shim(s) above";
            if (against == null) {
                // The verdict states its own scope. Without --against this run
                // cannot say "no reference to the source survives", and saying
                // it anyway is how three earlier versions of this line came to
                // be wrong.
                Log.ok("every reference in %s resolves, and no path in it reaches any other "
                        + "Skill Manager home%s (source-reference check not run — "
                        + "see NOT CHECKED above)", home, except);
                return 0;
            }
            Log.ok("no %sreference to %s survives in %s, and no path in it reaches any "
                            + "other Skill Manager home%s",
                    mentions.isEmpty() && diagnostics.isEmpty() ? "" : "repairable ",
                    against, home, except);
            return 0;
        }

        /**
         * The re-provisioning remedy: {@code build} over the artifacts that
         * own the references this run just refused on.
         *
         * <p>Falls back to {@code build --stale} when the join finds nothing —
         * a home whose unresolved paths belong to no artifact with a producer.
         * That fallback is the general command rather than a refusal, because a
         * remedy line with no command in it is the #142 class, and
         * {@code build --stale} at least names every stale artifact with the
         * command that rebuilds each one.
         *
         * <p>Every id is a shell word ({@link ArtifactBuild#shellWord}):
         * {@code cli-shim:pip/jinja2-cli[yaml]} is a real id and {@code [yaml]}
         * is a glob, and this line is pasted into shells and executed by tests.
         */
        private static String reprovisionRemedy(Path home, List<String> unresolved) {
            List<String> ids = List.of();
            try {
                ids = ArtifactBuild.buildableFor(new SkillStore(home), unresolved);
            } catch (RuntimeException ignored) {
                // The check's verdict does not depend on the remedy resolving.
            }
            if (ids.isEmpty()) return homeEnvPrefix(home) + " build --stale";
            StringBuilder out = new StringBuilder(homeEnvPrefix(home)).append(" build");
            for (String id : ids) out.append(' ').append(ArtifactBuild.shellWord(id));
            return out.toString();
        }

        /**
         * The remedy line for the isolation verdict, in the one spelling every
         * caller of this command parses ({@code complete it with: <cmd>, then
         * re-run this check}).
         *
         * <h2>Why this one did NOT become {@code build --stale} in ARTI-06</h2>
         *
         * <p>The unresolved-reference remedy above did, because that block names
         * artifacts that are stale and {@code build} rebuilds artifacts. This
         * block names paths that RESOLVE — into another home — and the repair is
         * {@code CliShimPruner} removing them so something can be provisioned
         * here instead. {@code build} does not prune, and a foreign link is not
         * stale: {@code CliPresence} calls it "already provisioned in this
         * home", so a per-artifact rebuild would skip it forever. Pointing this
         * line at {@code build} would be a remedy that runs and repairs nothing,
         * which is the defect one worse than having none.
         *
         * <h2>Why {@code sync --force-scripts} repairs an isolation leak</h2>
         *
         * <p>Because {@code CliShimPruner} now runs at the head of the CLI
         * install pass and removes exactly what this check refuses: a
         * {@code bin/cli} entry resolving into another home that is not this
         * home's parent store. Removing it is also what lets the SAME sync
         * re-provision it here — before, {@code CliPresence} called a foreign
         * link "already provisioned in this home", because it resolves and it
         * is executable, so the install pass skipped it forever.
         *
         * <h2>And why the launcher pin gets a different command</h2>
         *
         * <p>{@code bin/cli/skill-manager} and {@code bin/launch/*} are written
         * by {@code home shims} and by nothing else; {@code sync} does not
         * touch them, and {@code CliShimPruner} deliberately leaves the pin
         * alone (a stale pin failing loudly is {@code LauncherShims}' stated
         * tradeoff). Printing the sync line for that path would be a remedy
         * that runs and repairs nothing, which is the defect, not the fix. When
         * a home has both kinds the two commands are chained, so the single
         * line stays runnable as printed.
         */
        private static String isolationRemedy(List<HomeCloner.Leak> isolation, Path home) {
            boolean launcher = false;
            boolean other = false;
            for (HomeCloner.Leak leak : isolation) {
                String rel = leak.path().replace(java.io.File.separatorChar, '/');
                if (rel.equals("bin/cli/skill-manager") || rel.startsWith("bin/launch/")) {
                    launcher = true;
                } else {
                    other = true;
                }
            }
            String prefix = homeEnvPrefix(home);
            List<String> commands = new java.util.ArrayList<>();
            if (launcher) commands.add(prefix + " home shims --home " + home);
            if (other || commands.isEmpty()) commands.add(prefix + " sync --force-scripts");
            return "complete it with: " + String.join(" && ", commands)
                    + ", then re-run this check";
        }

        /**
         * The first {@link #TOLERATED_SAMPLE} entries on the console, the rest
         * in the run log.
         *
         * <p>The sample is NOT demoted with the rest of the per-item output,
         * and that is deliberate: these are the categories the verify report
         * tolerates, so the count alone would be a number with nothing behind
         * it. Three entries is what makes "163 history files mention the source
         * home" checkable without becoming the 163 lines that buried the six
         * findings underneath them.
         */
        private static void sample(List<String> refs) {
            int shown = Math.min(TOLERATED_SAMPLE, refs.size());
            for (int i = 0; i < shown; i++) Log.info("    %s", refs.get(i));
            for (int i = shown; i < refs.size(); i++) Log.detail("    %s", refs.get(i));
            if (refs.size() > shown) Log.info("    … %d more", refs.size() - shown);
        }

        /**
         * A runnable {@code env ...} prefix for a command that writes
         * {@code home}.
         *
         * <h2>Why the home path alone is not enough</h2>
         *
         * <p>{@code SKILL_MANAGER_HOME} says where the UNITS live.
         * {@code CLAUDE_CONFIG_DIR} / {@code CODEX_HOME} / {@code GEMINI_HOME}
         * say where the AGENT CONFIGS live, and they are a separate axis. A
         * remedy that pins only the first resolves the agent half against the
         * ambient environment — which is the operator's real
         * {@code ~/.claude.json}, {@code ~/.codex/config.toml} and
         * {@code ~/.gemini/settings.json}. Measured on the onboarding walk:
         * {@code SKILL_MANAGER_HOME=<home> skill-manager sync --force-scripts}
         * reported {@code ADDED claude (~/.claude.json)} — the global-binding
         * hijack recorded as skill-manager#145. Every remedy this command
         * prints for a home-mutating command goes through here.
         *
         * <h2>And the command itself was a bare {@code skill-manager}</h2>
         *
         * <p>Which is #142, sitting in the remedy for a defect that is itself a
         * remedy that did not work. This method is the single chokepoint its
         * javadoc above says it is, and it ended with
         * {@code out.append(" skill-manager")} — so on a machine whose PATH
         * carries an older release, or none at all, the pasted line runs a
         * different program than the one that printed it, or nothing.
         * {@link HomeDescriptor#cliInvocation} resolves the CLI that IS running
         * and falls back to the bare name only when it cannot; it is the same
         * routing the drift gate, {@code close-out}, {@code exec} and the sync
         * renderer already use.
         *
         * <p>Resolved against {@code home} rather than the ambient store: the
         * remedy is for THAT home, and a home carries its own launcher.
         */
        private static String homeEnvPrefix(Path home) {
            Path root = dev.skillmanager.agent.AgentHomes.homeRootFor(home);
            StringBuilder out = new StringBuilder("env SKILL_MANAGER_HOME=").append(home);
            for (Path dir : dev.skillmanager.agent.AgentHomes.agentDirsUnder(root)) {
                String name = dir.getFileName().toString();
                String var = switch (name) {
                    case ".codex" -> "CODEX_HOME";
                    case ".gemini" -> "GEMINI_HOME";
                    default -> "CLAUDE_CONFIG_DIR";
                };
                out.append(' ').append(var).append('=').append(dir);
            }
            return out.append(' ').append(HomeDescriptor.cliInvocation(home)).toString();
        }

        /** "40 authored mention(s)", "10 diagnostic message(s)", or both. */
        private static String toleratedPhrase(HomeCloner.Verification result) {
            long authored = result.toleratedFailures().stream()
                    .filter(leak -> HomeCloner.Leak.CONTENT_REFERENCE.equals(leak.kind()))
                    .count();
            long diagnostic = result.toleratedFailures().size() - authored;
            if (diagnostic == 0) return authored + " authored mention(s)";
            if (authored == 0) return diagnostic + " diagnostic message(s)";
            return authored + " authored mention(s) and " + diagnostic + " diagnostic message(s)";
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
            // The three filenames are claude/codex/gemini every time; the
            // directory above names where they are.
            for (Path shim : result.written()) Log.detail("  %s", shim);
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
                // The count is the verdict; the per-unit lines are already
                // read by the time you acknowledge them.
                for (String line : acked.report().render()) Log.detail("  %s", line);
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
            // A pending drift BLOCKS a launch, so this is the caller's whole
            // reason for running the command: the list stays on the console,
            // bounded, because a home with 200 changed units is still one
            // decision and the first dozen make it.
            Log.warn("%d unit(s) changed in %s (%s) and have not been read:",
                    pending.report().units().size(), store.root(), pending.operation());
            Log.errorList("  ", pending.report().render());
            Log.warn("  run `%s home drift --ack` once you have taken it in",
                    HomeDescriptor.cliInvocation(store.root()));
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

        @Option(names = "--unit",
                description = "Reconcile ONLY this unit, by name, instead of every unit either "
                        + "home holds. A whole-home sync is all-or-nothing: one unrelated "
                        + "conflicted unit blocks the unit you actually edited, which is what "
                        + "stopped `skt publish <unit>` publishing it. Refused when neither home "
                        + "holds the name — a filter that matches nothing would otherwise report "
                        + "success for work that did not happen.")
        String unit;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore source = new SkillStore(from.toAbsolutePath().normalize());
            SkillStore dest = new SkillStore(to.toAbsolutePath().normalize());
            HomeSync.Report report;
            try {
                report = HomeSync.run(source, dest, new HomeSync.Options(merge, dryRun, unit));
            } catch (HomeSync.UnknownUnitException unknown) {
                // Same contract as the two refusals below: an exit code and a
                // sentence, not a stack trace. This one matters most to a
                // script, because what it replaces would have been silent
                // success — an empty unit list reads exactly like agreement.
                if (json) System.out.println(errorJson(unknown));
                Log.error("%s", unknown.getMessage());
                return HomeSync.UnknownUnitException.EXIT_CODE;
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
            List<String> lines = HomeCloseOut.render(verdict);
            if (verdict.safe()) {
                // Nothing to act on, so the per-unit walk is the log's job. The
                // verdict is the whole answer a teardown script waits for.
                for (String line : lines) Log.detail("%s", line);
                Log.ok("%s holds nothing that removing it would destroy", verdict.home());
                return 0;
            }
            // Blocked: every line here names a unit and the command that clears
            // it, which is exactly "what the caller must act on" — so it stays,
            // bounded rather than unbounded.
            Log.errorList("", lines);
            Log.error("%d unit(s) in %s would be lost if it were removed now",
                    verdict.blockers().size(), verdict.home());
            return verdict.exitCode();
        }
    }

    // --------------------------------------------------------- home sync IO

    private static void renderSync(HomeSync.Report report) {
        Log.detail("  from:        %s", report.from());
        Log.info("  to:          %s%s", report.to(), report.dryRun() ? "  (dry run — nothing written)" : "");
        if (report.targeted()) {
            // Said before the counts, not after: every count below is zero for
            // every unit that was never visited, and a summary of eight zeroes
            // reads exactly like a clean home. It is not one — nothing else
            // was looked at.
            Log.info("  unit:        %s  (only this unit was reconciled; the rest of the "
                    + "home was not visited)", report.unit());
        }
        if (report.destinationFrozen()) {
            Log.warn("  the destination is frozen (%s declares policy = \"frozen\"), so this is "
                            + "what a run WOULD do and no run will be allowed to do it — thaw it "
                            + "with `skill-manager home policy live --home %s`, or clone it",
                    report.to().resolve(dev.skillmanager.policy.HomePolicy.FILENAME), report.to());
        }
        for (ChildHomeMaterializer.UnitSync unit : report.units()) {
            // Tensed by whether THIS run wrote. A dry run reaches the same
            // verdicts as a real one and used to print them in the same past
            // tense — see UnitSync#statusLabel and issue #133.
            String status = unit.statusLabel(!report.dryRun());
            // UNCHANGED is the case that scales with the home and says nothing
            // — on a twenty-unit reconcile where two units moved, eighteen of
            // these lines bury the two. The counted summary below still states
            // how many there were.
            if (unit.status() == ChildHomeMaterializer.SyncStatus.UNCHANGED) {
                Log.detail("  %-18s %s", status, unit.label());
                continue;
            }
            Log.info("  %-18s %s — %s", status, unit.label(), unit.detail());
            for (String conflict : unit.conflicts()) Log.warn("      conflict  %s", conflict);
        }
        Log.info("  " + (report.dryRun() ? "would be: " : "")
                        + "%d unchanged, %d updated, %d new, %d merged, %d held back, "
                        + "%d conflicted, %d removed upstream, %d linked",
                report.count(ChildHomeMaterializer.SyncStatus.UNCHANGED),
                report.count(ChildHomeMaterializer.SyncStatus.UPDATED),
                report.count(ChildHomeMaterializer.SyncStatus.NEW),
                report.count(ChildHomeMaterializer.SyncStatus.MERGED),
                report.count(ChildHomeMaterializer.SyncStatus.HELD_BACK),
                report.count(ChildHomeMaterializer.SyncStatus.CONFLICTED),
                report.count(ChildHomeMaterializer.SyncStatus.REMOVED_UPSTREAM),
                report.count(ChildHomeMaterializer.SyncStatus.LINKED));
        if (report.clean()) {
            Log.ok("%s%s%s", report.dryRun() ? "would reconcile " : "reconciled ", report.to(),
                    report.targeted() ? " (unit " + report.unit() + " only)" : "");
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
                // `unit` is null for a whole-home pass and the name for a
                // targeted one. Without it `clean:true` on a one-unit sync is
                // indistinguishable from `clean:true` on the whole home.
                + ",\"unit\":" + (report.unit() == null ? "null" : "\"" + esc(report.unit()) + "\"")
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
    private static String errorJson(HomeSync.UnknownUnitException error) {
        // Same shape as the not-a-home payload: a script parsing stdout must
        // be able to tell "refused" from "crashed" from "reconciled", and the
        // `clean:false` here is what stops a targeted typo reading as success.
        return "{\"error\":\"unknown_unit\",\"unit\":\"" + esc(String.valueOf(error.unit()))
                + "\",\"message\":\"" + esc(error.getMessage())
                + "\",\"safe\":false,\"clean\":false,\"blockers\":[],\"units\":[],\"exitCode\":"
                + HomeSync.UnknownUnitException.EXIT_CODE + "}";
    }

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
                    "danglingLinks":%d,"danglingReferences":%d,"droppedRegistrations":%d,\
                    "droppedBindings":%d,"droppedChildHomes":%d,"clean":%b}"""
                    .formatted(esc(report.source().toString()), esc(report.dest().toString()),
                            report.directories(), report.files(), report.symlinks(),
                            report.bytes(), report.linksRelativized(), report.stateReanchored(),
                            report.provisionedRewritten(), report.leaks().size(),
                            report.contentReferences().size(), report.danglingLinks().size(),
                            report.danglingReferences().size(),
                            report.droppedRegistrations().size(),
                            report.droppedBindings().size(),
                            report.droppedChildHomes().size(), report.clean()));
            return;
        }
        // Both restated verbatim by the verdict line at the bottom of this
        // method, which names the destination and the source it was checked
        // against. Kept in the log; not printed twice.
        Log.detail("  source:      %s", report.source());
        Log.detail("  destination: %s", report.dest());
        Log.info("  copied:      %d dirs, %d files, %d links (%d bytes; %s skipped)",
                report.directories(), report.files(), report.symlinks(), report.bytes(),
                String.join(", ", HomeCloner.SKIPPED_DIRS.stream().sorted().toList()));
        Log.info("  re-anchored: %d links relativized, %d records, %d provisioned files",
                report.linksRelativized(), report.stateReanchored(), report.provisionedRewritten());
        if (!report.droppedRegistrations().isEmpty()) {
            // Named, not merely omitted. Dropping them is right (see
            // HomeCloner.DROPPED_STATE_DIRS) and it is still a change to what
            // the copy knows, so it is stated with the command that undoes it.
            Log.info("  registrations: %d not inherited — a registration names a repository "
                            + "this copy has not been asked to manage. Re-register with "
                            + "`skill-manager project register --project-dir <path>`: %s",
                    report.droppedRegistrations().size(),
                    String.join(", ", report.droppedRegistrations()));
        }
        if (!report.droppedBindings().isEmpty()) {
            // The half of the same claim that carries projection targets. A
            // ledger row is the exact footprint `unbind`/`uninstall` will
            // undo, so an inherited one is a live instruction to delete
            // something in a checkout this copy was never pointed at.
            Log.info("  bindings: %d not inherited — each named a path outside this home. "
                            + "`skill-manager sync` re-derives this home's own projections: %s",
                    report.droppedBindings().size(),
                    String.join(", ", report.droppedBindings()));
        }
        if (!report.droppedChildHomes().isEmpty()) {
            Log.info("  child homes: %d not inherited — each named a child home outside this "
                            + "home. They are re-created by `skill-manager project resolve` in "
                            + "the checkout that owns them: %s",
                    report.droppedChildHomes().size(),
                    String.join(", ", report.droppedChildHomes()));
        }
        if (!report.contentReferences().isEmpty()) {
            Log.info("  %d unit-content file(s) mention the source home — historical records, "
                    + "left as written", report.contentReferences().size());
        }
        int unresolved = report.danglingLinks().size() + report.danglingReferences().size();
        if (unresolved > 0) {
            // THE REMEDY PARAGRAPH THAT USED TO BE HERE IS DELETED, NOT MOVED.
            //
            // It read: "re-provision with `SKILL_MANAGER_HOME=<dest>
            // skill-manager sync --force-scripts` — `home verify` refuses this
            // home until you do", followed by one line per dangling link and
            // one per dangling script reference. Three things were wrong with
            // it, and none of them is fixed by putting it in a log:
            //
            //  1. THE COMMAND DAMAGES THE MACHINE AS SPELLED. SKILL_MANAGER_HOME
            //     pins only one of the two axes an agent-writing command needs.
            //     Measured (skill-manager#145, and recorded in
            //     bootstrap-home.sh's own comment, which is why that script
            //     stopped printing this verbatim): run as written it reports
            //     `ADDED claude (~/.claude.json)` — it writes the OPERATOR'S
            //     global agent configs, not this home's. A remedy that hijacks
            //     the machine is not output to demote.
            //  2. IT IS A SECOND COPY OF A GATE THAT ALREADY EXISTS. `home
            //     verify` re-derives this identical set from the copy and
            //     refuses while it is non-empty (#133 item 2). The remedy
            //     belongs there, once, spelled correctly — which is where it
            //     now is, with both axes named.
            //  3. THE ENUMERATION SCALES WITH THE HOME. One line per dangling
            //     link, in the middle of a SUCCESSFUL clone, is the "pages of
            //     caveats" an agent pays for on every bootstrap.
            //
            // What is left is the count and the command that will enforce it,
            // spelled with its argument so it is runnable as printed. The
            // entries themselves are in the run log.
            Log.warn("  %d reference(s) do not resolve in the copy (targets under a directory "
                            + "the clone skips) — `skill-manager home verify --home %s` reports "
                            + "each one and refuses the home until they are re-provisioned",
                    unresolved, report.dest());
            for (String dangling : report.danglingLinks()) Log.detail("    link   %s", dangling);
            for (String dangling : report.danglingReferences()) Log.detail("    script %s", dangling);
        }
        if (report.clean()) {
            // Says what was actually checked — and this line has now been wrong
            // twice in the same way, each time by naming the category the check
            // covered and letting the reader hear a guarantee it did not make.
            //
            // #49: "no path in it resolves back to <source>" was true while
            // links resolved into a THIRD home. Widened to "any other Skill
            // Manager home".
            //
            // #145: "any other Skill Manager home" was true while the copy's
            // ledger named ~/.claude, ~/.codex and ~/.gemini — AGENT homes,
            // which are not Skill Manager homes and so were invisible to both
            // the re-anchoring and the check. An uninstall in such a copy
            // deleted three of the source home's global skill links and exited
            // 0. Both classes are now re-anchored and both are checked, so the
            // sentence may name them — under a standing rule this line broke
            // twice and now states outright: enumerate what was examined, say
            // what was not, and never generalise to "independent" or
            // "isolated". A reader who wants the general claim has to assemble
            // it from the list, which is the only form in which it is honest.
            //
            // D2: "NOT checked: ... project checkouts" was where the next
            // defect lived, and the sentence was honest about it — 18 binding
            // rows and four child-home records naming the operator's real
            // repositories rode into a scratch home under that clause. Records
            // are now filtered on "does it name anything outside this home",
            // so the clause narrows to what it still covers: file CONTENT,
            // which is not rewritten, and links that resolve nowhere in
            // particular.
            Log.ok("cloned home to %s — checked: nothing in it resolves back to %s; no path in "
                    + "it reaches another Skill Manager home; no record or link in it names "
                    + "another home's agent directories (.claude, .codex, .gemini); no binding, "
                    + "child-home record or registration in it claims a path outside this home. "
                    + "NOT checked: mentions inside unit content, and anything reached by a "
                    + "path this copy does not record — toolchains, caches, anything else on "
                    + "this machine.", report.dest(), report.source());
        } else {
            Log.error("clone verification FAILED — %d path(s) reach outside this copy",
                    report.leaks().size());
            List<String> leaks = new java.util.ArrayList<>();
            for (HomeCloner.Leak leak : report.leaks()) leaks.add(leak.toString());
            Log.errorList("    ", leaks);
        }
    }

    /**
     * Re-establish the harness-side plugin surface for THIS home: regenerate
     * the plugin marketplace under the home's own per-home name, then
     * (best-effort) register it and reinstall each plugin with every harness
     * CLI on PATH.
     *
     * <p>Exists because a home CLONE carries plugin <i>bytes</i> but no
     * harness <i>registration</i>: projectors emit no PLUGIN projections, so
     * no ledger records them, and {@code claude plugin install} is a CLI side
     * effect that copying files never re-runs. Without this, a fresh worktree
     * home's hooks/commands never load. Content is untouched — running this
     * never syncs unit bytes, so it cannot reintroduce the close-out
     * {@code .git/index} drift that a full {@code sync} causes (issue #50).
     */
    @Command(name = "refresh-plugins",
            description = "Regenerate this home's plugin marketplace and re-register its "
                    + "plugins with the harness CLIs. No unit content is synced.")
    public static final class RefreshPluginsCmd implements Callable<Integer> {

        @Option(names = "--home",
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        public RefreshPluginsCmd() { this(null); }

        public RefreshPluginsCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, false,
                        home != null
                                ? "home refresh-plugins --home"
                                : "home refresh-plugins (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        null);
            } catch (NotAHomeException notAHome) {
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
            var mp = new dev.skillmanager.project.PluginMarketplace(store);
            dev.skillmanager.project.PluginMarketplace.RegenerateResult regen;
            try {
                regen = mp.regenerate();
            } catch (IOException e) {
                Log.error("refresh-plugins: marketplace regeneration failed — %s", e.getMessage());
                return 1;
            }
            String marketplaceName = mp.name();
            List<String> plugins = regen.pluginNames();
            int registered = 0;
            int attempted = 0;
            List<String> notes = new ArrayList<>();
            for (var driver : dev.skillmanager.project.HarnessPluginCli.defaultDrivers()) {
                if (!driver.available()) {
                    notes.add(driver.agentId() + ": CLI not on PATH (" + driver.installHint()
                            + ") — registration skipped, non-fatal");
                    continue;
                }
                attempted++;
                try {
                    var added = driver.ensureMarketplaceAdded(mp.root(), marketplaceName);
                    if (!added.ok()) {
                        notes.add(driver.agentId() + ": marketplace-add failed — "
                                + (added.stderr().isBlank() ? added.stdout() : added.stderr()));
                        continue;
                    }
                    driver.refreshMarketplace(mp.root(), marketplaceName);
                    boolean allOk = true;
                    for (String plugin : plugins) {
                        var r = driver.reinstallPlugin(plugin, marketplaceName);
                        if (!r.ok()) {
                            allOk = false;
                            notes.add(driver.agentId() + ": install " + plugin + " failed — "
                                    + r.stderr());
                        }
                    }
                    if (allOk) registered++;
                } catch (Exception ex) {
                    notes.add(driver.agentId() + ": " + ex.getMessage());
                }
            }
            if (json) {
                System.out.println("""
                        {"marketplace":"%s","plugins":%d,"driversRegistered":%d,"driversAttempted":%d,"notes":[%s]}"""
                        .formatted(esc(marketplaceName), plugins.size(), registered, attempted,
                                notes.stream().map(n -> "\"" + esc(n) + "\"")
                                        .collect(java.util.stream.Collectors.joining(","))));
            } else {
                Log.ok("marketplace '%s' regenerated with %d plugin(s); %d/%d harness driver(s) registered",
                        marketplaceName, plugins.size(), registered, attempted);
                for (String note : notes) Log.warn("%s", note);
            }
            // Missing/failed harness CLIs are non-fatal by design (matching the
            // RefreshHarnessPlugins effect): the marketplace on disk is the
            // durable outcome; registration is re-attemptable.
            return 0;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
