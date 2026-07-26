package dev.skillmanager.bindings;

import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs a harness into a project-local child Skill Manager home.
 */
public final class ChildHomeHarnessInstaller {

    private final SkillStore parentStore;

    public ChildHomeHarnessInstaller(SkillStore parentStore) {
        this.parentStore = parentStore;
    }

    public record Layout(
            Path targetDir,
            Path childSkillManagerHome,
            Path claudeHome,
            Path codexHome,
            Path geminiHome
    ) {}

    public record Result(
            String harnessName,
            String instanceId,
            Layout layout,
            List<String> childUnits,
            HarnessInstantiator.Plan harnessPlan,
            List<ChildHomeMaterializer.UnitOutcome> heldBack
    ) {
        public Result {
            heldBack = heldBack == null ? List.of() : List.copyOf(heldBack);
        }
    }

    public Result instantiate(String harnessName, String instanceId, Path targetDir,
                              GatewayConfig gateway, boolean json) throws IOException {
        parentStore.init();
        String id = instanceId != null && !instanceId.isBlank() ? instanceId : harnessName;
        if (harnessName == null || harnessName.isBlank()) {
            throw new IOException("harness name must not be blank");
        }
        if (targetDir == null) {
            throw new IOException("child home target directory is required");
        }

        UnitStore parentUnits = new UnitStore(parentStore);
        InstalledUnit harnessRecord = parentUnits.read(harnessName).orElseThrow(() ->
                new IOException("not installed: " + harnessName + " - `skill-manager install harness:"
                        + harnessName + "` first"));
        if (harnessRecord.unitKind() != UnitKind.HARNESS) {
            throw new IOException(harnessName + " is not a harness template (kind="
                    + harnessRecord.unitKind() + ")");
        }

        Layout layout = layout(targetDir);
        HarnessUnit parentHarness = HarnessParser.load(parentStore.unitDir(harnessName, UnitKind.HARNESS));
        HarnessInstantiator.Plan parentPlan = HarnessInstantiator.plan(
                parentHarness, id, layout.claudeHome(), layout.codexHome(), layout.geminiHome(),
                layout.targetDir(), parentStore);

        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        childStore.init();
        UnitStore childUnits = new UnitStore(childStore);
        ChildHomeMaterializer materializer = new ChildHomeMaterializer(parentStore, childStore);
        materializer.cleanStaging();
        List<ChildHomeMaterializer.UnitOutcome> heldBack = new ArrayList<>();
        Map<String, InstalledUnit> projected = new LinkedHashMap<>();
        record(heldBack, projectInstalledUnit(harnessRecord, materializer, childUnits));
        projected.put(key(harnessRecord), harnessRecord);
        for (Binding b : parentPlan.bindings()) {
            InstalledUnit unitRecord = parentUnits.read(b.unitName()).orElseThrow(() ->
                    new IOException("harness " + harnessName + " resolved " + b.unitName()
                            + " but parent installed record is missing"));
            record(heldBack, projectInstalledUnit(unitRecord, materializer, childUnits));
            projected.put(key(unitRecord), unitRecord);
        }

        HarnessUnit childHarness = HarnessParser.load(childStore.unitDir(harnessName, UnitKind.HARNESS));
        HarnessInstantiator.Plan childPlan = HarnessInstantiator.plan(
                childHarness, id, layout.claudeHome(), layout.codexHome(), layout.geminiHome(),
                layout.targetDir(), childStore);
        mirrorToolShims(childStore, materializer);
        materializer.cleanStaging();

        List<SkillEffect> effects = new ArrayList<>();
        for (Binding b : childPlan.bindings()) {
            for (Projection p : b.projections()) {
                effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
            }
            effects.add(new SkillEffect.CreateBinding(b));
        }
        Executor.Outcome<Void> outcome = new Executor(parentStore, gateway, json)
                .run(new Program<>("child-home-harness-" + id, effects, receipts -> null));
        if (outcome.rolledBack()) {
            throw new IOException("child home harness instantiate rolled back "
                    + outcome.applied().size() + " effect(s)");
        }
        new HarnessInstanceLock(harnessName, id,
                layout.claudeHome(), layout.codexHome(), layout.geminiHome(), layout.targetDir(),
                BindingStore.nowIso())
                .write(parentStore.harnessesDir()
                        .resolve(dev.skillmanager.commands.HarnessCommand.INSTANCES_DIR));
        List<String> claimedUnits = new ArrayList<>();
        claimedUnits.add(harnessName);
        for (Binding binding : childPlan.bindings()) {
            claimedUnits.add(binding.unitName());
        }
        new ChildHomeRegistry(parentStore).write(new ChildHomeRegistry.ChildHomeRecord(
                id,
                parentStore.root().toString(),
                layout.childSkillManagerHome().toString(),
                harnessName,
                claimedUnits.stream()
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList(),
                BindingStore.nowIso()));

        List<String> names = projected.values().stream()
                .map(u -> u.unitKind().name().toLowerCase() + ":" + u.name())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new Result(harnessName, id, layout, names, childPlan, List.copyOf(heldBack));
    }

    public static Layout layout(Path targetDir) {
        Path target = targetDir.toAbsolutePath().normalize();
        return new Layout(
                target,
                target.resolve(".skill-manager"),
                target.resolve(".claude"),
                target.resolve(".codex"),
                target.resolve(".gemini"));
    }

    /**
     * Materializes one parent unit into the child home.
     *
     * <p>Routed through {@link ChildHomeMaterializer} with
     * {@link MaterializationMode#COPY}: this installer writes into the very
     * same {@code <dir>/.skill-manager} layout that {@code project resolve}
     * uses, so if it symlinked the units back at the parent store it would
     * re-open the write-through hole in a directory the project flow had
     * already isolated.
     */
    private ChildHomeMaterializer.UnitOutcome projectInstalledUnit(
            InstalledUnit record, ChildHomeMaterializer materializer, UnitStore childUnits)
            throws IOException {
        ChildHomeMaterializer.UnitOutcome outcome = materializer.materializeUnit(
                record.name(), record.unitKind(), MaterializationMode.COPY);
        if (!outcome.heldBack()) {
            childUnits.write(record);
        } else if (childUnits.read(record.name()).isEmpty()) {
            // Held back: the child tree is the agent's, so do not advertise the
            // parent's git sha for it.
            childUnits.write(new InstalledUnit(
                    record.name(), record.version(), record.kind(), record.installSource(),
                    record.origin(), null, record.gitRef(), record.installedAt(),
                    record.errors(), record.unitKind()));
        }
        return outcome;
    }

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

    private static void record(List<ChildHomeMaterializer.UnitOutcome> heldBack,
                               ChildHomeMaterializer.UnitOutcome outcome) {
        if (outcome.heldBack()) heldBack.add(outcome);
    }

    private static String key(InstalledUnit unit) {
        return unit.unitKind() + ":" + unit.name();
    }
}
