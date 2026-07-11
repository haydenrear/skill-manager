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
    private final Path projectsDir;
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
        // Registered project manifests live under projects/<name>/ as
        // portable intent snapshots. Resolution/materialization happens in
        // later project-specific flows.
        this.projectsDir = root.resolve("projects");
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
    public Path projectsDir() { return projectsDir; }
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
        Fs.ensureDir(projectsDir);
        Fs.ensureDir(binDir);
        Fs.ensureDir(cliBinDir);
        Fs.ensureDir(mcpBinDir);
        Fs.ensureDir(venvsDir);
        Fs.ensureDir(npmDir);
        Fs.ensureDir(cacheDir);
        Fs.ensureDir(installedDir);
    }

    /**
     * Name of the working-copy directory under {@code skills/<name>/}.
     * Sibling directories are content-addressed snapshots keyed by sha.
     */
    public static final String LATEST_DIR = "latest";

    /** File under {@code skills/<name>/} naming the sha the working copy was last stored as. */
    public static final String LATEST_MARKER = ".store-latest";

    /**
     * The content-addressed store slot for one skill: {@code skills/<name>/}.
     * It holds the mutable working copy under {@link #LATEST_DIR} plus one
     * immutable snapshot directory per stored sha. Nothing reads unit
     * content from here directly — use {@link #skillDir(String)}.
     */
    public Path storeUnitDir(String name) {
        return skillsDir.resolve(name);
    }

    /** One immutable snapshot: {@code skills/<name>/<sha>/}. */
    public Path storeVersionDir(String name, String sha) {
        return storeUnitDir(name).resolve(sha);
    }

    /**
     * The mutable working copy: {@code skills/<name>/latest/}. Install and
     * sync write here in place (the git clone lives here), so this — not a
     * snapshot dir — is what every reader and projector resolves to.
     */
    public Path skillDir(String name) {
        return storeUnitDir(name).resolve(LATEST_DIR);
    }

    /**
     * Per-unit on-disk directory keyed on {@link UnitKind}. Plugins land
     * under {@code plugins/<name>}; skills under {@code skills/<name>/latest}.
     * Effects that do not yet know the kind continue to call
     * {@link #skillDir(String)}.
     */
    public Path unitDir(String name, UnitKind kind) {
        return switch (kind) {
            case PLUGIN -> pluginsDir.resolve(name);
            case SKILL -> skillDir(name);
            case DOC -> docsDir.resolve(name);
            case HARNESS -> harnessesDir.resolve(name);
        };
    }

    /**
     * Shas snapshotted for {@code name}, sorted. Excludes the working copy
     * and the latest marker. Empty when the unit was never stored.
     */
    public List<String> storedVersions(String name) throws IOException {
        Path slot = storeUnitDir(name);
        if (!Files.isDirectory(slot)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(slot)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(p)) continue;
                String dir = p.getFileName().toString();
                if (LATEST_DIR.equals(dir)) continue;
                out.add(dir);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** The sha the working copy was last stored as, if any. */
    public Optional<String> storeLatest(String name) throws IOException {
        Path marker = storeUnitDir(name).resolve(LATEST_MARKER);
        if (!Files.isRegularFile(marker)) return Optional.empty();
        String sha = Files.readString(marker).trim();
        return sha.isBlank() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * Snapshot the working copy as {@code sha} and move the latest pointer
     * onto it. Storing a sha that is already present refreshes the snapshot
     * rather than failing, so a re-store after a sync is not an error.
     * Snapshots are never removed here — the store is an immutable cache.
     *
     * <p>A snapshot records the unit's <em>content</em>, so the working copy's
     * {@code .git} is not copied: the sha already names the commit, and
     * duplicating the history once per stored version would grow the store
     * without bound. The clone stays in {@link #skillDir(String)}.
     */
    public void storeUnitVersion(String name, String sha) throws IOException {
        Path workingCopy = skillDir(name);
        Path snapshot = storeVersionDir(name, sha);
        if (Files.exists(snapshot)) Fs.deleteRecursive(snapshot);
        Fs.ensureDir(snapshot);
        Path git = workingCopy.resolve(".git");
        try (Stream<Path> entries = Files.list(workingCopy)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (entry.equals(git)) continue;
                Fs.copyRecursive(entry, snapshot.resolve(entry.getFileName().toString()));
            }
        }
        Files.writeString(storeUnitDir(name).resolve(LATEST_MARKER), sha + "\n");
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
                // p is the store slot skills/<name>; the unit content is the
                // working copy under it. Sibling sha snapshots are not units.
                Path workingCopy = p.resolve(LATEST_DIR);
                if (!Files.isRegularFile(workingCopy.resolve(SkillParser.SKILL_FILENAME))) continue;
                try {
                    out.add(SkillParser.load(workingCopy));
                } catch (Exception e) {
                    problems.add(readProblem(p.getFileName().toString(), workingCopy, UnitKind.SKILL, e));
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

    /**
     * Uninstall the working copy. Stored snapshots and the latest marker
     * survive: the store is an immutable cache, so a later reinstall of a
     * sha already present is served from disk. The slot itself is pruned
     * only when nothing was ever stored under it.
     */
    public void remove(String name) throws IOException {
        Path d = skillDir(name);
        if (Files.exists(d)) Fs.deleteRecursive(d);
        if (storedVersions(name).isEmpty()) {
            Path slot = storeUnitDir(name);
            if (Files.isDirectory(slot)) Fs.deleteRecursive(slot);
        }
    }

    /**
     * Kind-aware uninstall. Skills go through {@link #remove(String)} so
     * their snapshots survive and an empty slot is pruned; every other kind
     * still owns its directory outright.
     */
    public void removeUnit(String name, UnitKind kind) throws IOException {
        if (kind == UnitKind.SKILL) {
            remove(name);
            return;
        }
        Path d = unitDir(name, kind);
        if (Files.exists(d)) Fs.deleteRecursive(d);
    }

    /**
     * One-time migration to the content-addressed layout: a legacy
     * {@code skills/<name>/SKILL.md} becomes {@code skills/<name>/latest/SKILL.md},
     * leaving room for sibling {@code <sha>/} snapshots. The move is a rename,
     * so an in-place git clone under the unit survives intact.
     *
     * <p>Idempotent: a slot whose {@code SKILL.md} already sits under
     * {@code latest/} is skipped, so subsequent runs migrate nothing.
     *
     * <p>Migrating a slot invalidates every agent-home symlink that pointed at
     * it — the link still resolves, but to a slot that no longer holds a
     * {@code SKILL.md}. Callers must hand the returned names to
     * {@link dev.skillmanager.lifecycle.MigratedLinkRepair} to repoint them.
     *
     * @return names of the slots migrated this call (empty on subsequent runs).
     */
    public static List<String> migrateToContentAddressed(SkillStore store) throws IOException {
        Path skills = store.skillsDir();
        if (!Files.isDirectory(skills)) return List.of();
        List<Path> legacy = new ArrayList<>();
        try (Stream<Path> s = Files.list(skills)) {
            for (Path slot : (Iterable<Path>) s::iterator) {
                if (!Files.isDirectory(slot)) continue;
                if (Files.isRegularFile(slot.resolve(SkillParser.SKILL_FILENAME))) legacy.add(slot);
            }
        }
        List<String> migrated = new ArrayList<>(legacy.size());
        for (Path slot : legacy) {
            Path staging = skills.resolve(slot.getFileName() + ".migrating");
            if (Files.exists(staging)) Fs.deleteRecursive(staging);
            Files.move(slot, staging);
            Fs.ensureDir(slot);
            Files.move(staging, slot.resolve(LATEST_DIR));
            migrated.add(slot.getFileName().toString());
        }
        return migrated;
    }

    private static UnitReadProblem readProblem(Path dir, UnitKind kind, Exception e) {
        return readProblem(dir.getFileName().toString(), dir, kind, e);
    }

    private static UnitReadProblem readProblem(String name, Path dir, UnitKind kind, Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        return new UnitReadProblem(
                name,
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
