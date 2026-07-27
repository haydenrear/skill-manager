///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code home.runtime.json} resolves under the new root, and
 * {@code CLAUDE_CONFIG_DIR} lands inside the clone.
 *
 * <p>Two claims that are easy to conflate and must not be:
 *
 * <ul>
 *   <li><b>The stored form travels.</b> The descriptor written into the SOURCE
 *       home was carried by the copy. Its ON-DISK bytes must hold
 *       {@code $SKILL_MANAGER_HOME/...} tokens rather than either home's absolute
 *       path — that is what makes the same bytes describe the copy — while
 *       {@code home describe --json} read through the clone must print paths
 *       under the CLONE's root. Both directions are checked, because a
 *       descriptor that stored absolute paths and one that printed tokens are
 *       different bugs and each looks fine from the other side.</li>
 *   <li><b>{@code CLAUDE_CONFIG_DIR} is inside the clone.</b>
 *       {@code SKILL_MANAGER_HOME} alone does not isolate a home: Claude Code
 *       loads skills from its config dir, so a project-local home whose consumer
 *       still points Claude at {@code ~/.claude} is not isolated at all. The
 *       assertion is that the descriptor's value is under the clone's own root
 *       AND is not the developer's {@code ~/.claude} — the second half stated
 *       explicitly, because "is under the clone" would also be satisfied by a
 *       descriptor that omitted the field entirely and returned a default that
 *       happened to compare equal to nothing.</li>
 * </ul>
 */
public class HomeCloneDescriptorResolves {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.descriptor.resolves")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.cloned.into.project")
            .tags("home-clone", "descriptor")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String cloneStoreRaw = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
            String projectDirRaw = ctx.get("home.clone.fixture.built", "projectDir").orElse(null);
            if (cloneStoreRaw == null || fixture == null || projectDirRaw == null) {
                return NodeResult.fail("home.clone.descriptor.resolves", "missing upstream context");
            }
            Path cloneStore = Path.of(cloneStoreRaw);
            Path projectDir = Path.of(projectDirRaw);

            Path descriptorFile = cloneStore.resolve("home.runtime.json");
            String stored = HomeCloneSupport.read(descriptorFile);

            boolean descriptorCarried = Files.isRegularFile(descriptorFile) && !stored.isBlank();
            // Stored form: tokens, and no absolute path to EITHER home.
            boolean storedFormIsTokenized = stored.contains("$SKILL_MANAGER_HOME");
            boolean storedFormNamesNoHomeAbsolutely =
                    !stored.contains(fixture) && !stored.contains(cloneStoreRaw);

            // Printed form: resolved against the clone.
            ProcessRecord describe = HomeCloneSupport.sm(ctx, "describe-clone", cloneStoreRaw,
                    "home", "describe", "--json");
            String printed = HomeCloneSupport.log(ctx, "describe-clone");
            boolean describeSucceeded = describe.exitCode() == 0;

            String printedHomeRoot = HomeCloneSupport.jsonString(printed, "homeRoot");
            String printedStoreHome = HomeCloneSupport.jsonString(printed, "SKILL_MANAGER_HOME");
            String printedClaudeConfigDir = HomeCloneSupport.jsonString(printed, "CLAUDE_CONFIG_DIR");
            String printedClaudeHome = HomeCloneSupport.jsonString(printed, "CLAUDE_HOME");
            String printedCodexHome = HomeCloneSupport.jsonString(printed, "CODEX_HOME");
            String printedGeminiHome = HomeCloneSupport.jsonString(printed, "GEMINI_HOME");

            boolean printedFormResolvesUnderTheNewRoot =
                    printedHomeRoot.equals(projectDir.toString())
                            && printedStoreHome.equals(cloneStoreRaw)
                            && !printed.contains(fixture);

            Path realClaude = Path.of(System.getProperty("user.home"), ".claude");
            boolean claudeConfigDirIsInsideTheClone =
                    !printedClaudeConfigDir.isBlank()
                            && Path.of(printedClaudeConfigDir).startsWith(projectDir)
                            && !Path.of(printedClaudeConfigDir).equals(realClaude);
            // CLAUDE_HOME and CLAUDE_CONFIG_DIR carry ONE value by construction;
            // a consumer reading either must not be able to disagree.
            boolean claudeVarsCarryOneValue =
                    printedClaudeHome.equals(printedClaudeConfigDir);
            boolean everyAgentHomeIsInsideTheProject =
                    !printedCodexHome.isBlank() && !printedGeminiHome.isBlank()
                            && Path.of(printedCodexHome).startsWith(projectDir)
                            && Path.of(printedGeminiHome).startsWith(projectDir);

            boolean pass = descriptorCarried && storedFormIsTokenized
                    && storedFormNamesNoHomeAbsolutely && describeSucceeded
                    && printedFormResolvesUnderTheNewRoot && claudeConfigDirIsInsideTheClone
                    && claudeVarsCarryOneValue && everyAgentHomeIsInsideTheProject;
            return (pass
                    ? NodeResult.pass("home.clone.descriptor.resolves")
                    : NodeResult.fail("home.clone.descriptor.resolves",
                            "descriptorCarried=" + descriptorCarried
                                    + " storedFormIsTokenized=" + storedFormIsTokenized
                                    + " storedFormNamesNoHomeAbsolutely="
                                    + storedFormNamesNoHomeAbsolutely
                                    + " describeExit=" + describe.exitCode()
                                    + " homeRoot=" + printedHomeRoot
                                    + " SKILL_MANAGER_HOME=" + printedStoreHome
                                    + " CLAUDE_CONFIG_DIR=" + printedClaudeConfigDir
                                    + " CLAUDE_HOME=" + printedClaudeHome
                                    + " CODEX_HOME=" + printedCodexHome
                                    + " GEMINI_HOME=" + printedGeminiHome))
                    .process(describe)
                    .assertion("the_clone_carries_a_home_runtime_descriptor", descriptorCarried)
                    .assertion("the_stored_descriptor_is_tokenized", storedFormIsTokenized)
                    .assertion("the_stored_descriptor_names_neither_home_absolutely",
                            storedFormNamesNoHomeAbsolutely)
                    .assertion("home_describe_reads_the_clone_successfully", describeSucceeded)
                    .assertion("the_printed_descriptor_resolves_under_the_new_root",
                            printedFormResolvesUnderTheNewRoot)
                    .assertion("claude_config_dir_lands_inside_the_clone_not_the_real_home",
                            claudeConfigDirIsInsideTheClone)
                    .assertion("claude_home_and_claude_config_dir_carry_one_value",
                            claudeVarsCarryOneValue)
                    .assertion("codex_and_gemini_homes_land_inside_the_project",
                            everyAgentHomeIsInsideTheProject);
        });
    }
}
