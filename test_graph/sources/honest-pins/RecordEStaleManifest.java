///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HonestPinsKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Record shape (e), #394: a root-home sync removes a unit the project's own
 * {@code skill-project.toml} declares, judged against a stale cached copy of
 * that manifest, and writes no audit entry in the home it removed it from.
 *
 * <p>Plant: three local skill sources. The project's manifest declares all
 * three and {@code project resolve} (root home) materializes all three into
 * the project's child home, unmodified. Then the project's manifest drops
 * {@code three} and keeps {@code one} and {@code two}, while the root home's
 * registration snapshot ({@code projects/<name>/skill-project.toml}, the copy
 * {@code SyncClaimingProjects} loads via {@code loadSnapshot}) is an OLDER
 * manifest declaring only {@code one} — the aged copy #394 found, planted by
 * writing that older manifest's bytes there.
 *
 * <p>Then the root home syncs {@code one}, which reconciles the claiming
 * project's child home.
 *
 * <p>HONEST: {@code two} — declared by the project's own manifest — is still in
 * the child home, and the removal the sync does make ({@code three}) is in the
 * child home's {@code audit.log}. At 0.28.2 {@code two} is removed ("no longer
 * a project dependency and unmodified") and the child home has no audit
 * entry for either removal.
 *
 * <p>CONTROL: {@code three}, undeclared by the project's manifest AND by the
 * snapshot, and unmodified, is removed. The fix must not get there by never
 * pruning.
 */
public class RecordEStaleManifest {
    static final String ID = "honest.record-e";
    static final String PROJECT = "hp-record-e-project";
    static final String ONE = "hp-record-e-one";
    static final String TWO = "hp-record-e-two";
    static final String THREE = "hp-record-e-three";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "child-home", "registration", "394")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path rootHome;
            Path project;
            Path sources;
            try {
                root = HonestPinsKit.scratch(ctx, "record-e");
                rootHome = root.resolve("root-home");
                project = root.resolve("project");
                sources = root.resolve("sources");
                Files.createDirectories(rootHome);
                Files.createDirectories(project);
                for (String u : new String[]{ONE, TWO, THREE}) {
                    HonestPinsKit.writeSkill(sources.resolve(u), u, "0.1.0", "v1");
                }
                Files.writeString(project.resolve("skill-project.toml"), manifest(sources, ONE, TWO, THREE));
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            Path childSkills = project.resolve(".skill-manager/skills");
            Path snapshot = rootHome.resolve("projects").resolve(PROJECT).resolve("skill-project.toml");
            ProcessRecord resolve = HonestPinsKit.sm(ctx, "project_resolve", root, rootHome,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            boolean setupResolved = resolve.exitCode() == 0
                    && Files.isDirectory(childSkills.resolve(ONE))
                    && Files.isDirectory(childSkills.resolve(TWO))
                    && Files.isDirectory(childSkills.resolve(THREE))
                    && Files.isRegularFile(snapshot);
            boolean setupPlanted;
            try {
                Files.writeString(project.resolve("skill-project.toml"), manifest(sources, ONE, TWO));
                Files.writeString(snapshot, manifest(sources, ONE));
                Files.writeString(sources.resolve(ONE).resolve("SKILL.md"),
                        HonestPinsKit.read(sources.resolve(ONE).resolve("SKILL.md")) + "v2\n");
                setupPlanted = HonestPinsKit.read(project.resolve("skill-project.toml")).contains(TWO)
                        && !HonestPinsKit.read(snapshot).contains(TWO);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed planting the stale snapshot: " + e)
                        .process(resolve).assertion("setup_ok", false);
            }
            String auditBefore = HonestPinsKit.read(project.resolve(".skill-manager/audit.log"));

            ProcessRecord sync = HonestPinsKit.sm(ctx, "root_sync_one", root, rootHome,
                    "sync", ONE, "--from", sources.resolve(ONE).toString(), "--skip-mcp", "--yes", "--verbose");
            String auditAfter = HonestPinsKit.read(project.resolve(".skill-manager/audit.log"));
            String newAudit = auditAfter.length() >= auditBefore.length()
                    ? auditAfter.substring(auditBefore.length()) : auditAfter;
            boolean twoKept = Files.isDirectory(childSkills.resolve(TWO));
            boolean threeRemoved = !Files.isDirectory(childSkills.resolve(THREE));
            boolean removalAudited = threeRemoved && newAudit.contains(THREE);

            boolean setup = setupResolved && setupPlanted && sync.exitCode() == 0;
            String detail = "sync rc=" + sync.exitCode() + "; declared " + TWO + " kept=" + twoKept
                    + "; undeclared " + THREE + " removed=" + threeRemoved
                    + " audited in child home=" + removalAudited
                    + "; setup resolved=" + setupResolved + " planted=" + setupPlanted;
            NodeResult result = setup && threeRemoved && twoKept && removalAudited
                    ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(resolve).process(sync)
                    .assertion("setup_child_home_holds_all_three_and_stale_snapshot_planted", setupResolved && setupPlanted)
                    .assertion("setup_root_sync_ok", sync.exitCode() == 0)
                    .assertion("control_undeclared_unmodified_unit_removed", threeRemoved)
                    .assertion("shape_unit_declared_by_project_manifest_kept", twoKept)
                    .assertion("shape_removal_audited_in_child_home", removalAudited)
                    .log(detail);
        });
    }

    private static String manifest(Path sources, String... units) {
        StringBuilder sb = new StringBuilder("[project]\nname = \"" + PROJECT + "\"\n");
        for (String u : units) {
            sb.append("\n[skills.").append(u).append("]\nsource = \"")
                    .append(sources.resolve(u)).append("\"\n");
        }
        return sb.toString();
    }
}
