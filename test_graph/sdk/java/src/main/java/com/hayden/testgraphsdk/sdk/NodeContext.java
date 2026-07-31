package com.hayden.testgraphsdk.sdk;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Context passed by the Gradle plugin to a node invocation.
 *
 * CLI contract:
 *   --nodeId=<id>         currently executing node
 *   --runId=<id>          overall graph run id
 *   --reportDir=<path>    where to write envelopes/artifacts
 *   --input.<k>=<v>       typed inputs (may repeat)
 *   --context=<value>     upstream Context[] — inline JSON or '@<path>'
 */
public final class NodeContext {

    private final String nodeId;
    private final String runId;
    private final Path reportDir;
    private final Map<String, String> inputs;
    private final List<ContextItem> context;

    private NodeContext(
            String nodeId, String runId, Path reportDir,
            Map<String, String> inputs, List<ContextItem> context) {
        this.nodeId = nodeId;
        this.runId = runId;
        this.reportDir = reportDir;
        this.inputs = inputs;
        this.context = context;
    }

    public static NodeContext parse(String[] args) {
        String nodeId = null;
        String runId = null;
        Path reportDir = null;
        Map<String, String> inputs = new HashMap<>();
        String contextArg = null;
        for (String raw : args) {
            if (!raw.startsWith("--")) continue;
            int eq = raw.indexOf('=');
            if (eq < 0) continue;
            String key = raw.substring(2, eq);
            String value = raw.substring(eq + 1);
            switch (key) {
                case "nodeId" -> nodeId = value;
                case "runId" -> runId = value;
                case "reportDir" -> reportDir = Paths.get(value);
                case "context" -> contextArg = value;
                default -> {
                    if (key.startsWith("input.")) {
                        inputs.put(key.substring("input.".length()), value);
                    }
                }
            }
        }
        if (nodeId == null || reportDir == null) {
            throw new IllegalStateException(
                    "node context missing required --nodeId / --reportDir");
        }
        List<ContextItem> ctx = contextArg == null ? List.of() : ContextSerde.read(contextArg);
        return new NodeContext(
                nodeId, runId == null ? "local" : runId, reportDir, inputs, ctx);
    }

    public String nodeId() { return nodeId; }
    public String runId() { return runId; }
    public Path reportDir() { return reportDir; }
    public Optional<String> input(String key) { return Optional.ofNullable(inputs.get(key)); }

    /** Ordered upstream Context[] — one item per upstream node in execution order. */
    public List<ContextItem> context() { return context; }

    /** Look up a single value from an upstream node's published data. */
    public Optional<String> get(String upstreamNodeId, String key) {
        for (ContextItem it : context) {
            if (it.nodeId().equals(upstreamNodeId)) {
                return Optional.ofNullable(it.data().get(key));
            }
        }
        return Optional.empty();
    }

    /** Look up an entire upstream node's published data. */
    public Optional<ContextItem> item(String upstreamNodeId) {
        for (ContextItem it : context) {
            if (it.nodeId().equals(upstreamNodeId)) return Optional.of(it);
        }
        return Optional.empty();
    }
}
