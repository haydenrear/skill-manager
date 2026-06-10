package dev.skillmanager.lock;

import dev.skillmanager.model.CliDependency;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared uninstall/rollback cleanup for CLI dependencies.
 *
 * <p>CLI ownership is keyed the same way {@link CliLock} is keyed:
 * backend + requested tool. The manifest's display name is not enough
 * because aliases and package specs can differ while still mapping to
 * the same managed CLI row.
 */
public final class CliDependencyCleaner {

    private CliDependencyCleaner() {}

    public record Result(
            String backend,
            String tool,
            boolean claimedByOtherUnit,
            boolean lockUpdated,
            boolean lockRemoved,
            List<Path> removedPaths
    ) {
        public Result {
            removedPaths = removedPaths == null ? List.of() : List.copyOf(removedPaths);
        }
    }

    public static Result pruneIfOrphan(SkillStore store, String unitName, CliDependency dep)
            throws IOException {
        String backend = dep.backend();
        String tool = RequestedVersion.of(dep).tool();
        List<String> survivorClaimers = survivorClaimers(store, unitName, backend, tool);
        boolean claimed = !survivorClaimers.isEmpty();

        CliLock lock = CliLock.load(store);
        CliLock.Entry existing = lock.get(backend, tool);
        boolean lockUpdated = false;
        boolean lockRemoved = false;
        if (existing != null) {
            if (claimed) {
                lock.put(new CliLock.Entry(
                        existing.backend(),
                        existing.tool(),
                        existing.version(),
                        existing.spec(),
                        existing.sha256(),
                        survivorClaimers,
                        existing.installedAt(),
                        existing.installFingerprint()));
                lockUpdated = true;
            } else {
                lock.remove(backend, tool);
                lockRemoved = true;
            }
            lock.save(store);
        }

        List<Path> removed = claimed ? List.of() : removeArtifacts(store, dep, tool);
        return new Result(backend, tool, claimed, lockUpdated, lockRemoved, removed);
    }

    public static boolean isClaimedByOtherUnit(SkillStore store, String unitName, CliDependency dep) {
        String backend = dep.backend();
        String tool = RequestedVersion.of(dep).tool();
        try {
            return !survivorClaimers(store, unitName, backend, tool).isEmpty();
        } catch (IOException io) {
            Log.warn("cli cleanup: could not list installed units while checking %s.%s: %s",
                    backend, tool, io.getMessage());
            return true;
        }
    }

    private static List<String> survivorClaimers(SkillStore store, String unitName,
                                                 String backend, String tool) throws IOException {
        List<String> out = new ArrayList<>();
        var listed = store.listInstalledUnits();
        for (var u : listed.units()) {
            if (u.name().equals(unitName)) continue;
            for (CliDependency candidate : u.cliDependencies()) {
                if (sameCliIdentity(candidate, backend, tool)) {
                    out.add(u.name());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean sameCliIdentity(CliDependency dep, String backend, String tool) {
        return Objects.equals(dep.backend(), backend)
                && Objects.equals(RequestedVersion.of(dep).tool(), tool);
    }

    private static List<Path> removeArtifacts(SkillStore store, CliDependency dep, String tool)
            throws IOException {
        Set<Path> candidates = new LinkedHashSet<>();
        addUnder(candidates, store.cliBinDir(), dep.name());
        addUnder(candidates, store.cliBinDir(), dep.onPath());
        addUnder(candidates, store.cliBinDir(), tool);
        for (CliDependency.InstallTarget target : dep.install().values()) {
            addUnder(candidates, store.cliBinDir(), target.binary());
        }

        if ("pip".equals(dep.backend())) {
            addUnder(candidates, store.venvsDir(), tool);
            addUnder(candidates, store.venvsDir(), dep.name());
        } else if ("tar".equals(dep.backend())) {
            addUnder(candidates, store.cacheDir(), "cli-" + dep.name());
        }

        List<Path> removed = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
                Fs.deleteRecursive(candidate);
                removed.add(candidate);
            }
        }
        return removed;
    }

    private static void addUnder(Set<Path> out, Path root, String relative) {
        if (relative == null || relative.isBlank()) return;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(relative).normalize();
        if (candidate.startsWith(normalizedRoot)) {
            out.add(candidate);
        }
    }
}
