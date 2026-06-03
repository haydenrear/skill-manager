///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * External docs regression for the bundled skill-manager skill. This keeps
 * agent-facing project and child-home guidance visible through doc-smoke.
 */
public class SkillManagerSkillDocsProjected {
    static final NodeSpec SPEC = NodeSpec.of("skill-manager.skill.docs.projected")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("docs", "skill-manager-skill", "project", "child-home")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path root = repoRoot.resolve("skill-manager-skill");
            List<String> errors = new ArrayList<>();

            String skill = read(root.resolve("SKILL.md"));
            String projects = read(root.resolve("references/projects.md"));
            String workflows = read(root.resolve("references/workflows.md"));
            String cli = read(root.resolve("references/cli.md"));
            String toml = read(root.resolve("skill-manager.toml"));

            boolean skillMentionsProjects = contains(skill, "skill projects")
                    && contains(skill, "project child homes")
                    && contains(skill, "references/projects.md");
            if (!skillMentionsProjects) errors.add("SKILL.md missing project/child-home guidance");

            boolean projectReferenceComplete = contains(projects, "skill-project.toml")
                    && contains(projects, "<project>/.skill-manager")
                    && contains(projects, "CODEX_HOME=<project>/.codex")
                    && contains(projects, "CLAUDE_HOME=<project>/.claude")
                    && contains(projects, "GEMINI_HOME=<project>/.gemini")
                    && contains(projects, "skill-manager env sync");
            if (!projectReferenceComplete) errors.add("references/projects.md incomplete");

            boolean workflowRoutesProject = contains(workflows, "Resolve a skill project")
                    && contains(workflows, "SKILL_MANAGER_HOME=<project>/.skill-manager");
            if (!workflowRoutesProject) errors.add("workflows.md missing project route");

            boolean helperDocumented = contains(cli, "passive project context");
            if (!helperDocumented) errors.add("cli.md missing env helper project context");

            boolean manifestMentionsProjects = contains(toml, "skill projects")
                    && contains(toml, "project child homes");
            if (!manifestMentionsProjects) errors.add("skill-manager.toml description missing projects");

            boolean pass = errors.isEmpty();
            return (pass
                    ? NodeResult.pass("skill-manager.skill.docs.projected")
                    : NodeResult.fail("skill-manager.skill.docs.projected", String.join("; ", errors)))
                    .assertion("skill_md_mentions_projects_and_child_homes", skillMentionsProjects)
                    .assertion("project_reference_complete", projectReferenceComplete)
                    .assertion("workflow_routes_project_resolution", workflowRoutesProject)
                    .assertion("env_helper_project_context_documented", helperDocumented)
                    .assertion("skill_manifest_mentions_projects", manifestMentionsProjects);
        });
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean contains(String body, String needle) {
        return body != null && body.contains(needle);
    }
}
