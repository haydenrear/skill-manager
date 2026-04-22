package dev.skillmanager.cli;

import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.List;

/** Thin adapter over the backend-dispatching registry. */
public final class CliInstaller {

    private final SkillStore store;
    private final InstallerRegistry registry;

    public CliInstaller(SkillStore store) { this(store, new InstallerRegistry()); }

    public CliInstaller(SkillStore store, InstallerRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    public void installFor(List<Skill> skills) throws IOException {
        registry.installFor(skills, store);
    }
}
