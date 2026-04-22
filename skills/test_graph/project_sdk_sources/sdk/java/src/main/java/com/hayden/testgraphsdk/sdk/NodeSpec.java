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

    /** Serialize to the spec JSON the plugin's describe-loader consumes. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"id\":").append(Json.quote(id));
        sb.append(",\"kind\":").append(Json.quote(kind.name().toLowerCase()));
        sb.append(",\"runtime\":\"jbang\"");
        sb.append(",\"dependsOn\":"); appendStringList(sb, dependsOn);
        sb.append(",\"tags\":"); appendStringList(sb, new ArrayList<>(tags));
        sb.append(",\"timeout\":").append(Json.quote(timeout));
        sb.append(",\"cacheable\":").append(cacheable);
        sb.append(",\"sideEffects\":"); appendStringList(sb, new ArrayList<>(sideEffects));
        sb.append(",\"inputs\":"); appendStringMap(sb, inputs);
        sb.append(",\"outputs\":"); appendStringMap(sb, outputs);
        sb.append(",\"reports\":{")
          .append("\"structuredJson\":").append(reportStructuredJson)
          .append(",\"junitXml\":").append(reportJunitXml)
          .append(",\"cucumber\":").append(reportCucumber)
          .append('}');
        sb.append('}');
        return sb.toString();
    }

    private static void appendStringList(StringBuilder sb, List<String> items) {
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(items.get(i)));
        }
        sb.append(']');
    }

    private static void appendStringMap(StringBuilder sb, Map<String, String> m) {
        sb.append('{');
        int i = 0;
        for (var e : m.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
        }
        sb.append('}');
    }
}
