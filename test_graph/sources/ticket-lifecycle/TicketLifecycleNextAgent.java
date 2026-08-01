///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>Step 7 — the next agent.</b> A third ticket is provisioned after both of
 * the first two are gone, and it must inherit everything they contributed.
 *
 * <h2>The question this answers</h2>
 *
 * <p>"Does the next ticket agent get the change?" Everything before this node
 * is about work reaching the project home safely; this is the only node that
 * asks whether the project home is then any use. The answer today is yes, so
 * this pins it — a regression here would be silent in exactly the way the rest
 * of the epic's regressions were: every command reports success and the next
 * agent simply starts from an older skill.
 *
 * <h2>All three contributions, not one</h2>
 *
 * <ul>
 *   <li>{@code tl-x}'s file, which only ticket A wrote;</li>
 *   <li>{@code tl-y}'s file, which only ticket B wrote;</li>
 *   <li>the RESOLVED shared unit, carrying both tickets' lines — the one that
 *       went through a conflict. Inheriting the two easy ones and losing the
 *       contested one would be the interesting failure, so it is asserted
 *       separately rather than folded into a single "the home matches".</li>
 * </ul>
 *
 * <p>And the whole unit tree is compared against the project home's, so an
 * inherited file that arrived truncated is a failure rather than a passing
 * substring match.
 */
public class TicketLifecycleNextAgent {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.next.agent")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.teardown")
            .tags("ticket-lifecycle", "new-change", "inheritance")
            .timeout("900s")
            .output("worktreeC", "string")
            .output("homeC", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String checkoutRaw = ctx.get("ticket.lifecycle.fixture.built", "checkout").orElse(null);
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            String scriptsRaw = ctx.get("ticket.lifecycle.fixture.built", "scriptsDir")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            String resolvedShared = ctx.get("ticket.lifecycle.conflict", "resolvedShared")
                    .orElse(null);
            if (checkoutRaw == null || projectRaw == null || scriptsRaw == null || ambient == null
                    || resolvedShared == null) {
                return NodeResult.fail("ticket.lifecycle.next.agent", "missing upstream context");
            }
            Path checkout = Path.of(checkoutRaw);
            Path project = Path.of(projectRaw);

            ProcessRecord provision = TicketLifecycleSupport.script(ctx, "new-change-c", checkout,
                    Path.of(scriptsRaw).resolve("new-change.sh"), ambient,
                    TicketLifecycleSupport.TICKET_C);
            Path worktreeC = TicketLifecycleSupport.worktreeFor(checkout,
                    TicketLifecycleSupport.TICKET_C);
            Path homeC = TicketLifecycleSupport.homeOf(worktreeC);
            boolean theThirdWorktreeWasProvisioned = provision.exitCode() == 0
                    && Files.isRegularFile(homeC.resolve("home.runtime.json"));

            boolean itInheritedTicketAsUnit = HomeSyncSupport.read(TicketLifecycleSupport
                    .unitDir(homeC, TicketLifecycleSupport.UNIT_A).resolve("references/from-a.md"))
                    .equals(TicketLifecycleSupport.A_ONLY);
            boolean itInheritedTicketBsUnit = HomeSyncSupport.read(TicketLifecycleSupport
                    .unitDir(homeC, TicketLifecycleSupport.UNIT_B).resolve("references/from-b.md"))
                    .equals(TicketLifecycleSupport.B_ONLY);
            String inheritedShared = HomeSyncSupport.read(TicketLifecycleSupport
                    .unitDir(homeC, TicketLifecycleSupport.SHARED).resolve("SKILL.md"));
            boolean itInheritedTheResolvedSharedUnit = inheritedShared.equals(resolvedShared)
                    && inheritedShared.contains(TicketLifecycleSupport.A_SHARED.strip())
                    && inheritedShared.contains(TicketLifecycleSupport.B_SHARED.strip());

            // Whole trees, not sampled files: a truncated inherited file is a
            // failure rather than a passing substring match.
            List<String> drift = HomeSyncSupport.difference(
                    HomeSyncSupport.entryDigests(project.resolve("skills")),
                    HomeSyncSupport.entryDigests(homeC.resolve("skills")));
            boolean everyUnitArrivedByteForByte = drift.isEmpty();

            // And a fresh worktree's home is still its own copy — the property
            // asserted at the start, re-asserted after the project home has
            // been written into four times, because a clone of a home that has
            // MOVED is the case with a history of surprises (#135's baseline).
            boolean itIsStillItsOwnCopy = TicketLifecycleSupport.inode(homeC) > 0
                    && TicketLifecycleSupport.inode(homeC) != TicketLifecycleSupport.inode(project);

            boolean pass = theThirdWorktreeWasProvisioned && itInheritedTicketAsUnit
                    && itInheritedTicketBsUnit && itInheritedTheResolvedSharedUnit
                    && everyUnitArrivedByteForByte && itIsStillItsOwnCopy;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.next.agent")
                    : NodeResult.fail("ticket.lifecycle.next.agent",
                            "provisionExit=" + provision.exitCode()
                                    + " inheritedA=" + itInheritedTicketAsUnit
                                    + " inheritedB=" + itInheritedTicketBsUnit
                                    + " inheritedShared=" + itInheritedTheResolvedSharedUnit
                                    + " drift=" + drift
                                    + " ownCopy=" + itIsStillItsOwnCopy))
                    .process(provision)
                    .assertion("a_third_ticket_worktree_provisions_after_the_first_two_are_gone",
                            theThirdWorktreeWasProvisioned)
                    .assertion("the_next_agent_inherits_the_first_tickets_unit",
                            itInheritedTicketAsUnit)
                    .assertion("the_next_agent_inherits_the_second_tickets_unit",
                            itInheritedTicketBsUnit)
                    .assertion("the_next_agent_inherits_the_resolved_shared_unit",
                            itInheritedTheResolvedSharedUnit)
                    .assertion("every_unit_arrived_byte_for_byte", everyUnitArrivedByteForByte)
                    .assertion("the_third_worktrees_home_is_still_its_own_copy",
                            itIsStillItsOwnCopy)
                    .publish("worktreeC", worktreeC.toString())
                    .publish("homeC", homeC.toString());
        });
    }
}
