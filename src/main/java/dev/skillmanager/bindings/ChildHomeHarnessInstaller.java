package dev.skillmanager.bindings;

import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            HarnessInstantiator.Plan harnessPlan
    ) {}

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
        Map<String, InstalledUnit> projected = new LinkedHashMap<>();
        projectInstalledUnit(harnessRecord, childStore, childUnits);
        projected.put(key(harnessRecord), harnessRecord);
        for (Binding b : parentPlan.bindings()) {
            InstalledUnit record = parentUnits.read(b.unitName()).orElseThrow(() ->
                    new IOException("harness " + harnessName + " resolved " + b.unitName()
                            + " but parent installed record is missing"));
            projectInstalledUnit(record, childStore, childUnits);
            projected.put(key(record), record);
        }

        HarnessUnit childHarness = HarnessParser.load(childStore.unitDir(harnessName, UnitKind.HARNESS));
        HarnessInstantiator.Plan childPlan = HarnessInstantiator.plan(
                childHarness, id, layout.claudeHome(), layout.codexHome(), layout.geminiHome(),
                layout.targetDir(), childStore);
        mirrorToolShims(childStore);

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
        new ChildHomeRegistry(parentStore).write(new ChildHomeRegistry.ChildHomeRecord(
                id,
                parentStore.root().toString(),
                layout.childSkillManagerHome().toString(),
                harnessName,
                childPlan.bindings().stream()
                        .map(Binding::unitName)
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList(),
                BindingStore.nowIso()));

        List<String> names = projected.values().stream()
                .map(u -> u.unitKind().name().toLowerCase() + ":" + u.name())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new Result(harnessName, id, layout, names, childPlan);
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

    private void projectInstalledUnit(InstalledUnit record, SkillStore childStore,
                                      UnitStore childUnits) throws IOException {
        Path source = parentStore.unitDir(record.name(), record.unitKind()).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("parent unit directory missing for " + record.unitKind()
                    + ":" + record.name() + " at " + source);
        }
        Path dest = childStore.unitDir(record.name(), record.unitKind()).toAbsolutePath().normalize();
        linkOrCopy(source, dest);
        childUnits.write(record);
    }

    private void mirrorToolShims(SkillStore childStore) throws IOException {
        for (AgentUnit unit : childStore.listInstalledUnits().units()) {
            for (var dep : unit.cliDependencies()) {
                mirrorExistingShim(parentStore.cliBinDir().resolve(dep.name()),
                        childStore.cliBinDir().resolve(dep.name()));
            }
            for (var dep : unit.mcpDependencies()) {
                mirrorExistingShim(parentStore.mcpBinDir().resolve(dep.name()),
                        childStore.mcpBinDir().resolve(dep.name()));
            }
        }
    }

    private static void mirrorExistingShim(Path source, Path dest) throws IOException {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            linkOrCopy(source.toAbsolutePath().normalize(), dest.toAbsolutePath().normalize());
        }
    }

    private static void linkOrCopy(Path source, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(dest)) {
                Path existing = Files.readSymbolicLink(dest);
                Path normalizedExisting = existing.isAbsolute()
                        ? existing.normalize()
                        : dest.getParent().resolve(existing).normalize();
                if (normalizedExisting.equals(source)) return;
                Files.delete(dest);
            } else if (Files.isDirectory(dest)) {
                return;
            } else {
                throw new IOException("child home path already exists: " + dest);
            }
        }
        try {
            Files.createSymbolicLink(dest, source);
        } catch (UnsupportedOperationException | IOException sym) {
            if (Files.isDirectory(source)) {
                Fs.copyRecursive(source, dest);
            } else {
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String key(InstalledUnit unit) {
        return unit.unitKind() + ":" + unit.name();
    }
}
