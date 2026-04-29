///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Assertion: {@code skill-manager onboard} wrote the
 * {@code virtual-mcp-gateway} entry into the per-run agent configs (NOT
 * the developer's real {@code ~/.claude.json} / {@code ~/.codex/config.toml}).
 *
 * <p>{@link OnboardCompleted} sets {@code HOME=<home>/agent-home} on the
 * subprocess so {@link System#getProperty(String) user.home} in the child
 * JVM resolves to that scoped dir. We read both files from there and
 * compare the URL field against {@code http://127.0.0.1:<gatewayPort>/mcp},
 * which is the canonical MCP endpoint the install path persists.
 *
 * <p>Failure modes this catches:
 *
 * <ul>
 *   <li>HOME override didn't propagate (configs land in real $HOME) →
 *       per-run files don't exist.</li>
 *   <li>install path silently dropped agent-sync → file exists but no
 *       {@code virtual-mcp-gateway} entry.</li>
 *   <li>Wrong gateway URL written (e.g. default {@code 51717} instead of
 *       the env.prepared port) → URL mismatch.</li>
 * </ul>
 */
public class OnboardAgentConfigsWritten {
    static final NodeSpec SPEC = NodeSpec.of("onboard.agent.configs.written")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboard.completed")
            .tags("onboard", "agent")
            .timeout("10s")
            .retries(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GATEWAY_ENTRY = "virtual-mcp-gateway";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String agentHome = ctx.get("onboard.completed", "agentHome").orElse(null);
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElse(null);
            if (agentHome == null || gatewayPort == null) {
                return NodeResult.fail("onboard.agent.configs.written", "missing upstream context");
            }
            String expectedUrl = "http://127.0.0.1:" + gatewayPort + "/mcp";
            Path agentRoot = Path.of(agentHome);

            ClaudeCheck claude = checkClaude(agentRoot.resolve(".claude.json"), expectedUrl);
            CodexCheck codex = checkCodex(agentRoot.resolve(".codex").resolve("config.toml"), expectedUrl);

            // We also explicitly assert the developer's real configs were
            // NOT touched during this run. The CLI subprocess inherited
            // HOME=<agentHome>, so writes against System.getProperty(
            // "user.home") landed in agentRoot. If that propagation
            // breaks in the future, we'd see entries appearing at the
            // parent JVM's user.home — flag that loudly.
            Path realHome = Path.of(System.getProperty("user.home"));
            boolean realHomeDistinct = !agentRoot.toAbsolutePath().equals(realHome.toAbsolutePath());

            boolean pass = claude.fileExists && claude.entryPresent && claude.urlMatches
                    && codex.fileExists && codex.entryPresent && codex.urlMatches
                    && realHomeDistinct;

            return (pass
                    ? NodeResult.pass("onboard.agent.configs.written")
                    : NodeResult.fail("onboard.agent.configs.written",
                            "claude{exists=" + claude.fileExists + " entry=" + claude.entryPresent
                                    + " urlOk=" + claude.urlMatches + " url=" + claude.url + "}"
                                    + " codex{exists=" + codex.fileExists + " entry=" + codex.entryPresent
                                    + " urlOk=" + codex.urlMatches + " url=" + codex.url + "}"
                                    + " realHomeDistinct=" + realHomeDistinct))
                    .assertion("claude_config_exists", claude.fileExists)
                    .assertion("claude_gateway_entry_present", claude.entryPresent)
                    .assertion("claude_gateway_url_matches", claude.urlMatches)
                    .assertion("codex_config_exists", codex.fileExists)
                    .assertion("codex_gateway_entry_present", codex.entryPresent)
                    .assertion("codex_gateway_url_matches", codex.urlMatches)
                    .assertion("real_home_untouched", realHomeDistinct);
        });
    }

    private static ClaudeCheck checkClaude(Path file, String expectedUrl) {
        ClaudeCheck c = new ClaudeCheck();
        c.fileExists = Files.isRegularFile(file);
        if (!c.fileExists) return c;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.readValue(file.toFile(), Map.class);
            Object servers = root == null ? null : root.get("mcpServers");
            if (!(servers instanceof Map<?, ?> sm)) return c;
            Object entry = sm.get(GATEWAY_ENTRY);
            if (!(entry instanceof Map<?, ?> em)) return c;
            c.entryPresent = true;
            Object url = em.get("url");
            c.url = url == null ? null : url.toString();
            c.urlMatches = expectedUrl.equals(c.url);
        } catch (Exception ignored) {
            // leave flags false
        }
        return c;
    }

    /**
     * Codex stores MCP entries as a TOML table. McpWriter writes:
     *
     * <pre>
     *   [mcp_servers.virtual_mcp_gateway]
     *   url = "http://127.0.0.1:&lt;port&gt;/mcp"
     * </pre>
     *
     * Substring-match within the relevant table is sufficient for the
     * assertion — we don't need a real TOML parser here.
     */
    private static CodexCheck checkCodex(Path file, String expectedUrl) {
        CodexCheck c = new CodexCheck();
        c.fileExists = Files.isRegularFile(file);
        if (!c.fileExists) return c;
        try {
            String body = Files.readString(file);
            String header = "[mcp_servers." + GATEWAY_ENTRY.replace('-', '_') + "]";
            int start = body.indexOf(header);
            if (start < 0) return c;
            c.entryPresent = true;
            // Slice from the table header to the next [section] or EOF so
            // we don't false-positive on a URL belonging to another table.
            int end = body.indexOf("\n[", start + header.length());
            String slice = end < 0 ? body.substring(start) : body.substring(start, end);
            String want = "url = \"" + expectedUrl + "\"";
            c.urlMatches = slice.contains(want);
            c.url = slice;
        } catch (Exception ignored) {
            // leave flags false
        }
        return c;
    }

    private static final class ClaudeCheck {
        boolean fileExists;
        boolean entryPresent;
        boolean urlMatches;
        String url;
    }

    private static final class CodexCheck {
        boolean fileExists;
        boolean entryPresent;
        boolean urlMatches;
        String url;
    }
}
