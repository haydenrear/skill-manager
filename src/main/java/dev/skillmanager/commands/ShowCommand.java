package dev.skillmanager.commands;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.ContainedSkill;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocSource;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.HarnessMcpToolSelection;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager show <name>} — kind-aware detail view.
 *
 * <p>For a skill, the existing layout is preserved byte-for-byte (name /
 * version / description / path + cli/refs/mcp blocks). Other unit kinds
 * use kind-shaped views: plugins show contained skills and unioned deps,
 * doc-repos show bindable sources, and harnesses show referenced units,
 * docs, and MCP tool selections.
 */
@Command(name = "show", description = "Show details for an installed unit.")
public final class ShowCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Unit name")
    String name;

    private final SkillStore store;

    public ShowCommand() {
        this(SkillStore.defaultStore());
    }

    public ShowCommand(SkillStore store) {
        this.store = store;
    }

    public ShowCommand(SkillStore store, String name) {
        this.store = store;
        this.name = name;
    }

    @Override
    public Integer call() throws Exception {
        store.init();
        UnitStore sources = new UnitStore(store);
        InstalledUnit rec = sources.read(name).orElse(null);

        if (rec != null) {
            Integer rc = showRecordedKind(store, rec.unitKind());
            if (rc != null) return rc;
        }

        // Fallback for legacy installs or manually seeded test fixtures
        // without an installed-record.
        if (store.containsPlugin(name)) return showPlugin(store);
        if (store.containsHarness(name)) return showHarness(store);
        if (store.containsDocRepo(name)) return showDocRepo(store);
        Skill s = store.load(name).orElse(null);
        if (s == null) {
            System.err.println("unit not found: " + name);
            return 1;
        }
        return showSkill(s);
    }

    private Integer showRecordedKind(SkillStore store, UnitKind kind) throws IOException {
        if (kind == null) return null;
        return switch (kind) {
            case PLUGIN -> store.containsPlugin(name) ? showPlugin(store) : null;
            case HARNESS -> store.containsHarness(name) ? showHarness(store) : null;
            case DOC -> store.containsDocRepo(name) ? showDocRepo(store) : null;
            case SKILL -> {
                Skill s = store.load(name).orElse(null);
                yield s == null ? null : showSkill(s);
            }
        };
    }

    /**
     * Skill detail view — unchanged from pre-ticket-14 layout. Scripts
     * that parse this output keep working.
     */
    private static int showSkill(Skill s) {
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
                System.out.println("  - " + m.name() + "  load=" + describeLoad(m));
            }
        }
        return 0;
    }

    /**
     * Plugin detail view — header with kind/version/sha/source, contained
     * skills (just names — they aren't separately addressable), and the
     * unioned effective deps with attribution back to where each entry
     * was declared (plugin level vs. specific contained skill).
     */
    private int showPlugin(SkillStore store) throws IOException {
        Path pluginDir = store.unitDir(name, UnitKind.PLUGIN);
        PluginUnit p = PluginParser.load(pluginDir);
        UnitStore sources = new UnitStore(store);
        InstalledUnit rec = sources.read(name).orElse(null);

        String version = p.version() != null ? p.version() : "-";
        String sha = rec != null && rec.gitHash() != null && !rec.gitHash().isBlank()
                ? rec.gitHash().substring(0, Math.min(7, rec.gitHash().length()))
                : "-";
        String source = rec != null && rec.installSource() != null
                ? rec.installSource().name().toLowerCase()
                : "-";

        System.out.printf("PLUGIN  %s@%s  (sha %s, source %s)%n",
                p.name(), version, sha, source);
        if (p.description() != null && !p.description().isBlank()) {
            System.out.println("description: " + p.description());
        }
        System.out.println("path:        " + p.sourcePath());

        // Contained skills — just names. They aren't addressable units of
        // their own at the install level (the plugin owns them).
        if (!p.containedSkills().isEmpty()) {
            System.out.println();
            System.out.println("contained skills (" + p.containedSkills().size() + "):");
            for (ContainedSkill cs : p.containedSkills()) {
                System.out.println("  - " + cs.name());
            }
        }

        // Effective deps with attribution. Walk plugin-level + each
        // contained skill, building (depName → source) so duplicate entries
        // (a CLI dep declared by both the plugin and a contained skill)
        // surface their full origin chain.
        printAttributedDeps(p);

        if (!p.references().isEmpty()) {
            System.out.println();
            System.out.println("references:");
            for (UnitReference r : p.references()) {
                String label;
                if (r.isLocal()) label = r.path() + "  (local)";
                else label = r.name() + (r.version() == null ? "" : "@" + r.version()) + "  (registry)";
                System.out.println("  - " + label);
            }
        }
        return 0;
    }

    private int showDocRepo(SkillStore store) throws IOException {
        Path docDir = store.unitDir(name, UnitKind.DOC);
        DocUnit d = DocRepoParser.load(docDir);
        UnitStore sources = new UnitStore(store);
        InstalledUnit rec = sources.read(name).orElse(null);

        System.out.printf("DOC  %s@%s  (sha %s, source %s)%n",
                d.name(), versionOrDash(d.version()), shaOrDash(rec), sourceOrDash(rec));
        if (d.description() != null && !d.description().isBlank()) {
            System.out.println("description: " + d.description());
        }
        System.out.println("path:        " + d.sourcePath());
        System.out.println();
        if (d.sources().isEmpty()) {
            System.out.println("sources: (none)");
        } else {
            System.out.println("sources:");
            for (DocSource s : d.sources()) {
                System.out.printf("  - %s  file=%s  agents=%s%n",
                        s.id(), s.file(), String.join(",", s.agents()));
            }
        }
        return 0;
    }

    private int showHarness(SkillStore store) throws IOException {
        Path harnessDir = store.unitDir(name, UnitKind.HARNESS);
        HarnessUnit h = HarnessParser.load(harnessDir);
        UnitStore sources = new UnitStore(store);
        InstalledUnit rec = sources.read(name).orElse(null);

        System.out.printf("HARNESS  %s@%s  (sha %s, source %s)%n",
                h.name(), versionOrDash(h.version()), shaOrDash(rec), sourceOrDash(rec));
        if (h.description() != null && !h.description().isBlank()) {
            System.out.println("description: " + h.description());
        }
        System.out.println("path:        " + h.sourcePath());
        System.out.println();
        printReferences("units", h.units());
        printReferences("docs", h.docs());
        printMcpTools(h.mcpTools());
        return 0;
    }

    private static void printReferences(String label, java.util.List<UnitReference> refs) {
        if (refs.isEmpty()) {
            System.out.println(label + ": (none)");
            return;
        }
        System.out.println(label + ":");
        for (UnitReference r : refs) {
            System.out.println("  - " + r.coord().raw());
        }
    }

    private static void printMcpTools(java.util.List<HarnessMcpToolSelection> tools) {
        if (tools.isEmpty()) {
            System.out.println("mcp_tools: (none)");
            return;
        }
        System.out.println("mcp_tools:");
        for (HarnessMcpToolSelection m : tools) {
            String selected = m.exposesAllTools() ? "*" : String.join(",", m.tools());
            System.out.printf("  - %s [%s]%n", m.server(), selected);
        }
    }

    private static void printAttributedDeps(PluginUnit p) {
        java.util.List<String[]> cliRows = new java.util.ArrayList<>();   // [name, source]
        java.util.List<String[]> mcpRows = new java.util.ArrayList<>();
        for (CliDependency d : p.cliDependencies()) {
            cliRows.add(new String[]{d.name(), attributionForCli(p, d)});
        }
        for (McpDependency d : p.mcpDependencies()) {
            mcpRows.add(new String[]{d.name(), attributionForMcp(p, d)});
        }
        if (cliRows.isEmpty() && mcpRows.isEmpty()) return;

        System.out.println();
        System.out.println("effective dependencies (unioned):");
        for (String[] row : cliRows) {
            System.out.printf("  CLI:  %-20s (declared at: %s)%n", row[0], row[1]);
        }
        for (String[] row : mcpRows) {
            System.out.printf("  MCP:  %-20s (declared at: %s)%n", row[0], row[1]);
        }
    }

    /**
     * Return where a CLI dep was declared. Looks for a contained-skill
     * declarer first; falls back to "plugin level" when no contained
     * skill claims it (= the plugin-level toml is the source).
     */
    private static String attributionForCli(PluginUnit p, CliDependency dep) {
        for (ContainedSkill cs : p.containedSkills()) {
            for (CliDependency d : cs.cliDependencies()) {
                if (d.name().equals(dep.name())) return "skills/" + cs.name();
            }
        }
        return "plugin level";
    }

    private static String attributionForMcp(PluginUnit p, McpDependency dep) {
        for (ContainedSkill cs : p.containedSkills()) {
            for (McpDependency d : cs.mcpDependencies()) {
                if (d.name().equals(dep.name())) return "skills/" + cs.name();
            }
        }
        return "plugin level";
    }

    private static String describeLoad(McpDependency m) {
        return switch (m.load()) {
            case McpDependency.DockerLoad d -> "docker " + d.image();
            case McpDependency.BinaryLoad b -> "binary (" + b.install().size() + " target(s))";
            case McpDependency.NpmLoad n -> "npm " + n.packageName()
                    + (n.version() != null ? "@" + n.version() : "");
            case McpDependency.UvLoad u -> "uv " + u.packageName()
                    + (u.version() != null ? "==" + u.version() : "");
            case McpDependency.ShellLoad sh -> "shell "
                    + (sh.command().isEmpty() ? "<empty>" : sh.command().get(0));
        };
    }

    private static String versionOrDash(String version) {
        return version != null && !version.isBlank() ? version : "-";
    }

    private static String shaOrDash(InstalledUnit rec) {
        return rec != null && rec.gitHash() != null && !rec.gitHash().isBlank()
                ? rec.gitHash().substring(0, Math.min(7, rec.gitHash().length()))
                : "-";
    }

    private static String sourceOrDash(InstalledUnit rec) {
        return rec != null && rec.installSource() != null
                ? rec.installSource().name().toLowerCase()
                : "-";
    }
}
