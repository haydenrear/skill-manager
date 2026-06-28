///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end contract for opt-in agent context output.
 */
public class CliAgentContextOutput {
    static final NodeSpec SPEC = NodeSpec.of("cli.agent.context.output")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "cli.help.progressive")
            .tags("cli", "agent-context", "progressive-disclosure")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("cli.agent.context.output", "missing env.prepared.home");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            List<Check> checks = List.of(
                    new Check("install", "install-local-unit", "install", "--help"),
                    new Check("sync", "sync-one-unit", "sync", "--help"),
                    new Check("bind", "bind-projection", "bind", "--help"),
                    new Check("env-sync", "project-env", "env", "sync", "--help"),
                    new Check("harness-instantiate", "harness-instantiate", "harness", "instantiate", "--help"),
                    new Check("publish", "publish-unit", "publish", "--help")
            );

            List<ProcessRecord> processes = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (Check check : checks) {
                ProcessRecord process = run(ctx, check.label(), home, repoRoot, sm, check.args());
                processes.add(process);
                String output = readLog(ctx, check.label());
                if (process.exitCode() != 0) failures.add(check.label() + ": exit=" + process.exitCode());
                if (!output.contains("SKILL_MANAGER_AGENT_CONTEXT_BEGIN")) failures.add(check.label() + ": missing begin");
                if (!output.contains("SKILL_MANAGER_AGENT_CONTEXT_END")) failures.add(check.label() + ": missing end");
                if (!output.contains(check.workflowId())) failures.add(check.label() + ": missing workflow");
                if (!output.contains("related_skill_docs:")) failures.add(check.label() + ": missing docs");
                if (!output.contains("next_commands:")) failures.add(check.label() + ": missing next");
                if (!output.contains("$SKILL_MANAGER_HOME/logs")) failures.add(check.label() + ": missing logs");
            }

            NodeResult result = failures.isEmpty()
                    ? NodeResult.pass("cli.agent.context.output")
                    : NodeResult.fail("cli.agent.context.output", String.join("; ", failures));
            for (ProcessRecord process : processes) {
                result = result.process(process);
            }
            return result
                    .assertion("agent_context_all_representatives_pass", failures.isEmpty())
                    .metric("representativeCommands", checks.size());
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 2];
        command[0] = sm.toString();
        command[1] = "--agent-context";
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    private record Check(String label, String workflowId, String... args) {}
}
