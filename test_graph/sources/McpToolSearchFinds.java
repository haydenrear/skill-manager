///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Calls the gateway's semantic-search tool ({@code search_tools}) through
 * {@code skill-manager gateway search "echo"} and asserts the echo tool
 * surfaces at a non-zero score. This exercises the spaCy-backed matcher
 * that fronts the registry.
 */
public class McpToolSearchFinds {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.search.finds")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.deployed")
            .tags("mcp", "search", "spacy")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || gatewayUrl == null) {
                return NodeResult.fail("mcp.tool.search.finds", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "search", "echo",
                    "--gateway", gatewayUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int rc = p.waitFor();
            String body = out.toString();
            boolean echoHit = body.contains("\"echo\"") || body.contains("tool_name");
            boolean hasMatches = body.contains("\"matches\"");

            return (rc == 0 && echoHit && hasMatches
                    ? NodeResult.pass("mcp.tool.search.finds")
                    : NodeResult.fail("mcp.tool.search.finds",
                            "rc=" + rc + " echoHit=" + echoHit + " hasMatches=" + hasMatches))
                    .assertion("search_ok", rc == 0)
                    .assertion("matches_array_present", hasMatches)
                    .assertion("echo_in_results", echoHit)
                    .metric("outputBytes", body.length());
        });
    }
}
