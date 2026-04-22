package dev.skillmanager.cli.installer;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InstallerRegistry {

    private final Map<String, InstallerBackend> backends = new LinkedHashMap<>();

    public InstallerRegistry() {
        register(new TarBackend());
        register(new PipBackend());
        register(new NpmBackend());
        register(new BrewBackend());
    }

    public void register(InstallerBackend backend) {
        backends.put(backend.id(), backend);
    }

    public InstallerBackend get(String id) {
        return backends.get(id);
    }

    /** Install every CLI dep declared by every skill, preserving skill-name context for per-skill isolation. */
    public void installFor(List<Skill> skills, SkillStore store) throws IOException {
        Fs.ensureDir(store.cliBinDir());
        for (Skill s : skills) {
            for (CliDependency dep : s.cliDependencies()) {
                installOne(dep, store, s.name());
            }
        }
    }

    public void installOne(CliDependency dep, SkillStore store, String skillName) throws IOException {
        String id = dep.backend();
        InstallerBackend backend = backends.get(id);
        if (backend == null) {
            Log.warn("cli: unknown backend '%s' for %s (supported: %s)", id, dep.name(), backends.keySet());
            return;
        }
        if (!backend.available()) {
            Log.warn("cli: backend %s not available on this host; skipping %s", id, dep.name());
            return;
        }
        backend.install(dep, store, skillName);
    }
}
