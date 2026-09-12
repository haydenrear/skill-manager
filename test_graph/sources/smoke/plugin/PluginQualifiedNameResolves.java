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
 * OUN-13, on a real home: a contained skill is addressed {@code plugin:skill},
 * and the bare name is a convenience rather than the resolution strategy.
 *
 * <h2>Why this needs a real home and not a unit test</h2>
 *
 * <p>The unit tests build a store and ask {@code qualifiedSkillDir} directly.
 * That proves the lookup. It does not prove that an INSTALL of a skill whose
 * markdown names {@code plugin:skill} validates — which is the thing an author
 * actually does, and the path where OUN-1's predecessor was reported as a
 * missing unit for as long as skt had shipped {@code unit-authoring}.
 *
 * <h2>The permutation that matters</h2>
 *
 * <p>A standalone {@code hello-impl} is installed BESIDE the plugin that
 * contains one. Under OUN-2 that install was refused outright; under OUN-13 it
 * is legal, and the two are then addressed separately. So this asserts both
 * halves in the same home at the same time:
 *
 * <ul>
 *   <li>{@code hello-plugin:hello-impl} resolves to the copy inside the
 *       plugin;</li>
 *   <li>{@code hello-impl}, bare, resolves to the standalone.</li>
 * </ul>
 *
 * <p>Neither shadows the other, which is the whole claim.
 *
 * <h2>The controls</h2>
 *
 * <ul>
 *   <li><b>a wrong plugin half fails.</b> Otherwise "it resolved" is also what
 *       a validator that ignored the qualifier would produce — and the
 *       qualifier would be decoration.</li>
 *   <li><b>the path inside the qualified target is still checked.</b> The
 *       branch resolves a unit; it does not wave the file through.</li>
 *   <li><b>an escape attempt fails.</b> {@code hello-plugin:../../../etc} must
 *       not resolve to anything: the name is a name, not a path.</li>
 * </ul>
 */
public class PluginQualifiedNameResolves {

    static final NodeSpec SPEC = NodeSpec.of("plugin.qualified.name.resolves")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.plugin.installed")
            .tags("plugin", "resolve", "oun-13")
            .timeout("600s");

    private static final String PLUGIN = "hello-plugin";
    private static final String CONTAINED = "hello-impl";
    private static final String QUALIFIED = PLUGIN + ":" + CONTAINED;

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
        Path scratch = Files.createTempDirectory("plugin-qualified-import-");

        // 1. FROM OUTSIDE THE PLUGIN, by qualified name.
        ProcessRecord qualified = installImporter(ctx, home, scratch,
                "imports-qualified", QUALIFIED, "SKILL.md");
        boolean qualifiedResolves = qualified.exitCode() == 0;

        // 2. A ROOT SKILL OF THE SAME NAME, installed beside it. Refused under
        //    OUN-2; legal under OUN-13, because the two have different names.
        ProcessRecord standalone = installStandalone(ctx, home, scratch, CONTAINED);
        boolean standaloneInstalls = standalone.exitCode() == 0;

        // 3. ...and with BOTH present, each name still means what it should.
        ProcessRecord qualifiedStill = installImporter(ctx, home, scratch,
                "imports-qualified-with-twin", QUALIFIED, "SKILL.md");
        boolean qualifiedStillResolves = qualifiedStill.exitCode() == 0;

        ProcessRecord bare = installImporter(ctx, home, scratch,
                "imports-bare-with-twin", CONTAINED, "SKILL.md");
        boolean bareStillResolves = bare.exitCode() == 0;

        // 4. CONTROL: a wrong plugin half must not resolve.
        ProcessRecord wrongPlugin = installImporter(ctx, home, scratch,
                "imports-wrong-plugin", "no-such-plugin:" + CONTAINED, "SKILL.md");
        boolean wrongPluginFails = wrongPlugin.exitCode() != 0;

        // 5. CONTROL: the path inside the qualified target is still checked.
        ProcessRecord badPath = installImporter(ctx, home, scratch,
                "imports-qualified-bad-path", QUALIFIED, "definitely-not-here.md");
        boolean pathStillChecked = badPath.exitCode() != 0;

        // 6. CONTROL: the name is a name, not a path.
        ProcessRecord escape = installImporter(ctx, home, scratch,
                "imports-escape", PLUGIN + ":../../../etc", "SKILL.md");
        boolean escapeRefused = escape.exitCode() != 0;

        boolean pass = qualifiedResolves && standaloneInstalls && qualifiedStillResolves
                && bareStillResolves && wrongPluginFails && pathStillChecked && escapeRefused;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "qualifiedResolves=" + qualifiedResolves
                                + " (rc=" + qualified.exitCode() + ")"
                                + " standaloneInstalls=" + standaloneInstalls
                                + " (rc=" + standalone.exitCode() + ")"
                                + " qualifiedStillResolves=" + qualifiedStillResolves
                                + " (rc=" + qualifiedStill.exitCode() + ")"
                                + " bareStillResolves=" + bareStillResolves
                                + " (rc=" + bare.exitCode() + ")"
                                + " wrongPluginFails=" + wrongPluginFails
                                + " (rc=" + wrongPlugin.exitCode() + ")"
                                + " pathStillChecked=" + pathStillChecked
                                + " (rc=" + badPath.exitCode() + ")"
                                + " escapeRefused=" + escapeRefused
                                + " (rc=" + escape.exitCode() + ")"))
                .process(qualified).process(standalone).process(qualifiedStill)
                .process(bare).process(wrongPlugin).process(badPath).process(escape)
                .assertion("a_qualified_name_resolves_from_outside_the_plugin", qualifiedResolves)
                .assertion("a_root_skill_of_the_same_name_installs_beside_it", standaloneInstalls)
                .assertion("the_qualified_name_still_reaches_the_contained_copy",
                        qualifiedStillResolves)
                .assertion("and_the_bare_name_still_reaches_the_standalone", bareStillResolves)
                .assertion("CONTROL_a_wrong_plugin_half_does_not_resolve", wrongPluginFails)
                .assertion("CONTROL_a_missing_path_inside_it_is_still_a_violation",
                        pathStillChecked)
                .assertion("CONTROL_the_name_is_a_name_not_a_path", escapeRefused)
                .log("Under OUN-2 assertion 2 was a REFUSAL: a plugin whose contained skill "
                        + "name was already claimed could not install. OUN-13 removed the "
                        + "premise — `" + CONTAINED + "` and `" + QUALIFIED + "` are two "
                        + "names, so neither shadows the other and both resolve in the same "
                        + "home. That is what assertions 3 and 4 hold at once.");
    }

    /** A skill whose markdown imports {@code unit} at {@code path}. */
    private static ProcessRecord installImporter(NodeContext ctx, String home, Path scratch,
                                                 String name, String unit, String path)
            throws IOException {
        Path dir = scratch.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Qualified-import probe for the contained-skill contract.
                skill-imports:
                  - unit: %s
                    path: %s
                    reason: Probes whether `plugin:skill` resolves.
                ---

                # %s
                """.formatted(name, unit, path, name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "Qualified-import probe for the contained-skill contract."
                """.formatted(name));
        return install(ctx, home, "qualified-import-" + name, dir);
    }

    /** A standalone skill deliberately taking the contained skill's name. */
    private static ProcessRecord installStandalone(NodeContext ctx, String home, Path scratch,
                                                   String name) throws IOException {
        Path dir = scratch.resolve("standalone-" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: A standalone unit deliberately sharing a contained skill's name.
                ---

                # %s

                Installed to prove `%s` and `%s` are two names rather than one
                name with two answers.
                """.formatted(name, name, name, QUALIFIED));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "A standalone unit deliberately sharing a contained skill's name."
                """.formatted(name));
        return install(ctx, home, "standalone-" + name, dir);
    }

    private static ProcessRecord install(NodeContext ctx, String home, String label, Path dir) {
        ProcessBuilder pb = new ProcessBuilder(
                SmEnv.cli().toString(), "install", dir.toString(), "--yes");
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, label, pb);
    }
}
