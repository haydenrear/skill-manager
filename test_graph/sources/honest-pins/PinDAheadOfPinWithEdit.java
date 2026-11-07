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
 * Pin shape (d), #407: a unit installed AHEAD of an older pin WITH one local
 * edit that applies cleanly to the pin, and {@code project resolve} must put
 * it back on the pin keeping the edit, or refuse by name.
 *
 * <p>Plant: as pin-c (A then B on {@code main}, the project pins A, the unit
 * is resolved at A and then moved to B by {@code sync --git-latest}), plus an
 * uncommitted edit to {@code NOTES.md} — a file identical in A and B, so the
 * edit applies to the pin without conflict.
 *
 * <p>HONEST: the next {@code project resolve} leaves HEAD == A with the edit
 * still in the tree and exit 0, or exits non-zero naming the unit. At 0.28.2
 * it reports success and leaves B.
 *
 * <p>CONTROL: the local edit is never lost — after the resolve, whatever it
 * did, {@code NOTES.md} still carries it.
 */
public class PinDAheadOfPinWithEdit {
    static final String ID = "honest.pin-d";
    static final String UNIT = "hp-pin-d-unit";
    static final String PROJECT = "hp-pin-d-project";
    static final String EDIT = "LOCAL EDIT that applies to the pin";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "pin", "project-resolve", "local-edit", "407")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path upstream;
            Path home;
            Path project;
            String a;
            String b;
            try {
                root = HonestPinsKit.scratch(ctx, "pin-d");
                upstream = root.resolve("upstream");
                home = root.resolve("home");
                project = root.resolve("project");
                Files.createDirectories(home);
                Files.createDirectories(project);
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker A");
                Files.writeString(upstream.resolve("NOTES.md"), "notes, identical in A and B\n");
                HonestPinsKit.git(upstream, "init", "-b", "main", "--quiet");
                HonestPinsKit.commitAll(upstream, "A");
                a = HonestPinsKit.gitOut(upstream, "rev-parse", "HEAD");
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker B");
                HonestPinsKit.commitAll(upstream, "B");
                b = HonestPinsKit.gitOut(upstream, "rev-parse", "HEAD");
                Files.writeString(project.resolve("skill-project.toml"), """
                        [project]
                        name = "%s"

                        [skills.%s]
                        source = "git+%s#main"
                        revision = "%s"
                        """.formatted(PROJECT, UNIT, upstream.toUri(), a));
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            Path unitDir = home.resolve("skills").resolve(UNIT);
            Path notes = unitDir.resolve("NOTES.md");
            ProcessRecord resolve1 = HonestPinsKit.sm(ctx, "resolve_at_pin", root, home,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            boolean setupResolvedAtA = resolve1.exitCode() == 0 && a.equals(HonestPinsKit.head(unitDir));
            ProcessRecord ahead = HonestPinsKit.sm(ctx, "sync_git_latest_ahead", root, home,
                    "sync", UNIT, "--git-latest", "--skip-mcp", "--yes");
            boolean setupAhead = ahead.exitCode() == 0 && b.equals(HonestPinsKit.head(unitDir));
            boolean setupEdited;
            try {
                Files.writeString(notes, HonestPinsKit.read(notes) + EDIT + "\n");
                setupEdited = HonestPinsKit.read(notes).contains(EDIT);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed writing the local edit: " + e)
                        .assertion("setup_ok", false);
            }

            // SHAPE.
            ProcessRecord resolve2 = HonestPinsKit.sm(ctx, "resolve_while_ahead_with_edit", root, home,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            String headAfter = HonestPinsKit.head(unitDir);
            boolean editKept = HonestPinsKit.read(notes).contains(EDIT);
            boolean honoured = resolve2.exitCode() == 0 && a.equals(headAfter) && editKept;
            boolean refusedByName = resolve2.exitCode() != 0
                    && HonestPinsKit.output(ctx, resolve2).contains(UNIT);
            boolean falseConvergence = resolve2.exitCode() == 0 && !a.equals(headAfter);

            boolean setup = setupResolvedAtA && setupAhead && setupEdited;
            boolean shape = honoured || refusedByName;
            String where = headAfter.equals(a) ? "A (the pin)" : headAfter.equals(b) ? "B (ahead)" : headAfter;
            String detail = "A(pin)=" + a + " B=" + b + "; resolve rc=" + resolve2.exitCode()
                    + " HEAD=" + where + " editKept=" + editKept
                    + "; setup resolvedAtA=" + setupResolvedAtA + " ahead=" + setupAhead
                    + " edited=" + setupEdited;
            NodeResult result = setup && editKept && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(resolve1).process(ahead).process(resolve2)
                    .assertion("setup_resolved_at_pin_A", setupResolvedAtA)
                    .assertion("setup_unit_moved_ahead_to_B", setupAhead)
                    .assertion("setup_local_edit_written", setupEdited)
                    .assertion("control_local_edit_never_lost", editKept)
                    .assertion("shape_resolve_restores_pin_keeping_edit_or_refuses_by_name", shape)
                    .assertion("shape_no_false_convergence", !falseConvergence)
                    .log(detail);
        });
    }
}
