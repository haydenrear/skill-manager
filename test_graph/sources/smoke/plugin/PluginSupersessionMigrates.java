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

        // ---- and the OTHER route a home takes: its own sync ------------
        // The path every project home takes on its own, and the one OUN-5
        // declared (test_graph key "home-sync") without exercising. It has no
        // collision gate to be refused by, which is what made its failure the
        // quiet one: both copies exist and the home keeps running the copy the
        // upgrade meant to replace.
        //
        // IN A HOME OF ITS OWN, and built the way a real home reaches this
        // state rather than by planting files. The first attempt wrote the
        // standalone's tree and installed/ record straight onto disk, which
        // produced a unit with a record and no units.lock entry — a shape the
        // product never creates — and left the SHARED fixture home holding an
        // installed/ record for a tree that was then retired.
        // home.membership.law caught it as "LOST [skill-manager] — a unit
        // nobody removed", which is precisely its job.
        //
        // The real chronology, reproduced: the standalone is installed while
        // nothing carries the name; the carrier arrives WITHOUT it, so nothing
        // is due; then the carrier gains the skill the way a git pull of skt
        // delivers it — and only then is the home in the two-copies state that
        // sync has to notice.
        Path syncHome = Files.createTempDirectory("supersession-sync-").resolve("home");
        Files.createDirectories(syncHome);
        String syncHomeStr = syncHome.toString();

        ProcessRecord seedStandalone = install(ctx, syncHomeStr,
                skill(scratch, MOVED), "sync-seed-standalone");
        // A SCRATCH ROOT OF ITS OWN. plugin() builds at <root>/<pluginName>,
        // and the install half already built a carrier of this name carrying
        // skill-manager at scratch/skt. Reusing the root left both contained
        // skills in one tree, so the "carrier without the skill" carried it
        // after all and the migration fired during the seed — which the
        // twoCopiesAgain control caught, exactly as a control should.
        Path plainScratch = Files.createTempDirectory("supersession-plain-");
        ProcessRecord seedCarrier = install(ctx, syncHomeStr,
                plugin(plainScratch, CARRIER, "unrelated-skill"), "sync-seed-carrier");
        boolean carrierArrivedWithoutTheSkill =
                !Files.exists(syncHome.resolve("plugins/" + CARRIER + "/skills/" + MOVED));

        // What the carrier's own upgrade delivers: a contained skill of the
        // name the standalone already holds.
        Path carried = syncHome.resolve("plugins/" + CARRIER + "/skills/" + MOVED);
        Files.createDirectories(carried);
        Files.writeString(carried.resolve("SKILL.md"),
                "---\nname: " + MOVED + "\ndescription: carried\n---\n\nbody\n");
        Files.writeString(carried.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + MOVED + "\"\nversion = \"0.0.1\"\n"
                        + "description = \"carried\"\n");

        Path standaloneCopy = syncHome.resolve("skills/" + MOVED);
        boolean twoCopiesAgain = seedStandalone.exitCode() == 0 && seedCarrier.exitCode() == 0
                && carrierArrivedWithoutTheSkill
                && Files.isDirectory(standaloneCopy) && Files.isDirectory(carried);

        ProcessRecord synced = sm(ctx, syncHomeStr, "sync-retired-unit", "sync", MOVED);
        boolean syncRetiredIt = !Files.exists(standaloneCopy)
                // The RECORD goes with the tree. An installed/ entry naming a
                // tree the home does not hold is a LOST unit, and a migration
                // that leaves one has not migrated the home.
                && !Files.exists(syncHome.resolve("installed/" + MOVED + ".json"))
                && synced.exitCode() == 0;

        boolean pass = upgradeSucceeded && carrierLanded && standaloneRetired
                && nameStillResolves && obsoleteRetired && saidWhatItRetired
                && bystanderSurvived && ordinaryCollisionStillRefused
                && twoCopiesAgain && syncRetiredIt;

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
                                + ordinaryCollisionStillRefused
                                + " twoCopiesAgain=" + twoCopiesAgain
                                + " carrierArrivedWithoutTheSkill="
                                + carrierArrivedWithoutTheSkill
                                + " syncRetiredIt=" + syncRetiredIt
                                + " (syncExit=" + synced.exitCode() + ")"))
                .process(seedMoved).process(seedObsolete).process(seedBystander)
                .process(upgrade).process(refused).process(synced)
                .assertion("an_old_shape_home_takes_the_upgrade_that_would_have_collided",
                        upgradeSucceeded && carrierLanded)
                .assertion("the_superseded_standalone_is_retired", standaloneRetired)
                .assertion("and_the_name_still_resolves_to_the_carriers_copy", nameStillResolves)
                .assertion("the_unit_deleted_upstream_is_retired_too", obsoleteRetired)
                .assertion("the_upgrade_SAYS_what_it_retired", saidWhatItRetired)
                .assertion("CONTROL_a_unit_the_table_does_not_name_survives", bystanderSurvived)
                .assertion("CONTROL_an_ordinary_collision_is_still_refused_afterwards",
                        ordinaryCollisionStillRefused)
                .assertion("a_sync_NAMING_THE_RETIRED_UNIT_performs_the_retirement",
                        syncRetiredIt)
                .assertion("CONTROL_the_second_home_really_did_hold_two_copies_of_the_name",
                        twoCopiesAgain)
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
        return sm(ctx, home, label, "install", unit.toString(), "--yes");
    }

    private static ProcessRecord sm(NodeContext ctx, String home, String label, String... args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(SmEnv.cli().toString());
        command.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
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
