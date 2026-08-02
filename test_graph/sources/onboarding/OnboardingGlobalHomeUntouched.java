///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java
//SOURCES ../tripwire/TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Last — the operator's four homes did not move, and the sibling config
 * files did not either.</b> A finalizer, so it runs even when a node above it
 * failed: this graph is expected to be red in places, and the isolation
 * invariant is the one property whose answer must not depend on that.
 *
 * <h2>Three sides, because none of them alone is enough</h2>
 *
 * <ol>
 *   <li><b>The sandbox's global home was never created.</b> Every child in this
 *       graph runs with {@code $HOME} redirected to the run's temp root, so
 *       {@code bootstrap-home.sh}'s {@code GLOBAL_HOME=$HOME/.skill-manager} —
 *       the home it refuses to write — is a path inside the sandbox. If
 *       anything had fallen back to "the global home", that directory would
 *       exist. A POSITIVE statement about where the writes went.</li>
 *   <li><b>The four real roots did not move</b>, by metadata diff against the
 *       baseline the fixture took before any of the walk ran.</li>
 *   <li><b>The agent CONFIG REGISTRATIONS are unchanged.</b> This is the
 *       side the tree walk cannot supply, and the reason it is here.</li>
 * </ol>
 *
 * <h2>Why the config files get their own check — a mistake, preserved</h2>
 *
 * <p>{@code TripwireSupport}'s tree walk covers {@code ~/.skill-manager} and the
 * {@code skills/} surface of each agent home. <b>{@code ~/.claude.json} is a
 * SIBLING of those roots, not a child of one</b>, so no walk rooted at them
 * reaches it — and it is exactly the file this product writes Claude MCP
 * registrations into. The hand-run eval's first isolation filter excluded it
 * BY NAME and would have missed a write to it. It also excluded two
 * {@code ~/.claude/skills} links because their TARGETS contained
 * {@code /tmp/} — filtering on target CONTENT, which would have hidden the
 * precise leak shape the tripwire exists to catch.
 *
 * <p>So the rule this node follows: <b>never filter on target content, and
 * never leave a surface skill-manager writes out of the watch.</b> What IS
 * filtered is stated as a scope in {@link TripwireSupport} — the surfaces the
 * product's own {@code Agent} contract declares — rather than as a growing list
 * of directories a live session was observed churning.
 *
 * <h2>The sensitivity proof, without which every zero above is meaningless</h2>
 *
 * <p>A diff that comes back empty is indistinguishable from a diff that could
 * not look, and this project has been misled by that zero four separate times.
 * So the same {@code collect}/{@code difference} pair is pointed at a DECOY
 * tree with writes planted in it, in the same run, and must report each one:
 * a new unit directory, a new symlink, and an in-place rewrite. An unmutated
 * control is asserted clean in the same run, so an over-eager oracle fails
 * here too.
 *
 * <h2>The narrowing is proved against the incident it must not hide</h2>
 *
 * <p>This oracle exists because a documented remedy, run in anger, repointed 24
 * of the operator's {@code ~/.claude/skills/<unit>} links at a foreign store.
 * Any narrowing of it therefore has to be shown incapable of hiding that, not
 * merely argued to be. So the decoy carries an AGENT HOME as well as a store,
 * and the retarget is planted in it: {@code .claude/skills/<unit>} is pointed
 * somewhere else and the narrowed walk must still report it. If it ever does
 * not, this node goes red and the narrowing is wrong.
 *
 * <p>The config half is proved the same way, and in both directions — every
 * registration shape it claims to watch is planted and must be DETECTED, and a
 * session counter beside them is moved and must NOT be. Either assertion alone
 * is satisfiable by a broken oracle: hashing the whole file passes the first
 * and fails the second, hashing nothing passes the second and fails the first.
 *
 * <p>{@code TripwireSupport} is reused rather than re-implemented: it is the
 * oracle {@code home-tripwire} already proves sensitive, and a second copy
 * could be killed here while the other stayed green.
 */
public class OnboardingGlobalHomeUntouched {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.global.home.untouched")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.wt.contract.lines")
            .tags("onboarding", "leak", "oracle", "finalizer")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixture = "onboarding.fixture.built";
            String sandboxGlobal = ctx.get(fixture, "sandboxGlobalHome").orElse(null);
            String baselineRaw = ctx.get(fixture, "leakBaseline").orElse(null);
            String configRaw = ctx.get(fixture, "configBaseline").orElse(null);
            String rootsRaw = ctx.get(fixture, "leakRoots").orElse(null);
            String workspaceRaw = ctx.get(fixture, "workspace").orElse(null);
            if (sandboxGlobal == null || baselineRaw == null || rootsRaw == null
                    || workspaceRaw == null || configRaw == null) {
                return NodeResult.fail("onboarding.global.home.untouched",
                        "missing upstream context");
            }

            // --- side one ------------------------------------------------------
            boolean theSandboxGlobalHomeWasNeverCreated = !Files.exists(Path.of(sandboxGlobal));

            // --- side two ------------------------------------------------------
            List<Path> roots = new ArrayList<>();
            for (String raw : rootsRaw.split(java.io.File.pathSeparator)) {
                if (!raw.isBlank()) roots.add(Path.of(raw));
            }
            Path baselineFile = Path.of(baselineRaw);
            boolean theBaselineIsReadable = Files.isRegularFile(baselineFile) && !roots.isEmpty();
            Path realHome = TripwireSupport.realHome();
            List<String> before = theBaselineIsReadable
                    ? TripwireSupport.readLines(baselineFile) : List.of();
            List<String> after = theBaselineIsReadable
                    ? TripwireSupport.collectAll(roots, realHome,
                            TripwireSupport.Fidelity.METADATA)
                    : List.of();
            List<String> differences = TripwireSupport.difference(before, after);
            // A worktree registered or removed inside one of the operator's own
            // repositories is git's bookkeeping, not a skill-manager write, and
            // this graph creates and removes one. TripwireSupport already knows
            // how to name those, so they are subtracted rather than tolerated by
            // a prefix rule.
            List<String> worktreeNoise = TripwireSupport.worktreeRegistrationChanges(differences);
            List<String> realDifferences = new ArrayList<>(differences);
            realDifferences.removeAll(worktreeNoise);
            boolean theOperatorsRealHomesDidNotMove =
                    theBaselineIsReadable && realDifferences.isEmpty();
            boolean theBaselineActuallyWatchedSomething = before.size() > 100;

            // --- side three: the config registrations, including the sibling ----
            List<String> configBefore = TripwireSupport.readLines(Path.of(configRaw));
            List<String> configAfter = TripwireSupport.ownedConfig(realHome);
            List<String> configChanges = new ArrayList<>();
            for (int i = 0; i < Math.min(configBefore.size(), configAfter.size()); i++) {
                if (!configBefore.get(i).equals(configAfter.get(i))) {
                    configChanges.add(configBefore.get(i) + "  ->  " + configAfter.get(i));
                }
            }
            boolean theAgentConfigFilesAreUnchanged =
                    configBefore.size() == configAfter.size() && configChanges.isEmpty();
            // The floor: the fingerprint has to name the file a root-scoped walk
            // cannot reach, or this side is watching only what side two already
            // covers.
            boolean theConfigCheckCoversTheSiblingClaudeJson =
                    configBefore.stream()
                            .anyMatch(l -> l.startsWith(TripwireSupport.CLAUDE_JSON_LABEL));

            // --- the sensitivity proof -----------------------------------------
            Path decoyRoot = Path.of(workspaceRaw).resolve("leak-decoy");
            Path decoyHome = decoyRoot.resolve(".skill-manager");
            Path decoySkills = decoyHome.resolve("skills");
            Files.createDirectories(decoySkills.resolve("existing"));
            Files.writeString(decoySkills.resolve("existing").resolve("SKILL.md"), "decoy v1\n");
            Files.writeString(decoyHome.resolve("home.runtime.json"), "{\"decoy\":true}\n");
            List<String> decoyBefore = TripwireSupport.collect(decoyHome, decoyRoot,
                    TripwireSupport.Fidelity.METADATA);

            boolean anUnchangedTreeReportsClean = TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();

            Files.createDirectories(decoySkills.resolve("leaked"));
            Files.writeString(decoySkills.resolve("leaked").resolve("SKILL.md"), "leaked\n");
            boolean aPlantedUnitIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();
            deleteTree(decoySkills.resolve("leaked"));

            Path link = decoySkills.resolve("linked");
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, Path.of("/nowhere/at/all"));
            boolean aPlantedSymlinkIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();
            Files.delete(link);

            Files.writeString(decoyHome.resolve("home.runtime.json"),
                    "{\"decoy\":true,\"rewritten\":true}\n");
            boolean anInPlaceRewriteIsDetected = !TripwireSupport.difference(decoyBefore,
                    TripwireSupport.collect(decoyHome, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();

            // --- THE INCIDENT, planted -----------------------------------------
            //
            // The reason this oracle exists: a documented remedy, run in anger,
            // repointed 24 of the operator's ~/.claude/skills/<unit> links at a
            // foreign store. The tree walk is narrower than it used to be — it
            // covers ~/.skill-manager whole and, inside an agent home, the
            // skills/ projection surface — so "the narrowing cannot hide the
            // incident" is a claim that has to be executed rather than argued.
            // Here it is executed: a decoy AGENT home, a link into a store, the
            // link retargeted, and the narrowed walk must still name it.
            Path decoyAgent = decoyRoot.resolve(".claude");
            Path decoyAgentSkills = decoyAgent.resolve("skills");
            Files.createDirectories(decoyAgentSkills);
            Path projected = decoyAgentSkills.resolve("ob-alpha");
            Files.deleteIfExists(projected);
            Files.createSymbolicLink(projected, decoySkills.resolve("existing"));
            List<String> agentBefore = TripwireSupport.collect(decoyAgent, decoyRoot,
                    TripwireSupport.Fidelity.METADATA);
            // The control for THIS decoy, in its own right: the retarget below
            // means nothing if the agent-home walk reports differences anyway.
            boolean anUnchangedAgentHomeReportsClean = TripwireSupport.difference(agentBefore,
                    TripwireSupport.collect(decoyAgent, decoyRoot,
                            TripwireSupport.Fidelity.METADATA)).isEmpty();
            Files.delete(projected);
            Files.createSymbolicLink(projected, Path.of("/some/other/home/skills/ob-alpha"));
            List<String> retargeted = TripwireSupport.difference(agentBefore,
                    TripwireSupport.collect(decoyAgent, decoyRoot,
                            TripwireSupport.Fidelity.METADATA));
            boolean aRepointedAgentSkillLinkIsDetected = !retargeted.isEmpty();

            // --- the config half's plants, in both directions --------------------
            //
            // The fingerprint reads the REGISTRATION BLOCKS rather than hashing
            // whole files, so it has to be shown to see every shape a
            // registration takes AND to ignore the counters that made the
            // whole-file version flaky. Either half alone is satisfiable by a
            // broken oracle: hashing everything passes the first and fails the
            // second, hashing nothing passes the second and fails the first.
            List<String> configPlantMisses = new ArrayList<>();
            List<String> configBase = plantConfig(decoyRoot, CLAUDE_JSON, MARKETPLACES);
            check(configPlantMisses, "a top-level mcpServers entry", true, configBase,
                    plantConfig(decoyRoot,
                            CLAUDE_JSON.replace("\"gateway\":{\"url\":\"http://127.0.0.1:1\"}",
                                    "\"gateway\":{\"url\":\"http://127.0.0.1:1\"},"
                                            + "\"planted\":{\"url\":\"http://x\"}"),
                            MARKETPLACES));
            check(configPlantMisses, "an extraKnownMarketplaces entry", true, configBase,
                    plantConfig(decoyRoot,
                            CLAUDE_JSON.replace("\"extraKnownMarketplaces\":{}",
                                    "\"extraKnownMarketplaces\":{\"planted\":{\"source\":\"/x\"}}"),
                            MARKETPLACES));
            check(configPlantMisses, "a PROJECT-scoped mcpServers entry", true, configBase,
                    plantConfig(decoyRoot,
                            CLAUDE_JSON.replace("\"mcpServers\":{},\"lastCost\":1",
                                    "\"mcpServers\":{\"planted\":{}},\"lastCost\":1"),
                            MARKETPLACES));
            check(configPlantMisses, "a plugin marketplace registration", true, configBase,
                    plantConfig(decoyRoot, CLAUDE_JSON,
                            MARKETPLACES.replace("{\"skill-manager\"",
                                    "{\"planted\":{\"source\":{\"source\":\"directory\","
                                            + "\"path\":\"/elsewhere\"}},\"skill-manager\"")));
            // …and the churn that made the whole-file version flaky. Each of
            // these is a write a live session makes to the SAME files while the
            // graph runs, and each must be invisible.
            check(configPlantMisses, "a session counter", false, configBase,
                    plantConfig(decoyRoot,
                            CLAUDE_JSON.replace("\"promptQueueUseCount\":40", "\"promptQueueUseCount\":41")
                                    .replace("\"lastCost\":1", "\"lastCost\":2"),
                            MARKETPLACES));
            check(configPlantMisses, "a project opened for the first time", false, configBase,
                    plantConfig(decoyRoot,
                            CLAUDE_JSON.replace("\"projects\":{",
                                    "\"projects\":{\"/newly-opened\":{\"lastCost\":3},"),
                            MARKETPLACES));
            check(configPlantMisses, "a marketplace lastUpdated stamp", false, configBase,
                    plantConfig(decoyRoot, CLAUDE_JSON,
                            MARKETPLACES.replace("2026-01-01T00:00:00.000Z",
                                    "2026-08-02T20:35:01.047Z")));
            check(configPlantMisses, "an unreadable .claude.json", true, configBase,
                    plantConfig(decoyRoot, "{not json at all", MARKETPLACES));
            boolean theConfigFingerprintSeesRegistrationsAndNotCounters =
                    configPlantMisses.isEmpty();

            boolean pass = theSandboxGlobalHomeWasNeverCreated && theBaselineIsReadable
                    && theOperatorsRealHomesDidNotMove && theBaselineActuallyWatchedSomething
                    && theAgentConfigFilesAreUnchanged
                    && theConfigCheckCoversTheSiblingClaudeJson
                    && anUnchangedTreeReportsClean && aPlantedUnitIsDetected
                    && aPlantedSymlinkIsDetected && anInPlaceRewriteIsDetected
                    && anUnchangedAgentHomeReportsClean && aRepointedAgentSkillLinkIsDetected
                    && theConfigFingerprintSeesRegistrationsAndNotCounters;

            return (pass
                    ? NodeResult.pass("onboarding.global.home.untouched")
                    : NodeResult.fail("onboarding.global.home.untouched",
                            "sandboxGlobalHomeAbsent=" + theSandboxGlobalHomeWasNeverCreated
                                    + " baselineEntries=" + before.size()
                                    + " differences=" + head(realDifferences)
                                    + " worktreeNoise=" + worktreeNoise.size()
                                    + " configChanges=" + configChanges
                                    + " control=" + anUnchangedTreeReportsClean
                                    + " m1=" + aPlantedUnitIsDetected
                                    + " m2=" + aPlantedSymlinkIsDetected
                                    + " m3=" + anInPlaceRewriteIsDetected
                                    + " agentControl=" + anUnchangedAgentHomeReportsClean
                                    + " m4=" + aRepointedAgentSkillLinkIsDetected
                                    + " configPlantMisses=" + configPlantMisses))
                    .assertion("the_walk_never_created_a_global_home",
                            theSandboxGlobalHomeWasNeverCreated)
                    .assertion("the_leak_baseline_is_readable", theBaselineIsReadable)
                    .assertion("the_leak_baseline_watched_a_non_trivial_tree",
                            theBaselineActuallyWatchedSomething)
                    .assertion("the_operators_real_agent_homes_did_not_move",
                            theOperatorsRealHomesDidNotMove)
                    .assertion("the_config_check_covers_the_sibling_claude_json_file",
                            theConfigCheckCoversTheSiblingClaudeJson)
                    .assertion("the_agent_config_registrations_are_unchanged",
                            theAgentConfigFilesAreUnchanged)
                    .assertion("an_unchanged_tree_reports_clean", anUnchangedTreeReportsClean)
                    .assertion("the_leak_oracle_detects_a_planted_unit_directory",
                            aPlantedUnitIsDetected)
                    .assertion("the_leak_oracle_detects_a_planted_symlink",
                            aPlantedSymlinkIsDetected)
                    .assertion("the_leak_oracle_detects_an_in_place_rewrite",
                            anInPlaceRewriteIsDetected)
                    .assertion("an_unchanged_agent_home_reports_clean",
                            anUnchangedAgentHomeReportsClean)
                    .assertion("the_leak_oracle_detects_a_repointed_agent_skill_link",
                            aRepointedAgentSkillLinkIsDetected)
                    .assertion("the_config_fingerprint_sees_registrations_and_not_counters",
                            theConfigFingerprintSeesRegistrationsAndNotCounters)
                    .metric("baselineEntries", before.size())
                    .metric("differencesFound", realDifferences.size())
                    .metric("worktreeRegistrationChanges", worktreeNoise.size())
                    .metric("configFilesChanged", configChanges.size())
                    .log("config changes: " + configChanges);
        });
    }

    /**
     * A decoy {@code ~/.claude.json} in the shape the real one has: a
     * registration block, a project map holding both a registration and the
     * session bookkeeping that lives beside it, and a top-level counter.
     *
     * <p>The plants below mutate ONE thing in it at a time, so each says which
     * of the two claims it is about — "this registration is seen" or "this
     * counter is not" — rather than comparing two files that differ in several
     * ways at once.
     */
    private static final String CLAUDE_JSON = "{"
            + "\"promptQueueUseCount\":40,"
            + "\"mcpServers\":{\"gateway\":{\"url\":\"http://127.0.0.1:1\"}},"
            + "\"extraKnownMarketplaces\":{},"
            + "\"projects\":{\"/w\":{\"mcpServers\":{},\"lastCost\":1}}"
            + "}";

    /** The same, for the plugin marketplace registration the harness stamps. */
    private static final String MARKETPLACES = "{\"skill-manager\":{"
            + "\"source\":{\"source\":\"directory\",\"path\":\"/p\"},"
            + "\"lastUpdated\":\"2026-01-01T00:00:00.000Z\"}}";

    /**
     * Write a decoy {@code .claude.json} and plugin registration, and return
     * {@link TripwireSupport#ownedConfig}'s reading of the decoy root.
     *
     * <p>Through the same function the real comparison uses, on a decoy shaped
     * like a real home. A plant proved against a private copy of the logic would
     * prove the copy.
     */
    private static List<String> plantConfig(Path decoyRoot, String claudeJson,
                                            String knownMarketplaces) throws java.io.IOException {
        Files.createDirectories(decoyRoot.resolve(".claude/plugins"));
        Files.writeString(decoyRoot.resolve(".claude.json"), claudeJson);
        Files.writeString(decoyRoot.resolve(".claude/plugins/known_marketplaces.json"),
                knownMarketplaces);
        return TripwireSupport.ownedConfig(decoyRoot);
    }

    /** Record a plant whose detection did not match what was claimed for it. */
    private static void check(List<String> misses, String what, boolean expectDetected,
                              List<String> before, List<String> after) {
        boolean detected = !before.equals(after);
        if (detected != expectDetected) {
            misses.add(what + (expectDetected ? " was NOT detected" : " WAS detected"));
        }
    }

    private static List<String> head(List<String> lines) {
        return lines.size() <= 20 ? lines : lines.subList(0, 20);
    }

    private static void deleteTree(Path root) throws java.io.IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // a leftover decoy entry is not a finding
                }
            });
        }
    }
}
