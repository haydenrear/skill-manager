///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Step 10 — caches, with two live worktrees.</b> The package store stays
 * shared across both worktree homes; the install targets stay per home.
 *
 * <h2>What is new here</h2>
 *
 * <p>{@code SharedPackageCacheIsNotPrivateToTheHome} already asserts the rule
 * at the unit level, against {@code PackageCaches} directly. This node asserts
 * that it still holds where it matters — TWO homes that exist at the same time,
 * each provisioned by the workflow rather than constructed by a test, read
 * through the launch environment an agent would actually get. The regression
 * that motivated the rule ("give each worktree its own cache, for isolation")
 * is precisely a regression that only shows up once there are two worktrees.
 *
 * <h2>Both directions, because they fail in opposite ways</h2>
 *
 * <ul>
 *   <li><b>Sharing narrows.</b> Something starts pointing {@code UV_CACHE_DIR}
 *       (or {@code npm_config_cache}, or {@code PIP_CACHE_DIR}) inside
 *       {@code $SKILL_MANAGER_HOME}, and every worktree begins carving its own
 *       copy out of a store that was already there and already correct.</li>
 *   <li><b>Sharing widens.</b> Somebody concludes a venv is a cache and shares
 *       an install target too, which reintroduces the cross-project mutation
 *       the split exists to prevent.</li>
 * </ul>
 *
 * <h2>The companion</h2>
 *
 * <p>The verdict is a function over two environment maps, so the same function
 * can be handed a synthetic pair in which each home has been given a private
 * cache — and must report it. Without that, "the caches are shared" is a
 * sentence the node could be printing about two maps it never compared.
 */
public class TicketLifecycleCaches {

    /** Content-addressed stores: shareable, and expensive to duplicate. */
    private static final List<String> SHARED_STORES =
            List.of("UV_CACHE_DIR", "npm_config_cache", "PIP_CACHE_DIR");

    /** What a home may own: the trees a package manager INSTALLS into. */
    private static final List<String> PER_HOME_TREES = List.of("venvs", "tools", "npm");

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.caches")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.first.launch")
            .tags("ticket-lifecycle", "caches", "isolation")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            String homeARaw = ctx.get("ticket.lifecycle.provisioned", "homeA").orElse(null);
            String homeBRaw = ctx.get("ticket.lifecycle.provisioned", "homeB").orElse(null);
            String worktreeARaw = ctx.get("ticket.lifecycle.provisioned", "worktreeA").orElse(null);
            String worktreeBRaw = ctx.get("ticket.lifecycle.provisioned", "worktreeB").orElse(null);
            if (ambient == null || homeARaw == null || homeBRaw == null
                    || worktreeARaw == null || worktreeBRaw == null) {
                return NodeResult.fail("ticket.lifecycle.caches", "missing upstream context");
            }

            ProcessRecord envA = printEnv(ctx, "print-env-a", ambient, homeARaw, worktreeARaw);
            ProcessRecord envB = printEnv(ctx, "print-env-b", ambient, homeBRaw, worktreeBRaw);
            Map<String, String> a = parse(HomeSyncSupport.log(ctx, "print-env-a"));
            Map<String, String> b = parse(HomeSyncSupport.log(ctx, "print-env-b"));

            // A floor before any comparison: two empty maps agree about
            // everything, and an `allMatch` over nothing is the shape of every
            // vacuous pass this epic has hit.
            List<String> missing = new ArrayList<>();
            for (String key : SHARED_STORES) {
                if (!a.containsKey(key)) missing.add("a:" + key);
                if (!b.containsKey(key)) missing.add("b:" + key);
            }
            boolean bothLaunchEnvironmentsWereRead = envA.exitCode() == 0 && envB.exitCode() == 0
                    && missing.isEmpty();

            List<String> violations = privateCaches(a, b, homeARaw, homeBRaw);
            boolean thePackageStoresAreSharedAcrossBothWorktrees = violations.isEmpty();

            // The install targets are NOT shared: each home keeps its own.
            List<String> shared = new ArrayList<>();
            for (String tree : PER_HOME_TREES) {
                Path fromA = Path.of(homeARaw).resolve(tree);
                Path fromB = Path.of(homeBRaw).resolve(tree);
                if (fromA.equals(fromB)) shared.add(tree);
                for (String key : SHARED_STORES) {
                    // The other direction of the same mistake: an install
                    // target smuggled into the shared block.
                    String value = a.get(key);
                    if (value != null && Path.of(value).getFileName() != null
                            && PER_HOME_TREES.contains(
                                    Path.of(value).getFileName().toString())) {
                        shared.add(key + "=" + value);
                    }
                }
            }
            boolean noInstallTargetIsShared = shared.isEmpty();

            // --- the companion -------------------------------------------------
            Map<String, String> privateA = new LinkedHashMap<>(a);
            Map<String, String> privateB = new LinkedHashMap<>(b);
            privateA.put("UV_CACHE_DIR", homeARaw + "/cache/uv");
            privateB.put("UV_CACHE_DIR", homeBRaw + "/cache/uv");
            boolean aPrivateCacheIsDetected =
                    !privateCaches(privateA, privateB, homeARaw, homeBRaw).isEmpty();
            // ... and the unmutated pair is still clean under the same call, so
            // an oracle that simply always reports a violation fails here.
            boolean theUnmutatedPairIsStillClean =
                    privateCaches(a, b, homeARaw, homeBRaw).isEmpty();

            boolean pass = bothLaunchEnvironmentsWereRead
                    && thePackageStoresAreSharedAcrossBothWorktrees && noInstallTargetIsShared
                    && aPrivateCacheIsDetected && theUnmutatedPairIsStillClean;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.caches")
                    : NodeResult.fail("ticket.lifecycle.caches",
                            "exits=" + envA.exitCode() + "/" + envB.exitCode()
                                    + " missing=" + missing
                                    + " violations=" + violations
                                    + " sharedInstallTargets=" + shared
                                    + " privateCacheDetected=" + aPrivateCacheIsDetected))
                    .process(envA).process(envB)
                    .assertion("both_worktrees_launch_environments_were_read",
                            bothLaunchEnvironmentsWereRead)
                    .assertion("the_package_stores_are_shared_across_both_live_worktrees",
                            thePackageStoresAreSharedAcrossBothWorktrees)
                    .assertion("no_install_target_is_smuggled_into_the_shared_block",
                            noInstallTargetIsShared)
                    .assertion("the_cache_oracle_detects_a_private_per_home_cache",
                            aPrivateCacheIsDetected)
                    .assertion("the_cache_oracle_leaves_the_unmutated_pair_clean",
                            theUnmutatedPairIsStillClean)
                    .metric("sharedStoresChecked", SHARED_STORES.size())
                    .log("A: " + summary(a) + "  B: " + summary(b));
        });
    }

    /**
     * Why these two environments do NOT share a package store, if they do not.
     *
     * <p>One function, used for the measurement and for its own sensitivity
     * test. Two ways to fail, both reported: the two homes name different
     * stores, or a store is named inside either home.
     */
    private static List<String> privateCaches(Map<String, String> a, Map<String, String> b,
                                              String homeA, String homeB) {
        List<String> out = new ArrayList<>();
        for (String key : SHARED_STORES) {
            String valueA = a.get(key);
            String valueB = b.get(key);
            if (valueA == null || valueB == null) {
                out.add(key + " is unset in one of the two launch environments");
                continue;
            }
            if (!valueA.equals(valueB)) out.add(key + ": " + valueA + " != " + valueB);
            if (valueA.startsWith(homeA) || valueA.startsWith(homeB)
                    || valueB.startsWith(homeA) || valueB.startsWith(homeB)) {
                out.add(key + " is private to a home: " + valueA + " / " + valueB);
            }
        }
        return out;
    }

    private static ProcessRecord printEnv(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label,
                                          String ambient, String home, String homeRoot) {
        Path pin = Path.of(home).resolve("bin").resolve("cli").resolve("skill-manager");
        return TicketLifecycleSupport.plain(ctx, label, null, ambient,
                List.of(pin.toString(), "exec", "--home", home, "--home-root", homeRoot,
                        "--print-env"));
    }

    /** {@code KEY=value} lines from {@code exec --print-env}. */
    private static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            out.put(key, line.substring(eq + 1).strip());
        }
        return out;
    }

    private static String summary(Map<String, String> env) {
        StringBuilder sb = new StringBuilder();
        for (String key : SHARED_STORES) sb.append(key).append('=').append(env.get(key)).append(' ');
        return sb.toString().strip();
    }
}
