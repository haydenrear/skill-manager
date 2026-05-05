package dev.skillmanager.commands;

import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "deps", description = "Show the transitive skill dependency tree.")
public final class DepsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name (omit for all)")
    String name;

    @Option(names = "--cli", description = "Include CLI deps")
    boolean cli;

    @Option(names = "--mcp", description = "Include MCP deps")
    boolean mcp;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (name != null) {
            Skill s = store.load(name).orElse(null);
            if (s == null) {
                System.err.println("skill not found: " + name);
                return 1;
            }
            render(store, s, "", new HashSet<>());
        } else {
            for (Skill s : store.listInstalled()) render(store, s, "", new HashSet<>());
        }
        return 0;
    }

    private void render(SkillStore store, Skill s, String indent, Set<String> seen) throws java.io.IOException {
        System.out.println(indent + s.name() + (s.version() != null ? " @" + s.version() : ""));
        if (!seen.add(s.name())) {
            System.out.println(indent + "  (cycle)");
            return;
        }
        if (cli && !s.cliDependencies().isEmpty()) {
            for (var d : s.cliDependencies()) System.out.println(indent + "  [cli] " + d.name());
        }
        if (mcp && !s.mcpDependencies().isEmpty()) {
            for (var d : s.mcpDependencies()) System.out.println(indent + "  [mcp] " + d.name());
        }
        for (UnitReference r : s.skillReferences()) {
            String childName = r.name();
            if (childName == null && r.isLocal()) {
                Path p = Path.of(r.path());
                childName = p.getFileName() != null ? p.getFileName().toString() : null;
            }
            if (childName == null) continue;
            var child = store.load(childName);
            if (child.isPresent()) {
                render(store, child.get(), indent + "  ", seen);
            } else {
                System.out.println(indent + "  " + childName + " (not installed)");
            }
        }
    }
}
