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
 * Record shape (a), #403: a {@code home sync} that changes a unit in the
 * destination must leave a pending drift record there, and a no-op sync must
 * leave none.
 *
 * <p>Plant: a "project" home with one installed unit and a recorded drift
 * baseline ({@code home drift --record}); a "worktree" home cloned from it
 * ({@code home clone}).
 *
 * <p>CONTROL (run first, so a pending record from the shape arm cannot leak
 * into it): {@code home sync --from worktree --to project} with nothing
 * changed reports the unit {@code unchanged}, and {@code home drift --json} on
 * the project home says {@code "pending":false}.
 *
 * <p>SHAPE: edit the unit in the worktree home, sync again. The report says
 * {@code updated} and the project home's SKILL.md carries the edit — so a
 * loadable unit changed. HONEST: {@code home drift --json} now says
 * {@code "pending":true}. At 0.28.2 it says false: HomeSync never calls
 * DriftGate.
 */
public class RecordAHomeSyncArmsDrift {
    static final String ID = "honest.record-a";
    static final String UNIT = "hp-record-a-unit";
    static final String EDIT = "EDITED IN THE WORKTREE HOME";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "home-sync", "drift", "403")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path source;
            Path project;
            Path worktree;
            try {
                root = HonestPinsKit.scratch(ctx, "record-a");
                source = root.resolve("source").resolve(UNIT);
                project = root.resolve("project-home");
                worktree = root.resolve("worktree-home");
                Files.createDirectories(project);
                HonestPinsKit.writeSkill(source, UNIT, "0.1.0", "original body");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            ProcessRecord install = HonestPinsKit.sm(ctx, "install", root, project,
                    "install", source.toString(), "--yes", "--no-bind-default");
            ProcessRecord baseline = HonestPinsKit.sm(ctx, "drift_record_baseline", root, project,
                    "home", "drift", "--record", "--home", project.toString());
            ProcessRecord clone = HonestPinsKit.sm(ctx, "clone_to_worktree", root, project,
                    "home", "clone", "--from", project.toString(), "--to", worktree.toString(), "--json");
            ProcessRecord driftBefore = HonestPinsKit.sm(ctx, "drift_before", root, project,
                    "home", "drift", "--json", "--home", project.toString());
            boolean setup = install.exitCode() == 0 && baseline.exitCode() == 0 && clone.exitCode() == 0
                    && Files.isDirectory(worktree.resolve("skills").resolve(UNIT))
                    && pending(ctx, driftBefore) == Boolean.FALSE;

            // CONTROL: a sync that changes nothing records nothing.
            ProcessRecord noop = HonestPinsKit.sm(ctx, "home_sync_noop", root, project,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(), "--json");
            ProcessRecord driftAfterNoop = HonestPinsKit.sm(ctx, "drift_after_noop", root, project,
                    "home", "drift", "--json", "--home", project.toString());
            boolean noopUnchanged = noop.exitCode() == 0 && HonestPinsKit.output(ctx, noop).contains("\"status\":\"unchanged\"");
            boolean controlNoPending = noopUnchanged && pending(ctx, driftAfterNoop) == Boolean.FALSE;

            // SHAPE: a sync that changes the unit.
            Path worktreeSkill = worktree.resolve("skills").resolve(UNIT).resolve("SKILL.md");
            try {
                Files.writeString(worktreeSkill, HonestPinsKit.read(worktreeSkill) + EDIT + "\n");
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed editing the worktree unit: " + e)
                        .assertion("setup_ok", false);
            }
            ProcessRecord changing = HonestPinsKit.sm(ctx, "home_sync_changing", root, project,
                    "home", "sync", "--from", worktree.toString(), "--to", project.toString(), "--json");
            ProcessRecord driftAfterChange = HonestPinsKit.sm(ctx, "drift_after_change", root, project,
                    "home", "drift", "--json", "--home", project.toString());
            boolean unitChanged = changing.exitCode() == 0
                    && HonestPinsKit.read(project.resolve("skills").resolve(UNIT).resolve("SKILL.md")).contains(EDIT);
            Boolean pendingAfter = pending(ctx, driftAfterChange);
            boolean shape = unitChanged && pendingAfter == Boolean.TRUE;

            String detail = "changing sync rc=" + changing.exitCode() + " unitChanged=" + unitChanged
                    + " pending=" + pendingAfter
                    + "; noop sync unchanged=" + noopUnchanged + " pending=" + pending(ctx, driftAfterNoop)
                    + "; setup=" + setup;
            NodeResult result = setup && controlNoPending && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(install).process(baseline).process(clone).process(driftBefore)
                    .process(noop).process(driftAfterNoop).process(changing).process(driftAfterChange)
                    .assertion("setup_homes_built_and_baselined", setup)
                    .assertion("control_noop_sync_records_nothing", controlNoPending)
                    .assertion("setup_changing_sync_changed_the_unit", unitChanged)
                    .assertion("shape_changing_sync_leaves_pending_drift", shape)
                    .log(detail);
        });
    }

    /** {@code "pending"} from a {@code home drift --json} run; null when absent. */
    private static Boolean pending(com.hayden.testgraphsdk.sdk.NodeContext ctx, ProcessRecord drift) {
        String json = HonestPinsKit.lastJsonLine(HonestPinsKit.output(ctx, drift));
        if (json.contains("\"pending\":true")) return Boolean.TRUE;
        if (json.contains("\"pending\":false")) return Boolean.FALSE;
        return null;
    }
}
