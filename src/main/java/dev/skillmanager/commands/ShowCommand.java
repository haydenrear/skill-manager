package dev.skillmanager.commands;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "show", description = "Show details for an installed skill.")
public final class ShowCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name")
    String name;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Skill s = store.load(name).orElse(null);
        if (s == null) {
            System.err.println("skill not found: " + name);
            return 1;
        }
        System.out.println("name:        " + s.name());
        if (s.version() != null) System.out.println("version:     " + s.version());
        System.out.println("description: " + s.description());
        System.out.println("path:        " + s.sourcePath());

        if (!s.cliDependencies().isEmpty()) {
            System.out.println("\ncli dependencies:");
            for (CliDependency d : s.cliDependencies()) {
                System.out.println("  - " + d.name() + (d.minVersion() != null ? " (>= " + d.minVersion() + ")" : ""));
            }
        }
        if (!s.skillReferences().isEmpty()) {
            System.out.println("\nskill references:");
            for (UnitReference r : s.skillReferences()) {
                String label;
                if (r.isLocal()) label = r.path() + "  (local)";
                else label = r.name() + (r.version() == null ? "" : "@" + r.version()) + "  (registry)";
                System.out.println("  - " + label);
            }
        }
        if (!s.mcpDependencies().isEmpty()) {
            System.out.println("\nmcp dependencies:");
            for (McpDependency m : s.mcpDependencies()) {
                String typeInfo = switch (m.load()) {
                    case McpDependency.DockerLoad d -> "docker " + d.image();
                    case McpDependency.BinaryLoad b -> "binary (" + b.install().size() + " target(s))";
                    case McpDependency.NpmLoad n -> "npm " + n.packageName()
                            + (n.version() != null ? "@" + n.version() : "");
                    case McpDependency.UvLoad u -> "uv " + u.packageName()
                            + (u.version() != null ? "==" + u.version() : "");
                    case McpDependency.ShellLoad sh -> "shell "
                            + (sh.command().isEmpty() ? "<empty>" : sh.command().get(0));
                };
                System.out.println("  - " + m.name() + "  load=" + typeInfo);
            }
        }
        return 0;
    }
}
