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
