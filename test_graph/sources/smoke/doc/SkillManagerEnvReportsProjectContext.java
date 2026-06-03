///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * External regression for {@code skill-manager-skill/scripts/env.sh}
 * project-context reporting.
 */
public class SkillManagerEnvReportsProjectContext {
    static final NodeSpec SPEC = NodeSpec.of("skill-manager.env.project.context")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("docs", "skill-manager-skill", "env-script", "project")
            .timeout("60s")
            .retries(2);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("skill-manager.env.project.context", "missing env.prepared.home");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path envScript = repoRoot.resolve("skill-manager-skill/scripts/env.sh");
            Path project;
            try {
                project = Files.createTempDirectory("sm-env-project-context-").toRealPath();
                Files.writeString(project.resolve("skill-project.toml"), """
                        [project]
                        name = "env-context-project"

                        [skills.helper]
                        source = "skill:helper"

                        [docs.prompts]
                        source = "doc:prompts"

                        [harnesses.codex]
                        source = "harness:codex"

                        [envs.dev]
                        dependencies = ["pytest"]

                        [[libs]]
                        name = "docs"
                        source = "github:org/docs"
                        """);
                Files.createDirectories(project.resolve(".skill-manager"));
                Files.createDirectories(project.resolve(".codex"));
                Files.createDirectories(project.resolve(".claude"));
                Files.createDirectories(project.resolve(".gemini"));
                Files.writeString(project.resolve(".skill-manager/env.md"), "generated env docs\n");
            } catch (Exception e) {
                return NodeResult.fail("skill-manager.env.project.context",
                        "could not scaffold project fixture: " + e.getMessage());
            }

            RunResult run;
            try {
                run = runEnvScript(new ObjectMapper(), envScript, home, ctx, project);
            } catch (Exception e) {
                return NodeResult.fail("skill-manager.env.project.context",
                        "failed to invoke env.sh: " + e.getMessage());
            }

            JsonNode p = run.json.path("project");
            List<String> errors = new ArrayList<>();
            boolean detected = p.path("detected").asBoolean(false);
            boolean projectName = "env-context-project".equals(textOrEmpty(p.path("project_name")));
            boolean manifest = textOrEmpty(p.path("manifest")).endsWith("skill-project.toml");
            boolean childHome = textOrEmpty(p.path("child_skill_manager_home"))
                    .equals(project.resolve(".skill-manager").toString())
                    && p.path("child_home_initialized").asBoolean(false);
            boolean agentHomes = textOrEmpty(p.path("launch_env").path("SKILL_MANAGER_HOME"))
                    .equals(project.resolve(".skill-manager").toString())
                    && textOrEmpty(p.path("launch_env").path("CODEX_HOME")).equals(project.resolve(".codex").toString())
                    && textOrEmpty(p.path("launch_env").path("CLAUDE_HOME")).equals(project.resolve(".claude").toString())
                    && textOrEmpty(p.path("launch_env").path("GEMINI_HOME")).equals(project.resolve(".gemini").toString());
            boolean declared = arrayToStrings(p.path("declared").path("skills")).contains("helper")
                    && arrayToStrings(p.path("declared").path("docs")).contains("prompts")
                    && arrayToStrings(p.path("declared").path("harnesses")).contains("codex")
                    && arrayToStrings(p.path("declared").path("envs")).contains("dev")
                    && arrayToStrings(p.path("declared").path("libs")).contains("docs");
            boolean envDocs = textOrEmpty(p.path("project_env_docs"))
                    .equals(project.resolve(".skill-manager/env.md").toString());

            if (!detected) errors.add("project not detected");
            if (!projectName) errors.add("project_name mismatch");
            if (!manifest) errors.add("manifest mismatch");
            if (!childHome) errors.add("child home mismatch");
            if (!agentHomes) errors.add("launch env mismatch");
            if (!declared) errors.add("declared manifest sections missing");
            if (!envDocs) errors.add("env docs not reported");

            boolean pass = errors.isEmpty();
            return (pass
                    ? NodeResult.pass("skill-manager.env.project.context")
                    : NodeResult.fail("skill-manager.env.project.context", String.join("; ", errors)))
                    .process(run.record)
                    .assertion("project_context_detected", detected)
                    .assertion("project_name_reported", projectName)
                    .assertion("manifest_reported", manifest)
                    .assertion("child_home_reported", childHome)
                    .assertion("launch_env_reported", agentHomes)
                    .assertion("declared_sections_reported", declared)
                    .assertion("project_env_docs_reported", envDocs);
        });
    }

    private record RunResult(ProcessRecord record, JsonNode json) {}

    private static RunResult runEnvScript(ObjectMapper om, Path script, String home,
                                          NodeContext ctx, Path project) throws Exception {
        List<String> cmd = List.of(script.toString(), "--project-root", project.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        Instant startedAt = Instant.now();
        Process process = pb.start();
        long pid = process.pid();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = r.readLine()) != null) err.append(line).append('\n');
        }
        int rc = process.waitFor();
        Instant endedAt = Instant.now();
        Path log = Procs.logFile(ctx, "env-project-context");
        Files.writeString(log,
                "$ " + String.join(" ", cmd) + "\nrc=" + rc
                        + "\n--- stdout ---\n" + out
                        + "\n--- stderr ---\n" + err);
        ProcessRecord record = new ProcessRecord(
                "env-project-context", cmd, startedAt, endedAt, rc, pid,
                Procs.relativeToReport(ctx, log), null);
        if (rc != 0) {
            throw new RuntimeException("env.sh exited rc=" + rc + " stderr=" + err);
        }
        return new RunResult(record, om.readTree(out.toString()));
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? "" : n.asText("");
    }

    private static List<String> arrayToStrings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (Iterator<JsonNode> it = n.elements(); it.hasNext(); ) {
            JsonNode e = it.next();
            if (e.isTextual()) out.add(e.asText());
        }
        return out;
    }
}
