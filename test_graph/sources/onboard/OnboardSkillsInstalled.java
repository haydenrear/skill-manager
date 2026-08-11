///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confirms the on-disk side of {@code skill-manager onboard}: the
 * bundled skills should land under {@code $SKILL_MANAGER_HOME/skills/}
 * with their {@code SKILL.md} present, and local onboard installs should
 * still record the GitHub remotes used by later {@code sync}.
 *
 * <p>Note the dir name and skill name diverge: the source dir
 * {@code skill-manager-skill/} publishes as {@code skill-manager}, so
 * we look for the latter in the SkillStore.
 */
public class OnboardSkillsInstalled {
    static final NodeSpec SPEC = NodeSpec.of("onboard.skills.installed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboard.completed")
            .tags("onboard", "store")
            .timeout("10s")
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("onboard.skills.installed", "missing env.prepared context");
            }
            // skill-publisher's repo ships the skt PLUGIN now: it installs
            // to plugins/skt (plugin manifest + contained skills), while
            // skill-manager and skill-dev-skill stay bundled skills.
            Path skillsDir = Path.of(home).resolve("skills");
            Path manager = skillsDir.resolve("skill-manager");
            Path dev = skillsDir.resolve("skill-dev-skill");
            Path skt = Path.of(home).resolve("plugins").resolve("skt");
            Path installedDir = Path.of(home).resolve("installed");

            boolean managerDirOk = Files.isDirectory(manager);
            boolean managerMdOk = Files.isRegularFile(manager.resolve("SKILL.md"));
            boolean devDirOk = Files.isDirectory(dev);
            boolean devMdOk = Files.isRegularFile(dev.resolve("SKILL.md"));
            boolean sktDirOk = Files.isDirectory(skt);
            boolean sktManifestOk = Files.isRegularFile(skt.resolve("skill-manager-plugin.toml"));
            boolean sktContainedOk = Files.isRegularFile(
                    skt.resolve("skills").resolve("skt").resolve("SKILL.md"));
            boolean managerGitOk = Files.exists(manager.resolve(".git"));
            boolean devGitOk = Files.exists(dev.resolve(".git"));
            String managerRecord = read(installedDir.resolve("skill-manager.json"));
            String devRecord = read(installedDir.resolve("skill-dev-skill.json"));
            String managerGithub = "https://github.com/haydenrear/skill-manager-skill.git";
            String devGithub = "https://github.com/haydenrear/skill-dev-skill.git";
            boolean managerRemoteOk = managerRecord.contains(managerGithub)
                    && managerGithub.equals(gitRemote(manager));
            boolean devRemoteOk = devRecord.contains(devGithub)
                    && devGithub.equals(gitRemote(dev));

            boolean pass = managerDirOk && managerMdOk
                    && devDirOk && devMdOk
                    && sktDirOk && sktManifestOk && sktContainedOk
                    && managerGitOk && devGitOk
                    && managerRemoteOk && devRemoteOk;
            return (pass
                    ? NodeResult.pass("onboard.skills.installed")
                    : NodeResult.fail("onboard.skills.installed",
                            "managerDir=" + managerDirOk + " managerMd=" + managerMdOk
                                    + " devDir=" + devDirOk + " devMd=" + devMdOk
                                    + " sktDir=" + sktDirOk
                                    + " sktManifest=" + sktManifestOk
                                    + " sktContained=" + sktContainedOk
                                    + " managerGit=" + managerGitOk
                                    + " devGit=" + devGitOk
                                    + " managerRemote=" + managerRemoteOk
                                    + " devRemote=" + devRemoteOk))
                    .assertion("skill_manager_dir_present", managerDirOk)
                    .assertion("skill_manager_md_present", managerMdOk)
                    .assertion("skill_dev_dir_present", devDirOk)
                    .assertion("skill_dev_md_present", devMdOk)
                    .assertion("skt_plugin_dir_present", sktDirOk)
                    .assertion("skt_plugin_manifest_present", sktManifestOk)
                    .assertion("skt_contained_skill_present", sktContainedOk)
                    .assertion("skill_manager_git_metadata_present", managerGitOk)
                    .assertion("skill_dev_git_metadata_present", devGitOk)
                    .assertion("skill_manager_origin_points_to_github", managerRemoteOk)
                    .assertion("skill_dev_origin_points_to_github", devRemoteOk);
        });
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }

    private static String gitRemote(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "remote", "get-url", "origin")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 ? output : "";
        } catch (Exception e) {
            return "";
        }
    }
}
