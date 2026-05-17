///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs one throwaway unit of each kind with a broken markdown
 * skill-import. The install must still succeed, and the final console
 * renderer must report the advisory unit-reference violation for every
 * kind.
 */
public class MarkdownImportViolationsReported {
    static final NodeSpec SPEC = NodeSpec.of("markdown.import.violations.reported")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("install", "markdown-imports", "skill", "plugin", "doc-repo", "harness")
            .timeout("120s");

    private static final String MISSING_UNIT = "missing-smoke-reference-unit";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("markdown.import.violations.reported",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixturesRoot = Path.of(home).resolve("markdown-import-violation-fixtures");

            List<InstallCheck> checks = new ArrayList<>();
            try {
                Files.createDirectories(fixturesRoot);
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        skillFixture(fixturesRoot), "skill",
                        "markdown-import-broken-skill"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        pluginFixture(fixturesRoot), "plugin",
                        "markdown-import-broken-plugin"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        docFixture(fixturesRoot), "doc",
                        "markdown-import-broken-doc"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome,
                        harnessFixture(fixturesRoot), "harness",
                        "markdown-import-broken-harness"));
            } catch (Exception e) {
                return NodeResult.error("markdown.import.violations.reported", e);
            }

            boolean allExitZero = checks.stream().allMatch(c -> c.process().exitCode() == 0);
            boolean allReported = checks.stream().allMatch(InstallCheck::reportedViolation);
            boolean pass = allExitZero && allReported;

            NodeResult result = pass
                    ? NodeResult.pass("markdown.import.violations.reported")
                    : NodeResult.fail("markdown.import.violations.reported",
                            "allExitZero=" + allExitZero + " allReported=" + allReported);
            for (InstallCheck check : checks) result = result.process(check.process());
            return result
                    .assertion("skill_install_continues_and_reports", check(checks, "skill"))
                    .assertion("plugin_install_continues_and_reports", check(checks, "plugin"))
                    .assertion("doc_install_continues_and_reports", check(checks, "doc"))
                    .assertion("harness_install_continues_and_reports", check(checks, "harness"))
                    .assertion("all_installs_exit_zero", allExitZero)
                    .assertion("all_violations_rendered", allReported);
        });
    }

    private static InstallCheck install(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            Path sm,
            Path repoRoot,
            String home,
            String claudeHome,
            String codexHome,
            Path unitDir,
            String kind,
            String unitName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                sm.toString(), "install", "file://" + unitDir.toAbsolutePath(), "--yes");
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", claudeHome);
        pb.environment().put("CODEX_HOME", codexHome);
        ProcessRecord proc = Procs.run(ctx, "install-" + kind, pb);
        String log = logBody(ctx, proc);
        boolean rendered = log.contains("markdown skill-import violations")
                && log.contains(unitName + " (" + kind + ")")
                && log.contains(MISSING_UNIT)
                && log.contains("references missing unit");
        return new InstallCheck(kind, unitName, proc, rendered);
    }

    private static boolean check(List<InstallCheck> checks, String kind) {
        return checks.stream()
                .filter(c -> c.kind().equals(kind))
                .findFirst()
                .map(c -> c.process().exitCode() == 0 && c.reportedViolation())
                .orElse(false);
    }

    private static String logBody(com.hayden.testgraphsdk.sdk.NodeContext ctx,
                                  ProcessRecord proc) {
        if (proc.logPath() == null) return "";
        try {
            return Files.readString(ctx.reportDir().resolve(proc.logPath()));
        } catch (Exception e) {
            return "";
        }
    }

    private static Path skillFixture(Path root) throws Exception {
        Path dir = root.resolve("skill");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), markdownWithBrokenImport(
                "markdown-import-broken-skill",
                "Skill fixture with an unresolved markdown skill-import."));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "markdown-import-broken-skill"
                version = "0.1.0"
                description = "Skill fixture with an unresolved markdown skill-import."
                """);
        return dir;
    }

    private static Path pluginFixture(Path root) throws Exception {
        Path dir = root.resolve("plugin");
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """
                {
                  "name": "markdown-import-broken-plugin",
                  "version": "0.1.0",
                  "description": "Plugin fixture with an unresolved markdown skill-import."
                }
                """);
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "markdown-import-broken-plugin"
                version = "0.1.0"
                description = "Plugin fixture with an unresolved markdown skill-import."
                """);
        Files.writeString(dir.resolve("README.md"), markdownWithBrokenImport(
                "markdown-import-broken-plugin",
                "Plugin fixture with an unresolved markdown skill-import."));
        return dir;
    }

    private static Path docFixture(Path root) throws Exception {
        Path dir = root.resolve("doc");
        Files.createDirectories(dir.resolve("claude-md"));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "markdown-import-broken-doc"
                version = "0.1.0"
                description = "Doc repo fixture with an unresolved markdown skill-import."

                [[sources]]
                file = "claude-md/reference.md"
                """);
        Files.writeString(dir.resolve("claude-md/reference.md"), markdownWithBrokenImport(
                "markdown-import-broken-doc",
                "Doc repo fixture with an unresolved markdown skill-import."));
        return dir;
    }

    private static Path harnessFixture(Path root) throws Exception {
        Path dir = root.resolve("harness");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "markdown-import-broken-harness"
                version = "0.1.0"
                description = "Harness fixture with an unresolved markdown skill-import."
                """);
        Files.writeString(dir.resolve("README.md"), markdownWithBrokenImport(
                "markdown-import-broken-harness",
                "Harness fixture with an unresolved markdown skill-import."));
        return dir;
    }

    private static String markdownWithBrokenImport(String title, String description) {
        return """
                ---
                title: %s
                skill-imports:
                  - unit: %s
                    path: references/missing.md
                    reason: Smoke fixture verifies advisory validation output.
                ---

                # %s

                %s
                """.formatted(title, MISSING_UNIT, title, description);
    }

    private record InstallCheck(
            String kind,
            String unitName,
            ProcessRecord process,
            boolean reportedViolation) {}
}
