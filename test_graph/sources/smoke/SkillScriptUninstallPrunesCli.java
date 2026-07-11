///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ISSUE-91B command smoke: uninstalling a skill-script fixture removes the
 * orphaned bin/cli artifact and the matching cli-lock row.
 */
public class SkillScriptUninstallPrunesCli {
    static final String SKILL = "skill-script-skill";
    static final String TOOL = "skill-script-touched";

    static final NodeSpec SPEC = NodeSpec.of("skill.script.uninstall.prunes.cli")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cli", "skill-script", "uninstall")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail("skill.script.uninstall.prunes.cli",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path fixture = repoRoot.resolve("test_graph/fixtures/skill-script-skill");
            Path privateRoot = Path.of(envHome).resolve("skill-script-uninstall-home");
            Path privateHome = privateRoot.resolve("home");
            Path privateClaude = privateRoot.resolve("claude");
            Path privateCodex = privateRoot.resolve("codex");
            Path privateGemini = privateRoot.resolve("gemini");
            try {
                Files.createDirectories(privateHome);
                Files.createDirectories(privateClaude);
                Files.createDirectories(privateCodex);
                Files.createDirectories(privateGemini);
            } catch (IOException e) {
                return NodeResult.fail("skill.script.uninstall.prunes.cli",
                        "private home setup failed: " + e.getMessage());
            }

            ProcessRecord install = Procs.run(ctx, "install_skill_script",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "install", fixture.toString(), "--yes"));

            Path skillDir = privateHome.resolve("skills").resolve(SKILL).resolve("latest");
            Path bin = privateHome.resolve("bin").resolve("cli").resolve(TOOL);
            Path lockPath = privateHome.resolve("cli-lock.toml");
            String lockAfterInstall = read(lockPath);
            boolean installedSkill = Files.isDirectory(skillDir);
            boolean installedBin = Files.isRegularFile(bin);
            boolean installedLock = lockAfterInstall.contains("[\"skill-script\".\"" + TOOL + "\"]")
                    && lockAfterInstall.contains("requested_by = [\"" + SKILL + "\"]");

            ProcessRecord uninstall = Procs.run(ctx, "uninstall_skill_script",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "uninstall", SKILL, "--keep-mcp"));

            String lockAfterUninstall = read(lockPath);
            boolean removedSkill = !Files.exists(skillDir);
            boolean removedBin = !Files.exists(bin);
            boolean removedLock = !lockAfterUninstall.contains("[\"skill-script\".\"" + TOOL + "\"]")
                    && !lockAfterUninstall.contains(SKILL);

            boolean pass = install.exitCode() == 0
                    && uninstall.exitCode() == 0
                    && installedSkill
                    && installedBin
                    && installedLock
                    && removedSkill
                    && removedBin
                    && removedLock;

            NodeResult result = pass
                    ? NodeResult.pass("skill.script.uninstall.prunes.cli")
                    : NodeResult.fail("skill.script.uninstall.prunes.cli",
                            "install=" + install.exitCode()
                                    + " uninstall=" + uninstall.exitCode()
                                    + " installedSkill=" + installedSkill
                                    + " installedBin=" + installedBin
                                    + " installedLock=" + installedLock
                                    + " removedSkill=" + removedSkill
                                    + " removedBin=" + removedBin
                                    + " removedLock=" + removedLock);
            return result
                    .process(install)
                    .process(uninstall)
                    .assertion("install_ok", install.exitCode() == 0)
                    .assertion("uninstall_ok", uninstall.exitCode() == 0)
                    .assertion("skill_installed_before_uninstall", installedSkill)
                    .assertion("cli_bin_installed_before_uninstall", installedBin)
                    .assertion("cli_lock_row_installed_before_uninstall", installedLock)
                    .assertion("skill_removed_after_uninstall", removedSkill)
                    .assertion("cli_bin_removed_after_uninstall", removedBin)
                    .assertion("cli_lock_row_removed_after_uninstall", removedLock);
        });
    }

    private static ProcessBuilder smProc(Path sm, Path repoRoot, Path privateHome,
                                         Path privateClaude, Path privateCodex,
                                         Path privateGemini,
                                         String... cliArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(sm.toString());
        for (String arg : cliArgs) argv.add(arg);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("SKILL_MANAGER_HOME", privateHome.toString());
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", privateClaude.toString());
        pb.environment().put("CODEX_HOME", privateCodex.toString());
        pb.environment().put("GEMINI_HOME", privateGemini.toString());
        return pb;
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
