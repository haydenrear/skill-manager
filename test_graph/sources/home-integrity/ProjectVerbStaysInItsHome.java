///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * DEF-046 / DEF-047, reproduced and then refused: <b>a {@code project} verb run
 * from a working directory inside another repository does not touch that
 * repository's home.</b>
 *
 * <h2>The escape, as it actually happened</h2>
 *
 * <p>{@code project register|remove|resolve|sync} walk UP FROM THE WORKING
 * DIRECTORY to find {@code skill-project.toml}. Every home override in this
 * repository pins the HOME axis — {@code SKILL_MANAGER_HOME},
 * {@code CLAUDE_HOME}, {@code CLAUDE_CONFIG_DIR}, {@code CODEX_HOME},
 * {@code GEMINI_HOME} — and none pins CWD, which a JVM cannot change anyway.
 * So a driver that pinned five variables and asserted its sandbox drove
 * {@code project resolve} against the repository it happened to be standing
 * in, and re-realized that repository's worktree home:
 *
 * <pre>
 *   BEFORE: deploy-helm, spec-double-compiler, test-graph, tracing-observability
 *   AFTER:  skill-manager,  spec-double-compiler, test-graph, tracing-observability
 * </pre>
 *
 * <h2>What this node runs</h2>
 *
 * <ol>
 *   <li>Builds a {@code victim} checkout with its own child home and resolves
 *       it once, from OUTSIDE, with an explicit {@code --project-dir}. Its unit
 *       set is then known.</li>
 *   <li>Rewrites the victim's manifest to claim a DIFFERENT unit, so that a
 *       second resolve would necessarily change the victim home's membership.
 *       Without this step the claim would be untestable: an idempotent resolve
 *       leaves membership alone whether the guard works or not, and the check
 *       would pass for the wrong reason.</li>
 *   <li><b>THE CLAIM.</b> Runs {@code project resolve} with no
 *       {@code --project-dir}, {@code SKILL_MANAGER_HOME} naming the driver's
 *       own home, a confinement declared over the driver's root, and
 *       <b>{@code cwd} set to the victim checkout</b>. The verb must refuse,
 *       and the victim home's unit set must be exactly what it was.</li>
 *   <li><b>THE CONTROL.</b> The byte-identical run with the confinement
 *       removed. The victim home's unit set must CHANGE — which is the escape,
 *       reproduced, and the proof that step 3's assertion was live.</li>
 * </ol>
 *
 * <h2>Vacuity discipline</h2>
 *
 * <p>This epic's vacuity ledger records ten assertions that passed against
 * broken code, in three mechanisms. Each is answered here explicitly:
 *
 * <ul>
 *   <li><b>A — the probe reddens a precondition, not the claim.</b> The control
 *       must redden {@code the_other_homes_unit_set_is_unchanged}, by name, and
 *       the node reports which assertion the control moved. Establishing the
 *       fixture is a separate, separately-asserted step, and the control does
 *       not re-run it.</li>
 *   <li><b>B — the fixture cannot express the defect.</b> The victim home is
 *       asserted to exist and to hold the expected unit BEFORE the claim runs,
 *       and the manifest rewrite is asserted to have landed. HIS-14 found that
 *       neither of its "frozen" fixtures was frozen; a precondition that is not
 *       asserted is a hope.</li>
 *   <li><b>C — the mutation never reached the code.</b> The control is not
 *       "don't set the variable": it removes an inherited one through
 *       {@code SmEnv.unconfine}, and the node asserts the control's exit code
 *       DIFFERS from the claim's. A control that never reached the guard would
 *       exit 14 like the claim and is caught.</li>
 * </ul>
 *
 * <h2>Why a directory listing and not {@code home describe}</h2>
 *
 * <p>The reader here is deliberately the raw one: {@code <home>/skills}, listed.
 * That is precisely the observation the wave-5 close-out gate made when it
 * found DEF-047, and this node's job is to reproduce that finding. The
 * production-reader form of the same question — three readers, one answer, over
 * every home a graph produces — is {@code common/HomeMembershipLaw}.
 */
public final class ProjectVerbStaysInItsHome {

    static final NodeSpec SPEC = NodeSpec.of("project.verb.stays.in.its.home")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("home-integrity", "project", "confinement", "def-046", "def-047")
            .timeout("600s")
            .output("victimHome", "string")
            .output("driverHome", "string");

    /** {@code ConfinementEscapeException.EXIT_CODE}. */
    private static final int CONFINEMENT_ESCAPE = 14;

    private static final String UNIT_KEPT = "his16-unit-kept";
    private static final String UNIT_CLAIMED_LATER = "his16-unit-claimed-later";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail("project.verb.stays.in.its.home",
                        "missing env.prepared.home");
            }
            Path root = Path.of(envHome).resolve("his16-confinement");
            Path units = root.resolve("units");
            Path driverRoot = root.resolve("driver");
            Path driverHome = driverRoot.resolve("home");
            Path victim = root.resolve("victim");
            Path victimHome = victim.resolve(".skill-manager");

            Files.createDirectories(units);
            Files.createDirectories(driverHome);
            Files.createDirectories(victim);

            scaffold(units, UNIT_KEPT);
            scaffold(units, UNIT_CLAIMED_LATER);
            Files.writeString(victim.resolve("skill-project.toml"), manifest(units, UNIT_KEPT));

            List<ProcessRecord> procs = new ArrayList<>();
            List<String> log = new ArrayList<>();

            // ------------------------------------------------ the fixture
            // Established from OUTSIDE the victim, with an explicit
            // --project-dir, so nothing here depends on the behaviour under
            // test. Run unconfined: this step is not the claim.
            ProcessRecord seed = sm(ctx, "seed-victim", driverHome, null,
                    "project", "resolve", "--skip-gateway",
                    "--project-dir", victim.toString());
            procs.add(seed);

            Set<String> victimBefore = unitsIn(victimHome);
            Set<String> driverBefore = unitsIn(driverHome);
            // unitsIn() qualifies each name with its store directory, so the
            // precondition names the same string the set holds. A bare
            // contains(UNIT_KEPT) would be false FOREVER and would fail this
            // node on its precondition rather than its claim — mechanism A,
            // avoided by checking the spelling rather than assuming it.
            boolean fixtureReady = seed.exitCode() == 0
                    && victimBefore.contains("skills/" + UNIT_KEPT);
            log.add("FIXTURE  victim home " + victimHome + " -> " + victimBefore
                    + " (seed exit " + seed.exitCode() + ")");

            // The manifest now claims a DIFFERENT unit. Any resolve that
            // reaches this project MUST change the victim home's membership;
            // that is what makes the claim below falsifiable at all.
            Files.writeString(victim.resolve("skill-project.toml"),
                    manifest(units, UNIT_CLAIMED_LATER));
            boolean manifestRewritten = Files.readString(victim.resolve("skill-project.toml"))
                    .contains(UNIT_CLAIMED_LATER);

            // -------------------------------------------------- the claim
            // CWD is the victim checkout. SKILL_MANAGER_HOME is the driver's
            // own home. A confinement is declared over the driver's root, which
            // does not contain the victim. No --project-dir.
            ProcessRecord confined = sm(ctx, "confined-resolve-from-victim-cwd",
                    driverHome, driverRoot, victim,
                    "project", "resolve", "--skip-gateway");
            procs.add(confined);
            Set<String> victimAfterClaim = unitsIn(victimHome);
            Set<String> driverAfterClaim = unitsIn(driverHome);

            boolean refused = confined.exitCode() == CONFINEMENT_ESCAPE;
            boolean otherHomeUnchanged = victimAfterClaim.equals(victimBefore);
            boolean ownHomeUnchanged = driverAfterClaim.equals(driverBefore);
            log.add("CLAIM    exit " + confined.exitCode() + " (expected " + CONFINEMENT_ESCAPE
                    + "); victim " + victimAfterClaim + "; driver " + driverAfterClaim);

            // ------------------------------------------------- the control
            // Byte-identical, confinement REMOVED. If the victim home's unit
            // set does not move here, the claim above proved nothing.
            ProcessRecord unconfined = sm(ctx, "control-unconfined-resolve-from-victim-cwd",
                    driverHome, null, victim,
                    "project", "resolve", "--skip-gateway");
            procs.add(unconfined);
            Set<String> victimAfterControl = unitsIn(victimHome);

            boolean controlEscaped = !victimAfterControl.equals(victimBefore);
            boolean controlReachedTheGuard = unconfined.exitCode() != confined.exitCode();
            log.add("CONTROL  exit " + unconfined.exitCode() + "; victim " + victimAfterControl
                    + (controlEscaped
                            ? "  <- the escape, reproduced"
                            : "  <- NOTHING MOVED: the claim above is vacuous"));
            log.add("The control reddens: the_other_homes_unit_set_is_unchanged"
                    + " (victim " + victimBefore + " -> " + victimAfterControl + ")");

            boolean pass = fixtureReady && manifestRewritten && refused && otherHomeUnchanged
                    && ownHomeUnchanged && controlEscaped && controlReachedTheGuard;

            NodeResult result = pass
                    ? NodeResult.pass("project.verb.stays.in.its.home")
                    : NodeResult.fail("project.verb.stays.in.its.home",
                            "fixtureReady=" + fixtureReady
                                    + " manifestRewritten=" + manifestRewritten
                                    + " refused=" + refused
                                    + " otherHomeUnchanged=" + otherHomeUnchanged
                                    + " ownHomeUnchanged=" + ownHomeUnchanged
                                    + " controlEscaped=" + controlEscaped
                                    + " controlReachedTheGuard=" + controlReachedTheGuard);
            for (ProcessRecord p : procs) result = result.process(p);
            return result
                    // Preconditions, asserted separately from the claim and
                    // blind to the mutation under test (mechanism B).
                    .assertion("precondition_victim_home_holds_the_seeded_unit", fixtureReady)
                    .assertion("precondition_manifest_now_claims_a_different_unit",
                            manifestRewritten)
                    // The claim.
                    .assertion("a_confined_project_verb_refuses_a_cwd_derived_target", refused)
                    .assertion("the_other_homes_unit_set_is_unchanged", otherHomeUnchanged)
                    .assertion("the_drivers_own_home_is_unchanged", ownHomeUnchanged)
                    // The control (mechanisms A and C).
                    .assertion("removing_the_confinement_reproduces_the_escape", controlEscaped)
                    .assertion("the_control_reached_the_guard_it_removed", controlReachedTheGuard)
                    .metric("confinedExitCode", confined.exitCode())
                    .metric("unconfinedExitCode", unconfined.exitCode())
                    .metric("victimUnitsBefore", victimBefore.size())
                    .metric("victimUnitsAfterControl", victimAfterControl.size())
                    .publish("victimHome", victimHome.toString())
                    .publish("driverHome", driverHome.toString())
                    .log(String.join("\n", log));
        });
    }

    // ----------------------------------------------------------- observation

    /**
     * The unit names a home holds, by directory listing. The close-out gate's
     * reader; see the class comment for why it is deliberately the raw one.
     */
    static Set<String> unitsIn(Path home) {
        Set<String> out = new TreeSet<>();
        for (String dir : List.of("skills", "plugins", "docs", "harnesses")) {
            Path d = home.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                s.forEach(p -> out.add(dir + "/" + p.getFileName()));
            } catch (IOException ignored) {
                // unreadable: absent from the set, which shows up as a delta
            }
        }
        return out;
    }

    // -------------------------------------------------------------- process

    /**
     * The CLI, with the working directory stated.
     *
     * @param confineRoot the confinement to declare, or null for the control —
     *                    which does not merely omit it but REMOVES any
     *                    inherited one. See {@link SmEnv#unconfine}.
     * @param cwd         the working directory, or null for the node's own
     */
    private static ProcessRecord sm(NodeContext ctx, String label, Path home,
                                    Path confineRoot, Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add(SmEnv.cli().toString());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        SmEnv.apply(ctx, pb, home.toString());
        if (confineRoot == null) SmEnv.unconfine(pb); else SmEnv.confineTo(pb, confineRoot);
        if (cwd != null) pb.directory(cwd.toFile());
        return Procs.run(ctx, label, pb);
    }

    /** {@link #sm} for a run whose working directory does not matter. */
    private static ProcessRecord sm(NodeContext ctx, String label, Path home,
                                    Path confineRoot, String... args) {
        return sm(ctx, label, home, confineRoot, null, args);
    }

    // -------------------------------------------------------------- fixture

    private static void scaffold(Path unitsDir, String name) throws IOException {
        Path dir = unitsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: HIS-16 confinement fixture
                ---
                body
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "HIS-16 confinement fixture"
                """.formatted(name));
    }

    private static String manifest(Path unitsDir, String unit) {
        return """
                [project]
                name = "his16-victim-project"

                [skills.claimed]
                source = "%s"
                """.formatted(unitsDir.resolve(unit));
    }

    private ProjectVerbStaysInItsHome() {}
}
