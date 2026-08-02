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
import java.security.MessageDigest;
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
 *   <li><b>The four agent CONFIG FILES are byte-identical.</b> This is the
 *       side the tree walk cannot supply, and the reason it is here.</li>
 * </ol>
 *
 * <h2>Why the config files get their own check — a mistake, preserved</h2>
 *
 * <p>{@code TripwireSupport} watches {@code ~/.skill-manager}, {@code ~/.claude},
 * {@code ~/.codex} and {@code ~/.gemini}. <b>{@code ~/.claude.json} is a
 * SIBLING of those roots, not a child of one</b>, so no walk rooted at them
 * reaches it — and it is exactly the file this product writes Claude MCP
 * registrations into. The hand-run eval's first isolation filter excluded it
 * BY NAME and would have missed a write to it. It also excluded two
 * {@code ~/.claude/skills} links because their TARGETS contained
 * {@code /tmp/} — filtering on target CONTENT, which would have hidden the
 * precise leak shape the tripwire exists to catch.
 *
 * <p>So the rule this node follows: <b>filter on path prefixes of known-volatile
 * directories only</b> — never on target content, and never on the agent config
 * files. Those are hashed instead.
 *
 * <h2>The sensitivity proof, without which every zero above is meaningless</h2>
 *
 * <p>A diff that comes back empty is indistinguishable from a diff that could
 * not look, and this project has been misled by that zero four separate times.
 * So the same {@code collect}/{@code difference} pair is pointed at a DECOY
 * tree with writes planted in it, in the same run, and must report each one:
 * a new unit directory, a new symlink, and an in-place rewrite. An unmutated
 * control is asserted clean in the same run, so an over-eager oracle fails
 * here too. The config-hash half carries its own plant.
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

            // --- side three: the four config files, including the sibling ------
            List<String> configBefore = TripwireSupport.readLines(Path.of(configRaw));
            List<String> configAfter = configHashes(realHome);
            List<String> configChanges = new ArrayList<>();
            for (int i = 0; i < Math.min(configBefore.size(), configAfter.size()); i++) {
                if (!configBefore.get(i).equals(configAfter.get(i))) {
                    configChanges.add(configBefore.get(i) + "  ->  " + configAfter.get(i));
                }
            }
            boolean theAgentConfigFilesAreUnchanged =
                    configBefore.size() == configAfter.size() && configChanges.isEmpty();
            // The floor: the hash list has to name the file a root-scoped walk
            // cannot reach, or this side is watching only what side two already
            // covers.
            boolean theConfigCheckCoversTheSiblingClaudeJson =
                    configBefore.stream().anyMatch(l -> l.startsWith("/.claude.json\t"));

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

            // The config-hash half's own plant: a decoy .claude.json, rewritten.
            Path decoyConfig = decoyRoot.resolve(".claude.json");
            Files.writeString(decoyConfig, "{\"mcpServers\":{}}\n");
            String h1 = digest(decoyConfig);
            Files.writeString(decoyConfig,
                    "{\"mcpServers\":{\"planted\":{\"url\":\"http://x\"}}}\n");
            boolean theConfigHashDetectsARewrite = !h1.equals(digest(decoyConfig));

            boolean pass = theSandboxGlobalHomeWasNeverCreated && theBaselineIsReadable
                    && theOperatorsRealHomesDidNotMove && theBaselineActuallyWatchedSomething
                    && theAgentConfigFilesAreUnchanged
                    && theConfigCheckCoversTheSiblingClaudeJson
                    && anUnchangedTreeReportsClean && aPlantedUnitIsDetected
                    && aPlantedSymlinkIsDetected && anInPlaceRewriteIsDetected
                    && theConfigHashDetectsARewrite;

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
                                    + " m4=" + theConfigHashDetectsARewrite))
                    .assertion("the_walk_never_created_a_global_home",
                            theSandboxGlobalHomeWasNeverCreated)
                    .assertion("the_leak_baseline_is_readable", theBaselineIsReadable)
                    .assertion("the_leak_baseline_watched_a_non_trivial_tree",
                            theBaselineActuallyWatchedSomething)
                    .assertion("the_operators_real_agent_homes_did_not_move",
                            theOperatorsRealHomesDidNotMove)
                    .assertion("the_config_check_covers_the_sibling_claude_json_file",
                            theConfigCheckCoversTheSiblingClaudeJson)
                    .assertion("the_four_agent_config_files_are_byte_identical",
                            theAgentConfigFilesAreUnchanged)
                    .assertion("an_unchanged_tree_reports_clean", anUnchangedTreeReportsClean)
                    .assertion("the_leak_oracle_detects_a_planted_unit_directory",
                            aPlantedUnitIsDetected)
                    .assertion("the_leak_oracle_detects_a_planted_symlink",
                            aPlantedSymlinkIsDetected)
                    .assertion("the_leak_oracle_detects_an_in_place_rewrite",
                            anInPlaceRewriteIsDetected)
                    .assertion("the_config_hash_detects_a_rewrite", theConfigHashDetectsARewrite)
                    .metric("baselineEntries", before.size())
                    .metric("differencesFound", realDifferences.size())
                    .metric("worktreeRegistrationChanges", worktreeNoise.size())
                    .metric("configFilesChanged", configChanges.size())
                    .log("config changes: " + configChanges);
        });
    }

    private static List<String> configHashes(Path realHome) {
        List<String> out = new ArrayList<>();
        for (String rel : List.of(".claude.json", ".codex/config.toml",
                ".gemini/settings.json", ".claude/settings.json")) {
            out.add("/" + rel + "\t" + digest(realHome.resolve(rel)));
        }
        return out;
    }

    private static String digest(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "ABSENT";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "UNREADABLE:" + e.getClass().getSimpleName();
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
