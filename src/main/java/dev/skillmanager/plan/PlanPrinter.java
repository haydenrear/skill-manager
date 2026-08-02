package dev.skillmanager.plan;

import dev.skillmanager.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The install plan.
 *
 * <h2>Who it is for</h2>
 *
 * <p>The plan is a <em>consent</em> document: it exists so an operator can see
 * what is about to happen before saying yes. On the non-interactive path
 * ({@code --yes}, or any agent invocation, which is every invocation this tool
 * is optimised for) nobody is being asked, and the plan is a transcript of work
 * whose outcome is reported anyway — a dozen lines that arrive before the run
 * and are superseded by it.
 *
 * <p>So {@link #print} writes it through {@link Log#detail}: the run log
 * always, the console under {@code --verbose}. {@link #confirm} puts it back on
 * the console <b>in full</b> the moment it is about to prompt, because a
 * question about a plan the operator cannot see is not a question. The BLOCKED
 * and CONFLICT paths were never behind the prompt and are unchanged — those are
 * refusals, and refusals print.
 */
public final class PlanPrinter {

    private PlanPrinter() {}

    public static void print(InstallPlan plan) {
        for (String line : render(plan)) Log.detail("%s", line);
    }

    /** The same plan, unconditionally on stdout. Used when about to prompt. */
    static void printToConsole(InstallPlan plan) {
        for (String line : render(plan)) System.out.println(line);
    }

    /**
     * The plan as lines — one renderer, two destinations, so the plan an
     * operator approves and the plan in the log can never diverge.
     */
    public static List<String> render(InstallPlan plan) {
        List<String> out = new java.util.ArrayList<>();
        out.add("");
        out.add("skill-manager install plan");
        out.add("==========================");
        if (plan.isEmpty()) {
            out.add("  (nothing to do)");
            out.add("");
            return out;
        }

        Map<PlanAction.Section, List<PlanAction>> sections = new EnumMap<>(PlanAction.Section.class);
        for (PlanAction a : plan.actions()) {
            sections.computeIfAbsent(a.section(), s -> new java.util.ArrayList<>()).add(a);
        }

        renderSection(out, sections, PlanAction.Section.RESOLVE, "resolve & download");
        renderSection(out, sections, PlanAction.Section.STORE, "install into store");
        // TOOLS sits between STORE and CLI/MCP — same execution order as
        // ToolInstallRecorder so the printed plan matches what the
        // installer actually runs. EnsureTool entries surface
        // bundle-on-install actions for uv/node and PATH presence
        // checks for external tools (docker, brew); without this line
        // the operator never sees a missing-docker WARN before launch.
        renderSection(out, sections, PlanAction.Section.TOOLS, "tools (bundle / presence-check)");
        renderSection(out, sections, PlanAction.Section.CLI, "cli dependencies");
        renderSection(out, sections, PlanAction.Section.MCP,
                "mcp dependencies (registered with gateway)");
        renderSection(out, sections, PlanAction.Section.NOTES, "notes");

        long totalBytes = 0;
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.FetchUnit f) totalBytes += f.resolved().bytesDownloaded();
        }
        if (totalBytes > 0) {
            out.add("");
            out.add("total download: " + humanBytes(totalBytes));
        }
        out.add("");
        return out;
    }

    private static void renderSection(List<String> out,
                                      Map<PlanAction.Section, List<PlanAction>> sections,
                                      PlanAction.Section section, String header) {
        List<PlanAction> items = sections.get(section);
        if (items == null || items.isEmpty()) return;
        out.add("");
        out.add(header);
        for (PlanAction a : items) {
            String marker = switch (a.severity()) {
                case INFO -> "  ";
                case NOTICE -> "• ";
                case WARN -> "! ";
                case DANGER -> "⚠ ";
            };
            out.add("  " + marker + a.title());
            for (String note : a.notes()) out.add("       · " + note);
        }
    }

    public static boolean confirm(InstallPlan plan, boolean requireConfirmation, boolean assumeYes) {
        if (plan.blocked()) {
            System.err.println();
            if (!plan.blocks().isEmpty()) {
                System.err.println("BLOCKED by policy — edit ~/.skill-manager/policy.toml or update the skill:");
                for (PlanAction.BlockedByPolicy b : plan.blocks()) {
                    System.err.println("  ✗ " + b.title().replace("BLOCKED  ", "") + "  (" + b.notes().get(0) + ")");
                }
            }
            if (!plan.conflicts().isEmpty()) {
                System.err.println("CLI version conflict — a different version of these tools is already installed:");
                for (PlanAction.CliVersionConflict c : plan.conflicts()) {
                    System.err.println("  ✗ " + c.dep().backend() + ":" + c.dep().name()
                            + "  requested " + (c.requestedVersion() == null ? "any" : c.requestedVersion())
                            + " for " + c.unitName()
                            + "  ·  locked at " + c.lockedVersion()
                            + (c.previouslyRequestedBy().isEmpty() ? "" : " by " + String.join(", ", c.previouslyRequestedBy())));
                }
                System.err.println("resolve: pin both skills to the same version, or delete the conflicting row in ~/.skill-manager/cli-lock.toml");
            }
            return false;
        }
        if (plan.isEmpty()) return true;
        if (!requireConfirmation) return true;
        if (assumeYes) {
            System.out.println("(proceeding — --yes)");
            return true;
        }
        if (System.console() == null) {
            System.err.println("stdin is not a TTY and --yes was not passed — refusing to proceed.");
            return false;
        }
        // About to ask. Put the plan back on the console in full: under
        // --verbose it is already there, otherwise print() only wrote it to
        // the run log, and consenting to a plan you cannot see is not consent.
        if (!Log.isVerbose()) printToConsole(plan);
        System.out.print("Proceed? [y/N] ");
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line = r.readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }
}
