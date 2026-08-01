///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * <b>Step 3 — the agent works.</b> Ticket A edits the shared skill and
 * {@code tl-x}; ticket B edits the shared skill, differently, and
 * {@code tl-y}.
 *
 * <h2>The agent is the one part that is a stub</h2>
 *
 * <p>No {@code claude}/{@code codex}/{@code gemini} process is launched. What
 * an agent does to a home is write files into
 * {@code <home>/skills/<unit>/}, and what is under test is the machinery
 * around that — the gate, the reconcile, the lock, the teardown. Launching a
 * model would add a dependency on a network and a non-deterministic edit while
 * measuring nothing extra.
 *
 * <h2>Different content on each side, so the conflict is real</h2>
 *
 * <p>Both tickets append to the same file in {@code tl-shared} with different
 * text. That is the only configuration under which the merge has to make a
 * decision: identical edits on both sides reconcile silently, and a conflict
 * fixture that quietly stopped conflicting would take every downstream
 * assertion with it while still passing them.
 *
 * <h2>Invisible to the parent, which is the whole problem</h2>
 *
 * <p>The home is gitignored, so an agent's improvement appears in no diff:
 * {@code git add -A} never sees it and {@code propagate.sh} can never carry
 * it. That is asserted here — positively, by showing the tracked tree is clean
 * while the bytes demonstrably moved — because it is the premise the entire
 * close-out gate exists to compensate for. If the edits DID show up in
 * {@code git status}, the gate would be redundant, and a test suite that never
 * checked would not notice either way.
 */
public class TicketLifecycleAgentEdits {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.agent.edits")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("ticket.lifecycle.first.launch")
            .tags("ticket-lifecycle", "agent", "edits")
            .timeout("300s")
            .output("sharedContentA", "string")
            .output("sharedContentB", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeARaw = ctx.get("ticket.lifecycle.provisioned", "homeA").orElse(null);
            String homeBRaw = ctx.get("ticket.lifecycle.provisioned", "homeB").orElse(null);
            String worktreeARaw = ctx.get("ticket.lifecycle.provisioned", "worktreeA").orElse(null);
            String worktreeBRaw = ctx.get("ticket.lifecycle.provisioned", "worktreeB").orElse(null);
            String checkoutRaw = ctx.get("ticket.lifecycle.fixture.built", "checkout").orElse(null);
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            if (homeARaw == null || homeBRaw == null || worktreeARaw == null
                    || worktreeBRaw == null || checkoutRaw == null || projectRaw == null) {
                return NodeResult.fail("ticket.lifecycle.agent.edits", "missing upstream context");
            }
            Path homeA = Path.of(homeARaw);
            Path homeB = Path.of(homeBRaw);
            Path project = Path.of(projectRaw);
            Path checkout = Path.of(checkoutRaw);

            LinkedHashMap<String, String> projectBefore = TicketLifecycleSupport.digests(project);

            Path sharedA = TicketLifecycleSupport
                    .unitDir(homeA, TicketLifecycleSupport.SHARED).resolve("SKILL.md");
            Path sharedB = TicketLifecycleSupport
                    .unitDir(homeB, TicketLifecycleSupport.SHARED).resolve("SKILL.md");
            String contentA = HomeSyncSupport.read(sharedA) + TicketLifecycleSupport.A_SHARED;
            String contentB = HomeSyncSupport.read(sharedB) + TicketLifecycleSupport.B_SHARED;
            HomeSyncSupport.write(sharedA, contentA);
            HomeSyncSupport.write(sharedB, contentB);

            // One nested new file per ticket, in the unit only that ticket
            // touches. A reconcile that only ever moved top-level files would
            // satisfy every status assertion in this graph while being unable
            // to carry a real skill, whose content lives under references/.
            HomeSyncSupport.write(TicketLifecycleSupport
                    .unitDir(homeA, TicketLifecycleSupport.UNIT_A)
                    .resolve("references/from-a.md"), TicketLifecycleSupport.A_ONLY);
            HomeSyncSupport.write(TicketLifecycleSupport
                    .unitDir(homeB, TicketLifecycleSupport.UNIT_B)
                    .resolve("references/from-b.md"), TicketLifecycleSupport.B_ONLY);

            boolean bothSidesEditedTheSharedUnit =
                    HomeSyncSupport.read(sharedA).endsWith(TicketLifecycleSupport.A_SHARED)
                            && HomeSyncSupport.read(sharedB).endsWith(TicketLifecycleSupport.B_SHARED);
            boolean theTwoEditsDiffer = !HomeSyncSupport.read(sharedA)
                    .equals(HomeSyncSupport.read(sharedB));
            boolean eachTicketAlsoEditedAUnitOfItsOwn =
                    HomeSyncSupport.read(TicketLifecycleSupport
                            .unitDir(homeA, TicketLifecycleSupport.UNIT_A)
                            .resolve("references/from-a.md")).equals(TicketLifecycleSupport.A_ONLY)
                    && HomeSyncSupport.read(TicketLifecycleSupport
                            .unitDir(homeB, TicketLifecycleSupport.UNIT_B)
                            .resolve("references/from-b.md")).equals(TicketLifecycleSupport.B_ONLY);

            // Neither ticket's edit reached the other's home, nor the project's.
            // Distinct inodes were asserted at provisioning; this is the same
            // claim measured after a write, which is when a shared inode would
            // actually show.
            boolean neitherEditLeakedSideways =
                    !HomeSyncSupport.read(sharedB).contains(TicketLifecycleSupport.A_SHARED)
                            && !HomeSyncSupport.read(sharedA).contains(TicketLifecycleSupport.B_SHARED);
            List<String> projectMoved = HomeSyncSupport.difference(projectBefore,
                    TicketLifecycleSupport.digests(project));
            boolean theProjectHomeDidNotMove = projectMoved.isEmpty();

            // --- and none of it is in any git working tree -------------------
            String parentStatus =
                    HomeSyncSupport.git(checkout, "status", "--porcelain").trimmed();
            String statusA =
                    HomeSyncSupport.git(Path.of(worktreeARaw), "status", "--porcelain").trimmed();
            String statusB =
                    HomeSyncSupport.git(Path.of(worktreeBRaw), "status", "--porcelain").trimmed();
            boolean theEditsAreInvisibleToGit = parentStatus.isEmpty() && statusA.isEmpty()
                    && statusB.isEmpty();

            boolean pass = bothSidesEditedTheSharedUnit && theTwoEditsDiffer
                    && eachTicketAlsoEditedAUnitOfItsOwn && neitherEditLeakedSideways
                    && theProjectHomeDidNotMove && theEditsAreInvisibleToGit;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.agent.edits")
                    : NodeResult.fail("ticket.lifecycle.agent.edits",
                            "bothEdited=" + bothSidesEditedTheSharedUnit
                                    + " differ=" + theTwoEditsDiffer
                                    + " ownUnits=" + eachTicketAlsoEditedAUnitOfItsOwn
                                    + " noSidewaysLeak=" + neitherEditLeakedSideways
                                    + " projectMoved=" + projectMoved
                                    + " parentStatus=[" + parentStatus + "]"
                                    + " statusA=[" + statusA + "] statusB=[" + statusB + "]"))
                    .assertion("both_tickets_edited_the_shared_unit", bothSidesEditedTheSharedUnit)
                    .assertion("the_two_edits_to_the_shared_unit_are_different", theTwoEditsDiffer)
                    .assertion("each_ticket_also_edited_a_unit_only_it_touches",
                            eachTicketAlsoEditedAUnitOfItsOwn)
                    .assertion("neither_tickets_edit_reached_the_others_home",
                            neitherEditLeakedSideways)
                    .assertion("an_agents_edit_does_not_reach_the_project_home_by_itself",
                            theProjectHomeDidNotMove)
                    .assertion("every_edit_is_invisible_to_every_git_working_tree",
                            theEditsAreInvisibleToGit)
                    .publish("sharedContentA", contentA)
                    .publish("sharedContentB", contentB);
        });
    }
}
