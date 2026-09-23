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
 * respect to the plugin that carries it now, so <b>every existing home
 * was stuck</b>: the gate refuses the operation that would clear the
 * collision.
 *
 * <p>SI-18 renamed that carrier. It was {@code skt}, a plugin of its own; skt
 * is a contained skill of {@code tla-spec-dev} now, and every row of the table
 * moved with it — a row only fires when its carrier is the unit ARRIVING, and
 * skt never arrives any more. {@link #CARRIER} is the one place that name is
 * spelled here, so the rename is one edit and the behaviour under test is
 * unchanged.
 *
 * <p>The retirement runs before the gate, in the same operation, and removes
 * only what {@code UnitSupersession.TABLE} names. Afterwards there is
 * genuinely one claimant, which is what the gate is checking for.
 *
 * <h2>Why the controls are more of this node than the assertion</h2>
 *
 * <p>"The install succeeded" is what a DELETED gate produces too, and that is
 * the outcome the ticket's own constraint forbids: a migration that works by
 * disabling the guard has produced the state the guard exists to prevent. So a
 * unit the table does not name must survive a carrier that claims its name.
 *
 * <h2>OUN-13 changed one control's observable, not its subject</h2>
 *
 * <p>The second control used to be "an ordinary collision is still REFUSED
 * afterwards". Nothing refuses that now: a contained skill is addressed
 * {@code plugin:skill}, so a standalone {@code x} and a contained {@code p:x}
 * are two names and there is no collision to refuse. The refusal is no longer
 * available as evidence.
 *
 * <p>What the control was ever FOR is that the migration did not hand later
 * installs a licence to evict, and that is now asserted directly: the next
 * plugin installs, and the unrelated unit whose name it shares is untouched.
 */
public class PluginSupersessionMigrates {

    static final NodeSpec SPEC = NodeSpec.of("plugin.supersession.migrates")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("plugin.name.collision.refused")
            .tags("plugin", "migration", "oun-5")
            .timeout("300s");

    /** The rows of the table, spelled as the product spells them. */
    private static final String CARRIER = "tla-spec-dev";
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

        // CONTROL 2. THE ONE THAT MATTERS, with its observable changed by
        // OUN-13 and its SUBJECT intact.
        //
        // It used to read: the very next plugin claiming an unrelated
        // installed name is still REFUSED, proving the migration satisfied
        // the gate rather than switching it off. Nothing refuses that now —
        // the standalone is `x` and the contained one is `p:x`, two names —
        // so the refusal is no longer available as evidence.
        //
        // What the control is actually for is that the migration did not hand
        // later installs a licence to evict, and that is asserted directly:
        // the next plugin installs, and the unrelated unit whose name it
        // shares is UNTOUCHED.
        ProcessRecord refused = install(ctx, homeStr,
                plugin(scratch, "colliding-after-migration", BYSTANDER), "collision-after");
        boolean ordinaryCollisionStillRefused = refused.exitCode() == 0
                && Files.isDirectory(home.resolve("plugins/colliding-after-migration"))
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
        // is due; then the carrier gains the skill the way a git pull of the carrier
        // delivers it — and only then is the home in the two-copies state that
        // sync has to notice.
        Path syncHome = Files.createTempDirectory("supersession-sync-").resolve("home");
        Files.createDirectories(syncHome);
        String syncHomeStr = syncHome.toString();

        ProcessRecord seedStandalone = install(ctx, syncHomeStr,
                skill(scratch, MOVED), "sync-seed-standalone");
        // A SCRATCH ROOT OF ITS OWN. plugin() builds at <root>/<pluginName>,
        // and the install half already built a carrier of this name carrying
        // skill-manager at scratch/<carrier>. Reusing the root left both contained
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

        // ---- 0.27.1: the sync cloned the retired unit straight back ------
        // A unit written before the move still references the old repository
        // (git-epic-workflow 0.4.0 in every project home). Installing it walks
        // that reference, and the next sync's unmet-reference pass reads the
        // retired unit as missing. Both must treat the carrier as serving it.
        Path consumer = Files.createTempDirectory("supersession-consumer-").resolve("older-consumer");
        Files.createDirectories(consumer);
        Files.writeString(consumer.resolve("SKILL.md"),
                "---\nname: older-consumer\ndescription: still names the old repository\n---\n\nbody\n");
        Files.writeString(consumer.resolve("skill-manager.toml"), """
                skill_references = ["github:haydenrear/skill-manager-skill"]

                [skill]
                name = "older-consumer"
                version = "0.0.1"
                description = "still names the old repository"
                """);
        ProcessRecord consumerInstalled = install(ctx, syncHomeStr, consumer, "install-stale-consumer");
        boolean installDidNotReinstall = consumerInstalled.exitCode() == 0 && !Files.exists(standaloneCopy);
        ProcessRecord resynced = sm(ctx, syncHomeStr, "resync-with-stale-consumer", "sync", MOVED);
        boolean syncDidNotCloneItBack = resynced.exitCode() == 0 && !Files.exists(standaloneCopy)
                && !Files.exists(syncHome.resolve("installed/" + MOVED + ".json"));
        // Leave nothing behind: the consumer's agent links live in the SHARED
        // fixture agent home, and pointing into this scratch home they fail
        // the fixpoint law as FOREIGN_HOME (measured, 2026-09-13).
        ProcessRecord consumerRemoved = sm(ctx, syncHomeStr, "uninstall-stale-consumer",
                "uninstall", "older-consumer", "--yes");

        boolean pass = installDidNotReinstall && syncDidNotCloneItBack
                && upgradeSucceeded && carrierLanded && standaloneRetired
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
                .process(consumerInstalled).process(resynced).process(consumerRemoved)
                .assertion("an_old_shape_home_takes_the_upgrade_that_would_have_collided",
                        upgradeSucceeded && carrierLanded)
                .assertion("the_superseded_standalone_is_retired", standaloneRetired)
                .assertion("and_the_name_still_resolves_to_the_carriers_copy", nameStillResolves)
                .assertion("the_unit_deleted_upstream_is_retired_too", obsoleteRetired)
                .assertion("the_upgrade_SAYS_what_it_retired", saidWhatItRetired)
                .assertion("CONTROL_a_unit_the_table_does_not_name_survives", bystanderSurvived)
                .assertion("CONTROL_a_later_plugin_installs_and_evicts_NOTHING",
                        ordinaryCollisionStillRefused)
                .assertion("a_sync_NAMING_THE_RETIRED_UNIT_performs_the_retirement",
                        syncRetiredIt)
                .assertion("CONTROL_the_second_home_really_did_hold_two_copies_of_the_name",
                        twoCopiesAgain)
                .assertion("a_unit_still_naming_the_old_repository_installs_without_reinstalling_it",
                        installDidNotReinstall)
                .assertion("and_the_next_sync_does_not_clone_the_retired_unit_back",
                        syncDidNotCloneItBack)
                .log("The retirement removes only what UnitSupersession.TABLE names. The "
                        + "controls carry this node: a unit the table does not name survives a "
                        + "carrier claiming it, and a later plugin sharing that name installs "
                        + "WITHOUT evicting anything — which is what the refusal used to stand "
                        + "in for before OUN-13 made `x` and `p:x` two names.");
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
