///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>home-verdicts</b> (OHV-0, #356) — the epic's regression guard for home
 * defect shapes. Each shape node plants one shape that was measured on a real
 * home, and pins what {@code home repair --json} and {@code home verify} say
 * about it on this tree.
 *
 * <h2>Pinned at OHV-0 (green on today's tree)</h2>
 *
 * <table>
 *   <caption>shape, node, what today's tree says</caption>
 *   <tr><th>shape</th><th>node</th><th>repair</th><th>verify</th></tr>
 *   <tr><td>clean home, laid out from nothing</td><td>home.verdicts.clean.home</td><td>0</td><td>0</td></tr>
 *   <tr><td>fully frozen own-home shim (DEF-OUN-018)</td><td>home.verdicts.frozen.shim</td>
 *       <td>1 FROZEN_HOME_PATH_IN_SHIM</td><td>1, names it (0 until OHV-2)</td></tr>
 *   <tr><td>foreign path in a shim (DEF-104)</td><td>home.verdicts.foreign.path.in.shim</td>
 *       <td>1 FOREIGN_PATH_IN_SHIM</td><td>1, names it</td></tr>
 *   <tr><td>misanchored agent link (#159)</td><td>home.verdicts.misanchored.agent.link</td>
 *       <td>1 MISANCHORED_AGENT_LINK</td><td>1, names it (0 until OHV-2)</td></tr>
 *   <tr><td>unstamped pm tree (DEF-OUN-018)</td><td>home.verdicts.unstamped.pm.tree</td>
 *       <td>1 UNSTAMPED_PM_TREE</td><td>1, names it (0 until OHV-2)</td></tr>
 *   <tr><td>verify names every repair finding, five kinds in one home (OHV-2 a)</td>
 *       <td>home.verdicts.verify.names.every.repair.finding</td><td>1</td><td>1, names all</td></tr>
 *   <tr><td>dangling agent-dir link into the home (DEF-OHV-002, OHV-2 b)</td>
 *       <td>home.verdicts.dangling.agent.link</td><td>1 DANGLING_AGENT_LINK</td><td>1, names it</td></tr>
 *   <tr><td>orphaned installed/&lt;unit&gt;.projections.json (DEF-OHV-002, OHV-2 c)</td>
 *       <td>home.verdicts.orphaned.projection.record</td><td>1 ORPHANED_PROJECTION_RECORD</td>
 *       <td>1, names it</td></tr>
 * </table>
 *
 * <p>The three "0 until OHV-2" cells were GOAL-one-verdict clause (1) failing,
 * asserted as the behaviour of their day so the graph was green on arrival.
 * OHV-2 (#339) made verify fail whenever repair reports and flipped them.
 *
 * <h2>PENDING — each named shape gets its node from the ticket that fixes it</h2>
 *
 * <ul>
 *   <li>half-rewritten shim: header re-anchored, {@code exec} line literal
 *       (DEF-OHV-001) — <b>OHV-4 (#341)</b></li>
 *   <li>{@code /var} vs {@code /private/var} reference (#343) — <b>OHV-5 (#346)</b></li>
 *   <li>#352's four marketplace-identity shapes (1 Claude registers this path under
 *       another name; 2 generated name a substring of another; 3 generated name equal
 *       to another home's; 4 Codex/Claude registers or enables another home's
 *       marketplace, incl. DEF-OHV-005) — <b>OHV-6 (#352)</b></li>
 * </ul>
 *
 * <h2>This node</h2>
 *
 * <p>Makes the graph's scratch root and lays out ONE clean home from nothing
 * (no install, no clone of a real home). It publishes that home — the only
 * home this graph publishes, so the fixpoint and membership laws have exactly
 * one subject, and it is clean. Shape nodes build their damaged homes under
 * {@code scratchRoot} and delete them; see {@code HomeVerdictsSupport}.
 */
public class HomeVerdictsFixture {

    static final NodeSpec SPEC = NodeSpec.of(HomeVerdictsSupport.FIXTURE)
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("home", "verdicts", "fixture", "ohv-0")
            .timeout("120s")
            .output("cleanHome", "string")
            .output("scratchRoot", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail(SPEC.id(), "missing env.prepared context")
                        .assertion("a_clean_home_was_laid_out_from_nothing", false);
            }
            Path root = Path.of(envHome).resolve("home-verdicts");
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            // From nothing: the directory must not exist before this node.
            boolean fromNothing = !Files.exists(root.resolve("clean"));
            Path clean = HomeVerdictsSupport.layOutHome(root.resolve("clean"));
            boolean laidOut = HomeVerdictsSupport.STORE_DIRS.stream()
                    .allMatch(d -> Files.isDirectory(clean.resolve(d)));
            boolean holdsNoUnit;
            try (var units = Files.list(clean.resolve("skills"))) {
                holdsNoUnit = units.findAny().isEmpty();
            }
            boolean pass = fromNothing && laidOut && holdsNoUnit;
            return (pass ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(), "fromNothing=" + fromNothing + " laidOut=" + laidOut
                            + " holdsNoUnit=" + holdsNoUnit))
                    .assertion("the_home_did_not_exist_before_this_node", fromNothing)
                    .assertion("a_clean_home_was_laid_out_from_nothing", laidOut)
                    .assertion("the_published_home_holds_no_unit_so_membership_is_empty", holdsNoUnit)
                    .publish("cleanHome", clean.toString())
                    .publish("scratchRoot", scratch.toString());
        });
    }
}
