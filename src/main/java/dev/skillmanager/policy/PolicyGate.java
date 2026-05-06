package dev.skillmanager.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps an install plan's categorization output to {@link InstallPolicy}
 * violations. The plan-print already produces {@code !}-prefixed lines
 * for HOOKS / MCP / CLI / EXEC (see
 * {@link dev.skillmanager.plan.PlanBuilder#categorize}); this gate
 * decides which of those, given the current policy flags, block
 * {@code --yes}.
 *
 * <p>Pure function — no IO. Same lines + same policy → same answer.
 *
 * <p>Wired into {@code InstallCommand}: when the user passes
 * {@code --yes}, the command rejects the run if {@link #violations}
 * returns non-empty, with an error naming the specific
 * {@code policy.install.*} flags to flip. Without {@code --yes}, the
 * command prompts per category (interactive confirmation).
 */
public final class PolicyGate {

    /** Tag for a category that needs explicit confirmation. */
    public enum Category {
        HOOKS("require_confirmation_for_hooks"),
        MCP("require_confirmation_for_mcp"),
        CLI_DEPS("require_confirmation_for_cli_deps"),
        EXECUTABLE_COMMANDS("require_confirmation_for_executable_commands");

        public final String flagName;
        Category(String flag) { this.flagName = flag; }
    }

    private PolicyGate() {}

    /**
     * Return the categories that the given plan triggers AND the policy
     * still requires confirmation for. Empty list = no gate; {@code --yes}
     * is acceptable. Non-empty list = these flags would have to flip to
     * false in {@code policy.toml} for unattended install.
     */
    public static List<Category> violations(List<String> categorizationLines, InstallPolicy policy) {
        if (categorizationLines == null || categorizationLines.isEmpty()) return List.of();
        List<Category> out = new ArrayList<>();
        for (String line : categorizationLines) {
            if (line == null) continue;
            String s = line.trim();
            if (s.startsWith("! HOOKS") && policy.requireConfirmationForHooks()) {
                out.add(Category.HOOKS);
            } else if (s.startsWith("! MCP") && policy.requireConfirmationForMcp()) {
                out.add(Category.MCP);
            } else if (s.startsWith("! CLI") && policy.requireConfirmationForCliDeps()) {
                out.add(Category.CLI_DEPS);
            } else if (s.startsWith("! EXEC") && policy.requireConfirmationForExecutableCommands()) {
                out.add(Category.EXECUTABLE_COMMANDS);
            }
        }
        return out;
    }

    /**
     * Format violations into a user-facing error message naming the
     * specific {@code policy.install.*} flags that block the run.
     */
    public static String formatViolationMessage(List<Category> violations) {
        if (violations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("--yes blocked by policy.install:\n");
        for (Category c : violations) {
            sb.append("  - ").append(c.name()).append(" — set policy.install.")
                    .append(c.flagName).append(" = false to allow unattended\n");
        }
        return sb.toString();
    }
}
