package com.hayden.testgraphsdk.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One upstream node's published data, as seen by a downstream node.
 *
 * The full upstream view is an ordered {@code List<ContextItem>} in
 * plan-execution order. Each node contributes exactly one ContextItem,
 * whose {@link #data()} is the map it published via
 * {@link NodeResult#publish(String, String)}.
 *
 * ContextItem is the data contract between nodes. NodeResult is the
 * reporting contract. Keep them separate so the graph can evolve its
 * reporting surface without breaking the data wire.
 */
public final class ContextItem {
    private final String nodeId;
    private final Map<String, String> data;

    public ContextItem(String nodeId, Map<String, String> data) {
        this.nodeId = nodeId;
        this.data = Map.copyOf(data);
    }

    public static ContextItem of(String nodeId) {
        return new ContextItem(nodeId, new LinkedHashMap<>());
    }

    public String nodeId() { return nodeId; }
    public Map<String, String> data() { return data; }
    public String get(String key) { return data.get(key); }
}
