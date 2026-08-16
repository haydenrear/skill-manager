///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provision a home the way an operator does, so the rest of the graph has a
 * real subject rather than a mock of one.
 *
 * <h2>Why this is a real install and not a hand-built directory</h2>
 *
 * <p>Every invariant in this graph is a relation between two things the
 * <em>product</em> wrote: a record and a checkout, a lock row and a manifest, a
 * projection and a child-home registration. A hand-built home would let this
 * graph assert that my idea of a home is self-consistent, which is not a claim
 * anybody needs. So the fixture runs the repo's own CLI, and the invariants are
 * then checked against whatever it actually produced.
 *
 * <p>Two units, not one. Several of these checks report per-subject, and a
 * one-unit home cannot distinguish "this check found the one bad row" from
 * "this check reports every row". The second unit is the control that makes the
 * damaged-fixture nodes downstream mean something.
 *
 * <p>Both units are installed from local git repositories with a local bare
 * remote. That is required, not decorative: {@code RecordAgreesWithStore} and
 * {@code UpstreamTracksWhatSyncFetched} relate an {@code installed/<u>.json}
 * record to a real checkout and a real tracking ref, and a unit installed from
 * a plain directory has neither. Keeping the remote local also keeps the whole
 * graph network-free — #113 established that a hosted runner reaching the
 * network is not something this repo may assume.
 *
 * <p>The unit that declares a CLI dependency declares a {@code skill-script}
 * one. It is the only backend that installs into the home unconditionally;
 * {@code brew}, {@code npm} and {@code pip} would either find the tool already
 * on the machine's PATH — which is defect 7's whole subject and would make the
 * fixture depend on what the runner happens to have installed — or go to the
 * network.
 */
public class HomeIntegrityFixture {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.fixture")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("home", "integrity", "fixture")
            .timeout("600s")
            .output("integrityHome", "string")
            .output("unitsDir", "string")
            .output("scratchRoot", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail("home.integrity.fixture",
                        "missing env.prepared context")
                        .assertion("both_fixture_units_installed", false)
                        .assertion("the_home_records_a_git_hash_for_each_unit", false)
                        .assertion("the_declared_cli_dependency_produced_a_shim", false)
                        .assertion("the_lock_row_carries_an_install_fingerprint", false);
            }

            Path root = Path.of(envHome).resolve("home-integrity");
            Path unitsDir = root.resolve("units");
            Path home = root.resolve("home");
            Path scratch = root.resolve("scratch");

            ProcessRecord installA;
            ProcessRecord installB;
            try {
                Files.createDirectories(unitsDir);
                Files.createDirectories(home);
                Files.createDirectories(scratch);

                // The install-confirmation gates. EnvPrepared writes a permissive
                // policy at the run's temp ROOT; this home is a subdirectory of
                // that root, so it does not inherit it and would otherwise block
                // on a prompt in a non-interactive context — the documented
                // first failure mode for this suite.
                Files.writeString(home.resolve("policy.toml"), """
                        # home-integrity fixture policy. Mirrors EnvPrepared's:
                        # tests run unattended, so every install gate is relaxed
                        # here and nowhere near a production default.
                        require_confirmation = false
                        [install]
                        require_confirmation_for_hooks = false
                        require_confirmation_for_mcp = false
                        require_confirmation_for_cli_deps = false
                        require_confirmation_for_executable_commands = false
                        """);

                Path unitA = HomeIntegritySupport.scaffoldGitUnit(
                        unitsDir, HomeIntegritySupport.UNIT, true);
                Path unitB = HomeIntegritySupport.scaffoldGitUnit(
                        unitsDir, HomeIntegritySupport.UNIT_B, false);

                installA = HomeIntegritySupport.sm(ctx, "install-a", home,
                        "install", unitA.toString(), "--yes");
                installB = HomeIntegritySupport.sm(ctx, "install-b", home,
                        "install", unitB.toString(), "--yes");
            } catch (IOException e) {
                return NodeResult.error("home.integrity.fixture", e)
                        .assertion("both_fixture_units_installed", false)
                        .assertion("the_home_records_a_git_hash_for_each_unit", false)
                        .assertion("the_declared_cli_dependency_produced_a_shim", false)
                        .assertion("the_lock_row_carries_an_install_fingerprint", false);
            }

            boolean installsOk = installA.exitCode() == 0 && installB.exitCode() == 0;

            var records = HomeIntegrity.installedRecords(home);
            boolean bothRecorded = records.containsKey(HomeIntegritySupport.UNIT)
                    && records.containsKey(HomeIntegritySupport.UNIT_B);
            boolean bothGitBacked = bothRecorded
                    && records.values().stream()
                            .allMatch(r -> HomeIntegrity.text(r, "gitHash") != null);
            boolean bothHaveStores = bothRecorded
                    && records.keySet().stream()
                            .allMatch(u -> HomeIntegrity.storeOf(home, u) != null);

            Path shim = home.resolve("bin").resolve("cli").resolve(HomeIntegritySupport.TOOL);
            boolean shimBuilt = Files.isExecutable(shim);

            var lockRow = HomeIntegrity.readToml(home.resolve("cli-lock.toml"));
            boolean fingerprinted = lockRow != null
                    && lockRow.path("skill-script").path(HomeIntegritySupport.TOOL)
                            .path("install_fingerprint").isTextual();

            boolean pass = installsOk && bothRecorded && bothGitBacked && bothHaveStores
                    && shimBuilt && fingerprinted;

            NodeResult result = pass
                    ? NodeResult.pass("home.integrity.fixture")
                    : NodeResult.fail("home.integrity.fixture",
                            "installA=" + installA.exitCode() + " installB=" + installB.exitCode()
                                    + " bothRecorded=" + bothRecorded
                                    + " bothGitBacked=" + bothGitBacked
                                    + " bothHaveStores=" + bothHaveStores
                                    + " shimBuilt=" + shimBuilt
                                    + " fingerprinted=" + fingerprinted
                                    + " units=" + records.keySet());

            return result
                    .process(installA).process(installB)
                    .assertion("both_fixture_units_installed", installsOk && bothRecorded)
                    .assertion("the_home_records_a_git_hash_for_each_unit", bothGitBacked)
                    .assertion("each_unit_has_a_real_store_checkout", bothHaveStores)
                    .assertion("the_declared_cli_dependency_produced_a_shim", shimBuilt)
                    .assertion("the_lock_row_carries_an_install_fingerprint", fingerprinted)
                    .metric("unitsInstalled", records.size())
                    .log(HomeIntegrity.describe(HomeIntegrity.all(home)))
                    .publish("integrityHome", home.toString())
                    .publish("unitsDir", unitsDir.toString())
                    .publish("scratchRoot", scratch.toString());
        });
    }
}
