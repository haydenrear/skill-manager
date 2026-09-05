package dev.skillmanager.commands;

import dev.skillmanager.effects.UnitReadProblemReporter;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.resolve.UnitEdgeGraph;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "deps",
        description = "Show the transitive dependency tree of an installed unit (skill or plugin). "
                + "For plugins, the tree includes both plugin-level deps and every contained "
                + "skill's deps (unioned at parse time). With --who-imports, walk the edges "
                + "BACKWARDS instead: what in this home depends on the named unit, across both "
                + "`skill-imports` (addressed by name) and manifest references (addressed by "
                + "coordinate), transitively and without looping.")
public final class DepsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Unit name — skill or plugin (omit for all installed)")
    String name;

    @Option(names = "--cli", description = "Include CLI deps")
    boolean cli;

    @Option(names = "--mcp", description = "Include MCP deps")
    boolean mcp;

    @Option(names = "--who-imports",
            description = "Reverse the question: what in this home depends on <name>? "
                    + "Covers both edge mechanisms, follows the whole chain, and terminates "
                    + "on cycles. Recomputed from the unit trees on every run — nothing is "
                    + "indexed, because an index copied with a home describes the home it "
                    + "was built in.")
    boolean whoImports;

    @Option(names = "--direct-only",
            description = "With --who-imports, list only units that name <name> themselves, "
                    + "not the ones that reach it through another unit.")
    boolean directOnly;

    private final SkillStore store;

    public DepsCommand() {
        this(SkillStore.defaultStore());
    }

    public DepsCommand(SkillStore store) {
        this.store = store;
    }

    @Override
    public Integer call() throws Exception {
        store.init();
        if (whoImports) return renderWhoImports();
        if (name != null) {
            AgentUnit u = store.loadUnit(name).orElse(null);
            if (u == null) {
                System.err.println("unit not found: " + name);
                return 1;
            }
            render(store, u, "", new HashSet<>());
        } else {
            var listed = store.listInstalledUnits();
            UnitReadProblemReporter.render(store, listed.problems(), false);
            for (AgentUnit u : listed.units()) render(store, u, "", new HashSet<>());
        }
        return 0;
    }

    /**
     * The reverse edge, printed for someone about to change a unit.
     *
     * <p>Two mechanisms reach one unit by two different keys, and neither is
     * recorded anywhere on disk, so this recomputes from the unit trees. The
     * output names the FILE carrying each edge, because the question is
     * usually asked by whoever is about to break that line.
     */
    private Integer renderWhoImports() {
        if (name == null) {
            System.err.println("--who-imports needs a unit name: "
                    + "skill-manager deps --who-imports <name>");
            return 2;
        }
        UnitEdgeGraph graph = UnitEdgeGraph.of(store);
        if (!graph.nodes().containsKey(name)) {
            // NOT an error. "Nothing here is called that" and "nothing here
            // imports it" are different answers, and a caller deciding whether
            // a rename is safe needs to be able to tell them apart.
            System.out.println(name + " is not installed in this home ("
                    + store.root() + ")");
            System.out.println("  " + graph.nodes().size() + " unit(s) scanned; "
                    + "no edges can point at a unit that is not here");
            return 1;
        }

        List<UnitEdgeGraph.Edge> direct = graph.directImporters(name);
        List<String> transitive = graph.transitiveImporters(name);
        Set<String> directNames = new java.util.TreeSet<>();
        for (UnitEdgeGraph.Edge e : direct) directNames.add(e.from());

        System.out.println("what imports " + name + " in " + store.root());
        System.out.println();
        if (direct.isEmpty()) {
            System.out.println("  nothing imports it directly");
        } else {
            System.out.println("  direct (" + directNames.size() + "):");
            String last = null;
            for (UnitEdgeGraph.Edge e : direct) {
                if (!e.from().equals(last)) {
                    System.out.println("    " + e.from());
                    last = e.from();
                }
                System.out.println("      " + label(e.mechanism()) + "  "
                        + relative(e.source()) + spelledAs(e));
            }
        }

        if (!directOnly) {
            List<String> indirect = new ArrayList<>(transitive);
            indirect.removeAll(directNames);
            System.out.println();
            if (indirect.isEmpty()) {
                System.out.println("  reaches it only directly — "
                        + transitive.size() + " unit(s) in total");
            } else {
                System.out.println("  through another unit (" + indirect.size() + "):");
                for (String n : indirect) System.out.println("    " + n);
                System.out.println();
                System.out.println("  " + transitive.size() + " unit(s) in total");
            }
        }

        if (!graph.unresolvedCoordinates().isEmpty()) {
            // Reported, never guessed. A coordinate whose unit is not installed
            // here cannot be turned into a name -- the repository name is not
            // the unit name (github:haydenrear/skill-manager-skill installs
            // `skill-manager`), and the join lives in installed/<name>.json.
            System.out.println();
            System.out.println("  " + graph.unresolvedCoordinates().size()
                    + " reference(s) name nothing installed here, so they are "
                    + "not counted either way:");
            for (String c : graph.unresolvedCoordinates()) System.out.println("    " + c);
        }
        return 0;
    }

    private static String label(UnitEdgeGraph.Mechanism m) {
        return m == UnitEdgeGraph.Mechanism.SKILL_IMPORTS
                ? "[skill-imports  by name]"
                : "[references     by coord]";
    }

    private String spelledAs(UnitEdgeGraph.Edge e) {
        if (e.mechanism() != UnitEdgeGraph.Mechanism.SKILL_REFERENCES) return "";
        return "  → " + e.spelling();
    }

    private String relative(Path file) {
        Path root = store.root();
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException ex) {
            return file.toString();
        }
    }

    private void render(SkillStore store, AgentUnit u, String indent, Set<String> seen) throws java.io.IOException {
        System.out.println(indent + u.name() + " (" + u.kind().name().toLowerCase() + ")"
                + (u.version() != null ? " @" + u.version() : ""));
        if (!seen.add(u.name())) {
            System.out.println(indent + "  (cycle)");
            return;
        }
        if (cli && !u.cliDependencies().isEmpty()) {
            for (var d : u.cliDependencies()) System.out.println(indent + "  [cli] " + d.name());
        }
        if (mcp && !u.mcpDependencies().isEmpty()) {
            for (var d : u.mcpDependencies()) System.out.println(indent + "  [mcp] " + d.name());
        }
        for (UnitReference r : u.references()) {
            String childName = r.name();
            if (childName == null && r.isLocal()) {
                Path p = Path.of(r.path());
                childName = p.getFileName() != null ? p.getFileName().toString() : null;
            }
            if (childName == null) continue;
            var child = store.loadUnit(childName);
            if (child.isPresent()) {
                render(store, child.get(), indent + "  ", seen);
            } else {
                System.out.println(indent + "  " + r.coord().raw() + " (not installed)");
            }
        }
    }
}
