///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

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
 * skill-import. The install must still commit, the final console renderer
 * must report the unit-reference violation for every kind, and the run must
 * exit {@code MarkdownImportValidator.EXIT_CODE}.
 *
 * <h2>Why the exit code, and why this node changed</h2>
 *
 * <p>This node used to assert every one of these installs exited <b>0</b>. It
 * was encoding the defect: the violation block is printed after the success
 * banner and after {@code ACTION_REQUIRED}, at the bottom of a long install,
 * so a caller that reads the tail or checks {@code $?} concluded success. A
 * printed violation that does not reach an exit code is not a check, it is a
 * comment. {@code 11} is distinct from the generic 1 so a caller can tell
 * "your markdown names something that is not there" from "the install
 * failed" — and the unit IS still committed when it fires, which the
 * {@code committed} assertion below pins.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>"Every install exits 11" would also hold for a binary that failed every
 * install for an unrelated reason, and "the violation was rendered" would
 * hold over any log that happened to contain those words. So the same node,
 * in the same home, through the same binary, finally installs a unit whose
 * import RESOLVES — against one of the four units it just installed — and
 * asserts that one exits 0 with no violation block. The graph carries a
 * second, independent witness in {@code markdown.imports.cross_kind.targets},
 * which installs four cross-kind units with valid imports and asserts exit 0.
 */
public class MarkdownImportViolationsReported {
    static final NodeSpec SPEC = NodeSpec.of("markdown.import.violations.reported")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("install", "markdown-imports", "skill", "plugin", "doc-repo", "harness")
            .timeout("120s");

    private static final String MISSING_UNIT = "missing-smoke-reference-unit";

    /** {@code dev.skillmanager.validation.MarkdownImportValidator.EXIT_CODE}. */
    private static final int MARKDOWN_IMPORT_VIOLATION_EXIT = 11;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) {
                return NodeResult.fail("markdown.import.violations.reported",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixturesRoot = Path.of(home).resolve("markdown-import-violation-fixtures");

            List<InstallCheck> checks = new ArrayList<>();
            ProcessRecord cleanProc;
            String cleanLog;
            try {
                Files.createDirectories(fixturesRoot);
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        skillFixture(fixturesRoot), "skill",
                        "markdown-import-broken-skill"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        pluginFixture(fixturesRoot), "plugin",
                        "markdown-import-broken-plugin"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        docFixture(fixturesRoot), "doc",
                        "markdown-import-broken-doc"));
                checks.add(install(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        harnessFixture(fixturesRoot), "harness",
                        "markdown-import-broken-harness"));
                // The discriminator: same home, same binary, an import that
                // resolves against a unit installed two lines above.
                cleanProc = installUnit(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                        cleanFixture(fixturesRoot), "install-clean");
                cleanLog = logBody(ctx, cleanProc);
            } catch (Exception e) {
                return NodeResult.error("markdown.import.violations.reported", e);
            }

            boolean allExitViolationCode = checks.stream()
                    .allMatch(c -> c.process().exitCode() == MARKDOWN_IMPORT_VIOLATION_EXIT);
            boolean allReported = checks.stream().allMatch(InstallCheck::reportedViolation);
            boolean allCommitted = checks.stream().allMatch(c -> committed(home, c));
            boolean cleanExitsZero = cleanProc.exitCode() == 0;
            boolean cleanReportsNoViolation =
                    !cleanLog.contains("markdown skill-import violations");
            boolean pass = allExitViolationCode && allReported && allCommitted
                    && cleanExitsZero && cleanReportsNoViolation;

            NodeResult result = pass
                    ? NodeResult.pass("markdown.import.violations.reported")
                    : NodeResult.fail("markdown.import.violations.reported",
                            "allExitViolationCode=" + allExitViolationCode
                                    + " allReported=" + allReported
                                    + " allCommitted=" + allCommitted
                                    + " cleanRc=" + cleanProc.exitCode()
                                    + " cleanReportsNoViolation=" + cleanReportsNoViolation);
            for (InstallCheck check : checks) result = result.process(check.process());
            result = result.process(cleanProc);
            return result
                    .assertion("skill_install_commits_and_reports", check(checks, "skill", home))
                    .assertion("plugin_install_commits_and_reports", check(checks, "plugin", home))
                    .assertion("doc_install_commits_and_reports", check(checks, "doc", home))
                    .assertion("harness_install_commits_and_reports", check(checks, "harness", home))
                    .assertion("all_installs_exit_import_violation_code", allExitViolationCode)
                    .assertion("all_violations_rendered", allReported)
                    .assertion("all_units_still_committed", allCommitted)
                    .assertion("COMPANION_resolving_import_exits_zero", cleanExitsZero)
                    .assertion("COMPANION_resolving_import_reports_nothing", cleanReportsNoViolation);
        });
    }

    /** The store directory each kind commits into. */
    private static boolean committed(String home, InstallCheck c) {
        String dir = switch (c.kind()) {
            case "skill" -> "skills";
            case "plugin" -> "plugins";
            case "doc" -> "docs";
            default -> "harnesses";
        };
        return Files.isDirectory(Path.of(home, dir, c.unitName()));
    }

    private static InstallCheck install(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            Path sm,
            Path repoRoot,
            String home,
            String claudeHome,
            String codexHome,
            String geminiHome,
            Path unitDir,
            String kind,
            String unitName) throws Exception {
        ProcessRecord proc = installUnit(ctx, sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                unitDir, "install-" + kind);
        String log = logBody(ctx, proc);
        boolean rendered = log.contains("markdown skill-import violations")
                && log.contains(unitName + " (" + kind + ")")
                && log.contains(MISSING_UNIT)
                && log.contains("references missing unit");
        return new InstallCheck(kind, unitName, proc, rendered);
    }

    private static ProcessRecord installUnit(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            Path sm,
            Path repoRoot,
            String home,
            String claudeHome,
            String codexHome,
            String geminiHome,
            Path unitDir,
            String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                sm.toString(), "install", "file://" + unitDir.toAbsolutePath(), "--yes");
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, label, pb);
    }

    private static boolean check(List<InstallCheck> checks, String kind, String home) {
        return checks.stream()
                .filter(c -> c.kind().equals(kind))
                .findFirst()
                .map(c -> c.process().exitCode() == MARKDOWN_IMPORT_VIOLATION_EXIT
                        && c.reportedViolation()
                        && committed(home, c))
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

    /**
     * The companion fixture: an import that RESOLVES, against the broken-skill
     * unit installed earlier in this node (its {@code SKILL.md} is on disk in
     * the store by then). Same kind, same home, same binary — the only
     * difference is whether the reference is real.
     */
    private static Path cleanFixture(Path root) throws Exception {
        Path dir = root.resolve("clean");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                title: markdown-import-clean-skill
                skill-imports:
                  - unit: markdown-import-broken-skill
                    path: SKILL.md
                    reason: Companion proving a resolving import exits 0 in this same home.
                ---

                # markdown-import-clean-skill

                Skill fixture whose markdown skill-import resolves.
                """);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "markdown-import-clean-skill"
                version = "0.1.0"
                description = "Skill fixture whose markdown skill-import resolves."
                """);
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
