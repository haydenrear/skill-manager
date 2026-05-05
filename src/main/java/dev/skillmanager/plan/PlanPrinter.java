package dev.skillmanager.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class PlanPrinter {

    private PlanPrinter() {}

    public static void print(InstallPlan plan) {
        System.out.println();
        System.out.println("skill-manager install plan");
        System.out.println("==========================");
        if (plan.isEmpty()) {
            System.out.println("  (nothing to do)");
            System.out.println();
            return;
        }

        Map<PlanAction.Section, List<PlanAction>> sections = new EnumMap<>(PlanAction.Section.class);
        for (PlanAction a : plan.actions()) {
            sections.computeIfAbsent(a.section(), s -> new java.util.ArrayList<>()).add(a);
        }

        printSection(sections, PlanAction.Section.RESOLVE, "resolve & download");
        printSection(sections, PlanAction.Section.STORE, "install into store");
        // TOOLS sits between STORE and CLI/MCP — same execution order as
        // ToolInstallRecorder so the printed plan matches what the
        // installer actually runs. EnsureTool entries surface
        // bundle-on-install actions for uv/node and PATH presence
        // checks for external tools (docker, brew); without this line
        // the operator never sees a missing-docker WARN before launch.
        printSection(sections, PlanAction.Section.TOOLS, "tools (bundle / presence-check)");
        printSection(sections, PlanAction.Section.CLI, "cli dependencies");
        printSection(sections, PlanAction.Section.MCP, "mcp dependencies (registered with gateway)");
        printSection(sections, PlanAction.Section.NOTES, "notes");

        long totalBytes = 0;
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.FetchUnit f) totalBytes += f.resolved().bytesDownloaded();
        }
        if (totalBytes > 0) {
            System.out.println();
            System.out.println("total download: " + humanBytes(totalBytes));
        }
        System.out.println();
    }

    private static void printSection(Map<PlanAction.Section, List<PlanAction>> sections,
                                     PlanAction.Section section, String header) {
        List<PlanAction> items = sections.get(section);
        if (items == null || items.isEmpty()) return;
        System.out.println();
        System.out.println(header);
        for (PlanAction a : items) {
            String marker = switch (a.severity()) {
                case INFO -> "  ";
                case NOTICE -> "• ";
                case WARN -> "! ";
                case DANGER -> "⚠ ";
            };
            System.out.println("  " + marker + a.title());
            for (String note : a.notes()) System.out.println("       · " + note);
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
