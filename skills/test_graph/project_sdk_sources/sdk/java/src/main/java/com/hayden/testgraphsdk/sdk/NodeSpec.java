package com.hayden.testgraphsdk.sdk;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Self-declared node metadata. A node script builds a {@code NodeSpec}
 * and passes it to {@link Node#run(String[], NodeSpec, java.util.function.Function)}.
 * The same script is then the single source of truth: the Gradle plugin
 * invokes it with {@code --describe-out=<path>} to discover the spec, and
 * with full context args to execute the body.
 *
 * Runtime ({@code "jbang"}) is fixed for this SDK; Python's SDK emits
 * {@code "uv"}. Entry file paths are supplied by the discovering plugin —
 * the SDK has no reliable way to know its own source path.
 */
public final class NodeSpec {

    public enum Kind { TESTBED, FIXTURE, ACTION, ASSERTION, EVIDENCE, REPORT }

    private final String id;
    private Kind kind = Kind.ACTION;
    private final List<String> dependsOn = new ArrayList<>();
    private final Set<String> tags = new LinkedHashSet<>();
    private String timeout = "60s";
    private boolean cacheable = false;
    private final Set<String> sideEffects = new LinkedHashSet<>();
    private final Map<String, String> inputs = new LinkedHashMap<>();
    private final Map<String, String> outputs = new LinkedHashMap<>();
    private boolean reportStructuredJson = true;
    private boolean reportJunitXml = false;
    private boolean reportCucumber = false;

    private NodeSpec(String id) { this.id = id; }

    public static NodeSpec of(String id) { return new NodeSpec(id); }

    public NodeSpec kind(Kind k)                     { this.kind = k; return this; }
    public NodeSpec dependsOn(String... ids)         { dependsOn.addAll(Arrays.asList(ids)); return this; }
    public NodeSpec tags(String... t)                { tags.addAll(Arrays.asList(t)); return this; }
    public NodeSpec timeout(String v)                { this.timeout = v; return this; }
    public NodeSpec cacheable(boolean b)             { this.cacheable = b; return this; }
    public NodeSpec sideEffects(String... s)         { sideEffects.addAll(Arrays.asList(s)); return this; }
    public NodeSpec input(String name, String type)  { inputs.put(name, type); return this; }
    public NodeSpec output(String name, String type) { outputs.put(name, type); return this; }
    public NodeSpec junitXml()                       { this.reportJunitXml = true; return this; }
    public NodeSpec cucumber()                       { this.reportCucumber = true; return this; }

    public String id() { return id; }

    /**
     * Serialize to the spec JSON the plugin's describe-loader consumes.
     * Builds a {@link LinkedHashMap} (insertion-order preserved) and
     * lets {@link JsonMapper#MAPPER} handle escaping + indentation.
     */
    public String toJson() {
        Map<String, Object> reports = new LinkedHashMap<>();
        reports.put("structuredJson", reportStructuredJson);
        reports.put("junitXml", reportJunitXml);
        reports.put("cucumber", reportCucumber);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("kind", kind.name().toLowerCase());
        out.put("runtime", "jbang");
        out.put("dependsOn", dependsOn);
        out.put("tags", new ArrayList<>(tags));
        out.put("timeout", timeout);
        out.put("cacheable", cacheable);
        out.put("sideEffects", new ArrayList<>(sideEffects));
        out.put("inputs", inputs);
        out.put("outputs", outputs);
        out.put("reports", reports);

        try {
            return JsonMapper.MAPPER.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("failed to serialize NodeSpec for " + id, e);
        }
    }
}
