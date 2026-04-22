package dev.skillmanager.commands;

import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.plan.AuditLog;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.plan.PlanPrinter;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "install", description = "Install CLI dependencies for every installed skill (with consent).")
public final class InstallCommand implements Callable<Integer> {

    @Option(names = {"-y", "--yes"}, description = "Skip the confirmation prompt") boolean yes;
    @Option(names = "--dry-run", description = "Print the plan and exit without executing") boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);

        var skills = store.listInstalled();
        if (skills.isEmpty()) {
            Log.info("no skills installed");
            return 0;
        }

        InstallPlan plan = new PlanBuilder(policy, lock).plan(skills, true, false, store.cliBinDir());
        PlanPrinter.print(plan);

        if (dryRun) return plan.blocked() ? 2 : 0;
        if (!PlanPrinter.confirm(plan, policy.requireConfirmation(), yes)) {
            return plan.blocked() ? 2 : 1;
        }

        new AuditLog(store).recordPlan(plan, "install");
        CliInstallRecorder.run(plan, store);
        return 0;
    }
}
