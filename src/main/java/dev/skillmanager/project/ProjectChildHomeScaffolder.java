package dev.skillmanager.project;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates the project-local Skill Manager home used as the runtime harness for
 * a resolved skill project.
 *
 * <p>Units are materialized by {@link ChildHomeMaterializer}, which also owns
 * the isolation guarantee and the refusal to overwrite locally-modified child
 * units. Units it held back are reported in {@link Result#heldBack()}.
 */
public final class ProjectChildHomeScaffolder {

    /**
     * Materialization used by project child homes when a caller does not pick
     * one. Project child homes exist so an agent can edit its own units, so
     * this is {@link MaterializationMode#COPY}.
     */
    public static final MaterializationMode DEFAULT_MODE = MaterializationMode.COPY;

    private final SkillStore parentStore;

    public ProjectChildHomeScaffolder(SkillStore parentStore) {
        this.parentStore = parentStore;
    }

    public record Result(
            String id,
            ChildHomeHarnessInstaller.Layout layout,
            SkillStore childStore,
            List<String> childUnits,
            List<ChildHomeMaterializer.UnitOutcome> heldBack
    ) {
        public Result {
            heldBack = heldBack == null ? List.of() : List.copyOf(heldBack);
        }
    }

    /** Scaffolds with {@link #DEFAULT_MODE}. */
    public Result scaffold(SkillProject project, List<SkillProjectLock.ResolvedUnit> resolvedUnits)
            throws IOException {
        return scaffold(project, resolvedUnits, DEFAULT_MODE);
    }

    public Result scaffold(SkillProject project,
                           List<SkillProjectLock.ResolvedUnit> resolvedUnits,
                           MaterializationMode mode)
            throws IOException {
        return scaffold(project, resolvedUnits, mode, Set.of());
    }

    /**
     * @param checkoutUnits units to materialize as {@link MaterializationMode#CHECKOUT}
     *        instead of {@code mode}. Naming them here — rather than switching the
     *        whole child home — is what keeps the mode a per-unit fact, and the
     *        materializer persists it per unit so a later pass with the ordinary
     *        default does not clobber the checkout. See
     *        {@link MaterializationMode} for why that matters.
     */
    public Result scaffold(SkillProject project,
                           List<SkillProjectLock.ResolvedUnit> resolvedUnits,
                           MaterializationMode mode,
                           Set<String> checkoutUnits)
            throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        MaterializationMode materialization = mode == null ? DEFAULT_MODE : mode;
        Set<String> checkouts = checkoutUnits == null ? Set.of() : checkoutUnits;
        parentStore.init();
        ChildHomeHarnessInstaller.Layout layout = layoutFor(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        childStore.init();
        Fs.ensureDir(layout.claudeHome());
        Fs.ensureDir(layout.codexHome());
        Fs.ensureDir(layout.geminiHome());

        UnitStore parentUnits = new UnitStore(parentStore);
        UnitStore childUnits = new UnitStore(childStore);
        ChildHomeMaterializer materializer = new ChildHomeMaterializer(parentStore, childStore);
        materializer.cleanStaging();
        Set<String> claims = new LinkedHashSet<>();
        Set<String> desiredKeys = new LinkedHashSet<>();
        List<String> rendered = new ArrayList<>();
        List<ChildHomeMaterializer.UnitOutcome> heldBack = new ArrayList<>();
        for (SkillProjectLock.ResolvedUnit unit : resolvedUnits == null
                ? List.<SkillProjectLock.ResolvedUnit>of()
                : resolvedUnits) {
            InstalledUnit record = parentUnits.read(unit.name()).orElseThrow(() ->
                    new IOException("project resolved unit is not installed: " + unit.name()));
            ChildHomeMaterializer.UnitOutcome outcome = materializer.materializeUnit(
                    record.name(), record.unitKind(),
                    checkouts.contains(record.name())
                            ? MaterializationMode.CHECKOUT
                            : materialization);
            if (outcome.heldBack()) heldBack.add(outcome);
            writeChildRecord(childUnits, record, outcome.heldBack());
            claims.add(record.name());
            desiredKeys.add(record.unitKind() + ":" + record.name());
            rendered.add(record.unitKind().name().toLowerCase() + ":" + record.name());
        }
        heldBack.addAll(pruneOldUnits(childStore, childUnits, materializer, desiredKeys));
        mirrorToolShims(childStore, materializer);
        materializer.cleanStaging();

        String id = project.childHomeId();
        List<String> sortedClaims = claims.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        new ChildHomeRegistry(parentStore).write(new ChildHomeRegistry.ChildHomeRecord(
                id,
                parentStore.root().toString(),
                layout.childSkillManagerHome().toString(),
                null,
                sortedClaims,
                BindingStore.nowIso()));
        rendered.sort(String.CASE_INSENSITIVE_ORDER);
        return new Result(id, layout, childStore, List.copyOf(rendered), List.copyOf(heldBack));
    }

    public static ChildHomeHarnessInstaller.Layout layoutFor(SkillProject project) {
        if (project.activeProfile() == null) {
            return ChildHomeHarnessInstaller.layout(project.projectRoot());
        }
        Path profileRoot = project.projectRoot()
                .resolve(".skill-manager")
                .resolve("profiles")
                .resolve(safeSegment(project.activeProfile()))
                .toAbsolutePath()
                .normalize();
        return new ChildHomeHarnessInstaller.Layout(
                profileRoot,
                profileRoot,
                profileRoot.resolve("agents/claude"),
                profileRoot.resolve("agents/codex"),
                profileRoot.resolve("agents/gemini"));
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Mirrors CLI/MCP shims for whatever units the child home now holds.
     * Shims are always symlinked at the parent bin entry — see
     * {@link ChildHomeMaterializer#mirrorExistingShim(Path, Path)} for why
     * they do not follow the unit materialization mode.
     */
    private void mirrorToolShims(SkillStore childStore, ChildHomeMaterializer materializer)
            throws IOException {
        for (AgentUnit unit : childStore.listInstalledUnits().units()) {
            for (var dep : unit.cliDependencies()) {
                materializer.mirrorExistingShim(parentStore.cliBinDir().resolve(dep.name()),
                        childStore.cliBinDir().resolve(dep.name()));
            }
            for (var dep : unit.mcpDependencies()) {
                materializer.mirrorExistingShim(parentStore.mcpBinDir().resolve(dep.name()),
                        childStore.mcpBinDir().resolve(dep.name()));
            }
        }
    }

    /**
     * Drops child-home units the project no longer depends on — unless the
     * agent edited one, in which case the tree is left in place and reported,
     * exactly like a refresh that would have overwritten it. "No longer a
     * dependency" is not a licence to delete someone's work.
     *
     * <p>Skipped entirely when the child home resolves to the parent store
     * itself: in that degenerate layout every "stale" child unit is really a
     * parent unit, and pruning would delete units the parent still owns.
     * Per-unit materialization and shim mirroring each carry their own guard
     * for the same layout.
     */
    private List<ChildHomeMaterializer.UnitOutcome> pruneOldUnits(
            SkillStore childStore, UnitStore childUnits,
            ChildHomeMaterializer materializer, Set<String> desiredKeys)
            throws IOException {
        List<ChildHomeMaterializer.UnitOutcome> heldBack = new ArrayList<>();
        if (sameRealPath(childStore.root(), parentStore.root())) return heldBack;
        for (AgentUnit existing : childStore.listInstalledUnits().units()) {
            String key = existing.kind() + ":" + existing.name();
            if (desiredKeys.contains(key)) continue;
            Path unitDir = childStore.unitDir(existing.name(), existing.kind());
            if (materializer.isLocallyModified(existing.name(), existing.kind())) {
                Log.warn("child home %s:%s is no longer a project dependency but has local "
                                + "changes — left in place (%s)",
                        existing.kind().name().toLowerCase(), existing.name(), unitDir);
                heldBack.add(new ChildHomeMaterializer.UnitOutcome(
                        existing.name(), existing.kind(),
                        ChildHomeMaterializer.Status.SKIPPED_LOCAL_CHANGES, unitDir,
                        "local changes in a unit the project no longer depends on"));
                continue;
            }
            Fs.deleteRecursive(unitDir);
            childUnits.delete(existing.name());
            materializer.forgetUnit(existing.name(), existing.kind());
        }
        return heldBack;
    }

    /**
     * Keeps the child home's installed record honest: a held-back unit is the
     * agent's tree, not the parent's, so an existing child record is left alone
     * and a missing one is written without the parent's git sha rather than
     * advertising content that is not there.
     */
    private static void writeChildRecord(UnitStore childUnits, InstalledUnit record,
                                         boolean heldBack) throws IOException {
        if (!heldBack) {
            childUnits.write(record);
            return;
        }
        if (childUnits.read(record.name()).isPresent()) return;
        childUnits.write(new InstalledUnit(
                record.name(), record.version(), record.kind(), record.installSource(),
                record.origin(), null, record.gitRef(), record.installedAt(),
                record.errors(), record.unitKind()));
    }

    private static boolean sameRealPath(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
