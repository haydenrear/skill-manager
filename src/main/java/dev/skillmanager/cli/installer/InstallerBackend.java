package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;

public interface InstallerBackend {

    String id();

    boolean available();

    /**
     * Install {@code dep} requested by {@code skillName}. Each backend lands its
     * artifact(s) in {@link SkillStore#cliBinDir()} (via direct copy or symlink)
     * so a user only has to add one directory to PATH.
     */
    void install(CliDependency dep, SkillStore store, String skillName) throws IOException;

    /**
     * Variant for callers that need to force a backend-specific replay.
     * Most backends do not have a replay gate, so the default preserves the
     * existing install behavior.
     */
    default void install(CliDependency dep, SkillStore store, String skillName,
                         boolean force) throws IOException {
        install(dep, store, skillName);
    }

    default boolean isOnPath(String executable) {
        if (executable == null) return false;
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            java.nio.file.Path candidate = java.nio.file.Path.of(part, executable);
            if (java.nio.file.Files.isExecutable(candidate)) return true;
        }
        return false;
    }
}
