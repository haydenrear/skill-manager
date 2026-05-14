package dev.skillmanager.commands;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "deps",
        description = "Show the transitive dependency tree of an installed unit (skill or plugin). "
                + "For plugins, the tree includes both plugin-level deps and every contained "
                + "skill's deps (unioned at parse time).")
public final class DepsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1",
            description = "Unit name — skill or plugin (omit for all installed)")
    String name;

    @Option(names = "--cli", description = "Include CLI deps")
    boolean cli;

    @Option(names = "--mcp", description = "Include MCP deps")
    boolean mcp;

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
        if (name != null) {
            AgentUnit u = store.loadUnit(name).orElse(null);
            if (u == null) {
                System.err.println("unit not found: " + name);
                return 1;
            }
            render(store, u, "", new HashSet<>());
        } else {
            for (AgentUnit u : store.listInstalledUnits()) render(store, u, "", new HashSet<>());
        }
        return 0;
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
