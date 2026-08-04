package dev.skillmanager.cli.installer;

import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the {@code bin/cli} entries a home can never reach a clean state
 * with, so {@code sync} is a repair for them rather than a pass that walks by.
 *
 * <h2>The two states this exists for</h2>
 *
 * <ol>
 *   <li><b>An orphan.</b> A shim no installed unit declares, pointing at
 *       something this home does not hold. {@code sync} has nothing to
 *       re-install over it — that is what "no unit declares it" means — so
 *       {@code home verify} reported it as an unresolved reference, printed
 *       {@code sync --force-scripts}, and the remedy exited without changing
 *       anything. Refused forever, with a remedy that could not work: the #142
 *       class. On real homes it arises whenever a unit is uninstalled or a tool
 *       is renamed; the {@code onboarding} graph plants one deliberately
 *       ({@code bin/cli/ob-dangling}).</li>
 *   <li><b>A foreign link.</b> A shim resolving into another Skill Manager
 *       home that is not this home's parent store. {@code CliPresence} calls it
 *       provisioned — it resolves and it is executable — so the install pass
 *       skipped it and the isolation refusal stood. Removing it first is what
 *       makes the declared ones re-provision into THIS home on the same
 *       {@code sync}.</li>
 * </ol>
 *
 * <h2>What it will not touch, and why each one is named</h2>
 *
 * <ul>
 *   <li><b>Anything that works and stays inside this home.</b> The prune is
 *       for artifacts that are broken or that leave the home; a working shim is
 *       this home's tool whether or not a manifest still spells its name. npm
 *       and brew link every executable in a package prefix, so plenty of
 *       working entries are undeclared by construction.</li>
 *   <li><b>A declared name that is merely broken.</b> Left to the install pass,
 *       which already treats it as absent
 *       ({@link CliPresence#providedByThisHome}) and reinstalls it. Deleting it
 *       first would change nothing except the blast radius when the install
 *       then fails.</li>
 *   <li><b>{@code bin/cli/skill-manager}.</b> The home's own CLI pin, written
 *       by {@code home shims} and declared by no unit by design. It is
 *       deliberately an absolute path to the build that provisioned the home,
 *       and its repair is {@code home shims}, not {@code sync} — see
 *       {@code LauncherShims}, where a stale pin failing loudly is the stated
 *       tradeoff. Pruning it would take away the loud failure and the launch
 *       surface with it.</li>
 *   <li><b>A child home's mirror of its parent's shim.</b> Sanctioned, and
 *       recognised by the isolation rule itself — see
 *       {@link HomeCloner#unsanctionedForeignHome}. This class asks that
 *       method rather than deciding for itself, so the gate and the repair
 *       cannot drift apart.</li>
 * </ul>
 */
public final class CliShimPruner {

    private CliShimPruner() {}

    /** One pruned entry and the reason {@code home verify} would have refused it. */
    public record Pruned(Path path, String reason) {
        @Override
        public String toString() { return path.getFileName() + " — " + reason; }
    }

    /**
     * Prune {@code store}'s {@code bin/cli}, returning what was removed.
     *
     * <p>Best-effort per entry: an unreadable or undeletable one is warned
     * about and skipped rather than failing the sync around it. Nothing here
     * is load-bearing for the install that follows.
     */
    public static List<Pruned> prune(SkillStore store) {
        Path binDir = store.cliBinDir();
        if (!Files.isDirectory(binDir)) return List.of();
        Set<String> declared = declaredArtifactNames(store);
        Path homeRoot = store.root().toAbsolutePath().normalize();
        List<Pruned> pruned = new ArrayList<>();
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(binDir)) {
            for (Path entry : (Iterable<Path>) stream::iterator) entries.add(entry);
        } catch (IOException cannotList) {
            Log.warn("cli: could not list %s while pruning orphan shims: %s",
                    binDir, cannotList.getMessage());
            return List.of();
        }
        entries.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (LAUNCHER_PIN.equals(name)) continue;
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
            String reason = pruneReason(entry, name, homeRoot, declared);
            if (reason == null) continue;
            try {
                Fs.deleteRecursive(entry);
                pruned.add(new Pruned(entry, reason));
                Log.info("cli: pruned bin/cli/%s — %s", name, reason);
            } catch (IOException cannotDelete) {
                Log.warn("cli: could not prune bin/cli/%s (%s): %s",
                        name, reason, cannotDelete.getMessage());
            }
        }
        return List.copyOf(pruned);
    }

    /** Why this entry must go, or null when it may stay. */
    private static String pruneReason(Path entry, String name, Path homeRoot,
                                      Set<String> declared) {
        Path foreign = HomeCloner.unsanctionedForeignHome("bin/cli/" + name, entry, homeRoot);
        if (foreign != null) {
            return "resolved into the home at " + foreign
                    + ", which is not this home's parent store";
        }
        // Null is "the manifests could not be read", which suppresses the
        // undeclared arm entirely — see declaredArtifactNames.
        if (declared == null || declared.contains(name)) return null;
        if (!refusedByVerify(entry, homeRoot)) return null;
        return "no installed unit declares it and it is "
                + CliArtifact.inspect(entry, homeRoot).reason();
    }

    /**
     * The two broken shapes {@code home verify} actually refuses on: a dangling
     * link, and a generated wrapper naming a path this home does not hold.
     *
     * <h2>Why this is not {@code CliArtifact.inspect(...).usable()}</h2>
     *
     * <p>That predicate answers a wider question — "will this run" — and one of
     * its verdicts, {@code present but not executable}, is a state verify
     * tolerates and this must too. Measured: a {@code skill-script} install
     * script keeps its run counter at {@code bin/cli/<tool>.count}, a plain
     * 644 file no manifest names. Pruning on {@code !usable()} deleted it, and
     * {@code CommandKindCoverageTest}'s "sync named --force-scripts does not
     * force unrelated installed scripts" went red because the counter it
     * asserts on had been reset. A file in {@code bin/cli} that was never an
     * executable shim is not this mechanism's garbage to collect.
     *
     * <p>So the prune stays inside what the GATE refuses. {@code CliArtifact}
     * makes the same argument in the other direction, and the reason line the
     * caller prints still comes from it, so the two never describe the same
     * artifact differently.
     */
    private static boolean refusedByVerify(Path entry, Path homeRoot) {
        if (Files.isSymbolicLink(entry)) return !Files.exists(entry);
        if (!Files.isRegularFile(entry) || !Files.isExecutable(entry)) return false;
        return !HomeCloner.missingReferencesIn(entry, homeRoot).isEmpty();
    }

    /**
     * Every {@code bin/cli} basename an installed unit could be asking for.
     *
     * <p>The same four spellings {@code CliDependencyCleaner} removes on
     * uninstall — declared name, {@code on_path}, the requested tool, and each
     * backend's declared {@code binary} — because a dep's manifest name and the
     * file it lands as differ often enough that reading only one of them would
     * call a live tool an orphan.
     *
     * <p>A store that cannot be listed yields the EMPTY set nowhere: it yields
     * a set that suppresses the whole undeclared arm, because "I could not read
     * the manifests" must not become "nothing is declared".
     */
    private static Set<String> declaredArtifactNames(SkillStore store) {
        Set<String> out = new LinkedHashSet<>();
        List<AgentUnit> units;
        try {
            units = store.listInstalledUnits().units();
        } catch (IOException cannotRead) {
            Log.warn("cli: could not list installed units while pruning orphan shims (%s) — "
                    + "no shim is treated as undeclared this pass", cannotRead.getMessage());
            return null;
        }
        for (AgentUnit unit : units) {
            for (CliDependency dep : unit.cliDependencies()) {
                add(out, dep.name());
                add(out, dep.onPath());
                add(out, RequestedVersion.of(dep).tool());
                for (CliDependency.InstallTarget target : dep.install().values()) {
                    add(out, target.binary());
                }
            }
        }
        return out;
    }

    private static void add(Set<String> out, String value) {
        if (value == null || value.isBlank()) return;
        // A declared spelling may carry a path ("bin/foo"); the entry it lands
        // as is the basename.
        try {
            Path asPath = Path.of(value).getFileName();
            if (asPath != null) out.add(asPath.toString());
        } catch (RuntimeException notAPath) {
            out.add(value);
        }
    }

    /** The one reserved {@code bin/cli} basename. See the class javadoc. */
    private static final String LAUNCHER_PIN = "skill-manager";
}
