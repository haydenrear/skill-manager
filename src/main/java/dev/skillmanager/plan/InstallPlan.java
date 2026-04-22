package dev.skillmanager.plan;

import java.util.ArrayList;
import java.util.List;

public final class InstallPlan {

    private final List<PlanAction> actions = new ArrayList<>();
    private final List<PlanAction.BlockedByPolicy> blocks = new ArrayList<>();
    private final List<PlanAction.CliVersionConflict> conflicts = new ArrayList<>();

    public InstallPlan add(PlanAction action) {
        actions.add(action);
        if (action instanceof PlanAction.BlockedByPolicy b) blocks.add(b);
        if (action instanceof PlanAction.CliVersionConflict c) conflicts.add(c);
        return this;
    }

    public List<PlanAction> actions() { return List.copyOf(actions); }

    public boolean blocked() { return !blocks.isEmpty() || !conflicts.isEmpty(); }

    public List<PlanAction.BlockedByPolicy> blocks() { return List.copyOf(blocks); }

    public List<PlanAction.CliVersionConflict> conflicts() { return List.copyOf(conflicts); }

    public boolean isEmpty() { return actions.isEmpty(); }

    public PlanAction.Severity maxSeverity() {
        PlanAction.Severity out = PlanAction.Severity.INFO;
        for (PlanAction a : actions) if (a.severity().ordinal() > out.ordinal()) out = a.severity();
        return out;
    }
}
