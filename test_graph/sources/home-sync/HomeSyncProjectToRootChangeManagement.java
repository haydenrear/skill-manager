///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>DEF-103 — change management OF the root home, FROM a project home.</b>
 *
 * <blockquote>"We need to be enforcing change management of the root skill
 * manager from project home. However, we should still be able to update the
 * root." — the owner, 2026-08-24</blockquote>
 *
 * <h2>What was measured, and what {@code home.sync.project.to.root} does not cover</h2>
 *
 * <p>That node covers {@code home sync --from <project> --to <root>}: bytes
 * moving between two homes named on one command line. It does not cover the
 * command an operator actually runs to bring a unit into the root home —
 * {@code skill-manager sync <unit>}, which is what {@code skt sync} shells out
 * to — and that is where the promotion path broke.
 *
 * <p>Measured on the operator's root home on 2026-08-24: six registered
 * projects; one, {@code meta-harness}, carrying three absolutely-spelled
 * vendored symlinks. <b>All four unit syncs failed</b> with
 * {@code PROJECT_SYNC_FAILED: meta-harness}, and the direction the owner asked
 * to enforce was unusable.
 *
 * <p>The blast radius, not the check. A project's {@code [[vendored]]}
 * durability finding is a fact about that project's checkout: true before the
 * sync, true after, and no byte the sync moved can cause or cure it. Stamping
 * it on the unit and counting it into the exit code is what turned a correct
 * warning about project A into a refusal of change management for every unit A
 * claims.
 *
 * <h2>Three arms, and the third is here to keep the second honest</h2>
 *
 * <ol>
 *   <li><b>The fix.</b> {@code sync <unit>} at the root home exits 0 while a
 *       registered project that CLAIMS that unit is durability-broken.</li>
 *   <li><b>The guard.</b> {@code project resolve} on that same project still
 *       refuses. The check is scoped, not weakened — delete it and this arm
 *       goes red.</li>
 *   <li><b>The instrument's own positive control.</b> Arm 1 asserts the string
 *       {@code PROJECT_SYNC_FAILED} is ABSENT from the sync's output. An
 *       absence is worth nothing until something proves the output could have
 *       carried it, so arm 3 breaks a project in a way that IS attributable —
 *       its registration snapshot is deleted — and asserts the same sync output
 *       then DOES carry {@code PROJECT_SYNC_FAILED}. Nine instances in this
 *       epic of an output with no way to detect its own invalidity; this is the
 *       cheap countermeasure.</li>
 * </ol>
 */
public class HomeSyncProjectToRootChangeManagement {

    private static final String UNIT = "cm-unit";
    private static final String CONTROL_UNIT = "cm-control-unit";
    private static final String VENDOR_UNIT = "vendor-unit";
    private static final String VENDOR_SUBPATH = "vendor_sources";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.project.to.root.change.management")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.project.to.root")
            .tags("home-sync", "project-to-root", "change-management", "def-103")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            if (workspaceRaw == null) {
                return NodeResult.fail(SPEC.id(), "missing upstream context: workspace");
            }
            Path base = Path.of(workspaceRaw).resolve("project-to-root-change-management");
            Path root = base.resolve("root");
            Path sources = base.resolve("sources");
            Path projectDir = base.resolve("broken-project");
            Path project = projectDir.resolve(".skill-manager");
            Path controlDir = base.resolve("control-project");
            Files.createDirectories(sources);
            Files.createDirectories(projectDir);
            Files.createDirectories(controlDir);
            HomeSyncSupport.mkHome(root);
            HomeSyncSupport.mkSource(sources, UNIT, "change management unit v1");
            HomeSyncSupport.mkSource(sources, CONTROL_UNIT, "control unit v1");

            // The declared vendored path starts LEGAL — a real directory with
            // content, which is the --copy-sdk snapshot mode — so the resolve
            // that registers the project succeeds and the ONLY variable is the
            // breakage applied afterwards.
            Path declaredPath = projectDir.resolve("vendor/sdk");
            HomeSyncSupport.write(declaredPath.resolve("marker.txt"), "vendored sdk content\n");
            Files.writeString(projectDir.resolve("skill-project.toml"), """
                    [project]
                    name = "cm-broken-project"

                    [skills.u]
                    source = "%s"

                    [[vendored]]
                    name = "vendor-sdk"
                    paths = ["vendor/sdk"]
                    from_unit = "%s"
                    from_subpath = "%s"
                    on_invalid = "error"
                    """.formatted(sources.resolve(UNIT), VENDOR_UNIT, VENDOR_SUBPATH));
            Files.writeString(controlDir.resolve("skill-project.toml"), """
                    [project]
                    name = "cm-control-project"

                    [skills.u]
                    source = "%s"
                    """.formatted(sources.resolve(CONTROL_UNIT)));

            ProcessRecord register = HomeSyncSupport.setup(ctx, "cm-register-broken-project",
                    root.toString(), "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            ProcessRecord registerControl = HomeSyncSupport.setup(ctx, "cm-register-control-project",
                    root.toString(), "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", controlDir.toString());

            // FIXTURE PRECONDITION, asserted rather than assumed: the root home
            // really holds the unit, and the project really claims it. Without
            // the claim this node measures the case that was never broken.
            boolean rootHoldsTheUnit =
                    Files.isRegularFile(HomeSyncSupport.unitDir(root, UNIT).resolve("SKILL.md"));
            boolean projectClaimsTheUnit = HomeSyncSupport.read(
                            root.resolve("projects/cm-broken-project/project-lock.toml"))
                    .contains("\"" + UNIT + "\"");

            // Break it the way meta-harness was broken: absolute link text
            // landing at the declared source inside the project's own tree. It
            // RESOLVES — that is the whole point, and a check keyed on link
            // existence would call it fine.
            Path declaredSource = project.resolve("skills")
                    .resolve(VENDOR_UNIT).resolve(VENDOR_SUBPATH).resolve("sdk");
            HomeSyncSupport.write(declaredSource.resolve("marker.txt"), "vendored sdk content\n");
            HomeSyncSupport.deleteTree(declaredPath);
            Files.createSymbolicLink(declaredPath, declaredSource.toAbsolutePath().normalize());
            boolean theBreakageResolvesOnThisMachine = Files.isDirectory(declaredPath);

            // ---------------------------------------------- ARM 1: the fix
            ProcessRecord sync = HomeSyncSupport.sm(ctx, "cm-sync-unit-into-root",
                    root.toString(), "sync", UNIT, "--skip-mcp", "--skip-agents");
            String syncLog = HomeSyncSupport.log(ctx, "cm-sync-unit-into-root");
            boolean theUnitSyncIntoTheRootHomeSucceeds = sync.exitCode() == 0;
            boolean noProjectSyncErrorIsStampedOnTheUnit = !syncLog.contains("PROJECT_SYNC_FAILED");
            boolean theSkippedProjectIsNamedWithItsRemedy =
                    syncLog.contains("cm-broken-project")
                            && syncLog.contains("--repair-vendored");

            // -------------------------------------------- ARM 2: the guard
            ProcessRecord ownResolve = HomeSyncSupport.sm(ctx, "cm-broken-project-own-resolve",
                    root.toString(), "project", "resolve", "--skip-gateway",
                    "--project-dir", projectDir.toString());
            String ownResolveLog = HomeSyncSupport.log(ctx, "cm-broken-project-own-resolve");
            boolean theProjectsOwnResolveStillRefuses = ownResolve.exitCode() != 0
                    && ownResolveLog.contains("vendored paths are invalid");

            // ------------------- ARM 3: the positive control for arm 1's absence
            // Attributable breakage: the registration snapshot the reconcile
            // needs is gone. That is not a statement about the project's
            // checkout durability, so it must still be recorded on the unit.
            HomeSyncSupport.deleteTree(
                    root.resolve("projects/cm-control-project/registration.toml"));
            ProcessRecord controlSync = HomeSyncSupport.sm(ctx, "cm-sync-control-unit",
                    root.toString(), "sync", CONTROL_UNIT, "--skip-mcp", "--skip-agents");
            String controlLog = HomeSyncSupport.log(ctx, "cm-sync-control-unit");
            boolean theOutputCanCarryTheStringArmOneAssertsIsAbsent =
                    controlLog.contains("PROJECT_SYNC_FAILED");

            boolean pass = rootHoldsTheUnit && projectClaimsTheUnit
                    && theBreakageResolvesOnThisMachine
                    && theUnitSyncIntoTheRootHomeSucceeds
                    && noProjectSyncErrorIsStampedOnTheUnit
                    && theSkippedProjectIsNamedWithItsRemedy
                    && theProjectsOwnResolveStillRefuses
                    && theOutputCanCarryTheStringArmOneAssertsIsAbsent;

            return (pass ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "rootHoldsTheUnit=" + rootHoldsTheUnit
                                    + " projectClaimsTheUnit=" + projectClaimsTheUnit
                                    + " breakageResolves=" + theBreakageResolvesOnThisMachine
                                    + " syncExit=" + sync.exitCode()
                                    + " noStamp=" + noProjectSyncErrorIsStampedOnTheUnit
                                    + " named=" + theSkippedProjectIsNamedWithItsRemedy
                                    + " ownResolveExit=" + ownResolve.exitCode()
                                    + " guard=" + theProjectsOwnResolveStillRefuses
                                    + " positiveControl="
                                    + theOutputCanCarryTheStringArmOneAssertsIsAbsent))
                    .process(register).process(registerControl).process(sync)
                    .process(ownResolve).process(controlSync)
                    .assertion("the_root_home_holds_the_unit_and_the_broken_project_claims_it",
                            rootHoldsTheUnit && projectClaimsTheUnit)
                    .assertion("the_non_durable_vendored_link_resolves_on_this_machine",
                            theBreakageResolvesOnThisMachine)
                    .assertion("a_unit_sync_into_the_root_home_succeeds_anyway",
                            theUnitSyncIntoTheRootHomeSucceeds)
                    .assertion("and_stamps_no_project_sync_error_on_the_unit",
                            noProjectSyncErrorIsStampedOnTheUnit)
                    .assertion("while_naming_the_skipped_project_and_the_repair_command",
                            theSkippedProjectIsNamedWithItsRemedy)
                    .assertion("the_broken_projects_own_resolve_still_refuses",
                            theProjectsOwnResolveStillRefuses)
                    .assertion("positive_control_an_attributable_failure_is_still_stamped",
                            theOutputCanCarryTheStringArmOneAssertsIsAbsent)
                    .metric("syncExitCode", sync.exitCode())
                    .metric("ownResolveExitCode", ownResolve.exitCode());
        });
    }
}
