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
 * OUN-5: the upgrade that fixes an old-shape home is the upgrade that home
 * would have refused.
 *
 * <h2>The bind</h2>
 *
 * <p>OUN-1 made a plugin's contained skills addressable by name. OUN-2 then
 * made a plugin whose contained name is already claimed a hard refusal, with
 * no flag past it — deliberately, because two copies of one name is an
 * ambiguity the resolver would otherwise settle by directory order. A home
 * holding the standalone {@code skill-manager} is in exactly that state with
 * respect to the {@code skt} that carries it now, so <b>every existing home
 * was stuck</b>: the gate refuses the operation that would clear the
 * collision.
 *
 * <p>The retirement runs before the gate, in the same operation, and removes
 * only what {@code UnitSupersession.TABLE} names. Afterwards there is
 * genuinely one claimant, which is what the gate is checking for.
 *
 * <h2>Why the controls are more of this node than the assertion</h2>
 *
 * <p>"The install succeeded" is what a DELETED gate produces too, and that is
 * the outcome the ticket's own constraint forbids: a migration that works by
 * disabling the guard has produced the state the guard exists to prevent. So
 * the same home, in the same run, must still refuse an ordinary collision
 * afterwards — and a unit the table does not name must survive a carrier that
 * claims its name.
 */
public class PluginSupersessionMigrates {

    static final NodeSpec SPEC = NodeSpec.of("plugin.supersession.migrates")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("plugin.name.collision.refused")
            .tags("plugin", "migration", "oun-5")
            .timeout("300s");

    /** The two rows of the table, spelled as the product spells them. */
    private static final String CARRIER = "skt";
    private static final String MOVED = "skill-manager";
    private static final String OBSOLETE = "skill-dev-skill";
    /** A unit the table does not name, used as the negative control. */
    private static final String BYSTANDER = "bystander-tool";

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
        Path scratch = Files.createTempDirectory("supersession-");

        // The old shape: both units this version retires, standalone.
        ProcessRecord seedMoved = install(ctx, homeStr, skill(scratch, MOVED), "seed-standalone");
        ProcessRecord seedObsolete =
                install(ctx, homeStr, skill(scratch, OBSOLETE), "seed-obsolete");
        ProcessRecord seedBystander =
                install(ctx, homeStr, skill(scratch, BYSTANDER), "seed-bystander");
        if (seedMoved.exitCode() != 0 || seedObsolete.exitCode() != 0
                || seedBystander.exitCode() != 0) {
            return NodeResult.fail(SPEC.id(), "UNPROVEN: could not build the old-shape home")
                    .process(seedMoved).process(seedObsolete).process(seedBystander);
        }

        // The upgrade. Before OUN-5 this was exit 3.
        ProcessRecord upgrade = install(ctx, homeStr,
                plugin(scratch, CARRIER, MOVED), "install-carrier");
        String out = readLog(ctx.reportDir(), upgrade);

        boolean upgradeSucceeded = upgrade.exitCode() == 0;
        boolean carrierLanded = Files.isDirectory(home.resolve("plugins/" + CARRIER));
        boolean standaloneRetired = !Files.exists(home.resolve("skills/" + MOVED));
        boolean nameStillResolves = Files.isDirectory(
                home.resolve("plugins/" + CARRIER + "/skills/" + MOVED));
        boolean obsoleteRetired = !Files.exists(home.resolve("skills/" + OBSOLETE));
        boolean saidWhatItRetired = out.contains(MOVED) && out.contains("retired");

        // CONTROL 1. The bystander is not in the table, so nothing retired it.
        boolean bystanderSurvived = Files.isDirectory(home.resolve("skills/" + BYSTANDER));

        // CONTROL 2. THE ONE THAT MATTERS. The gate is satisfied by the
        // migration, not switched off by it: the very next plugin claiming an
        // unrelated installed name is still refused, in this same home.
        ProcessRecord refused = install(ctx, homeStr,
                plugin(scratch, "colliding-after-migration", BYSTANDER), "collision-after");
        boolean ordinaryCollisionStillRefused = refused.exitCode() != 0
                && !Files.isDirectory(home.resolve("plugins/colliding-after-migration"))
                && Files.isDirectory(home.resolve("skills/" + BYSTANDER));

        boolean pass = upgradeSucceeded && carrierLanded && standaloneRetired
                && nameStillResolves && obsoleteRetired && saidWhatItRetired
                && bystanderSurvived && ordinaryCollisionStillRefused;

        return (pass ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "upgradeSucceeded=" + upgradeSucceeded + " (rc=" + upgrade.exitCode() + ")"
                                + " carrierLanded=" + carrierLanded
                                + " standaloneRetired=" + standaloneRetired
                                + " nameStillResolves=" + nameStillResolves
                                + " obsoleteRetired=" + obsoleteRetired
                                + " saidWhatItRetired=" + saidWhatItRetired
                                + " bystanderSurvived=" + bystanderSurvived
                                + " ordinaryCollisionStillRefused="
                                + ordinaryCollisionStillRefused))
                .process(seedMoved).process(seedObsolete).process(seedBystander)
                .process(upgrade).process(refused)
                .assertion("an_old_shape_home_takes_the_upgrade_that_would_have_collided",
                        upgradeSucceeded && carrierLanded)
                .assertion("the_superseded_standalone_is_retired", standaloneRetired)
                .assertion("and_the_name_still_resolves_to_the_carriers_copy", nameStillResolves)
                .assertion("the_unit_deleted_upstream_is_retired_too", obsoleteRetired)
                .assertion("the_upgrade_SAYS_what_it_retired", saidWhatItRetired)
                .assertion("CONTROL_a_unit_the_table_does_not_name_survives", bystanderSurvived)
                .assertion("CONTROL_an_ordinary_collision_is_still_refused_afterwards",
                        ordinaryCollisionStillRefused)
                .log("The gate is SATISFIED, not weakened: after the retirement there is "
                        + "genuinely one claimant, which is the property the gate checks.");
    }

    // ------------------------------------------------------------- fixtures

    private static Path skill(Path root, String name) throws IOException {
        Path dir = root.resolve("standalone-" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: migration fixture\n---\n\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "migration fixture"
                """.formatted(name));
        return dir;
    }

    private static Path plugin(Path root, String pluginName, String contained) throws IOException {
        Path dir = root.resolve(pluginName);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + pluginName + "\",\"version\":\"0.0.1\","
                        + "\"description\":\"migration fixture\"}\n");
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.0.1"
                description = "migration fixture"
                """.formatted(pluginName));
        Path skill = dir.resolve("skills").resolve(contained);
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: " + contained + "\ndescription: contained fixture\n---\n\nbody\n");
        Files.writeString(skill.resolve("skill-manager.toml"), """
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
