package com.hayden.testgraphsdk.sdk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical per-node reporting envelope.
 *
 * Reporting fields (status, assertions, artifacts, metrics, logs) are
 * for the report aggregator. The {@code published} map is this node's
 * contribution to downstream Context[] — i.e. the data a later node
 * can read via {@code ctx.get(nodeId, key)}.
 *
 * Two surfaces, one object: report once, publish downstream.
 */
public final class NodeResult {

    private final String nodeId;
    private NodeStatus status;
    private String failureMessage;
    private String errorStack;
    private Instant startedAt;
    private Instant endedAt;
    private final List<Assertion> assertions = new ArrayList<>();
    private final List<Artifact> artifacts = new ArrayList<>();
    private final Map<String, Number> metrics = new LinkedHashMap<>();
    private final List<String> logs = new ArrayList<>();
    private final Map<String, String> published = new LinkedHashMap<>();

    private NodeResult(String nodeId, NodeStatus status) {
        this.nodeId = nodeId;
        this.status = status;
    }

    public static NodeResult pass(String nodeId) {
        return new NodeResult(nodeId, NodeStatus.PASSED);
    }

    public static NodeResult fail(String nodeId, String message) {
        NodeResult r = new NodeResult(nodeId, NodeStatus.FAILED);
        r.failureMessage = message;
        return r;
    }

    public static NodeResult error(String nodeId, Throwable t) {
        NodeResult r = new NodeResult(nodeId, NodeStatus.ERRORED);
        r.failureMessage = t.getMessage();
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        r.errorStack = sw.toString();
        return r;
    }

    public NodeResult assertion(String name, boolean ok) {
        assertions.add(new Assertion(name, ok ? NodeStatus.PASSED : NodeStatus.FAILED));
        if (!ok && status == NodeStatus.PASSED) status = NodeStatus.FAILED;
        return this;
    }

    public NodeResult artifact(String type, String path) {
        artifacts.add(new Artifact(type, path));
        return this;
    }

    public NodeResult metric(String name, Number value) {
        metrics.put(name, value);
        return this;
    }

    public NodeResult log(String line) {
        logs.add(line);
        return this;
    }

    /** Publish a value so downstream nodes can read it via ctx.get(...). */
    public NodeResult publish(String key, String value) {
        published.put(key, value);
        return this;
    }

    NodeResult startedAt(Instant t) { this.startedAt = t; return this; }
    NodeResult endedAt(Instant t) {
        this.endedAt = t;
        if (startedAt != null) {
            metrics.putIfAbsent("durationMs", t.toEpochMilli() - startedAt.toEpochMilli());
        }
        return this;
    }

    public NodeStatus status() { return status; }

    /** Project the published map into a ContextItem for downstream consumption. */
    public ContextItem toContextItem() {
        return new ContextItem(nodeId, new LinkedHashMap<>(published));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        kv(sb, "nodeId", nodeId, true);
        kvRaw(sb, "status", Json.quote(status.wire()), false);
        if (startedAt != null) kvRaw(sb, "startedAt", Json.quote(startedAt.toString()), false);
        if (endedAt != null) kvRaw(sb, "endedAt", Json.quote(endedAt.toString()), false);
        if (failureMessage != null) kv(sb, "failureMessage", failureMessage, false);
        if (errorStack != null) kv(sb, "errorStack", errorStack, false);

        sb.append(",\"assertions\":[");
        for (int i = 0; i < assertions.size(); i++) {
            if (i > 0) sb.append(',');
            Assertion a = assertions.get(i);
            sb.append("{\"name\":").append(Json.quote(a.name))
              .append(",\"status\":").append(Json.quote(a.status.wire())).append('}');
        }
        sb.append(']');

        sb.append(",\"artifacts\":[");
        for (int i = 0; i < artifacts.size(); i++) {
            if (i > 0) sb.append(',');
            Artifact a = artifacts.get(i);
            sb.append("{\"type\":").append(Json.quote(a.type))
              .append(",\"path\":").append(Json.quote(a.path)).append('}');
        }
        sb.append(']');

        sb.append(",\"metrics\":{");
        int i = 0;
        for (var e : metrics.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(Json.quote(e.getKey())).append(':').append(e.getValue().toString());
        }
        sb.append('}');

        sb.append(",\"logs\":[");
        for (int j = 0; j < logs.size(); j++) {
            if (j > 0) sb.append(',');
            sb.append(Json.quote(logs.get(j)));
        }
        sb.append(']');

        sb.append(",\"published\":{");
        int k = 0;
        for (var e : published.entrySet()) {
            if (k++ > 0) sb.append(',');
            sb.append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
        }
        sb.append('}');

        sb.append('}');
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v, boolean first) {
        if (!first) sb.append(',');
        sb.append(Json.quote(k)).append(':').append(Json.quote(v));
    }

    private static void kvRaw(StringBuilder sb, String k, String rawValue, boolean first) {
        if (!first) sb.append(',');
        sb.append(Json.quote(k)).append(':').append(rawValue);
    }

    private record Assertion(String name, NodeStatus status) {}
    private record Artifact(String type, String path) {}
}
