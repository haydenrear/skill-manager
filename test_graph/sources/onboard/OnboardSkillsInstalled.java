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
            // SI-18: the bundled PLUGIN is tla-spec-dev, not skt. skt is a
            // contained skill of it now, so the bytes that used to land at
            // plugins/skt/skills/skt land at
            // plugins/tla-spec-dev/skills/skt. skill-manager stays a bundled
            // skill of its own.
            //
            // The contained assertion is deliberately still about SKT rather
            // than about the plugin's entry skill: what this node has to prove
            // is that onboarding a fresh home still delivers skt, by the new
            // route. A plugin dir with a manifest and no skt in it would be
            // the migration half-done, and it would pass a check that only
            // asked whether the plugin arrived.
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
            Path carrier = Path.of(home).resolve("plugins").resolve("tla-spec-dev");
            Path standaloneSkt = Path.of(home).resolve("plugins").resolve("skt");
            Path installedDir = Path.of(home).resolve("installed");

            // INVERTED at OUN-6, the fifth and last. Onboarding installed a
            // STANDALONE skill-manager from the vendored skill-manager-skill/
            // tree; that tree is gone and the carrier ships the skill, so a
            // standalone copy reappearing means the seeding came back and the
            // home holds two copies of one name again.
            boolean standaloneManagerAbsent = !Files.exists(manager);
            boolean containedManagerOk = Files.isRegularFile(
                    carrier.resolve("skills").resolve("skill-manager").resolve("SKILL.md"));
            boolean retiredAbsent = !Files.exists(retired);
            boolean retiredRecordAbsent =
                    !Files.exists(installedDir.resolve("skill-dev-skill.json"));
            boolean carrierDirOk = Files.isDirectory(carrier);
            boolean carrierManifestOk =
                    Files.isRegularFile(carrier.resolve("skill-manager-plugin.toml"));
            boolean sktContainedOk = Files.isRegularFile(
                    carrier.resolve("skills").resolve("skt").resolve("SKILL.md"));
            // INVERTED, like skill-dev-skill above and for the same reason.
            // Onboarding used to install a STANDALONE skt plugin; if it ever
            // does again the home holds two copies of skt, one of them
            // updatable from a repository that no longer publishes it. That is
            // the duplication SI-18 removed, and nothing else would notice it
            // coming back — the unit installs perfectly cleanly, it is simply
            // not wanted.
            boolean standaloneSktAbsent = !Files.exists(standaloneSkt);
            // Provenance moved with the unit: the CARRIER is what carries a
            // git remote now, and it is the record `skt check` and `sync` read.
            boolean carrierGitOk = Files.exists(carrier.resolve(".git"));
            String carrierRecord = read(installedDir.resolve("tla-spec-dev.json"));
            String carrierGithub = "https://github.com/haydenrear/tla-spec-dev-plugin";
            // RECORD *AND* REAL REMOTE, as the manager check did before OUN-6.
            // A substring of the installed record alone would pass for a
            // carrier cloned from a fork or mirror whose record still names
            // the upstream — which is the provenance regression this node
            // exists to catch. gitRemote() is the half that reads the clone.
            String carrierRemote = gitRemote(carrier);
            boolean carrierRemoteOk = carrierRecord.contains(carrierGithub)
                    && carrierRemote != null
                    && carrierRemote.startsWith(carrierGithub);
            // And no installed record is written for the standalone.
            boolean managerRecordAbsent = !Files.exists(installedDir.resolve("skill-manager.json"));

            boolean pass = standaloneManagerAbsent && containedManagerOk
                    && retiredAbsent && retiredRecordAbsent
                    && carrierDirOk && carrierManifestOk && sktContainedOk
                    && standaloneSktAbsent && managerRecordAbsent
                    && carrierGitOk
                    && carrierRemoteOk;
            return (pass
                    ? NodeResult.pass("onboard.skills.installed")
                    : NodeResult.fail("onboard.skills.installed",
                            "standaloneManagerAbsent=" + standaloneManagerAbsent
                                    + " containedManager=" + containedManagerOk
                                    + " managerRecordAbsent=" + managerRecordAbsent
                                    + " retiredAbsent=" + retiredAbsent
                                    + " retiredRecordAbsent=" + retiredRecordAbsent
                                    + " carrierDir=" + carrierDirOk
                                    + " carrierManifest=" + carrierManifestOk
                                    + " sktContained=" + sktContainedOk
                                    + " standaloneSktAbsent=" + standaloneSktAbsent
                                    + " carrierGit=" + carrierGitOk
                                    + " carrierRemote=" + carrierRemoteOk + " (" + carrierRemote + ")"))
                    .assertion("standalone_skill_manager_is_NOT_installed", standaloneManagerAbsent)
                    .assertion("and_no_installed_record_is_written_for_it_either", managerRecordAbsent)
                    .assertion("the_carrier_contains_skill_manager", containedManagerOk)
                    .assertion("retired_skill_dev_is_NOT_seeded", retiredAbsent)
                    .assertion("and_no_installed_record_is_written_for_it",
                            retiredRecordAbsent)
                    .assertion("carrier_plugin_dir_present", carrierDirOk)
                    .assertion("carrier_plugin_manifest_present", carrierManifestOk)
                    .assertion("skt_contained_skill_present", sktContainedOk)
                    .assertion("standalone_skt_plugin_is_NOT_installed", standaloneSktAbsent)
                    .assertion("carrier_git_metadata_present", carrierGitOk)
                    .assertion("carrier_origin_points_to_the_plugin_repo", carrierRemoteOk);
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
