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
 * <b>Step 7 — the MCP registration must land in the file the launched agent
 * reads.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code install} and {@code sync} report three writes:
 *
 * <pre>
 * ADDED  claude  (&lt;root&gt;/.claude.json)          → http://127.0.0.1:51717/mcp
 * ADDED  codex   (&lt;root&gt;/.codex/config.toml)    → …
 * ADDED  gemini  (&lt;root&gt;/.gemini/settings.json) → …
 * </pre>
 *
 * <p>Codex and gemini land in {@code $CODEX_HOME} and {@code $GEMINI_HOME} —
 * correct. Claude lands in {@code <agentHomeRoot>/.claude.json}, the project
 * ROOT, while every launcher shim and {@code exec --print-env} set
 * {@code CLAUDE_CONFIG_DIR=<root>/.claude}. The real {@code claude} binary
 * demonstrably reads {@code $CLAUDE_CONFIG_DIR/.claude.json}: the very same
 * install makes it create {@code <root>/.claude/.claude.json} during its
 * {@code marketplace-add} step, and the contents diverge —
 * {@code {"mcpServers":{"virtual-mcp-gateway":…}}} in the outer file,
 * {@code {"firstStartTime":…,"machineID":…}} with no {@code mcpServers} key in
 * the inner one. The sibling {@code .claude/settings.json} (marketplace) DOES
 * land correctly, so only the {@code .claude.json} target is wrong.
 *
 * <p>Net effect: a per-checkout agent gets no MCP tools even though the tool
 * printed {@code ADDED}. It is also the direct cause of the untracked
 * {@code .claude.json} that stops {@code wt new}.
 *
 * <h2>Assertion</h2>
 *
 * <p>For each of the three agents, the file the install wrote the
 * {@code virtual-mcp-gateway} entry into is the file that agent reads under the
 * launch env {@code exec --print-env} produces. For claude specifically: the
 * entry must be in {@code $CLAUDE_CONFIG_DIR/.claude.json}.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>Asserting "a file containing {@code virtual-mcp-gateway} exists
 *       somewhere under the project".</b> Both files are under the project. The
 *       bug is WHICH one.
 *       <br><b>Companion:</b> the expected path is DERIVED from
 *       {@code exec --print-env}'s {@code CLAUDE_CONFIG_DIR} at runtime, never
 *       hard-coded, and the node additionally asserts that the file parses and
 *       that {@code .mcpServers["virtual-mcp-gateway"].url} is non-empty. A
 *       path-existence check would pass on an empty {@code {}}.</li>
 *   <li><b>{@code .claude/.claude.json} not existing at all</b> — if the real
 *       {@code claude} binary is absent from the machine, "the entry is not in
 *       it" is true for the wrong reason.
 *       <br><b>Companion:</b> the fixture pre-creates it as {@code {}} if the
 *       binary did not, so the distinction under test is always KEY-presence
 *       and never FILE-presence. The node reports which of the two it saw.</li>
 *   <li><b>Testing only claude.</b> Codex and gemini pass today; a graph that
 *       omitted them would lose the regression guard on the half that works.
 *       <br><b>Companion:</b> all three are asserted with the same
 *       derived-path rule, and reported separately.</li>
 * </ol>
 */
public class OnboardingClaudeMcpConfigReadable {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.claude.mcp.config.readable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.leaves.work.tree.clean")
            .tags("onboarding", "mcp", "blocker")
            .timeout("600s");

    static final String GATEWAY = "virtual-mcp-gateway";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            String syncLog = ctx.get("onboarding.synced", "syncLog").orElse("");
            if (proj == null || home == null) {
                return NodeResult.fail("onboarding.claude.mcp.config.readable",
                        "missing upstream context");
            }

            // --- the launch env, which is where the expected paths come from ---
            ProcessRecord printEnv = OnboardingSupport.pinned(ctx, "print-env-for-mcp", home, proj,
                    "exec", "--print-env");
            String envLog = OnboardingSupport.log(ctx, printEnv);
            String claudeConfigDir = OnboardingSupport.envValue(envLog, "CLAUDE_CONFIG_DIR");
            String codexHome = OnboardingSupport.envValue(envLog, "CODEX_HOME");
            String geminiHome = OnboardingSupport.envValue(envLog, "GEMINI_HOME");
            boolean theLaunchEnvNamedAllThreeAgentRoots = printEnv.exitCode() == 0
                    && claudeConfigDir != null && codexHome != null && geminiHome != null;
            if (!theLaunchEnvNamedAllThreeAgentRoots) {
                return NodeResult.fail("onboarding.claude.mcp.config.readable",
                        "exec --print-env did not name the agent roots (exit "
                                + printEnv.exitCode() + ")")
                        .process(printEnv)
                        .assertion("the_launch_env_named_all_three_agent_roots", false);
            }

            // --- companion 2: the file must EXIST before key-presence means
            //     anything. The real claude binary creates it during
            //     marketplace-add; on a machine without claude it will not, and
            //     the node must not read that absence as evidence.
            Path claudeRead = Path.of(claudeConfigDir).resolve(".claude.json");
            boolean theClaudeBinaryCreatedTheFileItReads = Files.isRegularFile(claudeRead);
            if (!theClaudeBinaryCreatedTheFileItReads) {
                Files.createDirectories(claudeRead.getParent());
                Files.writeString(claudeRead, "{}\n");
            }

            // --- what the tool SAID it wrote ------------------------------------
            // BOTH logs. The `agent MCP configs / ADDED …` block is emitted by
            // the sync that actually registered them — which on this walk is the
            // `sync --skip-mcp` run by onboarding.projections.materialized. The
            // later project-scoped sync reconciles the same state and does not
            // repeat the block, so reading only that one reports "the tool never
            // said it wrote anything" about a run that did.
            // Three logs, because WHICH command registers the gateway is not
            // part of the contract and has already moved once. It used to be
            // the `sync --skip-mcp` that repaired the missing projection; with
            // the projection materialized at clone time, the bootstrap does the
            // agent-config work and neither sync mentions it. A node keyed to
            // one command reported "the tool never said it wrote anything"
            // about a run in which the entry was, in fact, correctly written.
            // The assertion is about the FILE; this precondition only has to
            // establish that some command in the walk claimed the write.
            String remedyLog = ctx.get("onboarding.projections.materialized", "remedyLog")
                    .orElse("");
            String bootstrapLog = ctx.get("onboarding.bootstrapped", "bootstrapLog").orElse("");
            // Each of these is a CAPTURED CONSOLE log, and the console is now a
            // short contract plus a `log:` path — both the CLI and the scripts
            // demote detail to a file. The `agent MCP configs / ADDED …` block is
            // detail, so following the footer is not optional: without it this
            // precondition reports "the tool never said it wrote anything" about
            // a run in which it said exactly that, one file over.
            String sync = String.join("\n",
                    OnboardingSupport.withNamedLog(syncLog),
                    OnboardingSupport.withNamedLog(remedyLog),
                    OnboardingSupport.withNamedLog(bootstrapLog));
            List<String> addedLines = new ArrayList<>();
            for (String line : sync.split("\n", -1)) {
                if (line.contains("ADDED") && line.contains(GATEWAY.substring(0, 7))) {
                    addedLines.add(line.strip());
                }
                if (line.strip().startsWith("ADDED")) addedLines.add(line.strip());
            }
            boolean theToolReportedWritingTheGatewayEntry =
                    sync.contains("ADDED") || sync.contains(GATEWAY);

            // --- where it actually is --------------------------------------------
            String claudeReadBody = OnboardingSupport.read(claudeRead);
            boolean theClaudeEntryIsInTheFileClaudeReads =
                    claudeReadBody.contains(GATEWAY) && hasNonEmptyUrl(claudeReadBody);

            Path claudeRoot = Path.of(claudeConfigDir).getParent();
            Path claudeOuter = claudeRoot == null ? null : claudeRoot.resolve(".claude.json");
            String outerBody = claudeOuter == null ? "" : OnboardingSupport.read(claudeOuter);
            boolean theEntryExistsOnlyInTheRootFile =
                    outerBody.contains(GATEWAY) && !claudeReadBody.contains(GATEWAY);

            Path codexConfig = Path.of(codexHome).resolve("config.toml");
            String codexBody = OnboardingSupport.read(codexConfig);
            boolean theCodexEntryIsInTheFileCodexReads =
                    codexBody.contains("virtual_mcp_gateway") || codexBody.contains(GATEWAY);

            Path geminiSettings = Path.of(geminiHome).resolve("settings.json");
            String geminiBody = OnboardingSupport.read(geminiSettings);
            boolean theGeminiEntryIsInTheFileGeminiReads = geminiBody.contains(GATEWAY);

            // The marketplace half DOES land correctly. Asserted so a fix that
            // moved the wrong file cannot break the right one silently.
            Path settings = Path.of(claudeConfigDir).resolve("settings.json");
            boolean theClaudeMarketplaceEntryIsInTheFileClaudeReads =
                    OnboardingSupport.read(settings).contains("extraKnownMarketplaces");

            boolean pass = theLaunchEnvNamedAllThreeAgentRoots
                    && theToolReportedWritingTheGatewayEntry
                    && theClaudeEntryIsInTheFileClaudeReads
                    && theCodexEntryIsInTheFileCodexReads
                    && theGeminiEntryIsInTheFileGeminiReads
                    && theClaudeMarketplaceEntryIsInTheFileClaudeReads;

            return (pass
                    ? NodeResult.pass("onboarding.claude.mcp.config.readable")
                    : NodeResult.fail("onboarding.claude.mcp.config.readable",
                            "CLAUDE_CONFIG_DIR=" + claudeConfigDir
                                    + " entryInReadFile=" + theClaudeEntryIsInTheFileClaudeReads
                                    + " entryOnlyInRootFile=" + theEntryExistsOnlyInTheRootFile
                                    + " rootFile=" + claudeOuter
                                    + " codex=" + theCodexEntryIsInTheFileCodexReads
                                    + " gemini=" + theGeminiEntryIsInTheFileGeminiReads))
                    .process(printEnv)
                    .assertion("the_launch_env_named_all_three_agent_roots",
                            theLaunchEnvNamedAllThreeAgentRoots)
                    .assertion("the_tool_reported_writing_the_gateway_entry",
                            theToolReportedWritingTheGatewayEntry)
                    .assertion("the_claude_gateway_entry_is_in_the_file_claude_reads",
                            theClaudeEntryIsInTheFileClaudeReads)
                    .assertion("the_codex_gateway_entry_is_in_the_file_codex_reads",
                            theCodexEntryIsInTheFileCodexReads)
                    .assertion("the_gemini_gateway_entry_is_in_the_file_gemini_reads",
                            theGeminiEntryIsInTheFileGeminiReads)
                    .assertion("the_claude_marketplace_entry_is_in_the_file_claude_reads",
                            theClaudeMarketplaceEntryIsInTheFileClaudeReads)
                    .metric("entryOnlyInAgentHomeRootFile", theEntryExistsOnlyInTheRootFile ? 1 : 0)
                    .log("claude reads: " + claudeRead
                            + (theClaudeBinaryCreatedTheFileItReads
                                    ? " (created by the real claude binary during marketplace-add"
                                            + " — that IS the evidence for where claude reads)"
                                    : " (absent; the fixture created it as {} so the distinction"
                                            + " under test is key-presence, not file-presence)"))
                    .log("agent-home root file: " + claudeOuter + " contains the entry: "
                            + outerBody.contains(GATEWAY))
                    .log("ADDED lines from sync: " + addedLines);
        });
    }

    /** {@code "url": "http://…"} with something after the scheme. */
    private static boolean hasNonEmptyUrl(String json) {
        int i = json.indexOf(GATEWAY);
        if (i < 0) return false;
        int u = json.indexOf("\"url\"", i);
        if (u < 0) return false;
        int q = json.indexOf('"', json.indexOf(':', u) + 1);
        if (q < 0) return false;
        int end = json.indexOf('"', q + 1);
        return end > q + 8;
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
