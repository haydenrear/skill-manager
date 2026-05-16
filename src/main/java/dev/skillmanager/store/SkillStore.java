package dev.skillmanager.store;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
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
    private final Path docsDir;
    private final Path harnessesDir;
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
        // Doc-repos (#48) land under docs/<name>/; sub-elements
        // (individual markdown files under claude-md/) are addressed
        // via the coord `doc:<name>/<source>` and projected into
        // target roots through tracked-copy bindings.
        this.docsDir = root.resolve("docs");
        // Harness templates (#47) land under harnesses/<name>/. Templates
        // are metadata only (a harness.toml + an optional README); the
        // instantiator turns them into Bindings on demand.
        this.harnessesDir = root.resolve("harnesses");
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
    public Path docsDir() { return docsDir; }
    public Path harnessesDir() { return harnessesDir; }
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
        Fs.ensureDir(docsDir);
        Fs.ensureDir(harnessesDir);
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
            case DOC -> docsDir.resolve(name);
            case HARNESS -> harnessesDir.resolve(name);
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

    /** True iff {@code docs/<name>/skill-manager.toml} exists (#48). */
    public boolean containsDocRepo(String name) {
        Path dd = docsDir.resolve(name);
        return Files.isDirectory(dd)
                && Files.isRegularFile(dd.resolve(dev.skillmanager.model.DocRepoParser.TOML_FILENAME));
    }

    /** True iff {@code harnesses/<name>/harness.toml} exists (#47). */
    public boolean containsHarness(String name) {
        Path hd = harnessesDir.resolve(name);
        return Files.isDirectory(hd)
                && Files.isRegularFile(hd.resolve(dev.skillmanager.model.HarnessParser.TOML_FILENAME));
    }

    /**
     * Kind-agnostic install check: true if the unit's directory exists
     * with the appropriate manifest under any of {@code skills/},
     * {@code plugins/}, {@code docs/}, or {@code harnesses/}.
     */
    public boolean containsUnit(String name) {
        return contains(name) || containsPlugin(name)
                || containsDocRepo(name) || containsHarness(name);
    }

    public Optional<Skill> load(String name) throws IOException {
        Path d = skillDir(name);
        if (!Files.isDirectory(d)) return Optional.empty();
        return Optional.of(SkillParser.load(d));
    }

    public InstalledSkillsResult listInstalled() throws IOException {
        List<Skill> out = new ArrayList<>();
        List<UnitReadProblem> problems = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) return new InstalledSkillsResult(out, problems);
        try (Stream<Path> s = Files.list(skillsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                if (!Files.isRegularFile(p.resolve(SkillParser.SKILL_FILENAME))) continue;
                try {
                    out.add(SkillParser.load(p));
                } catch (Exception e) {
                    problems.add(readProblem(p, UnitKind.SKILL, e));
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        problems.sort(SkillStore::compareProblem);
        return new InstalledSkillsResult(out, problems);
    }

    /**
     * Kind-aware install listing. Returns every {@link AgentUnit} the
     * store knows about: skills, plugins, doc-repos, and harness
     * templates. Sorted alphabetically by name across all kinds.
     *
     * <p>Used by the {@code list} / {@code search} surface from ticket 14
     * (where non-skill units need their own row) and by code that wants the full
     * "what's installed" view without down-casting (orphan checks,
     * lock-vs-live drift, the reconciler in future).
     */
    public InstalledUnitsResult listInstalledUnits() throws IOException {
        List<AgentUnit> out = new ArrayList<>();
        List<UnitReadProblem> problems = new ArrayList<>();
        InstalledSkillsResult skills = listInstalled();
        for (Skill s : skills.skills()) out.add(s.asUnit());
        problems.addAll(skills.problems());
        if (Files.isDirectory(pluginsDir)) {
            try (Stream<Path> s = Files.list(pluginsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(p)) continue;
                    if (!Files.isRegularFile(p.resolve(PluginParser.PLUGIN_JSON_PATH))) continue;
                    try {
                        PluginUnit plugin = PluginParser.load(p);
                        out.add(plugin);
                    } catch (Exception e) {
                        problems.add(readProblem(p, UnitKind.PLUGIN, e));
                    }
                }
            }
        }
        if (Files.isDirectory(docsDir)) {
            try (Stream<Path> s = Files.list(docsDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(p)) continue;
                    if (!Files.isRegularFile(p.resolve(DocRepoParser.TOML_FILENAME))) continue;
                    try {
                        DocUnit doc = DocRepoParser.load(p);
                        out.add(doc);
                    } catch (Exception e) {
                        problems.add(readProblem(p, UnitKind.DOC, e));
                    }
                }
            }
        }
        if (Files.isDirectory(harnessesDir)) {
            try (Stream<Path> s = Files.list(harnessesDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(p)) continue;
                    if (!Files.isRegularFile(p.resolve(HarnessParser.TOML_FILENAME))) continue;
                    try {
                        HarnessUnit harness = HarnessParser.load(p);
                        out.add(harness);
                    } catch (Exception e) {
                        problems.add(readProblem(p, UnitKind.HARNESS, e));
                    }
                }
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        problems.sort(SkillStore::compareProblem);
        return new InstalledUnitsResult(out, problems);
    }

    /** Load one installed unit by name from whichever kind-specific store dir owns it. */
    public Optional<AgentUnit> loadUnit(String name) throws IOException {
        if (containsPlugin(name)) {
            return Optional.of(PluginParser.load(unitDir(name, UnitKind.PLUGIN)));
        }
        if (containsHarness(name)) {
            return Optional.of(HarnessParser.load(unitDir(name, UnitKind.HARNESS)));
        }
        if (containsDocRepo(name)) {
            return Optional.of(DocRepoParser.load(unitDir(name, UnitKind.DOC)));
        }
        Optional<Skill> s = load(name);
        return s.map(Skill::asUnit);
    }

    public void remove(String name) throws IOException {
        Path d = skillDir(name);
        if (Files.exists(d)) Fs.deleteRecursive(d);
    }

    private static UnitReadProblem readProblem(Path dir, UnitKind kind, Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        return new UnitReadProblem(
                dir.getFileName().toString(),
                kind,
                dir.toAbsolutePath(),
                msg);
    }

    private static int compareProblem(UnitReadProblem a, UnitReadProblem b) {
        int byName = a.name().compareToIgnoreCase(b.name());
        if (byName != 0) return byName;
        return a.kind().name().compareToIgnoreCase(b.kind().name());
    }
}
