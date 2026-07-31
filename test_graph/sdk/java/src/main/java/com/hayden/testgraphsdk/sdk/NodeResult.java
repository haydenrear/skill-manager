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
 * Reporting fields (status, assertions, artifacts, metrics, logs,
 * processes) are for the report aggregator. The {@code published}
 * map is this node's contribution to downstream Context[] — i.e.
 * the data a later node can read via {@code ctx.get(nodeId, key)}.
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
    private final List<ProcessRecord> processes = new ArrayList<>();
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

    /**
     * Attach a structured record for one subprocess this node spawned.
     * Mirrors {@link #assertion(String, boolean)} / {@link #metric(String, Number)}
     * — fluent, no hidden state, no implicit pass/fail. Pass/fail stays
     * the node author's call: some nodes want a non-zero exit code as
     * the success signal, so {@code .process()} only records facts.
     * Express the intended outcome via {@link #assertion(String, boolean)}.
     */
    public NodeResult process(ProcessRecord record) {
        processes.add(record);
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

    /**
     * Build the canonical envelope JSON.
     *
     * <p>Implementation is "build a {@link LinkedHashMap} and let
     * {@link JsonMapper#MAPPER} serialize it" — Jackson handles
     * escaping, null-suppression, indentation, and the embedded
     * {@link ProcessRecord} list. Insertion order is preserved by
     * LinkedHashMap, so the field order downstream tooling sees stays
     * stable across releases.
     */
    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", nodeId);
        out.put("status", status.wire());
        if (startedAt != null) out.put("startedAt", startedAt.toString());
        if (endedAt != null) out.put("endedAt", endedAt.toString());
        // Conditional puts for the optional fields — JsonMapper preserves
        // null elsewhere on purpose (ProcessRecord carries semantic nulls),
        // so suppression has to happen at the put-site here.
        if (failureMessage != null) out.put("failureMessage", failureMessage);
        if (errorStack != null) out.put("errorStack", errorStack);

        List<Map<String, Object>> assertionMaps = new ArrayList<>(assertions.size());
        for (Assertion a : assertions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.name);
            m.put("status", a.status.wire());
            assertionMaps.add(m);
        }
        out.put("assertions", assertionMaps);

        List<Map<String, Object>> artifactMaps = new ArrayList<>(artifacts.size());
        for (Artifact a : artifacts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type);
            m.put("path", a.path);
            artifactMaps.add(m);
        }
        out.put("artifacts", artifactMaps);

        List<Map<String, Object>> processMaps = new ArrayList<>(processes.size());
        for (ProcessRecord p : processes) processMaps.add(p.toMap());
        out.put("processes", processMaps);

        out.put("metrics", metrics);
        out.put("logs", logs);
        out.put("published", published);

        try {
            return JsonMapper.MAPPER.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("failed to serialize NodeResult for " + nodeId, e);
        }
    }

    private record Assertion(String name, NodeStatus status) {}
    private record Artifact(String type, String path) {}
}
