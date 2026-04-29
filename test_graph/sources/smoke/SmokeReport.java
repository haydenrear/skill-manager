///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads every {@code envelope/*.json} in the current run dir, including this
 * node's own (the plugin has written it by the time the report body runs
 * only for previous nodes; we emit ours at the end too, but skip self in
 * the summary). Writes a markdown report to
 * {@code <runDir>/smoke-report.md} with a pass/fail line per node plus
 * every assertion. Handy for humans; the JSON envelopes remain the
 * canonical record for the aggregator.
 */
public class SmokeReport {
    static final NodeSpec SPEC = NodeSpec.of("smoke.report")
            .kind(NodeSpec.Kind.REPORT)
            .dependsOn(
                    "transitive.clis.present",
                    "mcp.tool.search.finds",
                    "mcp.tool.invoked",
                    "echo.http.redeployed",
                    "agent.configs.correct",
                    "agent.skill.symlinks",
                    "search.finds",
                    "mcp.tools.visible",
                    "hello.installed",
                    "ownership.recorded",
                    "semver.enforced",
                    "immutability.enforced")
            .tags("report")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path envelopeDir = ctx.reportDir().resolve("envelope");
            if (!Files.isDirectory(envelopeDir)) {
                return NodeResult.fail("smoke.report", "no envelope dir at " + envelopeDir);
            }

            ObjectMapper json = new ObjectMapper();
            record Row(String nodeId, String status, List<String> assertions, String failureMessage) {}
            List<Row> rows = new ArrayList<>();
            int total = 0, passed = 0, failed = 0, errored = 0, assertionsPassed = 0, assertionsFailed = 0;

            try (Stream<Path> files = Files.list(envelopeDir)) {
                List<Path> sorted = files
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path p : sorted) {
                    JsonNode env = json.readTree(p.toFile());
                    String nodeId = env.path("nodeId").asText();
                    if (nodeId.equals("smoke.report")) continue;
                    String status = env.path("status").asText();
                    String failure = env.path("failureMessage").asText("");
                    List<String> asserts = new ArrayList<>();
                    for (JsonNode a : env.path("assertions")) {
                        String name = a.path("name").asText();
                        String s = a.path("status").asText();
                        asserts.add((s.equals("passed") ? "✓" : "✗") + " " + name);
                        if (s.equals("passed")) assertionsPassed++; else assertionsFailed++;
                    }
                    rows.add(new Row(nodeId, status, asserts, failure));
                    total++;
                    switch (status) {
                        case "passed" -> passed++;
                        case "failed" -> failed++;
                        case "errored" -> errored++;
                    }
                }
            }

            StringBuilder md = new StringBuilder();
            md.append("# skill-manager smoke graph — run ").append(ctx.runId()).append("\n\n");
            md.append("| nodes | passed | failed | errored | assertions (pass/fail) |\n");
            md.append("|------:|-------:|-------:|--------:|-----------------------:|\n");
            md.append("| ").append(total)
                    .append(" | ").append(passed)
                    .append(" | ").append(failed)
                    .append(" | ").append(errored)
                    .append(" | ").append(assertionsPassed).append(" / ").append(assertionsFailed)
                    .append(" |\n\n");

            for (Row row : rows) {
                String marker = switch (row.status) {
                    case "passed" -> "✅";
                    case "failed" -> "❌";
                    case "errored" -> "💥";
                    default -> "❓";
                };
                md.append("### ").append(marker).append(" ").append(row.nodeId).append("\n\n");
                if (!row.failureMessage.isEmpty()) {
                    md.append("_").append(row.failureMessage).append("_\n\n");
                }
                for (String a : row.assertions) md.append("- ").append(a).append("\n");
                md.append("\n");
            }

            Path reportPath = ctx.reportDir().resolve("smoke-report.md");
            Files.writeString(reportPath, md.toString());

            boolean allGreen = failed == 0 && errored == 0 && assertionsFailed == 0;
            return (allGreen
                    ? NodeResult.pass("smoke.report")
                    : NodeResult.fail("smoke.report",
                            "failed=" + failed + " errored=" + errored + " failed_asserts=" + assertionsFailed))
                    .assertion("report_written", Files.isRegularFile(reportPath))
                    .assertion("no_failed_nodes", failed == 0)
                    .assertion("no_errored_nodes", errored == 0)
                    .assertion("no_failed_assertions", assertionsFailed == 0)
                    .metric("total_nodes", total)
                    .metric("passed_nodes", passed)
                    .metric("failed_nodes", failed)
                    .metric("errored_nodes", errored)
                    .metric("passed_assertions", assertionsPassed)
                    .metric("failed_assertions", assertionsFailed);
        });
    }
}
