///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 8 — the launch contract: what is on PATH, where the agent roots come
 * from, and which caches are shared.</b> Three properties of one command
 * ({@code exec --print-env}), so one node, so they are measured against one
 * environment rather than three.
 *
 * <h2>1. The PATH sanitizer misses foreign AGENT-HOME plugin bins</h2>
 *
 * <p>{@code exec} strips {@code <other home>/.skill-manager/bin/*} from the
 * inherited PATH — good — and does not strip
 * {@code <other home root>/.claude/plugins/**}{@code /bin}. Measured on a
 * developer machine, a foreign
 * {@code ~/.claude/plugins/cache/claude-plugins-official/jdtls-lsp/1.0.0/bin}
 * survived at PATH position 4, ahead of {@code /usr/bin}. The cause is
 * structural rather than incidental: {@code LaunchEnv.isForeignHomeBin} walks
 * ancestors looking for {@code looksLikeStoreRoot}, and {@code ~/.claude} has
 * neither a descriptor nor {@code installed/}+{@code skills/}, so it never
 * matches. The right predicate already exists in the same class —
 * {@code LaunchEnv.agentDirOwnedByAHome}, written for the agent-span re-anchor
 * — and the PATH sanitizer does not call it.
 *
 * <p><b>Vacuous-pass risk 1, and it is the whole check:</b> the planted entries
 * were never on the PATH handed to {@code exec}, so "not in the output" is
 * trivially true.
 * <br><b>Companion — mandatory:</b> both planted entries are asserted PRESENT
 * in the inherited PATH before either is asserted absent from the output.
 * Without this the node measures nothing.
 *
 * <p><b>Vacuous-pass risk 2:</b> the foreign root is not recognisable as a home
 * at all, in which case stripping it would be wrong anyway and the survival of
 * the agent-dir entry means nothing.
 * <br><b>Companion:</b> the STORE-bin entry must be stripped in the same run.
 * That proves the sanitizer ran AND recognised the foreign home, which is what
 * makes the agent-dir entry's survival a finding rather than a shrug.
 *
 * <p><b>Vacuous-pass risk 3:</b> reading PATH from the wrong process — the
 * ambient PATH and the computed PATH are different strings in the same log.
 * <br><b>Companion:</b> only the {@code ^PATH=} line of {@code --print-env}'s
 * output is parsed, by {@link OnboardingSupport#envValue}.
 *
 * <p>Related and cheap: {@code bootstrap-home.sh} carries a comment saying
 * {@code LaunchEnv} "prunes foreign-home bin/ directories by walking at most
 * three parents up". That bound was removed — the walk now goes to the
 * filesystem root. The comment and the code are asserted to agree.
 *
 * <h2>2. The agent roots follow the active home, with nothing exported</h2>
 *
 * <p>The regression guard for the fix that removed the {@code $HOME} fallback.
 * <b>Vacuous-pass risk:</b> passing because the harness had set the variables.
 * <br><b>Companion:</b> a plain {@code /usr/bin/env} is run through the SAME
 * environment mutation immediately before, and the four names must be absent
 * from its output. And the home under test is one whose parent is not
 * {@code $HOME}, so the {@code ~/.skill-manager → ~} special case cannot mask
 * the derivation.
 *
 * <h2>3. Caches are shared; homes are not</h2>
 *
 * <p>{@code UV_CACHE_DIR}, {@code PIP_CACHE_DIR} and {@code npm_config_cache}
 * must be identical across two different homes AND must follow {@code HOME}.
 * <b>Vacuous-pass risk:</b> two homes that both fell back to one hard-coded
 * default would compare equal.
 * <br><b>Companion:</b> the same three values are read again under a
 * redirected {@code HOME}, and they must MOVE. Equality alone proves nothing;
 * equality plus mobility proves the derivation.
 */
public class OnboardingLaunchEnv {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.launch.env")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.claude.mcp.config.readable")
            .tags("onboarding", "launch", "isolation")
            .timeout("900s");

    static final List<String> CACHE_VARS =
            List.of("UV_CACHE_DIR", "PIP_CACHE_DIR", "npm_config_cache");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path workspace = path(ctx, "onboarding.fixture.built", "workspace");
            Path srcHome = path(ctx, "onboarding.fixture.built", "srcHome");
            Path srcAgents = path(ctx, "onboarding.fixture.built", "srcAgents");
            Path fakeHome = path(ctx, "onboarding.fixture.built", "fakeHome");
            Path scriptsDir = path(ctx, "onboarding.fixture.built", "scriptsDir");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || workspace == null || home == null || srcHome == null) {
                return NodeResult.fail("onboarding.launch.env", "missing upstream context");
            }

            // --- build the FOREIGN home ---------------------------------------
            //
            // It must be recognisable as a home (a store with installed/ and
            // skills/) or stripping its bin would be wrong, and it must have a
            // populated agent-home plugin bin or there is nothing to leak.
            Path foreignRoot = workspace.resolve("foreign-home");
            Path foreignStore = foreignRoot.resolve(".skill-manager");
            Path foreignStoreBin = foreignStore.resolve("bin").resolve("cli");
            Path foreignPluginBin = foreignRoot.resolve(".claude").resolve("plugins")
                    .resolve("cache").resolve("ob-plugins").resolve("1.0.0").resolve("bin");
            Files.createDirectories(foreignStore.resolve("installed"));
            Files.createDirectories(foreignStore.resolve("skills").resolve("ob-foreign"));
            Files.writeString(foreignStore.resolve("skills").resolve("ob-foreign")
                    .resolve("SKILL.md"), "---\nname: ob-foreign\n---\n");
            Files.createDirectories(foreignStoreBin);
            Files.writeString(foreignStoreBin.resolve("ob-foreign-tool"), "#!/bin/sh\nexit 0\n");
            foreignStoreBin.resolve("ob-foreign-tool").toFile().setExecutable(true);
            Files.createDirectories(foreignPluginBin);
            Files.writeString(foreignPluginBin.resolve("ob-plugin-tool"), "#!/bin/sh\nexit 0\n");
            foreignPluginBin.resolve("ob-plugin-tool").toFile().setExecutable(true);

            String plantedPath = foreignStoreBin + java.io.File.pathSeparator
                    + foreignPluginBin + java.io.File.pathSeparator
                    + System.getenv().getOrDefault("PATH", "/usr/bin:/bin");

            // The mandatory companion: prove the plants ARE on the inherited
            // PATH, by asking a plain child what it sees. Without this the
            // absence assertions below measure nothing.
            ProcessRecord ambient = OnboardingSupport.plain(ctx, "ambient-path", proj, home, proj,
                    List.of("/usr/bin/env"));
            // env inherits the harness PATH, not the planted one, so the plant
            // is asserted against the string actually handed to exec instead —
            // which is the thing that matters and is checkable directly.
            boolean theForeignStoreBinIsOnThePathHandedToExec =
                    plantedPath.contains(foreignStoreBin.toString());
            boolean theForeignPluginBinIsOnThePathHandedToExec =
                    plantedPath.contains(foreignPluginBin.toString());

            ProcessRecord sanitized = OnboardingSupport.pinnedWith(ctx, "print-env-planted-path",
                    home, proj,
                    pb -> pb.environment().put("PATH", plantedPath),
                    "exec", "--print-env");
            String sanitizedLog = OnboardingSupport.log(ctx, sanitized);
            String computedPath = OnboardingSupport.envValue(sanitizedLog, "PATH");
            boolean theLaunchEnvPrintedAPath = sanitized.exitCode() == 0 && computedPath != null;

            List<String> computedEntries = computedPath == null ? List.of()
                    : List.of(computedPath.split(java.io.File.pathSeparator));
            boolean theForeignStoreBinWasStripped =
                    !computedEntries.contains(foreignStoreBin.toString());
            boolean theForeignAgentHomePluginBinWasStripped =
                    !computedEntries.contains(foreignPluginBin.toString());
            int pluginBinPosition = computedEntries.indexOf(foreignPluginBin.toString());

            // The stale comment, checked against the code it describes.
            String bootstrapText = scriptsDir == null ? ""
                    : OnboardingSupport.read(scriptsDir.resolve("bootstrap-home.sh"));
            boolean theScriptStillClaimsAThreeParentBound =
                    bootstrapText.contains("at most three parents up");
            boolean theScriptsCommentAgreesWithTheCode = !theScriptStillClaimsAThreeParentBound;

            // --- 2. the agent roots, derived with nothing exported ---------------
            ProcessRecord bareEnv = OnboardingSupport.plain(ctx, "env-with-agent-vars-unset",
                    proj, home, proj, List.of("/usr/bin/env"));
            // Run env through the same mutation the print-env call uses, so the
            // "they were unset" claim is about the same environment.
            ProcessRecord unsetProbe = OnboardingSupport.plainWithUnsetAgentVars(ctx,
                    "env-unset-probe", proj, home, proj, List.of("/usr/bin/env"));
            String unsetProbeLog = OnboardingSupport.log(ctx, unsetProbe);
            List<String> stillSet = new ArrayList<>();
            for (String v : SmEnv.AGENT_VARS) {
                if (OnboardingSupport.envValue(unsetProbeLog, v) != null) stillSet.add(v);
            }
            boolean theAgentVariablesWereActuallyUnsetForTheProbe = stillSet.isEmpty();

            ProcessRecord derived = OnboardingSupport.pinnedWith(ctx, "print-env-agent-unset",
                    home, proj, OnboardingSupport::unsetAgentVars, "exec", "--print-env");
            String derivedLog = OnboardingSupport.log(ctx, derived);
            String dClaude = OnboardingSupport.envValue(derivedLog, "CLAUDE_CONFIG_DIR");
            String dCodex = OnboardingSupport.envValue(derivedLog, "CODEX_HOME");
            String dGemini = OnboardingSupport.envValue(derivedLog, "GEMINI_HOME");
            // Compared by REAL path. On macOS the sandbox lives under
            // /var/folders/..., which is a symlink to /private/var/folders/...,
            // and the CLI prints the resolved form while the fixture holds the
            // unresolved one. A prefix comparison of the two strings is false
            // for a home that is in fact correct — measured, and it looked
            // exactly like the defect.
            Path projReal = proj.toRealPath();
            boolean theAgentRootsWereDerivedFromTheActiveHome = derived.exitCode() == 0
                    && dClaude != null && dCodex != null && dGemini != null
                    && under(dClaude, projReal) && under(dCodex, projReal)
                    && under(dGemini, projReal);

            // --- 3. caches shared, and shown to be derived rather than fixed ------
            ProcessRecord otherHome = OnboardingSupport.sm(ctx, "print-env-source-home", srcHome,
                    srcAgents == null ? srcHome : srcAgents, "exec", "--print-env");
            String otherLog = OnboardingSupport.log(ctx, otherHome);
            List<String> projCaches = new ArrayList<>();
            List<String> otherCaches = new ArrayList<>();
            for (String v : CACHE_VARS) {
                projCaches.add(String.valueOf(OnboardingSupport.envValue(sanitizedLog, v)));
                otherCaches.add(String.valueOf(OnboardingSupport.envValue(otherLog, v)));
            }
            boolean theCachesAreIdenticalAcrossTwoDifferentHomes =
                    projCaches.equals(otherCaches) && !projCaches.contains("null");

            // The mobility companion: under a different HOME they must MOVE.
            ProcessRecord moved = fakeHome == null ? null
                    : OnboardingSupport.pinnedWith(ctx, "print-env-redirected-home", home, proj,
                            pb -> {
                                pb.environment().put("HOME", fakeHome.toString());
                                pb.environment().put("JAVA_TOOL_OPTIONS",
                                        "-Duser.home=" + fakeHome);
                            },
                            "exec", "--print-env");
            List<String> movedCaches = new ArrayList<>();
            if (moved != null) {
                String movedLog = OnboardingSupport.log(ctx, moved);
                for (String v : CACHE_VARS) {
                    movedCaches.add(String.valueOf(OnboardingSupport.envValue(movedLog, v)));
                }
            }
            boolean theCachesFollowHomeRatherThanAHardCodedDefault =
                    moved != null && !movedCaches.isEmpty() && !movedCaches.equals(projCaches)
                            && movedCaches.stream().anyMatch(c -> c.startsWith(
                                    fakeHome.toString()));

            boolean pass = theForeignStoreBinIsOnThePathHandedToExec
                    && theForeignPluginBinIsOnThePathHandedToExec
                    && theLaunchEnvPrintedAPath
                    && theForeignStoreBinWasStripped
                    && theForeignAgentHomePluginBinWasStripped
                    && theScriptsCommentAgreesWithTheCode
                    && theAgentVariablesWereActuallyUnsetForTheProbe
                    && theAgentRootsWereDerivedFromTheActiveHome
                    && theCachesAreIdenticalAcrossTwoDifferentHomes
                    && theCachesFollowHomeRatherThanAHardCodedDefault;

            return (pass
                    ? NodeResult.pass("onboarding.launch.env")
                    : NodeResult.fail("onboarding.launch.env",
                            "storeBinStripped=" + theForeignStoreBinWasStripped
                                    + " pluginBinStripped=" + theForeignAgentHomePluginBinWasStripped
                                    + " pluginBinPosition=" + pluginBinPosition
                                    + " staleThreeParentComment="
                                    + theScriptStillClaimsAThreeParentBound
                                    + " agentVarsStillSet=" + stillSet
                                    + " derivedClaude=" + dClaude
                                    + " projCaches=" + projCaches
                                    + " otherCaches=" + otherCaches
                                    + " movedCaches=" + movedCaches))
                    .process(ambient).process(sanitized).process(bareEnv).process(unsetProbe)
                    .process(derived).process(otherHome)
                    .assertion("the_foreign_store_bin_was_on_the_path_handed_to_exec",
                            theForeignStoreBinIsOnThePathHandedToExec)
                    .assertion("the_foreign_agent_home_plugin_bin_was_on_the_path_handed_to_exec",
                            theForeignPluginBinIsOnThePathHandedToExec)
                    .assertion("exec_print_env_emitted_a_computed_path", theLaunchEnvPrintedAPath)
                    .assertion("the_foreign_store_bin_was_stripped", theForeignStoreBinWasStripped)
                    .assertion("the_foreign_agent_home_plugin_bin_was_stripped",
                            theForeignAgentHomePluginBinWasStripped)
                    .assertion("the_scripts_comment_about_the_ancestor_walk_matches_the_code",
                            theScriptsCommentAgreesWithTheCode)
                    .assertion("the_agent_variables_were_actually_unset_for_the_probe",
                            theAgentVariablesWereActuallyUnsetForTheProbe)
                    .assertion("the_agent_roots_were_derived_from_the_active_home",
                            theAgentRootsWereDerivedFromTheActiveHome)
                    .assertion("the_caches_are_identical_across_two_different_homes",
                            theCachesAreIdenticalAcrossTwoDifferentHomes)
                    .assertion("the_caches_follow_home_rather_than_a_hard_coded_default",
                            theCachesFollowHomeRatherThanAHardCodedDefault)
                    .metric("foreignPluginBinPathPosition", pluginBinPosition)
                    .metric("computedPathEntries", computedEntries.size())
                    .log("planted store bin: " + foreignStoreBin)
                    .log("planted plugin bin: " + foreignPluginBin)
                    .log("caches (project home): " + projCaches)
                    .log("caches (source home): " + otherCaches)
                    .log("caches (redirected HOME): " + movedCaches);
        });
    }

    /** Is {@code candidate} under {@code root}, comparing resolved real paths? */
    private static boolean under(String candidate, Path root) {
        try {
            return Path.of(candidate).toRealPath().startsWith(root);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
