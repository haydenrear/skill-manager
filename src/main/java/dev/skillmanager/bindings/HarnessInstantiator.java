package dev.skillmanager.bindings;

import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.Fingerprints;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plans the {@link BindingSource#HARNESS} bindings a harness template
 * (#47) produces when instantiated. Projections land at agent-
 * discoverable paths — not in a dead-letter sandbox subdir — so
 * Claude Code / Codex / Gemini can actually load the harness's tools:
 *
 * <ul>
 *   <li>Skills → one SYMLINK at {@code <claudeConfigDir>/skills/<name>},
 *       one at {@code <codexHome>/skills/<name>}, and one at
 *       {@code <geminiHome>/skills/<name>}. Skills are agent-agnostic,
 *       so all runtimes see them.</li>
 *   <li>Plugins → one SYMLINK at {@code <claudeConfigDir>/plugins/<name>}.
 *       Claude-only; Codex doesn't load plugins (matches the
 *       {@code CodexProjector} contract).</li>
 *   <li>Doc-repo sources → tracked-copy + import-directive
 *       projections under {@code <projectDir>/docs/agents/} +
 *       {@code <projectDir>/{CLAUDE,AGENTS}.md} via
 *       {@link DocRepoBinder}, with stable ids
 *       {@code harness:<instanceId>:<repoName>:<sourceId>}.</li>
 * </ul>
 *
 * <p>All ids derived from the template are deterministic. Re-running
 * instantiation against the same {@code instanceId} re-emits the same
 * binding ids; the {@link BindingStore} replaces-by-id semantics make
 * the operation idempotent (modulo any source-side drift, which would
 * surface through {@code skill-manager sync} after the rebind).
 *
 * <p>Referenced units must already be installed in the store —
 * harness install resolves them transitively first, so by the time
 * the instantiator runs they're present.
 */
public final class HarnessInstantiator {

    private HarnessInstantiator() {}

    /** Versioned per {@link Fingerprints}: changing what it covers means a new suffix. */
    public static final String FINGERPRINT_SCHEME = "harness-template-v1";

    /**
     * The bindings a template produces, and a graded digest of the template
     * they were produced from.
     *
     * <p>{@code SyncHarness} re-instantiates against the template with
     * {@link ConflictPolicy#OVERWRITE} on every pass. Nothing recorded what the
     * template looked like when the instance was made, so re-running it was the
     * only way to learn whether it had moved — the artifact was regenerated in
     * order to answer a question that a recorded fingerprint answers without
     * touching the disk. {@link #templateFingerprint()} is that fingerprint;
     * {@link HarnessInstanceLock} is where it is kept.
     */
    public record Plan(List<Binding> bindings, Fingerprint templateFingerprint) {
        public Plan { bindings = List.copyOf(bindings); }

        /**
         * The shape callers written before the fingerprint existed use. The
         * fingerprint is a recorded GAP rather than null: "nobody computed one"
         * and "one could not be computed" are different claims, and
         * {@link Fingerprint} refuses to let them share a spelling.
         */
        public Plan(List<Binding> bindings) {
            this(bindings, Fingerprint.gap(
                    "this plan was built without digesting its template"));
        }
    }

    /**
     * @param harness          the resolved HarnessUnit (sourcePath = store dir)
     * @param instanceId       a stable identifier — defaults to {@code <name>}
     *                         in the CLI; tests pass arbitrary strings
     * @param claudeConfigDir  the {@code .claude/} dir Claude Code reads
     *                         from. Skills land at
     *                         {@code <claudeConfigDir>/skills/<name>};
     *                         plugins at {@code <claudeConfigDir>/plugins/<name>}.
     * @param codexHome        the dir Codex reads {@code skills/} from.
     *                         Skills also land at
     *                         {@code <codexHome>/skills/<name>}.
     * @param geminiHome       the dir Gemini reads {@code skills/} from.
     *                         Skills also land at
     *                         {@code <geminiHome>/skills/<name>}.
     * @param projectDir       where {@code CLAUDE.md} / {@code AGENTS.md} and
     *                         the tracked-copy {@code docs/agents/<file>}
     *                         entries live — typically a project repo root.
     */
    public static Plan plan(HarnessUnit harness, String instanceId,
                            Path claudeConfigDir, Path codexHome, Path projectDir,
                            SkillStore store) throws IOException {
        return plan(harness, instanceId, claudeConfigDir, codexHome,
                geminiSiblingOf(codexHome), projectDir, store);
    }

    /**
     * The Gemini home beside a given Codex home, for the legacy overload
     * that predates Gemini being passed explicitly.
     *
     * <p>It mirrors the sibling's own naming convention rather than
     * hardcoding {@code "gemini"}: the two live layouts spell these
     * directories differently — {@code <sandbox>/<id>/codex} in the
     * harness sandbox and {@code <target>/.codex} in a project child home
     * — and a flat {@code resolveSibling("gemini")} produced
     * {@code <target>/gemini} for the dotted one. That is a directory no
     * agent reads and no teardown deletes, so Gemini skills would have
     * landed somewhere nothing looked while {@code .gemini} stayed empty.
     * Every production caller passes the home explicitly, so this only
     * ever bit derived callers — but it is the one place Gemini was
     * second-class rather than declared.
     */
    private static Path geminiSiblingOf(Path codexHome) {
        if (codexHome == null) return null;
        Path name = codexHome.getFileName();
        boolean dotted = name != null && name.toString().startsWith(".");
        return codexHome.resolveSibling(dotted ? ".gemini" : "gemini");
    }

    public static Plan plan(HarnessUnit harness, String instanceId,
                            Path claudeConfigDir, Path codexHome, Path geminiHome,
                            Path projectDir, SkillStore store) throws IOException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("harness instanceId must not be blank");
        }
        if (claudeConfigDir == null || codexHome == null || geminiHome == null || projectDir == null) {
            throw new IllegalArgumentException(
                    "harness instantiate requires claudeConfigDir, codexHome, geminiHome, and projectDir");
        }
        List<Binding> all = new ArrayList<>();

        // --- referenced skills + plugins ---
        for (UnitReference ref : harness.units()) {
            NameKind nk = resolveReference(ref, store, harness.sourcePath()).orElseThrow(() -> new IOException(
                    "harness " + harness.name() + " references unit '"
                            + ref.coord().raw() + "' which is not installed"));
            if (nk.kind() == UnitKind.DOC || nk.kind() == UnitKind.HARNESS) {
                throw new IOException("harness " + harness.name()
                        + " units[] entry resolves to a " + nk.kind()
                        + " — use docs[] (for DOC) or split into a separate harness "
                        + "(for HARNESS): " + ref.coord().raw());
            }
            String bindingId = unitBindingId(instanceId, nk.name());
            Path source = store.unitDir(nk.name(), nk.kind());
            List<Projection> projections = projectionsFor(
                    bindingId, source, nk, claudeConfigDir, codexHome, geminiHome);
            all.add(new Binding(
                    bindingId, nk.name(), nk.kind(), null,
                    // targetRoot is informational; the projection destPaths
                    // carry the actual on-disk locations. We use projectDir
                    // so `bindings list` surfaces the harness's project
                    // root rather than an arbitrary agent dir.
                    projectDir,
                    ConflictPolicy.OVERWRITE,
                    BindingStore.nowIso(), BindingSource.HARNESS,
                    projections));
        }

        // --- referenced doc-repo sources ---
        for (UnitReference ref : harness.docs()) {
            DocRef d = resolveDocReference(ref, store, harness.sourcePath()).orElseThrow(() -> new IOException(
                    "harness " + harness.name() + " references doc-repo '"
                            + ref.coord().raw() + "' which is not installed"));
            DocUnit docUnit = DocRepoParser.load(store.unitDir(d.repoName(), UnitKind.DOC));
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    docUnit, projectDir, d.sourceId(),
                    ConflictPolicy.OVERWRITE,
                    BindingSource.HARNESS,
                    src -> docBindingId(instanceId, docUnit.name(), src.id()));
            all.addAll(plan.bindings());
        }

        return new Plan(all, fingerprintOf(harness, store));
    }

    /**
     * A graded digest of the template an instance is derived from.
     *
     * <h2>What it covers</h2>
     *
     * <ul>
     *   <li>the harness name and version;</li>
     *   <li><b>every byte of the installed {@code harness.toml}</b>, read off
     *       this home's disk;</li>
     *   <li>what each of its unit and doc coords RESOLVES TO in this home —
     *       {@code <kind>:<name>}, or {@code unresolved:<coord>} when nothing
     *       installed answers to it.</li>
     * </ul>
     *
     * <p>The manifest's own bytes are hashed, so anything the template declares
     * — {@code [[mcp_tools]]} rows included — is covered without being restated
     * here. What the bytes cannot say is which installed unit a coord landed on,
     * and that is a fact about this home rather than about the file, so it is
     * added explicitly.
     *
     * <h2>Why {@link Fingerprint.Kind#RESOLVED}</h2>
     *
     * <p>These are bytes and resolutions READ OFF THIS HOME on this pass, not a
     * declaration copied out of some other manifest. When the harness unit is
     * updated in the store the digest moves; when a unit it references is
     * removed the digest moves. That is what {@code resolved} asserts, and it is
     * the claim {@code SyncHarness} could not make before — it re-instantiated
     * with {@link ConflictPolicy#OVERWRITE} on every pass because re-running was
     * the only way to learn whether the template had moved.
     *
     * <p>What it deliberately does NOT cover: the CONTENTS of the units the
     * template references. Those are projected as symlinks into the store, so a
     * referenced skill gaining a file does not make the instance stale — its
     * link already points at the new bytes. Folding them in would make the
     * digest move for a reason that is not this artifact's, and a signal that
     * fires when nothing is wrong is the one that gets ignored.
     *
     * <p>What it also does not cover: the instance's target directories. Those
     * are recorded verbatim as fields of {@link HarnessInstanceLock} and they
     * differ between homes; digesting them would make a clone of a home report
     * every instance as stale.
     */
    public static Fingerprint fingerprintOf(HarnessUnit harness, SkillStore store) {
        Path source = harness.sourcePath();
        Path template = source == null ? null : source.resolve(HarnessParser.TOML_FILENAME);
        if (template == null || !Files.isRegularFile(template)) {
            return Fingerprint.gap("this home has no installed " + HarnessParser.TOML_FILENAME
                    + " for harness " + harness.name() + ", so the template these bindings were "
                    + "instantiated from could not be read");
        }
        Fingerprints digest = Fingerprints.scheme(FINGERPRINT_SCHEME)
                .field("harness", harness.name())
                .field("version", harness.version());
        try {
            digest.file("template", HarnessParser.TOML_FILENAME, template);
        } catch (IOException e) {
            return Fingerprint.gap("the installed " + HarnessParser.TOML_FILENAME
                    + " could not be read: " + e.getMessage());
        }
        for (UnitReference ref : harness.units()) {
            digest.field("unit", resolveReference(ref, store, source)
                    .map(nk -> nk.kind() + ":" + nk.name())
                    .orElse("unresolved:" + ref.coord().raw()));
        }
        for (UnitReference ref : harness.docs()) {
            digest.field("doc", resolveDocReference(ref, store, source)
                    .map(d -> d.sourceId() == null ? d.repoName()
                            : d.repoName() + "/" + d.sourceId())
                    .orElse("unresolved:" + ref.coord().raw()));
        }
        return Fingerprint.resolved(digest.hex(),
                "every byte of the installed " + HarnessParser.TOML_FILENAME + " for "
                        + harness.name() + ", plus the kind and name each of its unit and doc "
                        + "coords resolves to in this home");
    }

    /**
     * Per-kind projection list for skill/plugin units. Skills target
     * Claude, Codex, and Gemini; plugins target Claude only (Codex and
     * Gemini projectors return empty for plugins, so the binding mirror
     * is Claude-only here too).
     */
    private static List<Projection> projectionsFor(String bindingId, Path source,
                                                    NameKind nk, Path claudeConfigDir, Path codexHome,
                                                    Path geminiHome) {
        return switch (nk.kind()) {
            case SKILL -> List.of(
                    new Projection(bindingId, source,
                            claudeConfigDir.resolve("skills").resolve(nk.name()),
                            ProjectionKind.SYMLINK, null),
                    new Projection(bindingId, source,
                            codexHome.resolve("skills").resolve(nk.name()),
                            ProjectionKind.SYMLINK, null),
                    new Projection(bindingId, source,
                            geminiHome.resolve("skills").resolve(nk.name()),
                            ProjectionKind.SYMLINK, null));
            case PLUGIN -> List.of(
                    new Projection(bindingId, source,
                            claudeConfigDir.resolve("plugins").resolve(nk.name()),
                            ProjectionKind.SYMLINK, null));
            case DOC, HARNESS -> throw new IllegalStateException(
                    "projectionsFor not defined for " + nk.kind());
        };
    }

    /** Stable id for a skill/plugin harness binding. */
    public static String unitBindingId(String instanceId, String unitName) {
        return "harness:" + instanceId + ":" + unitName;
    }

    /** Stable id for a doc-repo source harness binding. */
    public static String docBindingId(String instanceId, String repoName, String sourceId) {
        return "harness:" + instanceId + ":" + repoName + ":" + sourceId;
    }

    private record NameKind(String name, UnitKind kind) {}

    /**
     * Look up the installed-unit name + kind a reference points at.
     *
     * <p>For coord-named refs ({@link Coord.Bare}, {@link Coord.Kinded})
     * the name is in the coord and we read the install record directly.
     * For {@link Coord.Local} (file://) refs the name lives in the
     * unit's on-disk manifest at the referenced path; parse the
     * appropriate manifest to recover the name, then look up the
     * install record by that name. Falls back to {@link Optional#empty}
     * when the manifest is unreadable or the unit isn't installed.
     */
    private static Optional<NameKind> resolveReference(UnitReference ref, SkillStore store, Path baseDir) {
        UnitStore us = new UnitStore(store);
        Coord c = ref.coord();
        String name = unitName(c);
        if (name == null) {
            // Local refs: parse the manifest at the path to find the name.
            name = nameFromLocalManifest(c, baseDir);
            if (name == null) return Optional.empty();
        }
        return us.read(name).map(rec -> new NameKind(rec.name(), rec.unitKind()));
    }

    private record DocRef(String repoName, String sourceId) {}

    /**
     * Resolve a {@code doc:<repo>} / {@code doc:<repo>/<src>} /
     * {@code file://<path>} coord to its repo name + optional
     * sub-element. Whole-doc-repo refs have {@code sourceId == null}
     * which {@link DocRepoBinder} treats as "fan out to every source."
     * Local (file://) doc-repo refs are always whole-repo binds.
     */
    private static Optional<DocRef> resolveDocReference(UnitReference ref, SkillStore store, Path baseDir) {
        UnitStore us = new UnitStore(store);
        Coord c = ref.coord();
        String repoName = unitName(c);
        if (repoName == null) {
            repoName = nameFromLocalManifest(c, baseDir);
            if (repoName == null) return Optional.empty();
        }
        String sourceId = c instanceof Coord.SubElement s ? s.elementName() : null;
        final String resolvedName = repoName;
        return us.read(resolvedName)
                .filter(rec -> rec.unitKind() == UnitKind.DOC)
                .map(rec -> new DocRef(resolvedName, sourceId));
    }

    /**
     * For a {@link Coord.Local} ref, read the unit's manifest at the
     * referenced path to recover its declared name. Returns {@code null}
     * for non-local coords or when the manifest can't be parsed.
     */
    private static String nameFromLocalManifest(Coord c, Path baseDir) {
        if (!(c instanceof Coord.Local l)) return null;
        java.nio.file.Path raw = java.nio.file.Path.of(l.path());
        java.nio.file.Path dir = raw.isAbsolute()
                ? raw.normalize()
                : (baseDir == null ? raw : baseDir.resolve(raw)).toAbsolutePath().normalize();
        // Try plugin first (same precedence as the resolver), then
        // harness, then doc-repo, then bare skill.
        try {
            if (dev.skillmanager.model.PluginParser.looksLikePlugin(dir)) {
                return dev.skillmanager.model.PluginParser.load(dir).name();
            }
            if (dev.skillmanager.model.HarnessParser.looksLikeHarness(dir)) {
                return dev.skillmanager.model.HarnessParser.load(dir).name();
            }
            if (dev.skillmanager.model.DocRepoParser.looksLikeDocRepo(dir)) {
                return dev.skillmanager.model.DocRepoParser.load(dir).name();
            }
            java.nio.file.Path skillMd = dir.resolve(dev.skillmanager.model.SkillParser.SKILL_FILENAME);
            if (java.nio.file.Files.isRegularFile(skillMd)) {
                return dev.skillmanager.model.SkillParser.load(dir).name();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String unitName(Coord c) {
        return switch (c) {
            case Coord.Bare b -> b.name();
            case Coord.Kinded k -> k.name();
            case Coord.SubElement s -> unitName(s.unitCoord());
            case Coord.DirectGit g -> null;
            case Coord.Local l -> null;
        };
    }
}
