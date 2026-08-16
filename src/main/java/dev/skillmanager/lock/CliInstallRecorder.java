package dev.skillmanager.lock;

import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.cli.installer.ProvisionTally;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;

/**
 * Runs every {@link PlanAction.RunCliInstall} in a plan, records each success
 * to the {@link CliLock}, and saves the lock once at the end.
 *
 * <p>Returns a {@link ProvisionTally} rather than printing a summary itself:
 * the handler turns it into a {@link
 * dev.skillmanager.effects.ContextFact.CliInstalledFor} fact and
 * {@code ConsoleProgramRenderer} is still the only place that prints.
 *
 * <h2>This class does not know any backend's name</h2>
 *
 * <p>It used to. The line was
 * {@code "skill-script".equals(rc.dep().backend()) ? SkillScriptBackend.fingerprintFor(…) : null}
 * — the recorder reaching past the {@link InstallerRegistry} it already holds
 * to a static on one concrete adapter, so that one backend of five recorded
 * what its artifact was derived FROM while the other four recorded nothing and
 * fell back to {@code CliPresence.alreadyProvided}, which answers whether a
 * file exists and runs. {@code LiveInterpreter.runCliInstall} carried a second
 * copy of the same branch, maintained separately and identical by hand.
 *
 * <p>{@link #record} is now the one place a lock row is written from a
 * dependency, and both callers use it.
 */
public final class CliInstallRecorder {

    private CliInstallRecorder() {}

    public static ProvisionTally run(InstallPlan plan, SkillStore store) throws IOException {
        InstallerRegistry registry = new InstallerRegistry();
        CliLock lock = CliLock.load(store);
        ProvisionTally tally = ProvisionTally.EMPTY;
        for (PlanAction a : plan.actions()) {
            if (!(a instanceof PlanAction.RunCliInstall rc)) continue;
            try {
                tally = tally.plus(
                        registry.installOne(rc.dep(), store, rc.unitName(), rc.forceScripts()));
                record(lock, registry, rc.dep(), store, rc.unitName());
            } catch (Exception e) {
                Log.warn("cli: %s failed: %s", rc.dep().name(), e.getMessage());
                tally = tally.withFailure();
            }
        }
        lock.save(store);
        return tally;
    }

    /**
     * Write {@code dep}'s row: its identity, the {@link Fingerprint} its own
     * backend computed over its declared inputs, and the binary the declaring
     * unit says this row produces.
     *
     * <p>The caller saves the lock — {@code run} saves once at the end of a bulk
     * pass and the single-dep effect path saves immediately — so this does not.
     *
     * @return the row as written
     */
    public static CliLock.Entry record(CliLock lock, InstallerRegistry registry,
                                       CliDependency dep, SkillStore store, String unitName) {
        RequestedVersion.Requested req = RequestedVersion.of(dep);
        return lock.recordInstall(dep.backend(), req.tool(), req.version(), dep.spec(),
                declaredHash(dep), unitName,
                registry.fingerprintFor(dep, store, unitName),
                producedBinary(dep));
    }

    /**
     * The binary name this dep's artifact lands under in {@code bin/cli/}.
     *
     * <p>A target's declared {@code binary} first, then {@code on_path}, then
     * nothing rather than a guess. brew and npm link every executable in a
     * package prefix, so a row's tool name is frequently not a binary at all,
     * and recording it as one would put a fabricated path into the record that
     * {@code ArtifactBackfill} probes.
     *
     * <p><b>ARTI-08 reversed the first two.</b> They used to read
     * "{@code on_path} first, because it is what the declaring unit asserts it
     * needs on PATH" — which is true and is an answer to a different question.
     * {@code on_path} is the name this dep PROBES for; {@code install.<os>.binary}
     * is the name the install PRODUCES. They are usually equal and they are
     * allowed to differ, and where they differ the old order recorded a
     * {@code binary} the install never wrote: the artifact's output was a path
     * that does not exist, so the tree the install actually created was
     * credited to nobody and survived every teardown. Measured on
     * {@code test_graph/fixtures/skill-script-skill}, whose {@code on_path} is
     * deliberately a name nothing provides so that the install always runs.
     */
    private static String producedBinary(CliDependency dep) {
        for (var target : dep.install().values()) {
            if (target.binary() != null && !target.binary().isBlank()) return target.binary();
        }
        if (dep.onPath() != null && !dep.onPath().isBlank()) return dep.onPath();
        return null;
    }

    private static String declaredHash(CliDependency dep) {
        for (var t : dep.install().values()) if (t.sha256() != null) return t.sha256();
        return null;
    }
}
