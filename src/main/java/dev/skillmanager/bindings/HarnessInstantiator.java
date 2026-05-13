package dev.skillmanager.bindings;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plans the {@link BindingSource#HARNESS} bindings a harness template
 * (#47) produces when instantiated into {@code <sandboxRoot>/<instanceId>}.
 *
 * <p>The plan is exhaustive over the template's referenced units:
 * <ul>
 *   <li>Skills + plugins → one SYMLINK Binding each, targetRoot
 *       = the sandbox dir, source = HARNESS.</li>
 *   <li>Doc-repo sub-elements → one Binding per referenced source via
 *       {@link DocRepoBinder}, source = HARNESS, with stable ids
 *       {@code harness:<instanceId>:<repoName>:<sourceId>}.</li>
 *   <li>Whole-doc-repo references → fan out to N sub-element bindings,
 *       one per declared {@code [[sources]]} row.</li>
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

    public record Plan(List<Binding> bindings) {
        public Plan { bindings = List.copyOf(bindings); }
    }

    /**
     * @param harness     the resolved HarnessUnit (sourcePath = store dir)
     * @param instanceId  a stable identifier — defaults to {@code <name>-<short-sha>}
     *                    in the CLI; tests pass arbitrary strings
     * @param sandboxRoot the root under which {@code <instanceId>/} lives —
     *                    typically {@code $SKILL_MANAGER_HOME/harnesses/instances/}
     */
    public static Plan plan(HarnessUnit harness, String instanceId,
                            Path sandboxRoot, SkillStore store) throws IOException {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("harness instanceId must not be blank");
        }
        Path instanceDir = sandboxRoot.resolve(instanceId);
        List<Binding> all = new ArrayList<>();

        // --- referenced skills + plugins ---
        for (UnitReference ref : harness.units()) {
            NameKind nk = resolveReference(ref, store).orElseThrow(() -> new IOException(
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
            Path dest = instanceDir.resolve(subdirFor(nk.kind())).resolve(nk.name());
            Projection sym = new Projection(bindingId, source, dest,
                    ProjectionKind.SYMLINK, null);
            all.add(new Binding(
                    bindingId, nk.name(), nk.kind(), null,
                    instanceDir, ConflictPolicy.OVERWRITE,
                    BindingStore.nowIso(), BindingSource.HARNESS,
                    List.of(sym)));
        }

        // --- referenced doc-repo sources ---
        for (UnitReference ref : harness.docs()) {
            DocRef d = resolveDocReference(ref, store).orElseThrow(() -> new IOException(
                    "harness " + harness.name() + " references doc-repo '"
                            + ref.coord().raw() + "' which is not installed"));
            DocUnit docUnit = DocRepoParser.load(store.unitDir(d.repoName(), UnitKind.DOC));
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    docUnit, instanceDir, d.sourceId(),
                    ConflictPolicy.OVERWRITE,
                    BindingSource.HARNESS,
                    src -> docBindingId(instanceId, docUnit.name(), src.id()));
            all.addAll(plan.bindings());
        }

        return new Plan(all);
    }

    /** Stable id for a skill/plugin harness binding. */
    public static String unitBindingId(String instanceId, String unitName) {
        return "harness:" + instanceId + ":" + unitName;
    }

    /** Stable id for a doc-repo source harness binding. */
    public static String docBindingId(String instanceId, String repoName, String sourceId) {
        return "harness:" + instanceId + ":" + repoName + ":" + sourceId;
    }

    /** Storage layout inside the instance sandbox dir. */
    private static String subdirFor(UnitKind kind) {
        return switch (kind) {
            case SKILL -> "skills";
            case PLUGIN -> "plugins";
            case DOC, HARNESS -> throw new IllegalStateException(
                    "harness sandbox subdirs only defined for skills + plugins");
        };
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
    private static Optional<NameKind> resolveReference(UnitReference ref, SkillStore store) {
        UnitStore us = new UnitStore(store);
        Coord c = ref.coord();
        String name = unitName(c);
        if (name == null) {
            // Local refs: parse the manifest at the path to find the name.
            name = nameFromLocalManifest(c);
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
    private static Optional<DocRef> resolveDocReference(UnitReference ref, SkillStore store) {
        UnitStore us = new UnitStore(store);
        Coord c = ref.coord();
        String repoName = unitName(c);
        if (repoName == null) {
            repoName = nameFromLocalManifest(c);
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
    private static String nameFromLocalManifest(Coord c) {
        if (!(c instanceof Coord.Local l)) return null;
        java.nio.file.Path dir = java.nio.file.Path.of(l.path()).toAbsolutePath();
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
