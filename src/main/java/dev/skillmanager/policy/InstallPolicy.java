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
 * <p><b>Default calibration</b>: only the genuinely dangerous categories
 * are gated by default — hooks (arbitrary shell at install time) and
 * executable commands (forward-looking; plugin {@code commands/}
 * entries that run as binaries). CLI deps and MCP registrations are
 * common enough on routine installs that gating them by default would
 * break automation (test harnesses, CI installs, scripted setup) without
 * meaningfully improving safety — pip / npm / docker installs are the
 * baseline for this ecosystem. Tighten by setting individual flags to
 * {@code true} in {@code ~/.skill-manager/policy.toml}.
 *
 * <table>
 *   <caption>Flag → category → default</caption>
 *   <tr><th>Flag</th><th>Categorization line</th><th>Default</th></tr>
 *   <tr><td>{@link #requireConfirmationForHooks}</td><td>{@code ! HOOKS}</td><td>{@code true}</td></tr>
 *   <tr><td>{@link #requireConfirmationForMcp}</td><td>{@code ! MCP}</td><td>{@code false}</td></tr>
 *   <tr><td>{@link #requireConfirmationForCliDeps}</td><td>{@code ! CLI}</td><td>{@code false}</td></tr>
 *   <tr><td>{@link #requireConfirmationForExecutableCommands}</td><td>{@code ! EXEC}</td><td>{@code true}</td></tr>
 * </table>
 *
 * <p>EXEC is forward-looking — plugin executable commands aren't in the
 * data model yet, so the flag has no current trigger. Landed gated-by-default
 * so the safe behavior is in place when commands/ entries land.
 */
public record InstallPolicy(
        boolean requireConfirmationForHooks,
        boolean requireConfirmationForMcp,
        boolean requireConfirmationForCliDeps,
        boolean requireConfirmationForExecutableCommands
) {
    public static InstallPolicy defaults() {
        return new InstallPolicy(true, false, false, true);
    }
}
