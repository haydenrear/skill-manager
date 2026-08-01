package com.hayden.testgraphsdk.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClusterLifecyclePlan {
    private final String command;
    private final String target;
    private final String backend;
    private final String dispatchKey;
    private final String environmentAction;
    private final List<String> tofuCommand;
    private final boolean shouldRun;
    private final String expectedState;
    private final String skipReason;
    private final boolean justProvisioned;
    private final boolean alreadyReset;
    private final boolean destroyRequested;
    private final boolean requiresExplicitSelection;

    ClusterLifecyclePlan(
            String command,
            String target,
            String backend,
            String dispatchKey,
            String environmentAction,
            List<String> tofuCommand,
            boolean shouldRun,
            String expectedState,
            String skipReason,
            boolean justProvisioned,
            boolean alreadyReset,
            boolean destroyRequested,
            boolean requiresExplicitSelection
    ) {
        this.command = command;
        this.target = target;
        this.backend = backend;
        this.dispatchKey = dispatchKey;
        this.environmentAction = environmentAction;
        this.tofuCommand = List.copyOf(tofuCommand);
        this.shouldRun = shouldRun;
        this.expectedState = expectedState;
        this.skipReason = skipReason;
        this.justProvisioned = justProvisioned;
        this.alreadyReset = alreadyReset;
        this.destroyRequested = destroyRequested;
        this.requiresExplicitSelection = requiresExplicitSelection;
    }

    public String command() { return command; }
    public String target() { return target; }
    public String backend() { return backend; }
    public String dispatchKey() { return dispatchKey; }
    public String environmentAction() { return environmentAction; }
    public List<String> tofuCommand() { return tofuCommand; }
    public boolean shouldRun() { return shouldRun; }
    public String expectedState() { return expectedState; }
    public String skipReason() { return skipReason; }
    public boolean justProvisioned() { return justProvisioned; }
    public boolean alreadyReset() { return alreadyReset; }
    public boolean destroyRequested() { return destroyRequested; }
    public boolean requiresExplicitSelection() { return requiresExplicitSelection; }

    public Map<String, String> published() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("LifecycleCommand", command);
        out.put("LifecycleTarget", target);
        out.put("LifecycleBackend", backend);
        out.put("LifecycleDispatchKey", dispatchKey);
        out.put("LifecycleEnvironmentAction", environmentAction == null ? "" : environmentAction);
        out.put("LifecycleTofuCommand", String.join(" ", tofuCommand));
        out.put("LifecycleShouldRun", Boolean.toString(shouldRun));
        out.put("LifecycleExpectedState", expectedState);
        out.put("LifecycleSkipReason", skipReason);
        out.put("LifecycleJustProvisioned", Boolean.toString(justProvisioned));
        out.put("LifecycleAlreadyReset", Boolean.toString(alreadyReset));
        out.put("LifecycleDestroyRequested", Boolean.toString(destroyRequested));
        out.put("LifecycleRequiresExplicitSelection", Boolean.toString(requiresExplicitSelection));
        return out;
    }
}
