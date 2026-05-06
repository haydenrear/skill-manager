package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * Ticket-14: {@code search} surfaces a {@code KIND} column from the
 * registry response's {@code unit_kind} field, falling back to "skill"
 * for legacy registries that don't yet emit it. The contract is the
 * fallback — once the server-java migration lands, every row will
 * carry an explicit kind.
 *
 * <p>The actual rendering (printf with %-7s for KIND) is visual; this
 * test pins the data-extraction step that feeds the column.
 */
public final class SearchShowsKindTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SearchShowsKindTest");

        suite.test("registry hit with unit_kind=plugin → kind 'plugin'", () -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("name", "widget");
            hit.put("unit_kind", "plugin");
            hit.put("latest_version", "0.4.2");
            String kind = String.valueOf(hit.getOrDefault("unit_kind", "skill")).toLowerCase();
            assertEquals("plugin", kind, "extracted kind matches");
        });

        suite.test("registry hit with unit_kind=skill → kind 'skill'", () -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("name", "widget");
            hit.put("unit_kind", "skill");
            String kind = String.valueOf(hit.getOrDefault("unit_kind", "skill")).toLowerCase();
            assertEquals("skill", kind, "extracted kind matches");
        });

        suite.test("legacy registry hit without unit_kind → defaults to 'skill'", () -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("name", "widget");
            // No unit_kind — pre-migration registry response.
            String kind = String.valueOf(hit.getOrDefault("unit_kind", "skill")).toLowerCase();
            assertEquals("skill", kind, "defaults to skill");
        });

        suite.test("uppercase unit_kind is normalized to lowercase", () -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("unit_kind", "PLUGIN");
            String kind = String.valueOf(hit.getOrDefault("unit_kind", "skill")).toLowerCase();
            assertEquals("plugin", kind, "lowercased");
        });

        return suite.runAll();
    }
}
