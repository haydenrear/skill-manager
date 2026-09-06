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
            .timeout("90s")
            // HIS-17 / DEF-065. Was 10s. That budget is BELOW this graph's
            // own fixed per-node cost: measured over 14 node gaps in run
            // 20260823-165545, 13.15-17.66s, mean 14.65s, and jbang startup
            // alone is 5.68s on an idle machine with a warm cache. This node's
            // BODY is milliseconds. It could do nothing at all and still time
            // out, and .retries(2) turned that into flake rather than a verdict.
            //
            // 90s is not a measurement of this node -- nothing here takes 90s.
            // It is headroom over a fixed cost nobody has attacked yet. The
            // number that matters is the 14s floor and the OTLP exporter
            // failing on every node of every graph; DEF-065 stays OPEN on it.
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("onboard.skills.installed", "missing env.prepared context");
            }
            // skill-publisher's repo ships the skt PLUGIN: it installs to
            // plugins/skt (plugin manifest + contained skills), while
            // skill-manager stays a bundled skill.
            //
            // skill-dev-skill was a third bundled skill until OUN-4. Its
            // assertions are INVERTED rather than deleted: onboarding must now
            // prove it does NOT seed it. Deleting them would have left the
            // absence untested, and a seed that comes back is exactly the
            // regression nobody would notice — the unit installs cleanly, it
            // is simply not wanted.
            Path skillsDir = Path.of(home).resolve("skills");
            Path manager = skillsDir.resolve("skill-manager");
            Path retired = skillsDir.resolve("skill-dev-skill");
            Path skt = Path.of(home).resolve("plugins").resolve("skt");
            Path installedDir = Path.of(home).resolve("installed");

            boolean managerDirOk = Files.isDirectory(manager);
            boolean managerMdOk = Files.isRegularFile(manager.resolve("SKILL.md"));
            boolean retiredAbsent = !Files.exists(retired);
            boolean retiredRecordAbsent =
                    !Files.exists(installedDir.resolve("skill-dev-skill.json"));
            boolean sktDirOk = Files.isDirectory(skt);
            boolean sktManifestOk = Files.isRegularFile(skt.resolve("skill-manager-plugin.toml"));
            boolean sktContainedOk = Files.isRegularFile(
                    skt.resolve("skills").resolve("skt").resolve("SKILL.md"));
            boolean managerGitOk = Files.exists(manager.resolve(".git"));
            String managerRecord = read(installedDir.resolve("skill-manager.json"));
            String managerGithub = "https://github.com/haydenrear/skill-manager-skill.git";
            boolean managerRemoteOk = managerRecord.contains(managerGithub)
                    && managerGithub.equals(gitRemote(manager));

            boolean pass = managerDirOk && managerMdOk
                    && retiredAbsent && retiredRecordAbsent
                    && sktDirOk && sktManifestOk && sktContainedOk
                    && managerGitOk
                    && managerRemoteOk;
            return (pass
                    ? NodeResult.pass("onboard.skills.installed")
                    : NodeResult.fail("onboard.skills.installed",
                            "managerDir=" + managerDirOk + " managerMd=" + managerMdOk
                                    + " retiredAbsent=" + retiredAbsent
                                    + " retiredRecordAbsent=" + retiredRecordAbsent
                                    + " sktDir=" + sktDirOk
                                    + " sktManifest=" + sktManifestOk
                                    + " sktContained=" + sktContainedOk
                                    + " managerGit=" + managerGitOk
                                    + " managerRemote=" + managerRemoteOk))
                    .assertion("skill_manager_dir_present", managerDirOk)
                    .assertion("skill_manager_md_present", managerMdOk)
                    .assertion("retired_skill_dev_is_NOT_seeded", retiredAbsent)
                    .assertion("and_no_installed_record_is_written_for_it",
                            retiredRecordAbsent)
                    .assertion("skt_plugin_dir_present", sktDirOk)
                    .assertion("skt_plugin_manifest_present", sktManifestOk)
                    .assertion("skt_contained_skill_present", sktContainedOk)
                    .assertion("skill_manager_git_metadata_present", managerGitOk)
                    .assertion("skill_manager_origin_points_to_github", managerRemoteOk);
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
