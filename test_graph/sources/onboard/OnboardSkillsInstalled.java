///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confirms the on-disk side of {@code skill-manager onboard}: both
 * bundled skills should land under {@code $SKILL_MANAGER_HOME/skills/}
 * with their {@code SKILL.md} present (the canonical proof that the
 * tar.gz round-trip through the registry actually committed files).
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
            Path skillsDir = Path.of(home).resolve("skills");
            Path manager = skillsDir.resolve("skill-manager");
            Path publisher = skillsDir.resolve("skill-publisher");

            boolean managerDirOk = Files.isDirectory(manager);
            boolean managerMdOk = Files.isRegularFile(manager.resolve("SKILL.md"));
            boolean publisherDirOk = Files.isDirectory(publisher);
            boolean publisherMdOk = Files.isRegularFile(publisher.resolve("SKILL.md"));

            boolean pass = managerDirOk && managerMdOk && publisherDirOk && publisherMdOk;
            return (pass
                    ? NodeResult.pass("onboard.skills.installed")
                    : NodeResult.fail("onboard.skills.installed",
                            "managerDir=" + managerDirOk + " managerMd=" + managerMdOk
                                    + " publisherDir=" + publisherDirOk + " publisherMd=" + publisherMdOk))
                    .assertion("skill_manager_dir_present", managerDirOk)
                    .assertion("skill_manager_md_present", managerMdOk)
                    .assertion("skill_publisher_dir_present", publisherDirOk)
                    .assertion("skill_publisher_md_present", publisherMdOk);
        });
    }
}
