package dev.skillmanager._lib.fakes;

import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitKindFilter;
import dev.skillmanager.resolve.Registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link Registry}. Builds a canned table of hits
 * keyed by name; {@link #lookup} returns matches respecting the
 * supplied {@link UnitKindFilter}.
 *
 * <p>Multiple entries under the same name are allowed — that's how
 * the test for {@link dev.skillmanager.resolve.ResolutionError.MultiKindCollision}
 * gets exercised (one skill-kind hit and one plugin-kind hit under
 * the same name).
 */
public final class FakeRegistry implements Registry {

    private final Map<String, List<Hit>> byName = new LinkedHashMap<>();

    public FakeRegistry add(String name, UnitKind kind, String version, String gitUrl, String gitRef) {
        byName.computeIfAbsent(name, k -> new ArrayList<>())
                .add(new Hit(name, kind, version, gitUrl, gitRef));
        return this;
    }

    @Override
    public List<Hit> lookup(String name, String version, UnitKindFilter filter) {
        List<Hit> all = byName.getOrDefault(name, List.of());
        List<Hit> out = new ArrayList<>();
        for (Hit h : all) {
            if (!filter.accepts(h.kind())) continue;
            if (version != null && !version.isBlank() && !version.equals(h.version())) continue;
            out.add(h);
        }
        return out;
    }
}
