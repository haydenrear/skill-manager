package dev.skillmanager.app;

import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.tools.ToolDependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link InstallPlan} (sequence of {@link PlanAction}s) into the
 * matching list of fine-grained {@link SkillEffect}s. The plan is the
 * single source of truth for what needs to happen; this expander just
 * lifts each action into the effect record so the interpreter sees one
 * effect per action with its own receipt.
 *
 * <p>Tools come first (a {@link SkillEffect.SetupPackageManagerRuntime}
 * over every distinct tool, plus per-tool {@link SkillEffect.EnsureTool}
 * effects for facts), then CLI installs, then MCP server registrations —
 * matching the section ordering in {@link PlanAction.Section}.
 */
public final class PlanExpander {

    private PlanExpander() {}

    public static List<SkillEffect> expand(InstallPlan plan, GatewayConfig gw) {
        List<SkillEffect> out = new ArrayList<>();

        Map<String, ToolDependency> uniqueTools = new LinkedHashMap<>();
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.EnsureTool e) {
                uniqueTools.putIfAbsent(e.tool().id(), e.tool());
            }
        }
        if (!uniqueTools.isEmpty()) {
            out.add(new SkillEffect.SetupPackageManagerRuntime(
                    new ArrayList<>(uniqueTools.values())));
        }

        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.EnsureTool e) {
                out.add(new SkillEffect.EnsureTool(e.tool(), e.missingOnPath()));
            }
        }
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.RunCliInstall c) {
                out.add(new SkillEffect.RunCliInstall(c.skillName(), c.dep()));
            }
        }
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.RegisterMcpServer m) {
                out.add(new SkillEffect.RegisterMcpServer(m.skillName(), m.dep(), gw));
            }
        }
        return out;
    }
}
