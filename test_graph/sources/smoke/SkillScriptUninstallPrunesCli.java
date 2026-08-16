///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

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
 *
 * <p><b>ARTI-08 widened it to the half that survived.</b> The node asserted
 * exactly what {@code PruneCliIfOrphan} already removed — the declared binary
 * and the lock row — so it was green while the {@code cache/skill-script-&lt;unit&gt;
 * -&lt;tool&gt;/} tree the install actually wrote outlived every uninstall
 * (skill-manager#104). The fixture now writes that tree and a wrapper that
 * execs into it, which is the real shape, and the two assertions below decide
 * the ticket's declared expected effect: install, uninstall, and the home is
 * comparable to before.
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

            Path skillDir = privateHome.resolve("skills").resolve(SKILL);
            Path bin = privateHome.resolve("bin").resolve("cli").resolve(TOOL);
            Path lockPath = privateHome.resolve("cli-lock.toml");
            Path cacheTree = privateHome.resolve("cache")
                    .resolve("skill-script-" + SKILL + "-" + TOOL);
            String lockAfterInstall = read(lockPath);
            boolean installedSkill = Files.isDirectory(skillDir);
            boolean installedBin = Files.isRegularFile(bin);
            boolean installedLock = lockAfterInstall.contains("[\"skill-script\".\"" + TOOL + "\"]")
                    && lockAfterInstall.contains("requested_by = [\"" + SKILL + "\"]");
            boolean installedTree = Files.isDirectory(cacheTree);

            ProcessRecord uninstall = Procs.run(ctx, "uninstall_skill_script",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "uninstall", SKILL, "--keep-mcp"));

            String lockAfterUninstall = read(lockPath);
            boolean removedSkill = !Files.exists(skillDir);
            boolean removedBin = !Files.exists(bin);
            boolean removedLock = !lockAfterUninstall.contains("[\"skill-script\".\"" + TOOL + "\"]")
                    && !lockAfterUninstall.contains(SKILL);
            boolean removedTree = !Files.exists(cacheTree);

            boolean pass = install.exitCode() == 0
                    && uninstall.exitCode() == 0
                    && installedSkill
                    && installedBin
                    && installedLock
                    && removedSkill
                    && removedBin
                    && removedLock
                    && installedTree
                    && removedTree;

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
                                    + " removedLock=" + removedLock
                                    + " installedTree=" + installedTree
                                    + " removedTree=" + removedTree);
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
                    .assertion("cli_lock_row_removed_after_uninstall", removedLock)
                    .assertion("skill_script_cache_tree_installed_before_uninstall", installedTree)
                    // The one this node did not have, and the one #104 is about.
                    .assertion("skill_script_cache_tree_removed_after_uninstall", removedTree);
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
        SmEnv.apply(pb, privateHome.toString(),
                SmEnv.sandbox(privateClaude, privateCodex, privateGemini));
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
