package dev.skillmanager.store;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class SkillStore {

    private final Path root;
    private final Path skillsDir;
    private final Path pluginsDir;
    private final Path binDir;
    private final Path cliBinDir;
    private final Path mcpBinDir;
    private final Path venvsDir;
    private final Path npmDir;
    private final Path cacheDir;
    private final Path installedDir;

    public SkillStore(Path root) {
        this.root = root;
        this.skillsDir = root.resolve("skills");
        this.pluginsDir = root.resolve("plugins");
        this.binDir = root.resolve("bin");
        this.cliBinDir = binDir.resolve("cli");
        this.mcpBinDir = binDir.resolve("mcp");
        this.venvsDir = root.resolve("venvs");
        this.npmDir = root.resolve("npm");
        this.cacheDir = root.resolve("cache");
        // Per-unit metadata JSON: storage kind (git / local), origin, git
        // hash, version, and now unitKind (skill / plugin). Renamed from
        // sources/ to installed/ in ticket 03; legacy files are migrated
        // by UnitStore.migrateFromLegacy on next reconcile.
        this.installedDir = root.resolve("installed");
    }

    public static SkillStore defaultStore() {
        String env = System.getenv("SKILL_MANAGER_HOME");
        Path root = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".skill-manager");
        return new SkillStore(root);
    }

    public Path root() { return root; }
    public Path skillsDir() { return skillsDir; }
    public Path pluginsDir() { return pluginsDir; }
    public Path binDir() { return binDir; }
    public Path cliBinDir() { return cliBinDir; }
    public Path mcpBinDir() { return mcpBinDir; }
    public Path venvsDir() { return venvsDir; }
    public Path npmDir() { return npmDir; }
    public Path cacheDir() { return cacheDir; }
    public Path installedDir() { return installedDir; }

    /**
     * Deprecated alias for {@link #installedDir()}. Kept for one
     * release so any out-of-tree caller still using the old name
     * resolves to the same path. Internal callers should migrate to
     * {@link #installedDir()}.
     */
    @Deprecated
    public Path sourcesDir() { return installedDir; }

    public void init() throws IOException {
        Fs.ensureDir(root);
        Fs.ensureDir(skillsDir);
        Fs.ensureDir(pluginsDir);
        Fs.ensureDir(binDir);
        Fs.ensureDir(cliBinDir);
        Fs.ensureDir(mcpBinDir);
        Fs.ensureDir(venvsDir);
        Fs.ensureDir(npmDir);
        Fs.ensureDir(cacheDir);
        Fs.ensureDir(installedDir);
    }

    public Path skillDir(String name) {
        return skillsDir.resolve(name);
    }

    /**
     * Per-unit on-disk directory keyed on {@link UnitKind}. Plugins land
     * under {@code plugins/<name>}; skills under {@code skills/<name>}.
     * Effects that do not yet know the kind continue to call
     * {@link #skillDir(String)}.
     */
    public Path unitDir(String name, UnitKind kind) {
        return switch (kind) {
            case PLUGIN -> pluginsDir.resolve(name);
            case SKILL -> skillsDir.resolve(name);
        };
    }

    public boolean contains(String name) {
        return Files.isDirectory(skillDir(name)) && Files.isRegularFile(skillDir(name).resolve(SkillParser.SKILL_FILENAME));
    }

    /** True iff {@code plugins/<name>/.claude-plugin/plugin.json} exists. */
    public boolean containsPlugin(String name) {
        Path pd = pluginsDir.resolve(name);
        return Files.isDirectory(pd)
                && Files.isRegularFile(pd.resolve(PluginParser.PLUGIN_JSON_PATH));
    }

    /**
     * Kind-agnostic install check: true if the unit's directory exists
     * with the appropriate manifest under either {@code skills/} (via
     * {@link #contains(String)}) or {@code plugins/} (via
     * {@link #containsPlugin(String)}).
     */
    public boolean containsUnit(String name) {
        return contains(name) || containsPlugin(name);
    }

    public Optional<Skill> load(String name) throws IOException {
        Path d = skillDir(name);
        if (!Files.isDirectory(d)) return Optional.empty();
        return Optional.of(SkillParser.load(d));
    }

    public List<Skill> listInstalled() throws IOException {
        List<Skill> out = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) return out;
        try (Stream<Path> s = Files.list(skillsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                if (!Files.isRegularFile(p.resolve(SkillParser.SKILL_FILENAME))) continue;
                try {
                    out.add(SkillParser.load(p));
                } catch (IOException e) {
                    // skip unreadable skills
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /**
     * Kind-aware install listing. Returns every {@link AgentUnit} the
     * store knows about — skills under {@code skills/} via
     * {@link #listInstalled} plus plugins under {@code plugins/} via
     * {@link PluginParser#load}. Sorted alphabetically by name across
     * both kinds.
     *
     * <p>Used by the {@code list} / {@code search} surface from ticket 14
     * (where plugins need their own row) and by code that wants the full
     * "what's installed" view without down-casting (orphan checks,
     * lock-vs-live drift, the reconciler in future).
     */
    public List<AgentUnit> listInstalledUnits() throws IOException {
        List<AgentUnit> out = new ArrayList<>();
        for (Skill s : listInstalled()) out.add(s.asUnit());
        if (Files.isDirectory(pluginsDir)) {
            try (Stream<Path> s = Files.list(pluginsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(p)) continue;
                    if (!Files.isRegularFile(p.resolve(PluginParser.PLUGIN_JSON_PATH))) continue;
                    try {
                        PluginUnit plugin = PluginParser.load(p);
                        out.add(plugin);
                    } catch (IOException e) {
                        // skip unreadable plugins
                    }
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    public void remove(String name) throws IOException {
        Path d = skillDir(name);
        if (Files.exists(d)) Fs.deleteRecursive(d);
    }
}
