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
 * Pin shape (c), #407: a unit installed AHEAD of an older pin, with no local
 * edit, and {@code project resolve} must put it back on the pin or refuse by
 * name.
 *
 * <p>Plant: a local Git remote with A then B on {@code main}. A project's
 * {@code skill-project.toml} declares the unit at {@code revision = A}.
 * {@code project resolve} installs it at A. Then {@code sync <unit>
 * --git-latest} moves the installed unit to B — ahead of its pin.
 *
 * <p>HONEST: the next {@code project resolve} leaves HEAD == A and the units
 * lock's {@code resolved_sha} == A with exit 0, or exits non-zero naming the
 * unit. At 0.28.2 it prints "resolved project" with exit 0 and leaves B.
 *
 * <p>CONTROL: a resolve while the unit is AT its pin leaves it there.
 */
public class PinCAheadOfPin {
    static final String ID = "honest.pin-c";
    static final String UNIT = "hp-pin-c-unit";
    static final String PROJECT = "hp-pin-c-project";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "pin", "project-resolve", "407")
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
                root = HonestPinsKit.scratch(ctx, "pin-c");
                upstream = root.resolve("upstream");
                home = root.resolve("home");
                project = root.resolve("project");
                Files.createDirectories(home);
                Files.createDirectories(project);
                HonestPinsKit.writeSkill(upstream, UNIT, "0.1.0", "marker A");
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
            ProcessRecord resolve1 = HonestPinsKit.sm(ctx, "resolve_at_pin", root, home,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            boolean setupResolvedAtA = resolve1.exitCode() == 0 && a.equals(HonestPinsKit.head(unitDir));

            // CONTROL: at the pin, a resolve keeps it at the pin.
            ProcessRecord control = HonestPinsKit.sm(ctx, "resolve_again_at_pin", root, home,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            boolean controlStaysAtPin = control.exitCode() == 0 && a.equals(HonestPinsKit.head(unitDir));

            // Move it AHEAD of the pin, the way #407's home got there.
            ProcessRecord ahead = HonestPinsKit.sm(ctx, "sync_git_latest_ahead", root, home,
                    "sync", UNIT, "--git-latest", "--skip-mcp", "--yes");
            boolean setupAhead = ahead.exitCode() == 0 && b.equals(HonestPinsKit.head(unitDir));

            // SHAPE.
            ProcessRecord resolve2 = HonestPinsKit.sm(ctx, "resolve_while_ahead", root, home,
                    "project", "resolve", "--skip-gateway", "--project-dir", project.toString());
            String headAfter = HonestPinsKit.head(unitDir);
            String lockAfter = HonestPinsKit.lockField(home, UNIT, "resolved_sha");
            boolean honoured = resolve2.exitCode() == 0 && a.equals(headAfter) && a.equals(lockAfter);
            boolean refusedByName = resolve2.exitCode() != 0
                    && HonestPinsKit.output(ctx, resolve2).contains(UNIT);
            boolean falseConvergence = resolve2.exitCode() == 0 && !a.equals(headAfter);

            boolean setup = setupResolvedAtA && setupAhead;
            boolean shape = honoured || refusedByName;
            String where = headAfter.equals(a) ? "A (the pin)" : headAfter.equals(b) ? "B (ahead)" : headAfter;
            String detail = "A(pin)=" + a + " B=" + b + "; resolve rc=" + resolve2.exitCode()
                    + " HEAD=" + where + " units.lock resolved_sha=" + lockAfter
                    + "; control rc=" + control.exitCode() + " stays=" + controlStaysAtPin
                    + "; setup resolvedAtA=" + setupResolvedAtA + " ahead=" + setupAhead;
            NodeResult result = setup && controlStaysAtPin && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(resolve1).process(control).process(ahead).process(resolve2)
                    .assertion("setup_resolved_at_pin_A", setupResolvedAtA)
                    .assertion("setup_unit_moved_ahead_to_B", setupAhead)
                    .assertion("control_resolve_at_pin_keeps_pin", controlStaysAtPin)
                    .assertion("shape_resolve_restores_pin_or_refuses_by_name", shape)
                    .assertion("shape_no_false_convergence", !falseConvergence)
                    .log(detail);
        });
    }
}
