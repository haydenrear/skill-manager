package dev.skillmanager.policy;

/**
 * Per-category confirmation flags consulted by {@link PolicyGate} when an
 * install / upgrade plan emits {@code !}-prefixed categorization lines
 * (see {@link dev.skillmanager.plan.PlanBuilder#categorize}).
 *
 * <p>Each flag is "require interactive confirmation for this category".
 * When the flag is on AND the corresponding {@code !} line appears in
 * the plan, {@code --yes} cannot bypass — the user has to flip the flag
 * to {@code false} in {@code policy.toml}'s {@code [install]} table to
 * approve unattended.
 *
 * <p>Defaults are conservative: every category requires confirmation.
 * Loosening is an explicit user choice.
 *
 * <table>
 *   <caption>Flag → category</caption>
 *   <tr><th>Flag</th><th>Categorization line</th></tr>
 *   <tr><td>{@link #requireConfirmationForHooks}</td><td>{@code ! HOOKS}</td></tr>
 *   <tr><td>{@link #requireConfirmationForMcp}</td><td>{@code ! MCP}</td></tr>
 *   <tr><td>{@link #requireConfirmationForCliDeps}</td><td>{@code ! CLI}</td></tr>
 *   <tr><td>{@link #requireConfirmationForExecutableCommands}</td><td>{@code ! EXEC}
 *       (forward-looking — plugin executable commands aren't in the data model yet,
 *       so this flag has no current trigger; landed for symmetry with the spec)</td></tr>
 * </table>
 */
public record InstallPolicy(
        boolean requireConfirmationForHooks,
        boolean requireConfirmationForMcp,
        boolean requireConfirmationForCliDeps,
        boolean requireConfirmationForExecutableCommands
) {
    public static InstallPolicy defaults() {
        return new InstallPolicy(true, true, true, true);
    }
}
