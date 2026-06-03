package dev.skillmanager.project;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.SkillProject;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates the project-local Skill Manager home used as the runtime harness for
 * a resolved skill project.
 */
public final class ProjectChildHomeScaffolder {

    private final SkillStore parentStore;

    public ProjectChildHomeScaffolder(SkillStore parentStore) {
        this.parentStore = parentStore;
    }

    public record Result(
            String id,
            ChildHomeHarnessInstaller.Layout layout,
            SkillStore childStore,
            List<String> childUnits
    ) {}

    public Result scaffold(SkillProject project, List<SkillProjectLock.ResolvedUnit> resolvedUnits)
            throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        parentStore.init();
        ChildHomeHarnessInstaller.Layout layout = layout(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        childStore.init();
        Fs.ensureDir(layout.claudeHome());
        Fs.ensureDir(layout.codexHome());
        Fs.ensureDir(layout.geminiHome());

        UnitStore parentUnits = new UnitStore(parentStore);
        UnitStore childUnits = new UnitStore(childStore);
        Set<String> claims = new LinkedHashSet<>();
        Set<String> desiredKeys = new LinkedHashSet<>();
        List<String> rendered = new ArrayList<>();
        for (SkillProjectLock.ResolvedUnit unit : resolvedUnits == null
                ? List.<SkillProjectLock.ResolvedUnit>of()
                : resolvedUnits) {
            InstalledUnit record = parentUnits.read(unit.name()).orElseThrow(() ->
                    new IOException("project resolved unit is not installed: " + unit.name()));
            projectInstalledUnit(record, childStore, childUnits);
            claims.add(record.name());
            desiredKeys.add(record.unitKind() + ":" + record.name());
            rendered.add(record.unitKind().name().toLowerCase() + ":" + record.name());
        }
        pruneOldUnits(childStore, childUnits, desiredKeys);
        mirrorToolShims(childStore);

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
        return new Result(id, layout, childStore, List.copyOf(rendered));
    }

    private static ChildHomeHarnessInstaller.Layout layout(SkillProject project) {
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

    private static void pruneOldUnits(SkillStore childStore, UnitStore childUnits, Set<String> desiredKeys)
            throws IOException {
        for (AgentUnit existing : childStore.listInstalledUnits().units()) {
            String key = existing.kind() + ":" + existing.name();
            if (desiredKeys.contains(key)) continue;
            Fs.deleteRecursive(childStore.unitDir(existing.name(), existing.kind()));
            childUnits.delete(existing.name());
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
                if (sameRealPath(source, dest)) return;
                Fs.deleteRecursive(dest);
            } else {
                throw new IOException("project child home path already exists: " + dest);
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

    private static boolean sameRealPath(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
