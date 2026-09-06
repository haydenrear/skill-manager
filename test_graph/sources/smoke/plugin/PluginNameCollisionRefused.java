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

        boolean rejected = refused.exitCode() != 0;
        boolean pluginAbsent = !Files.isDirectory(home.resolve("plugins/colliding-plugin"));
        boolean victimIntact = Files.isDirectory(home.resolve("skills/" + CLAIMED));
        boolean namesBothClaimants = out.contains("colliding-plugin")
                && out.contains(home.resolve("skills/" + CLAIMED).toString());
        boolean namesBothRemedies = out.contains("skill-manager remove " + CLAIMED)
                && out.contains("rename the skill inside the plugin");

        // THE CONTROL. A plugin whose contained skill has the PLUGIN's own
        // name is one unit under one name, and must install.
        ProcessRecord entrySkill = install(ctx, homeStr,
                plugin(scratch, "twin-plugin", "twin-plugin"), "install-entry-skill-plugin");
        boolean entrySkillAllowed = entrySkill.exitCode() == 0
                && Files.isDirectory(home.resolve("plugins/twin-plugin"));

        boolean pass = rejected && pluginAbsent && victimIntact
                && namesBothClaimants && namesBothRemedies && entrySkillAllowed;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "rejected=" + rejected + " (rc=" + refused.exitCode() + ")"
                                + " pluginAbsent=" + pluginAbsent
                                + " victimIntact=" + victimIntact
                                + " namesBothClaimants=" + namesBothClaimants
                                + " namesBothRemedies=" + namesBothRemedies
                                + " entrySkillAllowed=" + entrySkillAllowed
                                + " (rc=" + entrySkill.exitCode() + ")"))
                .process(seed).process(refused).process(entrySkill)
                .assertion("a_plugin_claiming_an_installed_name_is_refused", rejected)
                .assertion("nothing_is_committed_and_the_existing_unit_is_untouched",
                        pluginAbsent && victimIntact)
                .assertion("the_refusal_names_both_claimants", namesBothClaimants)
                .assertion("and_both_ways_out", namesBothRemedies)
                .assertion("CONTROL_a_plugins_own_entry_skill_still_installs", entrySkillAllowed)
                .log("--yes is passed on every install here, so this also shows the refusal "
                        + "is not a policy prompt that confirmation can answer.");
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
