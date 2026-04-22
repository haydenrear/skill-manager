package com.hayden.testgraphsdk.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serialize/deserialize {@code Context[]} for the single {@code --context}
 * CLI arg.
 *
 * Wire format (inline or in a file):
 *   {"items":[{"nodeId":"a","data":{"k":"v"}}, ...]}
 *
 * Inline values pass as --context=<json>. File references pass as
 * --context=@<absolute-path>, because OS arg limits make inlining
 * large contexts fragile. Both forms round-trip through {@link #read}.
 */
public final class ContextSerde {

    private ContextSerde() {}

    public static String toJson(List<ContextItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            ContextItem it = items.get(i);
            sb.append("{\"nodeId\":").append(Json.quote(it.nodeId()));
            sb.append(",\"data\":{");
            int k = 0;
            for (var e : it.data().entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
            }
            sb.append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Parse the CLI-arg value — inline JSON or '@<path>' — into a Context[]. */
    public static List<ContextItem> read(String argValue) {
        if (argValue == null || argValue.isEmpty()) return List.of();
        String json;
        try {
            if (argValue.startsWith("@")) {
                json = Files.readString(Path.of(argValue.substring(1)));
            } else {
                json = argValue;
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to read --context payload", e);
        }
        return fromJson(json);
    }

    @SuppressWarnings("unchecked")
    public static List<ContextItem> fromJson(String json) {
        Object parsed = Json.parse(json);
        Map<String, Object> root = (Map<String, Object>) parsed;
        List<Map<String, Object>> items = Json.asObjectList(root.get("items"));
        List<ContextItem> out = new ArrayList<>(items.size());
        for (Map<String, Object> raw : items) {
            String nodeId = (String) raw.get("nodeId");
            Map<String, String> data = Json.asStringMap(raw.get("data"));
            out.add(new ContextItem(nodeId, data));
        }
        return out;
    }
}
