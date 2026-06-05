package dev.skillmanager.project;

import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProjectRealizationSnapshot implements AutoCloseable {

    private final Path tempDir;
    private final List<Entry> entries;

    private ProjectRealizationSnapshot(Path tempDir, List<Entry> entries) {
        this.tempDir = tempDir;
        this.entries = entries;
    }

    static ProjectRealizationSnapshot capture(
            SkillStore store,
            SkillProject project,
            SkillProjectLock lock
    ) throws IOException {
        Path tempRoot = store.root().resolve("tmp");
        Files.createDirectories(tempRoot);
        Path tempDir = Files.createTempDirectory(tempRoot, "project-sync-rollback-");
        Set<Path> paths = new LinkedHashSet<>();
        BindingStore bindingStore = new BindingStore(store);

        add(paths, store.projectsDir().resolve(project.registryName()));
        add(paths, new SkillProjectLockStore(store).path(project.registryName()));
        add(paths, new ChildHomeRegistry(store).file(project.childHomeId()));
        for (Path path : ProjectRemoveUseCase.generatedProjectStatePaths(project, lock)) {
            add(paths, path);
        }

        if (lock != null) {
            ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
            SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
            for (SkillProjectLock.ProjectBinding row : lock.bindings()) {
                BindingStore.LocatedBinding located =
                        bindingStore.findById(row.bindingId()).orElse(null);
                if (located != null) {
                    add(paths, bindingStore.file(located.unitName()));
                    for (Projection projection : located.binding().projections()) {
                        add(paths, projection.destPath());
                        if (projection.backupOf() != null && !projection.backupOf().isBlank()) {
                            add(paths, Path.of(projection.backupOf()));
                        }
                    }
                } else {
                    add(paths, bindingStore.file(row.unitName()));
                    addOwnedAgentProjectionPaths(paths, layout, childStore,
                            row.bindingId(), row.unitName(), row.unitKind());
                }
            }
            for (SkillProjectLock.ResolvedUnit unit : lock.resolvedUnits()) {
                addOwnedAgentProjectionPaths(paths, layout, childStore,
                        "project-sync-rollback:" + unit.name(), unit.name(), unit.kind());
            }
        }

        List<Entry> entries = new ArrayList<>();
        int index = 0;
        for (Path path : paths) {
            Path normalized = normalize(path);
            Path backup = tempDir.resolve(String.valueOf(index++));
            boolean exists = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                copyPath(normalized, backup);
            }
            entries.add(new Entry(normalized, backup, exists));
        }
        return new ProjectRealizationSnapshot(tempDir, List.copyOf(entries));
    }

    void restore() throws IOException {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(e -> e.path().getNameCount()));
        IOException failure = null;
        for (Entry entry : ordered) {
            try {
                restore(entry);
            } catch (IOException io) {
                if (failure == null) {
                    failure = io;
                } else {
                    failure.addSuppressed(io);
                }
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void close() throws IOException {
        Fs.deleteRecursive(tempDir);
    }

    private static void restore(Entry entry) throws IOException {
        if (Files.exists(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
            Fs.deleteRecursive(entry.path());
        }
        if (!entry.existed()) return;
        Path parent = entry.path().getParent();
        if (parent != null) Files.createDirectories(parent);
        copyPath(entry.backup(), entry.path());
    }

    private static void addOwnedAgentProjectionPaths(
            Set<Path> paths,
            ChildHomeHarnessInstaller.Layout layout,
            SkillStore childStore,
            String bindingId,
            String unitName,
            UnitKind kind
    ) throws IOException {
        if (kind != UnitKind.SKILL && kind != UnitKind.PLUGIN) return;
        Path source = childStore.unitDir(unitName, kind).toAbsolutePath().normalize();
        for (Projection projection : ProjectRemoveUseCase.projectAgentProjections(
                bindingId, source, layout, unitName, kind)) {
            if (ProjectRemoveUseCase.isProjectOwnedSymlink(projection.destPath(), source)) {
                add(paths, projection.destPath());
            }
        }
    }

    private static void add(Set<Path> paths, Path path) {
        if (path == null) return;
        paths.add(normalize(path));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void copyPath(Path source, Path dest) throws IOException {
        if (Files.isSymbolicLink(source)) {
            Path parent = dest.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createSymbolicLink(dest, Files.readSymbolicLink(source));
        } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Fs.copyRecursive(source, dest);
        } else {
            Path parent = dest.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private record Entry(Path path, Path backup, boolean existed) {}
}
