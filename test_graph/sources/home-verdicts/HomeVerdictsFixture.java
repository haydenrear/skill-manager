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
 *   <tr><td>half-rewritten own-home shim: token header, literal exec line (DEF-OHV-001, OHV-4)</td>
 *       <td>home.verdicts.half.rewritten.shim</td><td>1 FROZEN_HOME_PATH_IN_SHIM</td>
 *       <td>1, names it; --fix re-anchors the exec line, a second --fix is a no-op</td></tr>
 *   <tr><td>#352 shape 1: Claude registers this home's path under another name (OHV-6)</td>
 *       <td>home.verdicts.marketplace.under.another.name</td>
 *       <td>1 MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME</td>
 *       <td>1, names it; --fix re-points registration and enablement together</td></tr>
 *   <tr><td>#352 shape 2: enabled under the identity, only a name containing it registered (OHV-6)</td>
 *       <td>home.verdicts.marketplace.identity.unregistered</td>
 *       <td>1 MARKETPLACE_IDENTITY_UNREGISTERED</td><td>1, names it; --fix registers the identity</td></tr>
 *   <tr><td>#352 shape 3: marketplace.json copied from another home (OHV-6)</td>
 *       <td>home.verdicts.copied.marketplace.identity</td><td>1 MARKETPLACE_IDENTITY_COPIED</td>
 *       <td>1, names it; --fix regenerates the manifest</td></tr>
 *   <tr><td>#352 shape 4: Codex marketplace and Claude enablement of another home's (OHV-6, DEF-OHV-005)</td>
 *       <td>home.verdicts.foreign.marketplace.registration</td>
 *       <td>1 FOREIGN_MARKETPLACE_REGISTRATION</td><td>1, names it; --fix removes only those entries</td></tr>
 *   <tr><td>a skill-script install in a child writes through a bin/cli link into its parent (DEF-OHV-011, OHV-9)</td>
 *       <td>home.verdicts.child.install.writes.only.itself</td>
 *       <td colspan="2">not a verdict shape: install into the child, parent byte-identical,
 *       child holds its own token-form shim</td></tr>
 * </table>
 *
 * <p>The three "0 until OHV-2" cells were GOAL-one-verdict clause (1) failing,
 * asserted as the behaviour of their day so the graph was green on arrival.
 * OHV-2 (#339) made verify fail whenever repair reports and flipped them.
 *
 * <h2>PENDING — each named shape gets its node from the ticket that fixes it</h2>
 *
 * <ul>
 *   <li>{@code /var} vs {@code /private/var} reference (#343) — <b>OHV-5 (#346)</b></li>
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
