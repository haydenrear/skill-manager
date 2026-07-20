package dev.skillmanager.cli;

import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a bounded agent-facing context block for an executed CLI command.
 *
 * <p>The block goes to stderr so command stdout remains usable for JSON and
 * scripts. It is opt-in through {@code --agent-context} or
 * {@code SKILL_MANAGER_AGENT_CONTEXT=1}.
 */
public final class CliAgentContext {
    public static final String BEGIN = "SKILL_MANAGER_AGENT_CONTEXT_BEGIN";
    public static final String END = "SKILL_MANAGER_AGENT_CONTEXT_END";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";

    private CliAgentContext() {}

    public static String commandPath(CommandLine.ParseResult parseResult) {
        if (parseResult == null) return "skill-manager";
        CommandLine.ParseResult leaf = parseResult;
        while (leaf.hasSubcommand()) {
            leaf = leaf.subcommand();
        }

        ArrayDeque<String> parts = new ArrayDeque<>();
        CommandLine command = leaf.commandSpec().commandLine();
        while (command != null) {
            String name = command.getCommandName();
            if (name != null && !name.isBlank() && !"skill-manager".equals(name)) {
                parts.addFirst(name);
            }
            command = command.getParent();
        }
        return parts.isEmpty() ? "skill-manager" : String.join(" ", parts);
    }

    public static void emit(PrintStream err, String commandPath, int exitCode) {
        emit(err, commandPath, exitCode, null);
    }

    public static void emit(PrintStream err, String commandPath, int exitCode, String traceId) {
        String block = render(commandPath, exitCode, traceId);
        if (!block.isBlank()) {
            err.print(block);
        }
    }

    public static String render(String commandPath, int exitCode) {
        return render(commandPath, exitCode, null);
    }

    public static String render(String commandPath, int exitCode, String traceId) {
        String normalized = commandPath == null || commandPath.isBlank()
                ? "skill-manager"
                : commandPath.trim();
        List<CliMetadata.WorkflowMetadata> workflows = workflowsForCommandPath(normalized);

        LinkedHashSet<String> workflowIds = new LinkedHashSet<>();
        LinkedHashSet<String> docs = new LinkedHashSet<>();
        LinkedHashSet<String> examples = new LinkedHashSet<>();
        for (CliMetadata.WorkflowMetadata workflow : workflows) {
            workflowIds.add(workflow.id());
            docs.addAll(workflow.relatedSkillDocs());
            examples.addAll(workflow.examples());
        }

        LinkedHashSet<String> nextCommands = nextCommands(normalized, examples);

        StringBuilder out = new StringBuilder();
        out.append(BEGIN).append('\n');
        out.append("command_path: ").append(normalized).append('\n');
        out.append("exit_code: ").append(exitCode).append('\n');
        out.append("status: ").append(exitCode == 0 ? "success" : "failed").append('\n');
        if (isValidTraceId(traceId)) {
            out.append("trace_id: ").append(traceId).append('\n');
        }
        out.append("workflow_state: command_completed").append('\n');
        appendList(out, "workflows", workflowIds);
        appendList(out, "related_skill_docs", docs);
        appendList(out, "examples", examples);
        appendList(out, "next_commands", nextCommands);
        out.append("logs:\n");
        out.append("  - $SKILL_MANAGER_HOME/logs\n");
        out.append(END).append('\n');
        return out.toString();
    }

    public static boolean isValidTraceId(String traceId) {
        return traceId != null
                && TRACE_ID.matcher(traceId).matches()
                && !ZERO_TRACE_ID.equals(traceId);
    }

    private static List<CliMetadata.WorkflowMetadata> workflowsForCommandPath(String commandPath) {
        List<CliMetadata.WorkflowMetadata> exact = workflowsMatching(commandPath);
        if (!exact.isEmpty()) return exact;

        String candidate = commandPath;
        while (candidate.contains(" ")) {
            candidate = candidate.substring(0, candidate.lastIndexOf(' '));
            List<CliMetadata.WorkflowMetadata> parent = workflowsMatching(candidate);
            if (!parent.isEmpty()) return parent;
        }
        return List.of();
    }

    private static List<CliMetadata.WorkflowMetadata> workflowsMatching(String commandPath) {
        List<CliMetadata.WorkflowMetadata> matches = new ArrayList<>();
        for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
            if (workflow.commandPath().equals(commandPath)) {
                matches.add(workflow);
            }
        }
        return matches;
    }

    private static LinkedHashSet<String> nextCommands(String commandPath, Set<String> examples) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (!"skill-manager".equals(commandPath)) {
            next.add("skill-manager " + commandPath + " --help");
        } else {
            next.add("skill-manager --help");
        }
        next.addAll(examples);

        if ("install".equals(commandPath)) {
            next.add("skill-manager show <unit>");
            next.add("skill-manager sync <unit>");
        } else if ("sync".equals(commandPath)) {
            next.add("skill-manager show <unit>");
            next.add("skill-manager bindings list");
        } else if ("bind".equals(commandPath)) {
            next.add("skill-manager bindings list");
            next.add("skill-manager unbind <binding-id>");
        } else if ("env sync".equals(commandPath) || "env".equals(commandPath)) {
            next.add("skill-manager env run <env> -- <cmd>");
            next.add("skill-manager project profiles list");
        } else if ("harness instantiate".equals(commandPath)) {
            next.add("skill-manager harness list");
            next.add("skill-manager sync <harness-template>");
        } else if ("publish".equals(commandPath)) {
            next.add("skill-manager install <unit>");
            next.add("skill-manager search <unit>");
        }
        return next;
    }

    private static void appendList(StringBuilder out, String name, Set<String> values) {
        out.append(name).append(":\n");
        if (values.isEmpty()) {
            out.append("  - none\n");
            return;
        }
        for (String value : values) {
            out.append("  - ").append(value).append('\n');
        }
    }
}
