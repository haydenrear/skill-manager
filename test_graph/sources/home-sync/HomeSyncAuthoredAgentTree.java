///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * CHM-16: the agent tree is the other half of a home, and the projectors
 * destroyed authored content in it without a word.
 *
 * <h2>What the projectors did</h2>
 *
 * <p>{@code <home>/.claude/skills/<name>} is where {@code skill-manager}
 * projects an installed unit — and it is also where a human writes a skill by
 * hand. The three projectors each carried the same six lines: delete whatever
 * is at the target, then symlink. Six call sites, one silent default. Install a
 * unit whose name collides with a skill the human already wrote there and the
 * hand-written directory was deleted outright: no warning, no backup, no
 * mention in any report, and — unlike a child-home unit — no materialization
 * record that could have said whose bytes those were.
 *
 * <h2>Why the fix is not another record</h2>
 *
 * <p>A projection has no authored state of skill-manager's: it is a pure
 * function of the store. So "may I destroy this?" needs no provenance file, and
 * one would be machinery for its own sake. It reduces to "can I show this IS my
 * projection?", answerable from the two paths alone — a symlink (its bytes live
 * where it points, so removing it destroys nothing) or a directory
 * byte-identical to the source (the copy fallback's own output). Anything else
 * is somebody's work, and is left alone and named. That decision now lives in
 * one place, {@code Projector#clearForProjection}, instead of being the silent
 * default at six.
 *
 * <h2>What is asserted</h2>
 *
 * <p>Bytes, in both directions: the hand-authored file is still readable
 * afterwards with its original content, and the install did not quietly replace
 * the directory with a link. Then the same question for {@code remove}: an
 * uninstall must not take an authored directory with it. And the ordinary case
 * is asserted alongside, because a guard that also refuses the happy path is a
 * regression, not a fix.
 */
public class HomeSyncAuthoredAgentTree {

    private static final String COLLIDING = "hs-authored";
    private static final String ORDINARY = "hs-projected";
    private static final String AUTHORED_BODY = "SIX MONTHS OF NOTES, WRITTEN BY HAND\n";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.authored.agent.tree")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "projection", "agent-tree", "no-destruction")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String claudeHomeRaw = ctx.get("env.prepared", "claudeHome").orElse(null);
            if (workspaceRaw == null || claudeHomeRaw == null) {
                return NodeResult.fail("home.sync.authored.agent.tree", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("authored-agent-tree");
            Path root = base.resolve("root");
            Path sources = base.resolve("sources");
            Path agentSkills = Path.of(claudeHomeRaw).resolve(".claude/skills");
            Files.createDirectories(sources);
            Files.createDirectories(agentSkills);

            HomeSyncSupport.mkSource(sources, COLLIDING, "the store's version");
            HomeSyncSupport.mkSource(sources, ORDINARY, "an ordinary unit");

            // The human's own skill, written straight into the agent tree, with
            // the same name as a unit about to be installed.
            Path authored = agentSkills.resolve(COLLIDING);
            Files.createDirectories(authored);
            HomeSyncSupport.write(authored.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: written by hand, not by skill-manager
                    ---
                    """.formatted(COLLIDING));
            HomeSyncSupport.write(authored.resolve("private-notes.md"), AUTHORED_BODY);

            ProcessRecord installColliding = HomeSyncSupport.sm(ctx, "authored-install-colliding",
                    root.toString(), "install", sources.resolve(COLLIDING).toString(), "--yes");

            boolean theHandWrittenSkillIsStillThere =
                    Files.isDirectory(authored, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(authored)
                            && HomeSyncSupport.read(authored.resolve("private-notes.md"))
                                    .equals(AUTHORED_BODY);
            String installLog = HomeSyncSupport.log(ctx, "authored-install-colliding");
            boolean theCollisionIsNamedRatherThanSwallowed =
                    installLog.contains(authored.toString());

            // The ordinary case must still work — a guard that refuses the happy
            // path is a regression wearing a fix's clothes.
            ProcessRecord installOrdinary = HomeSyncSupport.sm(ctx, "authored-install-ordinary",
                    root.toString(), "install", sources.resolve(ORDINARY).toString(), "--yes");
            Path projected = agentSkills.resolve(ORDINARY);
            boolean anOrdinaryUnitStillProjects = installOrdinary.exitCode() == 0
                    && Files.isSymbolicLink(projected)
                    && Files.isRegularFile(projected.resolve("SKILL.md"));

            // The ledger is the licence. `BindingBackfill` adopted any existing
            // path at a projection target into it, and a row there is what the
            // removal path later reads as "skill-manager put this here". A row
            // naming the human's directory is the deletion, one command early.
            Path ledger = root.resolve("installed").resolve(COLLIDING + ".projections.json");
            String ledgerText = Files.isRegularFile(ledger)
                    ? HomeSyncSupport.read(ledger) : "";
            boolean theLedgerDoesNotClaimTheAuthoredDirectory =
                    !ledgerText.contains(authored.toString());

            // And uninstall must not take an authored directory with it. The
            // removal path walked the same six lines.
            ProcessRecord removeColliding = HomeSyncSupport.sm(ctx, "authored-remove-colliding",
                    root.toString(), "uninstall", COLLIDING);
            boolean uninstallLeftTheHandWrittenSkillAlone =
                    Files.isDirectory(authored, LinkOption.NOFOLLOW_LINKS)
                            && HomeSyncSupport.read(authored.resolve("private-notes.md"))
                                    .equals(AUTHORED_BODY);

            ProcessRecord removeOrdinary = HomeSyncSupport.sm(ctx, "authored-remove-ordinary",
                    root.toString(), "uninstall", ORDINARY);
            boolean uninstallStillRemovesItsOwnProjection = removeOrdinary.exitCode() == 0
                    && !Files.exists(projected, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(projected);

            boolean pass = theHandWrittenSkillIsStillThere
                    && theCollisionIsNamedRatherThanSwallowed
                    && theLedgerDoesNotClaimTheAuthoredDirectory
                    && anOrdinaryUnitStillProjects
                    && uninstallLeftTheHandWrittenSkillAlone
                    && uninstallStillRemovesItsOwnProjection;
            return (pass
                    ? NodeResult.pass("home.sync.authored.agent.tree")
                    : NodeResult.fail("home.sync.authored.agent.tree",
                            "authoredSurvived=" + theHandWrittenSkillIsStillThere
                                    + " collisionNamed=" + theCollisionIsNamedRatherThanSwallowed
                                    + " ledgerClean=" + theLedgerDoesNotClaimTheAuthoredDirectory
                                    + " ordinaryProjects=" + anOrdinaryUnitStillProjects
                                    + " uninstallLeftAuthored=" + uninstallLeftTheHandWrittenSkillAlone
                                    + " uninstallRemovedOwn=" + uninstallStillRemovesItsOwnProjection
                                    + " installCollidingExit=" + installColliding.exitCode()
                                    + " installOrdinaryExit=" + installOrdinary.exitCode()
                                    + " removeCollidingExit=" + removeColliding.exitCode()))
                    .process(installColliding)
                    .process(installOrdinary)
                    .process(removeColliding)
                    .process(removeOrdinary)
                    .assertion("installing_over_a_hand_written_skill_does_not_destroy_it",
                            theHandWrittenSkillIsStillThere)
                    .assertion("the_held_back_projection_target_is_named_in_the_output",
                            theCollisionIsNamedRatherThanSwallowed)
                    .assertion("the_projection_ledger_does_not_claim_the_authored_directory",
                            theLedgerDoesNotClaimTheAuthoredDirectory)
                    .assertion("an_ordinary_unit_still_projects_as_a_symlink",
                            anOrdinaryUnitStillProjects)
                    .assertion("uninstall_does_not_delete_a_hand_written_skill",
                            uninstallLeftTheHandWrittenSkillAlone)
                    .assertion("uninstall_still_removes_its_own_projection",
                            uninstallStillRemovesItsOwnProjection)
                    .metric("installCollidingExitCode", installColliding.exitCode())
                    .metric("installOrdinaryExitCode", installOrdinary.exitCode());
        });
    }
}
