package dev.skillmanager._lib.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * Kind-agnostic description of a unit's dependencies and references.
 * Both {@link UnitFixtures} (in-memory) and the test_graph scaffolders
 * (on-disk) consume this so a single fixture description renders into
 * either layer's preferred form.
 *
 * <p>Dependency-free by design: importable from JBang without dragging
 * the rest of the test_lib onto its classpath.
 */
public final class DepSpec {

    public final List<String> cliSpecs;        // e.g. "pip:cowsay==6.0"
    public final List<String> mcpServers;       // e.g. "shared-mcp"
    public final List<String> references;       // e.g. "skill:hello", "plugin:foo"

    private DepSpec(Builder b) {
        this.cliSpecs = List.copyOf(b.cliSpecs);
        this.mcpServers = List.copyOf(b.mcpServers);
        this.references = List.copyOf(b.references);
    }

    public static Builder of() { return new Builder(); }

    public static DepSpec empty() { return of().build(); }

    public static final class Builder {
        private final List<String> cliSpecs = new ArrayList<>();
        private final List<String> mcpServers = new ArrayList<>();
        private final List<String> references = new ArrayList<>();

        public Builder cli(String spec) { cliSpecs.add(spec); return this; }
        public Builder mcp(String server) { mcpServers.add(server); return this; }
        public Builder ref(String coord) { references.add(coord); return this; }

        public DepSpec build() { return new DepSpec(this); }
    }
}
