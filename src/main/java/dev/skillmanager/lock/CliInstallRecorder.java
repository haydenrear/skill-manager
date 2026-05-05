package dev.skillmanager.lock;

import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;

/**
 * Runs every {@link PlanAction.RunCliInstall} in a plan, records each success
 * to the {@link CliLock}, and saves the lock once at the end.
 */
public final class CliInstallRecorder {

    private CliInstallRecorder() {}

    public static void run(InstallPlan plan, SkillStore store) throws IOException {
        InstallerRegistry registry = new InstallerRegistry();
        CliLock lock = CliLock.load(store);
        for (PlanAction a : plan.actions()) {
            if (!(a instanceof PlanAction.RunCliInstall rc)) continue;
            try {
                registry.installOne(rc.dep(), store, rc.unitName());
                var req = RequestedVersion.of(rc.dep());
                String sha = findHash(rc.dep());
                lock.recordInstall(rc.dep().backend(), req.tool(), req.version(), rc.dep().spec(), sha, rc.unitName());
            } catch (Exception e) {
                Log.warn("cli: %s failed: %s", rc.dep().name(), e.getMessage());
            }
        }
        lock.save(store);
    }

    private static String findHash(dev.skillmanager.model.CliDependency dep) {
        for (var t : dep.install().values()) if (t.sha256() != null) return t.sha256();
        return null;
    }
}
