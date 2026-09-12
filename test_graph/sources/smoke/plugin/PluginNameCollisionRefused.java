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
 * OUN-2, end to end: one name, one copy.
 *
 * <h2>Why a graph node and not only the unit cases</h2>
 *
 * <p>{@code ContainedNameCollisionIsRefusedTest} drives the install program
 * directly. What it cannot show is the operator's experience: a real
 * {@code install} against a real home that has units in it, ending with the
 * plugin NOT on disk and a message that names both claimants. The gate's whole
 * value is in that message — an operator who cannot see both paths cannot
 * choose between them.
 *
 * <h2>The control is the important half</h2>
 *
 * <p>"The install failed" is satisfied by a great many broken worlds. So this
 * node also installs a plugin whose contained skill carries <em>the plugin's
 * own name</em> — the shape {@code skt} is in, in 21 of this machine's homes —
 * and requires it to SUCCEED. A gate without that exception refuses skt
 * everywhere it is installed, including during its own migration, and a node
 * that only checked the refusal would have called that correct.
 */
public class PluginNameCollisionRefused {

    static final NodeSpec SPEC = NodeSpec.of("plugin.name.collision.refused")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.plugin.installed")
            .tags("plugin", "install", "oun-2")
            .timeout("300s");

    private static final String CLAIMED = "collision-victim";

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
        String homeStr = ctx.get("env.prepared", "home").orElse(null);
        if (homeStr == null) return NodeResult.fail(SPEC.id(), "UNPROVEN: no home in context");
        Path home = Path.of(homeStr);
        Path scratch = Files.createTempDirectory("plugin-collision-");

        // The standalone unit that will own the name first.
        ProcessRecord seed = install(ctx, homeStr, skill(scratch, CLAIMED), "seed-standalone");
        if (seed.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(),
                    "UNPROVEN: could not install the unit that claims the name first, rc="
                            + seed.exitCode()).process(seed);
        }

        // The collision: a plugin carrying a contained skill of that name.
        Path colliding = plugin(scratch, "colliding-plugin", CLAIMED);
        ProcessRecord refused = install(ctx, homeStr, colliding, "install-colliding-plugin");
        String out = readLog(ctx.reportDir(), refused);

        // OUN-13 INVERTED THE HEADLINE ASSERTION AND KEPT EVERY OTHER ONE.
        //
        // OUN-2 refused this install because it believed the name would then
        // have two answers. It does not: the standalone is `hello-impl` and
        // the contained one is `colliding-plugin:hello-impl`. So the install
        // now SUCCEEDS, and what must still hold is that the unit already
        // holding the name is untouched — which was always the property worth
        // protecting, and is the one a wrong implementation would break.
        boolean accepted = refused.exitCode() == 0;
        boolean pluginPresent = Files.isDirectory(home.resolve("plugins/colliding-plugin"));
        boolean victimIntact = Files.isDirectory(home.resolve("skills/" + CLAIMED));
        // It is legal and it is still worth SAYING, because one case hiding in
        // it is wrong: the same unit present twice. The notice names the
        // qualified form, which is the answer to the question an operator is
        // about to ask, and the remedy for the case that IS wrong.
        boolean namesBothClaimants = out.contains("colliding-plugin")
                && out.contains(home.resolve("skills/" + CLAIMED).toString());
        boolean namesTheQualifiedForm = out.contains("colliding-plugin:" + CLAIMED);
        boolean namesTheRemedy = out.contains("skill-manager remove " + CLAIMED);

        // THE CONTROL. A plugin whose contained skill has the PLUGIN's own
        // name is one unit under one name, and must install.
        ProcessRecord entrySkill = install(ctx, homeStr,
                plugin(scratch, "twin-plugin", "twin-plugin"), "install-entry-skill-plugin");
        boolean entrySkillAllowed = entrySkill.exitCode() == 0
                && Files.isDirectory(home.resolve("plugins/twin-plugin"));

        boolean pass = accepted && pluginPresent && victimIntact
                && namesBothClaimants && namesTheQualifiedForm && namesTheRemedy
                && entrySkillAllowed;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "accepted=" + accepted + " (rc=" + refused.exitCode() + ")"
                                + " pluginPresent=" + pluginPresent
                                + " victimIntact=" + victimIntact
                                + " namesBothClaimants=" + namesBothClaimants
                                + " namesTheQualifiedForm=" + namesTheQualifiedForm
                                + " namesTheRemedy=" + namesTheRemedy
                                + " entrySkillAllowed=" + entrySkillAllowed
                                + " (rc=" + entrySkill.exitCode() + ")"))
                .process(seed).process(refused).process(entrySkill)
                .assertion("a_plugin_sharing_a_name_with_a_standalone_unit_installs", accepted)
                .assertion("and_the_unit_already_holding_the_name_is_UNTOUCHED",
                        pluginPresent && victimIntact)
                .assertion("the_notice_names_both", namesBothClaimants)
                .assertion("and_the_qualified_form_that_separates_them", namesTheQualifiedForm)
                .assertion("and_the_remedy_for_the_case_that_IS_wrong", namesTheRemedy)
                .assertion("CONTROL_a_plugins_own_entry_skill_still_installs", entrySkillAllowed)
                .log("OUN-2 refused this install; OUN-13 does not, because the premise went "
                        + "away — `" + CLAIMED + "` and `colliding-plugin:" + CLAIMED + "` are "
                        + "two names. What survived the change is the property worth having: "
                        + "the unit already holding the name is untouched either way.");
    }

    // ------------------------------------------------------------- fixtures

    private static Path skill(Path root, String name) throws IOException {
        Path dir = root.resolve("standalone-" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: collision fixture\n---\n\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "collision fixture"
                """.formatted(name));
        return dir;
    }

    private static Path plugin(Path root, String pluginName, String contained) throws IOException {
        Path dir = root.resolve(pluginName);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + pluginName + "\",\"version\":\"0.0.1\","
                        + "\"description\":\"collision fixture\"}\n");
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.0.1"
                description = "collision fixture"
                """.formatted(pluginName));
        Path inner = dir.resolve("skills").resolve(contained);
        Files.createDirectories(inner);
        Files.writeString(inner.resolve("SKILL.md"),
                "---\nname: " + contained + "\ndescription: contained fixture\n---\n\nbody\n");
        Files.writeString(inner.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "contained fixture"
                """.formatted(contained));
        return dir;
    }

    private static ProcessRecord install(NodeContext ctx, String home, Path unit, String label) {
        ProcessBuilder pb = new ProcessBuilder(
                SmEnv.cli().toString(), "install", unit.toString(), "--yes");
        SmEnv.apply(ctx, pb, home);
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(Path reportDir, ProcessRecord rec) {
        try {
            if (rec.logPath() == null) return "";
            return Files.readString(reportDir.resolve(rec.logPath()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
