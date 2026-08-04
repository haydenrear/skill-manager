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
     *
     * @return what actually happened, so a caller can tell a run that installed
     *         something from a run that found everything already present. A
     *         backend that cannot tell should return
     *         {@link InstallOutcome#INSTALLED}: over-reporting an event costs
     *         one console line, under-reporting one hides work that was done.
     */
    InstallOutcome install(CliDependency dep, SkillStore store, String skillName)
            throws IOException;

    /**
     * Variant for callers that need to force a backend-specific replay.
     * Most backends do not have a replay gate, so the default preserves the
     * existing install behavior.
     */
    default InstallOutcome install(CliDependency dep, SkillStore store, String skillName,
                                   boolean force) throws IOException {
        return install(dep, store, skillName);
    }

    /**
     * Whether {@code dep} is already satisfied for the home rooted at
     * {@code store}, so this backend has nothing to do.
     *
     * <p>Delegates to {@link CliPresence}; see that class for why "is it on
     * PATH" was the wrong question and what replaced it. There used to be an
     * {@code isOnPath(String)} default here and every backend opened with it —
     * which is how one shorthand became four copies of the same defect. It is
     * gone rather than sharpened: a method on the interface every backend
     * implements, named for the check they must not perform, is how this
     * recurs.
     */
    default boolean alreadyProvided(CliDependency dep, SkillStore store) {
        return CliPresence.alreadyProvided(dep, store);
    }
}
