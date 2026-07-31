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
 * The ROOT home for the three-tier graph: the one an operator installs into.
 *
 * <h2>Installed by the real CLI, not scaffolded</h2>
 *
 * <p>The units are put here with {@code skill-manager install}, so the home
 * carries real installed state ({@code installed/*.json},
 * {@code installed/*.projections.json}, agent projections) rather than three
 * directories a test wrote. That matters for one specific claim this graph
 * makes: {@code home sync} reconciles <em>unit directories</em> and nothing
 * else, so a home reconciled into is not the same thing as a home installed
 * into. Asserting that against a hand-scaffolded home would be asserting it
 * against a home that had no state to leave behind in the first place.
 *
 * <p>One unit ({@code hs-alpha}) carries a nested subdirectory, because a
 * three-way merge that only ever sees top-level files would pass every
 * assertion in this graph while being unable to merge a real skill — whose
 * content lives under {@code references/} and {@code scripts/}.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>No git-backed unit. A unit installed from a git source keeps its
 * {@code .git} directory in the home, and a file-copy reconcile of one has its
 * own behaviour worth measuring on its own terms — {@code home.sync.git.flawless}
 * builds that fixture separately so the ordinary copy path is not measured
 * through fifty churning git objects.
 */
public class HomeSyncFixtureBuilt {

    static final String ALPHA = "hs-alpha";
    static final String BETA = "hs-beta";
    static final String GAMMA = "hs-gamma";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.fixture.built")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("home-sync", "fixture")
            .timeout("300s")
            .output("workspace", "string")
            .output("rootHome", "string")
            .output("projectHome", "string")
            .output("worktreeHome", "string")
            .output("sourcesDir", "string")
            .output("ambientHome", "string")
            .output("rootDigest", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (sandbox == null) {
                return NodeResult.fail("home.sync.fixture.built", "missing env.prepared.home");
            }
            Path workspace = Path.of(sandbox, "home-sync");
            Path sources = workspace.resolve("sources");
            Path rootHome = workspace.resolve("root");
            Path projectDir = workspace.resolve("project");
            Path worktreeDir = workspace.resolve("worktree");
            Files.createDirectories(sources);
            Files.createDirectories(projectDir);
            Files.createDirectories(worktreeDir);
            // A home that is neither end of any reconcile. Every skill-manager
            // command runs a reconcile pass against $SKILL_MANAGER_HOME before
            // it does anything else, and that pass writes — it adopts unit
            // directories it has not seen into the installed ledger. Pointing
            // the ambient home at a third, empty home is what lets the "the
            // source home is only read" assertions be about `home sync` rather
            // than about the CLI's own housekeeping.
            Path ambient = workspace.resolve("ambient");
            Files.createDirectories(ambient);

            HomeSyncSupport.mkSource(sources, ALPHA, "alpha v1");
            HomeSyncSupport.write(sources.resolve(ALPHA).resolve("references/deep/note.md"),
                    "nested reference v1\n");
            HomeSyncSupport.mkSource(sources, BETA, "beta v1");
            HomeSyncSupport.mkSource(sources, GAMMA, "gamma v1");

            ProcessRecord installAlpha = HomeSyncSupport.sm(ctx, "install-alpha", rootHome.toString(),
                    "install", sources.resolve(ALPHA).toString(), "--yes");
            ProcessRecord installBeta = HomeSyncSupport.sm(ctx, "install-beta", rootHome.toString(),
                    "install", sources.resolve(BETA).toString(), "--yes");
            ProcessRecord installGamma = HomeSyncSupport.sm(ctx, "install-gamma", rootHome.toString(),
                    "install", sources.resolve(GAMMA).toString(), "--yes");

            boolean installed = installAlpha.exitCode() == 0 && installBeta.exitCode() == 0
                    && installGamma.exitCode() == 0
                    && Files.isRegularFile(HomeSyncSupport.unitDir(rootHome, ALPHA)
                            .resolve("references/deep/note.md"))
                    && Files.isRegularFile(HomeSyncSupport.unitDir(rootHome, BETA)
                            .resolve("SKILL.md"))
                    && Files.isRegularFile(HomeSyncSupport.unitDir(rootHome, GAMMA)
                            .resolve("SKILL.md"));

            // A root home installed into has no materialization records at all:
            // records describe a tree that was materialized FROM somewhere, and
            // nothing materialized this one. That absence is the reason the
            // upward direction needs --merge, so it is stated here rather than
            // discovered as a surprise three nodes later.
            boolean rootHasNoRecords = !Files.isDirectory(
                    rootHome.resolve(".materialization").resolve("skill"));

            // Real installed state, which `home sync` will be shown NOT to carry.
            boolean rootHasInstalledState = Files.isDirectory(rootHome.resolve("installed"))
                    && !HomeSyncSupport.names(rootHome.resolve("installed")).isEmpty();

            String rootDigest = HomeSyncSupport.treeDigest(HomeSyncSupport.skills(rootHome));

            boolean pass = installed && rootHasNoRecords && rootHasInstalledState;
            return (pass
                    ? NodeResult.pass("home.sync.fixture.built")
                    : NodeResult.fail("home.sync.fixture.built",
                            "installed=" + installed
                                    + " rootHasNoRecords=" + rootHasNoRecords
                                    + " rootHasInstalledState=" + rootHasInstalledState
                                    + " exits=" + installAlpha.exitCode() + "/"
                                    + installBeta.exitCode() + "/" + installGamma.exitCode()))
                    .process(installAlpha).process(installBeta).process(installGamma)
                    .assertion("root_home_units_installed_by_the_real_cli", installed)
                    .assertion("a_root_home_carries_no_materialization_record", rootHasNoRecords)
                    .assertion("a_root_home_carries_real_installed_state", rootHasInstalledState)
                    .metric("rootUnits", HomeSyncSupport.names(
                            HomeSyncSupport.skills(rootHome)).size())
                    .publish("workspace", workspace.toString())
                    .publish("rootHome", rootHome.toString())
                    .publish("projectHome", projectDir.resolve(".skill-manager").toString())
                    .publish("worktreeHome", worktreeDir.resolve(".skill-manager").toString())
                    .publish("sourcesDir", sources.toString())
                    .publish("ambientHome", ambient.toString())
                    .publish("rootDigest", rootDigest);
        });
    }
}
