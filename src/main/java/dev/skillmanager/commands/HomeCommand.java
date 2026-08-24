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
import dev.skillmanager.store.HomeProvenance;
import dev.skillmanager.store.HomeRepair;
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
                HomeCommand.RepairCmd.class,
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

        @Option(names = "--lazy-artifacts", arity = "1", paramLabel = "true|false",
                description = "Whether the copy declares its artifacts and builds them on "
                        + "demand. Default: true unless the destination IS the operator root "
                        + "home. The decision is written into the copy's home.policy.toml.")
        Boolean lazyArtifacts;

        @Override
        public Integer call() throws Exception {
            Path source = from != null ? from : SkillStore.defaultStore().root();
            HomeCloner.Report report = HomeCloner.cloneHome(source, to, strict,
                    lazyArtifacts != null ? lazyArtifacts
                            : HomePolicy.lazyArtifactsDefault(new SkillStore(to)));
            // DEF-096. A clone copies what the source HOLDS; it cannot copy what
            // the source's manifest merely DECLARES. Measured on this
            // repository's own project home: skill-project.toml declares
            // plugin:skt and skill:skill-manager, the home held neither, and
            // every ticket worktree in the epic was cloned from it — silently
            // short the two units documenting the thing being worked on.
            // Reported about the SOURCE, because the shortfall is inherited: the
            // copy is short exactly what the original was short.
            dev.skillmanager.project.ProjectManifestRealization.Shortfall shortfall =
                    dev.skillmanager.project.ProjectManifestRealization
                            .inspect(new SkillStore(source));
            print(report, json, shortfall);
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
            // HIS-10. WHY those shims were sanctioned, printed with the verdict.
            //
            // The sanction used to be visible only when an operator passed
            // --against, so the same home read clean here and FOREIGN_HOME one
            // command later. It is now a record in the home; saying so is the
            // "and DECLARES it" half of the lazy contract — a clone keeps its
            // inherited artifacts on PATH and says whose they are, instead of
            // pruning and rebuilding a toolchain nobody changed.
            reportDescent(home);
            // ARTI-07: the THIRD state, and the reason this section is above
            // the failure section rather than inside it. A home that declares
            // its artifacts and builds them on demand ships these on purpose —
            // every clone does — so reporting them as failures would make this
            // command exit 1 on every fresh worktree, and what happens next is
            // not that somebody fixes an artifact. It is that somebody stops
            // running the check. Reported, never counted.
            List<String> declared = result.declaredNotBuilt();
            if (!declared.isEmpty()) {
                Log.info("%d entry point(s) in %s are DECLARED and not built — normal in a home "
                                + "with `%s = true`, and not a failure. Each one names the command "
                                + "that builds it when it is run.",
                        declared.size(), home, HomePolicy.LAZY_ARTIFACTS_KEY);
                sample(declared);
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
                noteCaveat(home);
            }
            // HIS-19 / DEF-012. THE HOME'S OWN FRONT DOOR PINS A BUILD THAT IS
            // GONE, and until now this command exited 0 on exactly that home.
            //
            // Measured on the operator's machine during this epic's 0.24.0
            // release: `brew upgrade` deleted the keg the root home pinned, the
            // home's `bin/cli/skill-manager` could only produce exit 127, and
            // `home verify` printed ✓ and exited 0. It was not lying about what
            // it had checked — the walk finds a missing path only INSIDE the
            // home and under a provisionable root, and a dead pin names one
            // outside the home entirely — which is why this is a separate
            // question rather than a wider walk.
            //
            // Never gated on --strict: a front door that cannot open is not a
            // historical record under any reading.
            List<String> deadPins = result.danglingCliPins();
            if (!deadPins.isEmpty()) {
                Log.error("%d CLI pin(s) in %s name a build that is not there — the entrypoint "
                                + "file exists and is executable, so every -x test passes, and "
                                + "running it can only produce exit 127",
                        deadPins.size(), home);
                Log.errorList("    ", deadPins);
                // HIS-13 owns the repair; this names it rather than inventing a
                // second one. `home shims` re-pins too and is named second,
                // because it pins whatever build is running it while
                // `home repair --fix` locates one itself.
                Log.error("  re-pin it with: skill-manager home repair --home %s --fix "
                                + "(or `skill-manager home shims --home %s`, run from the build "
                                + "this home should use), then re-run this check",
                        home, home);
                noteCaveat(home);
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
                noteCaveat(home);
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
            // HIS-19: `|| !deadPins.isEmpty()`. This clause is the regression
            // test for the 0.24.0 incident — every other term was already true
            // of that home and every one of them was FALSE, so it exited 0.
            if (!result.clean() || !unresolved.isEmpty() || !deadPins.isEmpty()) return 1;
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
            if (launcher) {
                commands.add(prefix + " home shims --home "
                        + HomeDescriptor.shellQuote(home.toString()));
            }
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
        /**
         * Print what {@code home} RECORDS about where it came from, and which
         * of it still re-derives.
         *
         * <p>Printed either way on purpose. "No descent recorded" is the state
         * in which an inherited shim is a hard {@code FOREIGN_HOME} refusal, and
         * an operator staring at that refusal needs to see the missing fact
         * rather than infer it. It is also what tells a pre-HIS-10 copy apart
         * from one this build made — the input HIS-13's repair needs.
         *
         * <h2>A claim and a fact are never printed as the same thing</h2>
         *
         * <p>The first version of this printed the record's own
         * {@code parentStores} as the answer. Measured on review of #228: a
         * hand-written record naming {@code /nowhere} as its source turned the
         * isolation gate off AND was reported here as authoritative descent —
         * and because this line names the filename, it told the next agent
         * exactly which file to write to make a refusal go away.
         *
         * <p>So the recorded set is a CLAIM, each entry is re-derived live
         * ({@link HomeProvenance#sanctions}), and an entry that no longer
         * re-derives is printed as the dead claim it is rather than omitted.
         * Omitting it would hide the one transition an operator has to act on:
         * a parent whose claim was revoked, whose shims are foreign again.
         */
        private static void reportDescent(Path home) {
            HomeProvenance.Descent descent = HomeProvenance.read(home);
            if (descent == null) {
                Log.detail("no recorded descent: %s carries no %s, so nothing in it sanctions a "
                                + "path into another home",
                        home, HomeProvenance.FILENAME);
                return;
            }
            List<Path> recorded = HomeProvenance.recordedParentStores(home);
            List<Path> verified = HomeProvenance.verifiedParentStores(home);
            if (recorded.isEmpty()) {
                Log.info("descent: %s records that it was cloned from %s, and names no parent "
                                + "store — so no foreign path in it is sanctioned by that record",
                        home, descent.clonedFrom());
                return;
            }
            Log.info("descent: %s records that it was cloned from %s; %d of %d recorded parent "
                            + "store(s) still re-derive as ancestors of this home",
                    home, descent.clonedFrom(), verified.size(), recorded.size());
            for (Path store : recorded) {
                if (verified.contains(store)) {
                    Log.info("    %s  — re-derived, so its artifacts are shared by right", store);
                } else {
                    Log.info("    %s  — NOT re-derivable: no live claim links this home to it, "
                            + "so its shims here are foreign again", store);
                }
            }
        }

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
         * {@link HomeDescriptor#cliInvocation} resolves a build for this home
         * and falls back to the bare name only when it cannot; it is the same
         * routing the drift gate, {@code close-out}, {@code exec} and the sync
         * renderer already use.
         *
         * <p>The sentence above used to read "resolves the CLI that IS
         * running", and that was an OVERSTATEMENT of the same family as the
         * precedence javadoc #161 is about: the running build is one of four
         * steps, and it is not the first. Which step answered is
         * {@link HomeDescriptor.CliSpelling#source()}, and when it is the
         * {@code PATH} walk the remedy says so — see
         * {@link HomeDescriptor.CliSpelling#caveat()}, printed here by
         * {@code noteCaveat}.
         *
         * <p>Resolved against {@code home} rather than the ambient store: the
         * remedy is for THAT home, and a home carries its own launcher.
         */
        private static String homeEnvPrefix(Path home) {
            // A BARE `env`, exactly as this shipped. #229's first attempt
            // spelled it /usr/bin/env, arguing that made the remedy honest
            // under the graph assertions that require an absolute head token.
            // It does the opposite: /usr/bin/env is absolute and executable
            // forever, whatever the resolution behind it does, so three
            // readers go permanently green and stop asserting anything.
            StringBuilder out = new StringBuilder("env");
            // HIS-14: the four variables are no longer written down here. This
            // method used to be the ONLY place that knew a home has two axes,
            // which is precisely why `--home` -- read by every other remedy --
            // bound one of them and sent the agent half to the operator's real
            // ~/.claude (DEF-029). AgentHomes.binding is now the single
            // statement, RENDERED here and APPLIED by --home, so the printed
            // remedy and the flag cannot mean two different things.
            dev.skillmanager.agent.AgentHomes.binding(home).forEach((var, value) ->
                    out.append(' ').append(var).append('=')
                            .append(HomeDescriptor.shellQuote(value)));
            // This is not a class-2 verb wearing an env prefix; it is the one
            // remedy whose target is four variables.
            //
            // The caveat is appended here rather than left to the caller: this
            // is the exact line #161 quotes, and it was the surface where a
            // PATH-resolved build read as authoritative.
            HomeDescriptor.CliSpelling spelling = HomeDescriptor.cliSpelling(home);
            out.append(' ').append(spelling.binary());
            return out.toString();
        }

        /**
         * Print the one line a remedy built on {@link #homeEnvPrefix} owes its
         * reader, when there is one.
         *
         * <p>M4 of #229's review: the caveat reached four surfaces and this one
         * dropped it — and this is the exact line issue #161 quotes, where a
         * PATH-resolved build read as authoritative while being the same binary
         * the bare token would have found.
         */
        private static void noteCaveat(Path home) {
            String caveat = HomeDescriptor.cliSpelling(home).caveat();
            if (caveat != null) Log.error("  note: %s", caveat);
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

        /**
         * Declare {@code lazy_artifacts}. A {@link Boolean} rather than a
         * {@code boolean} so that "not passed" is a third value: this command's
         * other half rewrites the file, and a primitive would make every
         * {@code home policy live} silently declare eagerness.
         */
        @Option(names = "--lazy-artifacts", arity = "1", paramLabel = "true|false",
                description = "Declare whether this home builds its artifacts on demand. "
                        + "Default: true for a project or worktree home, false for the operator "
                        + "root. May be given with or without a policy word.")
        Boolean lazyArtifacts;

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
            if (lazyArtifacts != null) HomePolicy.writeLazyArtifacts(store, lazyArtifacts);
            if (policy == null || policy.isBlank()) {
                HomePolicy current = HomePolicy.load(store);
                System.out.println("policy: " + current.wire());
                // Declared and effective, in those words. They differ for every
                // home that has not said anything, which is most of them, and a
                // reader who cannot tell them apart writes the default back
                // into the file as though somebody had chosen it.
                Boolean declared = HomePolicy.declaredLazyArtifacts(store);
                System.out.println("lazy_artifacts: " + HomePolicy.lazyArtifacts(store)
                        + (declared == null
                            ? "  (not declared — default for this tier)" : "  (declared)"));
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

        /**
         * The build to pin, when the caller already knows it; {@code RunningCli}
         * answers when nothing is injected.
         *
         * <p>The seam exists for the reason {@link RepairCmd}'s and
         * {@link LauncherShims#write(SkillStore, Path)}'s do: an in-process test
         * cannot be located as a running CLI. It is load-bearing for a specific
         * assertion — that this command PRINTS the pin it wrote — which cannot
         * be made at all unless a fixture build reaches
         * {@code LauncherShims.write}. Reverting the two lines below reddened
         * nothing in 1402 cases before that assertion existed (review of #250,
         * MAJOR 1).
         */
        private final Path injectedPin;

        public ShimsCmd() { this(null, null); }

        public ShimsCmd(SkillStore injectedStore) { this(injectedStore, null); }

        public ShimsCmd(SkillStore injectedStore, Path injectedPin) {
            this.injectedStore = injectedStore;
            this.injectedPin = injectedPin;
        }

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
                pin = injectedPin != null
                        ? injectedPin
                        : dev.skillmanager.launch.RunningCli.locate();
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
            // HIS-19: report what was WRITTEN, not what was located. The writer
            // may record a versionless spelling of the same build (DEF-027), and
            // a `pinned CLI:` line naming a path the file does not contain is a
            // reader disagreeing with the writer about one artifact — the class
            // this epic is about, and one its own fix could have introduced.
            pin = result.pin();
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

        @Option(names = "--detail",
                description = "Print every changed path instead of the per-unit rollup.")
        boolean detail;

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
                // read by the time you acknowledge them. Detailed here because
                // Log.detail is already an opt-in surface -- collapsing a
                // stream nobody sees by default buys nothing.
                for (String line : acked.report().renderDetailed()) Log.detail("  %s", line);
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
            // CLASS 2: `home drift` takes --home, so that is where the
            // binding goes.
            HomeDescriptor.CliSpelling spelling = HomeDescriptor.cliSpelling(store.root());
            String cli = spelling.binary();
            String homeArg = spelling.homeArg();
            // `--detail` is an explicit ask, so it always answers in full. The
            // collapse is about what an agent gets when it did NOT ask.
            if (detail || pending.firstSurfacing()) {
                Log.warn("%d unit(s) changed in %s (%s) and have not been read:",
                        pending.report().units().size(), store.root(), pending.operation());
                Log.errorList("  ", detail
                        ? pending.report().renderDetailed()
                        : pending.report().render());
                Log.warn("  run `%s home drift --ack %s` once you have taken it in",
                        cli, homeArg);
            } else {
                Log.warn("%s", pending.stillUnreadLine(cli, homeArg));
            }
            if (spelling.caveat() != null) Log.warn("  note: %s", spelling.caveat());
            DriftGate.markSurfaced(store);
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
                //
                // The `if (json)` line is #235's: this catch sat immediately
                // below one that DID answer a --json caller and it answered
                // nothing, so a frozen project home produced exit 9 with an
                // empty stdout. Nobody decided that; the catch was simply added
                // later than its sibling. The generic envelope in
                // JsonExitEnvelope would now cover it, but only with
                // error="failed" — a caught exception never reaches the
                // classifier — and this command's consumer reads `safe` and
                // `blockers`, so it gets the same shape as its sibling.
                if (json) System.out.println(frozenJson(frozen));
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
        // DEF-101. A unit that cleared the gate because the destination
        // declares it is a DECISION, and a decision a script can read. An empty
        // list here plus safe:true means "nothing was at risk"; a non-empty one
        // means "these were not copied and here is why that is correct".
        String obtainable = verdict.selfObtainable().stream()
                .map(o -> "{\"unit\":\"" + esc(o.label())
                        + "\",\"status\":\""
                        + o.unit().status().name().toLowerCase().replace('_', '-')
                        + "\",\"source\":\"" + esc(o.source())
                        + "\",\"publishedAt\":\"" + esc(o.publishedAt())
                        + "\",\"remedy\":\"" + esc(o.remedy()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"home\":\"" + esc(verdict.home().toString())
                + "\",\"into\":\"" + esc(verdict.into().toString())
                + "\",\"safe\":" + verdict.safe()
                + ",\"exitCode\":" + verdict.exitCode()
                + ",\"blockers\":" + blockers
                + ",\"selfObtainable\":" + obtainable
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

    private static String frozenJson(FrozenHomeException frozen) {
        return "{\"error\":\"home_frozen\",\"path\":\"" + esc(String.valueOf(frozen.homeRoot()))
                + "\",\"message\":\"" + esc(frozen.getMessage())
                + "\",\"safe\":false,\"clean\":false,\"blockers\":[],\"units\":[],\"exitCode\":"
                + FrozenHomeException.EXIT_CODE + "}";
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

    /**
     * DEF-096, machine-readable. {@code declared} is carried beside
     * {@code missing} so a consumer can tell "this home realizes its manifest"
     * from "this home has no manifest" — both of which have an empty
     * {@code missing} list and are not the same fact.
     */
    private static String manifestShortfallJson(
            dev.skillmanager.project.ProjectManifestRealization.Shortfall shortfall) {
        if (shortfall == null || !shortfall.hasManifest()) {
            return "{\"manifest\":null,\"declared\":0,\"missing\":[]}";
        }
        String missing = shortfall.missing().stream()
                .map(d -> "{\"unit\":\"" + esc(d.lookupName())
                        + "\",\"kind\":\"" + d.kind().name().toLowerCase()
                        + "\",\"source\":\"" + esc(d.source()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"manifest\":\"" + esc(shortfall.manifest().toString())
                + "\",\"declared\":" + shortfall.declared().size()
                + ",\"missing\":" + missing + "}";
    }

    private static void print(HomeCloner.Report report, boolean json,
                             dev.skillmanager.project.ProjectManifestRealization.Shortfall shortfall) {
        if (json) {
            System.out.println("""
                    {"source":"%s","dest":"%s","directories":%d,"files":%d,"symlinks":%d,\
                    "bytes":%d,"linksRelativized":%d,"stateReanchored":%d,\
                    "provisionedRewritten":%d,"leaks":%d,"contentReferences":%d,\
                    "danglingLinks":%d,"danglingReferences":%d,"droppedRegistrations":%d,\
                    "droppedBindings":%d,"droppedChildHomes":%d,"clean":%b,\
                    "manifestShortfall":%s}"""
                    .formatted(esc(report.source().toString()), esc(report.dest().toString()),
                            report.directories(), report.files(), report.symlinks(),
                            report.bytes(), report.linksRelativized(), report.stateReanchored(),
                            report.provisionedRewritten(), report.leaks().size(),
                            report.contentReferences().size(), report.danglingLinks().size(),
                            report.danglingReferences().size(),
                            report.droppedRegistrations().size(),
                            report.droppedBindings().size(),
                            report.droppedChildHomes().size(), report.clean(),
                            manifestShortfallJson(shortfall)));
            return;
        }
        // Both restated verbatim by the verdict line at the bottom of this
        // method, which names the destination and the source it was checked
        // against. Kept in the log; not printed twice.
        if (shortfall != null && !shortfall.clean()) {
            Log.warn("  manifest:    %s", shortfall.summary());
            for (String line : shortfall.render().subList(1, shortfall.render().size())) {
                Log.warn("  %s", line);
            }
            Log.warn("  this copy is short the same unit(s); resolving the SOURCE and "
                    + "re-cloning, or resolving here, is what obtains them");
        }
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
        // ARTI-07, and deliberately ONE line each. The clone's report is paid
        // for on every bootstrap; the entries are in the run log and in
        // `skill-manager artifacts list`, which is the command that owns them.
        if (!report.coldShims().isEmpty()) {
            Log.info("  declared:    %d entry point(s) name `skill-manager build <id>` instead "
                            + "of failing in the kernel's words — they were shims into a tree "
                            + "this copy does not carry: %s",
                    report.coldShims().size(), String.join(", ", report.coldShims()));
        }
        if (!report.deferredTrees().isEmpty()) {
            Log.info("  deferred:    %d virtualenv(s) inside units are declared, not copied — "
                            + "`uv` rebuilds each from the lockfile beside it on first use: %s",
                    report.deferredTrees().size(), String.join(", ", report.deferredTrees()));
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
     * {@code home repair} — name what is damaged in a home, and (only when
     * asked) fix it.
     *
     * <h2>The command that did not exist</h2>
     *
     * <p>HIS-13 / issue #159. This subcommand list held {@code clone},
     * {@code verify}, {@code describe}, {@code policy}, {@code shims},
     * {@code drift}, {@code sync}, {@code close-out} and
     * {@code refresh-plugins}, and <b>nothing that repaired</b>. Every guard in
     * this program prevents the NEXT instance; a home that already took the
     * damage stayed damaged until a person noticed an odd path in a file. That
     * happened twice to the operator's root home inside one epic, and both
     * times a person is what found it.
     *
     * <h2>Why the verb is {@code repair} and the default is DETECT</h2>
     *
     * <p>DEF-067: an observer that repairs is no longer an observer.
     * {@code HomeFixpointLaw} parses the remedy out of a refusal and runs it,
     * so it can silently repair the condition it was checking and report PASS
     * — and the evidence for telling "the product is broken" from "a fixture
     * left that there" is exactly what it destroys.
     *
     * <p>This command ships the repairer, so the split is a property of the
     * command rather than a convention: <b>the bare command mutates nothing.</b>
     * It prints one line per finding, each naming what would repair that
     * finding, and exits 1. {@code --fix} is the only spelling that writes.
     * A caller that wants an observer gets one by not passing a flag, and
     * running detection any number of times on a damaged home leaves it damaged
     * and leaves the verdict red. There is a graph node that asserts exactly
     * that, and it runs detect, repair and detect as three separate processes.
     *
     * <p>{@code --fix} re-runs detection AFTER repairing and exits on the
     * second verdict, not on its own opinion of how it went. A repairer that
     * reports its own success has asserted nothing (#142).
     */
    @Command(name = "repair",
            description = "Report what is damaged in a home — mis-anchored agent skill links, "
                    + "shims pointing into another home, entry points pruned that this home's "
                    + "descent record says were inherited, and a CLI pin naming a build that is "
                    + "gone. Reports only; pass --fix to repair.")
    public static final class RepairCmd implements Callable<Integer> {

        /**
         * {@code --root} is a synonym here for the reason it is on
         * {@code verify}: {@code bootstrap-home.sh} spells its home argument
         * {@code --root}, and a remedy an agent cannot type is not a remedy.
         */
        @Option(names = {"--home", "--root"},
                description = "Skill Manager home. Defaults to $SKILL_MANAGER_HOME.")
        Path home;

        @Option(names = "--fix",
                description = "Carry out the repairs. WITHOUT THIS THE COMMAND WRITES NOTHING — "
                        + "it reports, and a damaged home stays damaged.")
        boolean fix;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        /**
         * The build a broken CLI pin is re-pinned at, when the caller already
         * knows it; {@code RunningCli} answers when nothing is injected.
         *
         * <p>The seam exists because an in-process test cannot be located as a
         * running CLI, and a {@code DANGLING_CLI_PIN} finding that silently
         * downgraded to unrepairable would let an idempotence assertion pass
         * over a repair that never ran — mechanism C of the vacuity ledger.
         * Same argument, and the same shape, as
         * {@link LauncherShims#write(SkillStore, Path)}.
         */
        private final Path injectedPin;

        public RepairCmd() { this(null, null); }

        public RepairCmd(SkillStore injectedStore) { this(injectedStore, null); }

        public RepairCmd(SkillStore injectedStore, Path injectedPin) {
            this.injectedStore = injectedStore;
            this.injectedPin = injectedPin;
        }

        @Override
        public Integer call() throws Exception {
            SkillStore store;
            try {
                store = requireHome(injectedStore, home, false,
                        home != null
                                ? "home repair --home"
                                : "home repair (no --home; home taken from $"
                                        + SkillStore.HOME_ENV + ")",
                        null);
            } catch (NotAHomeException notAHome) {
                if (json) System.out.println(errorJson(notAHome));
                Log.error("%s", notAHome.getMessage());
                return NotAHomeException.EXIT_CODE;
            }
            Path root = store.root();
            Path pin = injectedPin != null
                    ? injectedPin
                    : dev.skillmanager.launch.RunningCli.locateOrNull();
            if (!fix) {
                HomeRepair.Report report = HomeRepair.detect(root, pin);
                if (json) {
                    System.out.println(reportJson(report, null));
                    return report.clean() ? 0 : 1;
                }
                render(report, root);
                return report.clean() ? 0 : 1;
            }
            HomeRepair.Outcome outcome;
            try {
                outcome = HomeRepair.repair(root, pin);
            } catch (FrozenHomeException frozen) {
                if (json) System.out.println("""
                        {"home":"%s","error":"frozen"}""".formatted(esc(root.toString())));
                Log.error("%s", frozen.getMessage());
                return FrozenHomeException.EXIT_CODE;
            }
            if (json) {
                System.out.println(reportJson(outcome.after(), outcome));
                return outcome.after().clean() ? 0 : 1;
            }
            Log.ok("repaired %d of %d finding(s) in %s",
                    outcome.repaired().size(), outcome.before().findings().size(), root);
            for (HomeRepair.Finding finding : outcome.repaired()) {
                Log.detail("  %s %s -> %s", finding.kind(), finding.subject(), finding.target());
            }
            if (!outcome.failed().isEmpty()) {
                Log.error("%d repair(s) were refused", outcome.failed().size());
                Log.errorList("    ", outcome.failed());
            }
            // The verdict is DETECTION's, taken after the repair ran. Saying
            // "repaired 3" and exiting 0 would be the remedy-that-does-not-work
            // class (#142) with a nicer message.
            render(outcome.after(), root);
            return outcome.after().clean() ? 0 : 1;
        }

        /**
         * One line per finding, and the repair on the line under it.
         *
         * <p>Not one line per finding with the repair appended: the paths in
         * both halves are absolute and long, and the measured failure mode for
         * this shape of output is that the reader stops at the first wrap. The
         * remedy is indented under the finding it belongs to, so a finding and
         * its repair cannot be read apart.
         */
        private static void render(HomeRepair.Report report, Path root) {
            if (report.clean()) {
                // The scope is stated, because a clean verdict over zero
                // subjects is not a clean home. Same discipline as `verify`'s
                // NOT CHECKED clause.
                Log.ok("nothing in %s is damaged in a way this command knows about "
                        + "(%d entr%s examined)", root, report.examined(),
                        report.examined() == 1 ? "y" : "ies");
                return;
            }
            Log.error("%d finding(s) in %s, of %d entr%s examined",
                    report.findings().size(), root, report.examined(),
                    report.examined() == 1 ? "y" : "ies");
            for (HomeRepair.Finding finding : report.findings()) {
                Log.error("  %s %s — %s",
                        finding.kind(), finding.subject(), finding.detail());
                Log.error("      repair: %s", finding.remedy());
            }
            int repairable = report.repairable().size();
            if (repairable > 0) {
                Log.error("  %d of these can be repaired by: skill-manager home repair "
                        + "--home %s --fix", repairable, root);
            }
            if (repairable < report.findings().size()) {
                // Named rather than silently folded in, so nobody runs --fix
                // and reads a non-zero exit as a failed repair.
                Log.error("  %d cannot be repaired by this command — the lines above say why",
                        report.findings().size() - repairable);
            }
        }

        private static String reportJson(HomeRepair.Report report, HomeRepair.Outcome outcome) {
            StringBuilder sb = new StringBuilder("{\"home\":\"")
                    .append(esc(report.home().toString()))
                    .append("\",\"examined\":").append(report.examined())
                    .append(",\"clean\":").append(report.clean())
                    .append(",\"findings\":[");
            for (int i = 0; i < report.findings().size(); i++) {
                HomeRepair.Finding f = report.findings().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"kind\":\"").append(f.kind())
                        .append("\",\"subject\":\"").append(esc(f.subject()))
                        .append("\",\"detail\":\"").append(esc(f.detail()))
                        .append("\",\"repair\":\"").append(esc(f.remedy()))
                        .append("\",\"repairable\":").append(f.repairable()).append('}');
            }
            sb.append(']');
            if (outcome != null) {
                sb.append(",\"repaired\":").append(outcome.repaired().size())
                        .append(",\"failed\":").append(outcome.failed().size())
                        .append(",\"wasDamaged\":").append(!outcome.before().clean());
            }
            return sb.append('}').toString();
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
