package dev.skillmanager.tools;

import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;

/**
 * Executes the {@code EnsureTool} actions in an install plan.
 *
 * <p>Mirrors {@code CliInstallRecorder} — same shape, runs after the
 * skills land in the store and before CLI installs / MCP registrations
 * fire. Each {@link ToolDependency.Bundled} is realized by
 * {@link PackageManagerRuntime#ensureBundled} (idempotent — short-circuits
 * if already installed). Each {@link ToolDependency.External} only does a
 * presence check; missing externals log an error but never block the
 * install — the operator can install the missing tool later, and the
 * error makes that requirement loud.
 *
 * <p>Net effect: a single uv install regardless of how many CLI / MCP
 * deps in the graph need uv, a single Node install regardless of how many
 * need npx, and a single docker presence check regardless of how many
 * docker-load MCPs are in flight.
 */
public final class ToolInstallRecorder {

    private ToolInstallRecorder() {}

    public static void run(InstallPlan plan, SkillStore store) {
        PackageManagerRuntime pm = new PackageManagerRuntime(store);
        for (PlanAction action : plan.actions()) {
            if (!(action instanceof PlanAction.EnsureTool ensure)) continue;
            ToolDependency tool = ensure.tool();
            try {
                if (tool instanceof ToolDependency.Bundled) {
                    String path = pm.ensureBundled(tool.id());
                    Log.ok("tool: %s ready  → %s", tool.id(), path);
                } else if (tool instanceof ToolDependency.External ext) {
                    String onPath = pm.systemPath(tool.id());
                    if (onPath != null) {
                        Log.ok("tool: %s on PATH  → %s", tool.id(), onPath);
                    } else {
                        // Don't fail install — the user might install the tool
                        // later and re-deploy. But surface the gap loudly so
                        // the next deploy of a dependent server isn't a
                        // mystery failure.
                        Log.error("tool: %s missing on PATH — %s", tool.id(),
                                ext.installHint() == null
                                        ? "install via the vendor's instructions"
                                        : ext.installHint());
                        Log.error("       deploys requiring '%s' will fail until "
                                + "it's installed", tool.id());
                    }
                }
            } catch (IOException e) {
                // Same posture as missing-external: log loudly, continue.
                // The error is most likely a network issue downloading the
                // bundleable PM; the operator can retry the install or set
                // up offline mirrors.
                Log.error("tool: %s install failed: %s", tool.id(), e.getMessage());
            }
        }
    }
}
