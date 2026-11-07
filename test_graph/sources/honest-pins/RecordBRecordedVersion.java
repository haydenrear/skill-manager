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
 * Record shape (b), #298: a unit's installed record ({@code installed/*.json})
 * carries a version that disagrees with the synced checkout's own manifest.
 *
 * <p>Plant: a "project" home with one unit installed from a local directory at
 * 0.1.0, and a "worktree" home cloned from it.
 *
 * <p>SHAPE, two clauses, one per byte-moving path measured to skip the record:
 * <ol>
 *   <li>the worktree bumps the unit's manifest to 0.2.0 and
 *       {@code home sync --from worktree --to project} carries the bytes;</li>
 *   <li>{@code sync <unit> --from <dir>} with a 0.3.0 manifest.</li>
 * </ol>
 * HONEST after each: the project home's {@code installed/<unit>.json}
 * "version" equals the version its {@code skills/<unit>/skill-manager.toml}
 * declares. At 0.28.2 both leave the record at 0.1.0 — #298's "recorded
 * 0.2.0 while running 0.8.1".
 *
 * <p>CONTROL: a second unit, installed from a local Git remote, whose upstream
 * bumps its manifest; {@code sync <unit> --git-latest} rewrites the record's
 * version. That path re-reads the manifest today and must keep doing so.
 */
public class RecordBRecordedVersion {
    static final String ID = "honest.record-b";
    static final String UNIT = "hp-record-b-unit";
    static final String GIT_UNIT = "hp-record-b-git-unit";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "installed-record", "version", "298")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path source;
            Path project;
            Path worktree;
            Path bumpedSource;
            Path upstream;
            try {
                root = HonestPinsKit.scratch(ctx, "record-b");
                source = root.resolve("source").resolve(UNIT);
                bumpedSource = root.resolve("source-0.3.0").resolve(UNIT);
                upstream = root.resolve("git-upstream");
                project = root.resolve("project-home");
                worktree = root.resolve("worktree-home");
                Files.createDirectories(project);
                HonestPinsKit.writeSkill(source, UNIT, "0.1.0", "release 0.1.0");
                HonestPinsKit.writeSkill(bumpedSource, UNIT, "0.3.0", "release 0.3.0");
                HonestPinsKit.writeSkill(upstream, GIT_UNIT, "0.1.0", "release 0.1.0");
                HonestPinsKit.git(upstream, "init", "-b", "main", "--quiet");
                HonestPinsKit.commitAll(upstream, "0.1.0");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            Path projectUnit = project.resolve("skills").resolve(UNIT);
            ProcessRecord install = HonestPinsKit.sm(ctx, "install", root, project,
                    "install", source.toString(), "--yes", "--no-bind-default");
            ProcessRecord installGit = HonestPinsKit.sm(ctx, "install_git_unit", root, project,
                    "install", "git+" + upstream.toUri(), "--yes", "--no-bind-default");
            ProcessRecord clone = HonestPinsKit.sm(ctx, "clone_to_worktree", root, project,
                    "home", "clone", "--from", project.toString(), "--to", worktree.toString(), "--json");
            boolean setupRecordAgrees = install.exitCode() == 0 && installGit.exitCode() == 0
                    && clone.exitCode() == 0
                    && "0.1.0".equals(HonestPinsKit.recordedVersion(project, UNIT))
                    && "0.1.0".equals(HonestPinsKit.recordedVersion(project, GIT_UNIT));
            try {
                HonestPinsKit.writeSkill(worktree.resolve("skills").resolve(UNIT), UNIT, "0.2.0", "release 0.2.0");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed bumping the worktree unit: " + e)
                        .assertion("setup_ok", false);
            }

            // SHAPE clause 1: home sync moves the 0.2.0 bytes.
            ProcessRecord homeSync = HonestPinsKit.sm(ctx, "home_sync_bumped", root, project,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(),
                    "--unit", UNIT, "--json");
            String bytes1 = HonestPinsKit.manifestVersion(projectUnit);
            String recorded1 = HonestPinsKit.recordedVersion(project, UNIT);
            boolean setupHomeSyncMoved = homeSync.exitCode() == 0 && "0.2.0".equals(bytes1);
            boolean shapeHomeSync = setupHomeSyncMoved && bytes1.equals(recorded1);

            // SHAPE clause 2: sync --from a local directory moves the 0.3.0 bytes.
            ProcessRecord syncFrom = HonestPinsKit.sm(ctx, "sync_from_bumped", root, project,
                    "sync", UNIT, "--from", bumpedSource.toString(), "--skip-mcp", "--yes");
            String bytes2 = HonestPinsKit.manifestVersion(projectUnit);
            String recorded2 = HonestPinsKit.recordedVersion(project, UNIT);
            boolean setupSyncFromMoved = syncFrom.exitCode() == 0 && "0.3.0".equals(bytes2);
            boolean shapeSyncFrom = setupSyncFromMoved && bytes2.equals(recorded2);

            // CONTROL: --git-latest re-reads the manifest.
            Path gitUnit = project.resolve("skills").resolve(GIT_UNIT);
            ProcessRecord gitLatest;
            try {
                HonestPinsKit.writeSkill(upstream, GIT_UNIT, "0.2.0", "release 0.2.0");
                HonestPinsKit.commitAll(upstream, "0.2.0");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed bumping the git upstream: " + e)
                        .assertion("setup_ok", false);
            }
            gitLatest = HonestPinsKit.sm(ctx, "sync_git_latest_bumped", root, project,
                    "sync", GIT_UNIT, "--git-latest", "--skip-mcp", "--yes");
            String gitRecorded = HonestPinsKit.recordedVersion(project, GIT_UNIT);
            boolean control = gitLatest.exitCode() == 0
                    && "0.2.0".equals(HonestPinsKit.manifestVersion(gitUnit))
                    && "0.2.0".equals(gitRecorded);

            boolean setup = setupRecordAgrees && setupHomeSyncMoved && setupSyncFromMoved;
            String detail = "after home sync: manifest=" + bytes1 + " record=" + recorded1
                    + "; after sync --from: manifest=" + bytes2 + " record=" + recorded2
                    + "; control git-latest: manifest=" + HonestPinsKit.manifestVersion(gitUnit)
                    + " record=" + gitRecorded + " rc=" + gitLatest.exitCode()
                    + "; setup recordAgrees=" + setupRecordAgrees + " homeSyncMoved=" + setupHomeSyncMoved
                    + " syncFromMoved=" + setupSyncFromMoved;
            NodeResult result = setup && control && shapeHomeSync && shapeSyncFrom
                    ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install).process(installGit).process(clone).process(homeSync)
                    .process(syncFrom).process(gitLatest)
                    .assertion("setup_installed_records_agree_at_0_1_0", setupRecordAgrees)
                    .assertion("setup_home_sync_moved_0_2_0_bytes", setupHomeSyncMoved)
                    .assertion("setup_sync_from_moved_0_3_0_bytes", setupSyncFromMoved)
                    .assertion("control_git_latest_rewrites_record_version", control)
                    .assertion("shape_home_sync_record_matches_manifest", shapeHomeSync)
                    .assertion("shape_sync_from_record_matches_manifest", shapeSyncFrom)
                    .log(detail);
        });
    }
}
