///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OUN-1, end to end: a skill contained in an installed plugin resolves when a
 * markdown {@code skill-imports} names it.
 *
 * <h2>Two senses of "addressable", and this graph now asserts both</h2>
 *
 * <p>{@code plugin.contained.skill.not.addressable} — the node next door —
 * asserts that {@code install hello-impl --registry} FAILS: a contained skill
 * has no independent identity as a registry unit. That is still true and this
 * node does not contradict it.
 *
 * <p>What changed is a different question with a confusingly similar name.
 * Until OUN-1, an import naming a contained skill was reported as a MISSING
 * UNIT while the skill sat on disk in the same home — skt has carried
 * {@code unit-authoring} since it shipped and nothing could import it. Those
 * two properties were one word apart and only one of them was covered, which
 * is how the gap survived.
 *
 * <p>So: <b>not installable as a top-level unit, and resolvable by name from
 * an import.</b> Both, deliberately.
 *
 * <h2>The controls</h2>
 *
 * <ul>
 *   <li><b>a name no plugin contains still fails</b> — otherwise "the import
 *       validated" is also what a validator that stopped checking would
 *       produce.</li>
 *   <li><b>the path inside the contained skill is still checked</b> — the
 *       branch resolves a unit, it does not wave the file through.</li>
 * </ul>
 */
public class PluginContainedSkillResolvesByName {

    static final NodeSpec SPEC = NodeSpec.of("plugin.contained.skill.resolves.by.name")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.plugin.installed")
            .tags("plugin", "resolve", "oun-1")
            .timeout("300s");

    private static final String CONTAINED = "hello-impl";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String home = ctx.get("env.prepared", "home").orElse(null);
        if (home == null) return NodeResult.fail(SPEC.id(), "UNPROVEN: no home in context");

        Path scratch = Files.createTempDirectory("plugin-contained-import-");

        ProcessRecord good = installImporter(ctx, home, scratch, "imports-contained",
                CONTAINED, "SKILL.md");
        boolean resolves = good.exitCode() == 0;

        ProcessRecord absent = installImporter(ctx, home, scratch, "imports-absent",
                "no-such-contained-skill", "SKILL.md");
        boolean absentStillFails = absent.exitCode() != 0;

        ProcessRecord badPath = installImporter(ctx, home, scratch, "imports-bad-path",
                CONTAINED, "definitely-not-here.md");
        boolean pathStillChecked = badPath.exitCode() != 0;

        boolean pass = resolves && absentStillFails && pathStillChecked;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "resolves=" + resolves + " (rc=" + good.exitCode() + ")"
                                + " absentStillFails=" + absentStillFails
                                + " (rc=" + absent.exitCode() + ")"
                                + " pathStillChecked=" + pathStillChecked
                                + " (rc=" + badPath.exitCode() + ")"))
                .process(good).process(absent).process(badPath)
                .assertion("an_import_naming_a_plugin_contained_skill_resolves", resolves)
                .assertion("CONTROL_a_name_no_plugin_contains_still_fails", absentStillFails)
                .assertion("CONTROL_a_missing_path_inside_it_is_still_a_violation",
                        pathStillChecked)
                .log("Before OUN-1 this import was reported as a MISSING UNIT while the "
                        + "skill sat on disk in the same home. The sibling node asserts the "
                        + "OTHER sense of addressable — not installable from the registry — "
                        + "and both hold.");
    }

    private static ProcessRecord installImporter(NodeContext ctx, String home, Path scratch,
                                                 String name, String unit, String path)
            throws IOException {
        Path dir = scratch.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Import probe for the contained-skill contract.
                skill-imports:
                  - unit: %s
                    path: %s
                    reason: Probes whether a plugin-contained skill resolves by name.
                ---

                # %s
                """.formatted(name, unit, path, name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "Import probe for the contained-skill contract."
                """.formatted(name));

        ProcessBuilder pb = new ProcessBuilder(
                SmEnv.cli().toString(), "install", dir.toString(), "--yes");
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, "contained-import-" + name, pb);
    }
}
