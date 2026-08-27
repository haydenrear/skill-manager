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
        return scaffold(project, resolvedUnits, mode, checkoutUnits, false);
    }

    /**
     * @param allowSameHome proceed even when the project's home resolves to the
     *        parent home itself. Accepted and no longer consulted; see
     *        {@link #reportSameHome}.
     */
    public Result scaffold(SkillProject project,
                           List<SkillProjectLock.ResolvedUnit> resolvedUnits,
                           MaterializationMode mode,
                           Set<String> checkoutUnits,
                           boolean allowSameHome)
            throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        MaterializationMode materialization = mode == null ? DEFAULT_MODE : mode;
        Set<String> checkouts = checkoutUnits == null ? Set.of() : checkoutUnits;
        ChildHomeHarnessInstaller.Layout layout = layoutFor(project);
        // Reported before init(), so the operator reads which layout they are
        // getting before anything is written rather than inferring it from the
        // result. `allowSameHome` is accepted and no longer consulted — the
        // condition it suppressed is not an error. See #reportSameHome.
        reportSameHome(parentStore.root(), layout.childSkillManagerHome());
        parentStore.init();
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

    /**
     * Say so, loudly, when the project's home and the parent home are the same
     * directory — and proceed.
     *
     * <h2>This was a refusal, and the refusal was wrong</h2>
     *
     * <p>It refused, and that broke the documented onboarding recipe on its
     * normal path. The recipe is: create {@code <repo>/.skill-manager}, point
     * {@code SKILL_MANAGER_HOME} at it, then {@code project resolve
     * --project-dir <repo>}. Those three steps <em>are</em> the same-home layout
     * by construction, so the guard fired on every onboarding — and a guard that
     * fires on the common case is a guard somebody deletes. Measured: all
     * sixteen onboarded homes carry a self-referential
     * {@code child-homes/project_<name>} record, so every one of them resolved
     * this way.
     *
     * <p>The deeper error was reading {@code references/skill-homes.md}'s "this
     * isolates nothing" as timeless. It was written about the older model, where
     * the parent was the single global home and a project got a child home
     * inside it. In the per-checkout model this epic exists to deliver, the
     * project home <em>is</em> the home its agents use, and units living
     * directly in it is the intended end state rather than a degenerate one. The
     * alternative — resolving with the operator's global home as the parent —
     * writes {@link ChildHomeRegistry} into that home, which is exactly what the
     * epic's standing constraint forbids.
     *
     * <h2>What #32 was actually about</h2>
     *
     * <p>Its complaint was not that the layout is wrong. It was that
     * <b>success was indistinguishable from correctness</b>: the command that
     * exists to produce an isolated home reported that it had, and an operator
     * could not tell which layout they had got. That is fixed by making the
     * outcome <em>visible</em>, not by refusing it. So this reports, names both
     * paths, and returns — and {@code --allow-same-home} is now accepted and
     * unnecessary, kept only so anything already passing it keeps working.
     *
     * <p>Compared by RESOLVED PHYSICAL PATH, not by string, with the
     * deepest-existing-ancestor walk below: the child home does not exist on a
     * first resolve, and {@code /var} vs {@code /private/var} has now defeated
     * three checks in this codebase.
     *
     * <h2>Why the old text is kept below</h2>
     *
     * <p>{@code project resolve} with {@code SKILL_MANAGER_HOME} already pointing
     * at {@code <project>/.skill-manager} exited 0 and reported every unit
     * resolved. Nothing was destroyed — three degenerate-layout guards inside
     * {@link ChildHomeMaterializer} see source and destination are the same tree
     * and decline — so this is not a data-loss bug. It is worse in one specific
     * way: <b>success was indistinguishable from correctness</b>. The command
     * that exists to produce an isolated home reported that it had, having
     * produced nothing, and an onboarding pilot walked straight into it because
     * there was no signal to walk into. {@code references/skill-homes.md} says
     * this layout "isolates nothing"; nothing enforced it. Issue #32.
     *
     * <p>Compared by RESOLVED PHYSICAL PATH, not by string. On macOS a temp home
     * handed out as {@code /var/...} really lives at {@code /private/var/...},
     * and the same two-spellings-of-one-directory shape has now defeated a
     * check in this codebase three times (the {@code [[vendored]]} validator,
     * {@link dev.skillmanager.store.HomePaths}, and the clone independence
     * check). A comparison that can be defeated by a spelling is a comparison
     * that will be.
     *
     * <p>Overridable rather than absolute: a home that is legitimately also a
     * project — the global home registering itself — is a real, if unusual,
     * layout, and a blanket refusal would break it silently in the other
     * direction. That escape hatch turned out to be needed on the <em>normal</em>
     * path, which is what made the refusal wrong; see the top of this javadoc.
     *
     * <h2>The message said one thing and the code did another</h2>
     *
     * <p>It read "units resolve in place and <b>no separate child home is
     * created</b>", and the second clause was not true of what happened next.
     * The boolean below is the only signal this method produces and
     * {@link #scaffold} discarded it: execution continued unconditionally into
     * {@code childStore.init()}, materialization, the claim set, and a
     * {@link ChildHomeRegistry} record whose {@code parentHome} and
     * {@code childHome} are the same directory. The sixteen self-referential
     * {@code child-homes/project_<name>} records this javadoc cites above as
     * evidence for the layout are the same records the message denied writing.
     *
     * <p><b>The wording changed and the behaviour did not</b>, and only after
     * establishing that the record is read. Three call sites read it:
     * {@link dev.skillmanager.app.RemoveUseCase} refuses to uninstall a unit a
     * child home claims, {@code ProjectRealizationSnapshot} captures the record
     * file so a failed {@code project sync} can roll back to it, and
     * {@code ProjectRemoveUseCase} deletes it as part of unwinding the resolve.
     * Skipping the write in the same-home case would silently change what
     * {@code project remove} unwinds and what a rollback restores — a behaviour
     * change smuggled in under a message fix, which is the shape of defect this
     * epic keeps finding. What was left to fix was the sentence, and the
     * sentence is what was wrong.
     *
     * @return true when the two are the same home, so a caller can report which
     *         layout it produced. Never throws for this condition.
     */
    public static boolean reportSameHome(Path parentHome, Path childHome) {
        Path parent = realOrNormalized(parentHome);
        Path child = realOrNormalized(childHome);
        if (!parent.equals(child)) return false;
        Log.warn("this project's home IS the parent home (%s) — a per-checkout layout: units "
                + "resolve in place rather than being copied into a separate home, and the "
                + "child-home record this resolve writes names this home as its own parent. "
                + "That is the intended shape for a repository-local home, and that record is "
                + "what `project remove` unwinds. If you meant to materialize a child home "
                + "from a DIFFERENT parent, point SKILL_MANAGER_HOME at that one first.", child);
        return true;
    }

    /**
     * {@code path} with every symlink in it resolved, whether or not the leaf
     * exists yet.
     *
     * <p>The child home usually does <em>not</em> exist on a first resolve, and
     * a plain {@link Path#toRealPath()} throws for it. Falling back to the
     * un-resolved path in that case is exactly the bug this guard exists to
     * avoid: on macOS the parent home resolves to {@code /private/var/...}
     * while the not-yet-created child stays {@code /var/...}, so two spellings
     * of one directory compare unequal and the guard passes on the case it was
     * written for. Resolve the deepest ancestor that does exist and re-append
     * the rest.
     */
    private static Path realOrNormalized(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path existing = normalized; existing != null; existing = existing.getParent()) {
            try {
                Path real = existing.toRealPath();
                Path tail = existing.relativize(normalized);
                return tail.toString().isEmpty() ? real : real.resolve(tail);
            } catch (IOException notThere) {
                // keep walking up
            }
        }
        return normalized;
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
                Path dest = childStore.cliBinDir().resolve(dep.name());
                reportKeptShim(unit.name(), "cli", dep.name(), dest,
                        materializer.mirrorExistingShim(
                                parentStore.cliBinDir().resolve(dep.name()), dest));
            }
            for (var dep : unit.mcpDependencies()) {
                Path dest = childStore.mcpBinDir().resolve(dep.name());
                reportKeptShim(unit.name(), "mcp", dep.name(), dest,
                        materializer.mirrorExistingShim(
                                parentStore.mcpBinDir().resolve(dep.name()), dest));
            }
        }
    }

    /**
     * Say when the child home's own tool was kept over the parent's shim.
     *
     * <p>Reported rather than silent, because it is a real difference between
     * the two homes; reported rather than fatal, because it is the normal state
     * of a home that has provisioned its own {@code skill-script} tools — see
     * {@link ChildHomeMaterializer.ShimOutcome#KEPT_LOCAL}. Issue #144.
     */
    public static void reportKeptShim(String unitName, String surface, String depName, Path dest,
                                      ChildHomeMaterializer.ShimOutcome outcome) {
        if (!outcome.keptLocal()) return;
        Log.warn("child home provisions %s %s itself (%s) — kept, not replaced with a link into "
                        + "the parent store (declared by %s)",
                surface, depName, dest, unitName);
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
        // AN EMPTY DESIRED SET IS NOT "THE PROJECT WANTS NOTHING".
        //
        // desiredKeys is built from the RESOLVED units. A resolve that produced
        // nothing -- the manifest could not be read, the lock was empty, the
        // network was down, the caller passed List.of() -- lands here
        // indistinguishable from a project that genuinely dropped every
        // dependency, and the loop below then deletes every CLEAN unit in the
        // child home.
        //
        // Measured on the operator's own project home, 2026-08-26: a parent
        // sync logged `resolve: 0 unit(s)`, walked its child homes, and deleted
        // the skt plugin and its installed record. `skt` stopped running in
        // that home. Four other units survived only because they were locally
        // modified, so the rule in practice was "clean units are deleted,
        // silently; edited ones are kept and reported" -- exactly backwards.
        //
        // So: no information is not a licence to delete. A project that really
        // has dropped everything can still be emptied by uninstalling the units
        // it no longer wants, which is a gesture someone makes on purpose.
        if (desiredKeys.isEmpty()) {
            long present = childStore.listInstalledUnits().units().size();
            if (present > 0) {
                Log.warn("child home: nothing resolved for this project, so %d installed unit(s) "
                                + "in %s were LEFT ALONE rather than pruned — an empty resolve "
                                + "means the project's dependencies are unknown, not empty. "
                                + "`skill-manager uninstall <unit>` removes one on purpose.",
                        present, childStore.root());
            }
            return heldBack;
        }
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
            // REPORTED, like every other outcome in this method. The
            // held-back branch above logs and reportKeptShim logs; the one
            // branch that DESTROYS was the only one that said nothing, so the
            // operator's first evidence of a deleted plugin was that the
            // command stopped existing.
            Log.warn("child home %s:%s — no longer a project dependency and unmodified, so it "
                            + "was removed from %s. Re-install it with "
                            + "`skill-manager install <source>` if that was not intended.",
                    existing.kind().name().toLowerCase(), existing.name(), unitDir);
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
