package dev.skillmanager.project;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A registered project's own manifest could not be read, so nothing can say
 * which units the project declares today (#394). Callers that would prune a
 * child home on the strength of a manifest leave it untouched instead: "cannot
 * tell" is not "undeclared".
 */
public final class ProjectManifestUnreadableException extends IOException {

    private final String projectName;
    private final Path manifest;

    public ProjectManifestUnreadableException(String projectName, Path manifest, String reason) {
        super(projectName + ": " + reason + " (" + manifest + ") — its child home was left "
                + "untouched rather than judged against the registry's cached copy. Repair: "
                + "skill-manager project register --project-dir <repo> --manifest <repo>/<manifest>");
        this.projectName = projectName;
        this.manifest = manifest;
    }

    public String projectName() { return projectName; }

    public Path manifest() { return manifest; }
}
