///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 9 — markdown {@code skill-imports} in the project's OWN files are
 * never validated by anything on the onboarding path.</b>
 *
 * <h2>The defect, and its exact boundary</h2>
 *
 * <p>An import <em>inside an installed unit</em> resolves end to end: it is
 * validated at install and, after a sync, the target is reachable from the
 * agent's own view. An import in the <em>project checkout's own markdown</em> —
 * {@code CLAUDE.md}, {@code docs/**}{@code .md} — is silently inert.
 * {@code ValidateMarkdownImports} is emitted only by {@code InstallUseCase},
 * {@code SyncUseCase}, {@code OnboardCommand} and {@code PublishCommand}, and
 * {@code MarkdownImportValidator.validateInstalled} walks installed unit roots.
 * A skill project checkout is not a unit root, so nothing checks its markdown
 * and nothing materializes its import targets.
 *
 * <p>Measured: with {@code CLAUDE.md}'s import broken to name both a missing
 * unit and a missing path, {@code project resolve --skip-gateway} exits 0 and
 * a grep for "import" or "violation" over the entire log returns nothing.
 *
 * <h2>The positive control, in the same node</h2>
 *
 * <p>The half that works is a regression guard worth as much as the defect:
 * after a full sync, {@code $CLAUDE_CONFIG_DIR/skills/<u>/<path>} exists for
 * every valid import target. It is asserted THROUGH the agent-visible link
 * rather than in the store, and the resolved real path must land under
 * {@code <home>/skills/<u>} — proving the link was traversed rather than that
 * the file merely exists somewhere.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>Mutating the import in a way the validator would reject for a
 *       DIFFERENT reason</b> — malformed YAML, a missing {@code reason} key —
 *       so that a future fix appears to work while actually rejecting the input
 *       for its shape.
 *       <br><b>Companion:</b> two mutations in two separate files, both
 *       well-formed: one missing-UNIT and one present-unit/missing-PATH. Both
 *       must be named in the output. A validator that only rejected malformed
 *       blocks would satisfy neither.</li>
 *   <li><b>The positive control passing because the paths exist in the STORE</b>
 *       rather than through the agent-visible link.
 *       <br><b>Companion:</b> resolution goes through
 *       {@code $CLAUDE_CONFIG_DIR/skills/<u>/…} explicitly, and the control is
 *       shown going RED with the link removed, in the same run, before the link
 *       is restored.</li>
 * </ol>
 */
public class OnboardingProjectMarkdownImportsChecked {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.project.markdown.imports.checked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.launch.env")
            .tags("onboarding", "imports")
            .timeout("900s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null) {
                return NodeResult.fail("onboarding.project.markdown.imports.checked",
                        "missing upstream context");
            }

            // --- the positive control, through the agent-visible link ----------
            Path claudeSkills = OnboardingSupport.agentSkills(proj, ".claude");
            List<String> reachable = new ArrayList<>();
            List<String> unreachable = new ArrayList<>();
            List<String[]> targets = List.of(
                    new String[] {OnboardingSupport.ALPHA, "SKILL.md"},
                    new String[] {OnboardingSupport.BETA, "references/page.md"});
            for (String[] t : targets) {
                Path viaLink = claudeSkills.resolve(t[0]).resolve(t[1]);
                boolean ok = Files.isRegularFile(viaLink);
                if (ok) {
                    // The link was TRAVERSED, not merely present: the real path
                    // has to land in the home's store.
                    ok = viaLink.toRealPath().startsWith(
                            home.resolve("skills").resolve(t[0]).toRealPath());
                }
                (ok ? reachable : unreachable).add(t[0] + "/" + t[1]);
            }
            boolean everyValidImportTargetIsReachableFromTheAgentsView =
                    unreachable.isEmpty() && reachable.size() == targets.size();

            // The control's own sensitivity proof: remove the link and it must
            // go red, then put it back.
            Path victimLink = claudeSkills.resolve(OnboardingSupport.ALPHA);
            boolean theReachabilityControlGoesRedWithoutTheLink = false;
            if (everyValidImportTargetIsReachableFromTheAgentsView
                    && Files.isSymbolicLink(victimLink)) {
                Path target = Files.readSymbolicLink(victimLink);
                Files.delete(victimLink);
                theReachabilityControlGoesRedWithoutTheLink =
                        !Files.isRegularFile(claudeSkills.resolve(OnboardingSupport.ALPHA)
                                .resolve("SKILL.md"));
                Files.createSymbolicLink(victimLink, target);
            }
            boolean theLinkWasRestored = Files.isRegularFile(
                    claudeSkills.resolve(OnboardingSupport.ALPHA).resolve("SKILL.md"));

            // --- the defect: break the project's own markdown, then resolve ------
            Path claudeMd = proj.resolve("CLAUDE.md");
            Path archMd = proj.resolve("docs").resolve("architecture.md");
            String claudeMdOriginal = Files.readString(claudeMd);
            String archMdOriginal = Files.readString(archMd);

            // Two mutations, two files, two distinct failure modes — both
            // well-formed YAML, so a rejection can only be about the reference.
            Files.writeString(claudeMd, "---\n" + OnboardingSupport.imports(
                    OnboardingSupport.entry("ob-no-such-unit", "SKILL.md",
                            "names a unit that is not installed"))
                    + "---\n\n# acme widgets\n");
            Files.writeString(archMd, "---\n" + OnboardingSupport.imports(
                    OnboardingSupport.entry(OnboardingSupport.ALPHA,
                            "references/definitely-missing.md",
                            "names an installed unit and a path it does not have"))
                    + "---\n\n# architecture\n");

            ProcessRecord resolve = OnboardingSupport.sm(ctx, "resolve-broken-project-imports",
                    home, proj, "project", "resolve", "--project-dir", proj.toString(),
                    "--skip-gateway");
            String log = OnboardingSupport.log(ctx, resolve);
            boolean theMissingUnitWasNamed = log.contains("ob-no-such-unit");
            boolean theMissingPathWasNamed = log.contains("definitely-missing.md");
            boolean anUnresolvableProjectImportIsReported =
                    theMissingUnitWasNamed && theMissingPathWasNamed;
            // A command that reports a violation and exits 0 has reported
            // nothing an automated caller can act on.
            boolean thatReportReachedTheExitCode =
                    !anUnresolvableProjectImportIsReported || resolve.exitCode() != 0;

            Files.writeString(claudeMd, claudeMdOriginal);
            Files.writeString(archMd, archMdOriginal);
            boolean theProjectMarkdownWasRestored =
                    Files.readString(claudeMd).equals(claudeMdOriginal);

            boolean pass = everyValidImportTargetIsReachableFromTheAgentsView
                    && theReachabilityControlGoesRedWithoutTheLink
                    && theLinkWasRestored
                    && anUnresolvableProjectImportIsReported
                    && thatReportReachedTheExitCode
                    && theProjectMarkdownWasRestored;

            return (pass
                    ? NodeResult.pass("onboarding.project.markdown.imports.checked")
                    : NodeResult.fail("onboarding.project.markdown.imports.checked",
                            "reachable=" + reachable + " unreachable=" + unreachable
                                    + " missingUnitNamed=" + theMissingUnitWasNamed
                                    + " missingPathNamed=" + theMissingPathWasNamed
                                    + " resolveExit=" + resolve.exitCode()))
                    .process(resolve)
                    .assertion("every_valid_import_target_is_reachable_from_the_agents_view",
                            everyValidImportTargetIsReachableFromTheAgentsView)
                    .assertion("the_reachability_control_goes_red_without_the_link",
                            theReachabilityControlGoesRedWithoutTheLink)
                    .assertion("the_link_was_restored_after_the_control_ran", theLinkWasRestored)
                    .assertion("an_unresolvable_import_in_the_projects_own_markdown_is_reported",
                            anUnresolvableProjectImportIsReported)
                    .assertion("a_reported_project_import_violation_reaches_the_exit_code",
                            thatReportReachedTheExitCode)
                    .assertion("the_projects_markdown_was_restored",
                            theProjectMarkdownWasRestored)
                    .metric("resolveExit", resolve.exitCode())
                    .log("reachable through the agent link: " + reachable)
                    .log("unreachable: " + unreachable);
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
