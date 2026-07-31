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

    /** The env var naming the home root. */
    public static final String HOME_ENV = "SKILL_MANAGER_HOME";

    /**
     * The ambient home: {@code $SKILL_MANAGER_HOME}, else
     * {@code <userHome>/.skill-manager}.
     *
     * <p>Resolved through {@link dev.skillmanager.agent.AgentHomes#resolve}
     * rather than {@code System.getenv} directly, so the home root has the
     * same single interception point every other home-shaped variable has.
     * A JVM cannot mutate its own environment, and without an override an
     * in-process test of "what does this command write" has no choice but to
     * aim at whatever home the developer's shell exported — which is the
     * operator's real home, which is the thing being tested. Behaviour with
     * no override set is unchanged: env var when non-blank, else user home.
     */
    public static SkillStore defaultStore() {
        Path root = dev.skillmanager.agent.AgentHomes.resolve(HOME_ENV);
        if (root == null) {
            root = dev.skillmanager.agent.AgentHomes.userHome().resolve(".skill-manager");
        }
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

    /**
     * True when this root is already a Skill Manager home — somebody ran
     * {@link #init()} against it, or cloned one here, before now.
     *
     * <p>Deliberately not "the root directory exists": the reproduction for
     * the eager-scaffold defect creates an empty decoy directory first, and
     * an empty directory is not a home. Callers that only have work to do
     * when a home is already present — reconciliation, outstanding-error
     * reporting — ask this instead of creating one to find out.
     *
     * <h2>Why this delegates rather than deciding for itself</h2>
     *
     * <p>It shipped (in {@code 7d87a06}, as {@code isMaterialized()}) as
     * {@code installed/ || skills/} while
     * {@link dev.skillmanager.launch.LaunchEnv#looksLikeStoreRoot} — the
     * predicate {@link NotAHomeException#require} and the PATH sanitizer use —
     * reads {@code descriptor || (installed/ && skills/)}. Two spellings, and
     * they disagreed in both directions: a descriptor-only home was "not
     * materialized" (so a read command skipped reconcile) yet "is a home" (so
     * {@code exec} launched against it), and an {@code installed/}-only
     * directory was the reverse. Neither disagreement was ever demonstrated to
     * lose data, and that is the point — this epic has already paid four times
     * for one question asked in two spellings, each time in the gap nobody had
     * demonstrated yet. There is one predicate; this asks it.
     *
     * <p>The consequence worth naming: a <em>partial</em> home — one that
     * carries {@code installed/} but no {@code skills/} and no descriptor —
     * no longer self-heals on a read-only command, because a read-only command
     * no longer calls {@code init()} at all (that is {@link HomeScaffold}) and
     * reconcile is now skipped for it as well. The first writing command
     * completes the layout, exactly as it does for a home that does not exist.
     */
    public boolean isHome() {
        return dev.skillmanager.launch.LaunchEnv.looksLikeStoreRoot(root);
    }

    /**
     * Create the home layout, unless the running command was declared
     * {@link HomeScaffold.Access#READ_ONLY}.
     *
     * <p>This is the single point at which a skill-manager home comes into
     * being, and therefore the single point at which laziness can be
     * enforced. See {@link HomeScaffold} for the defect
     * ({@code --version} materializing twelve directories in whatever
     * {@code SKILL_MANAGER_HOME} named) and for why the mode is declared once
     * per invocation instead of threaded through every call site.
     *
     * <p>Skipping is safe for readers because every listing below already
     * treats a missing directory as an empty one.
     */
    public void init() throws IOException {
        if (!HomeScaffold.mayScaffold()) return;
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
