///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java
//SOURCES ../tripwire/TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The three tiers this graph needs before a ticket can exist: a root home, a
 * checkout, and that checkout's project home — built by the real CLI and the
 * real {@code bootstrap-home.sh}, never scaffolded.
 *
 * <h2>Every setup command's exit status is asserted, not just its effect</h2>
 *
 * <p>This is issue #135's lesson stated as a rule. {@code HomeSyncPermutations}
 * froze a path that was never a home and ignored the exit code that said so;
 * its later assertions then measured a destination in a state it had never
 * reached, and the node looked green-adjacent for a long time. So each install,
 * each git command and the bootstrap are checked for exit 0 <em>and</em> for the
 * artefact they were supposed to leave, and the node reports both.
 *
 * <h2>The sandbox is the whole point of the fixture</h2>
 *
 * <p>{@code $HOME} for every child in this graph is {@code env.prepared}'s temp
 * root, so {@code bootstrap-home.sh}'s {@code GLOBAL_HOME} — the home it
 * refuses to write — resolves to {@code <sandbox>/.skill-manager}. That path is
 * published here and asserted absent by
 * {@code ticket.lifecycle.global.home.untouched} at the end of the run, which
 * is a positive statement about where the workflow's writes went rather than a
 * hope that they went nowhere.
 *
 * <h2>Three units, because two tickets need a shared one and one each</h2>
 *
 * <p>{@code tl-shared} is edited by both tickets and is where the conflict
 * comes from; {@code tl-x} and {@code tl-y} are edited by one ticket each and
 * are what proves the conflict did not stop the rest of the reconcile. The
 * shared unit carries a {@code references/} subdirectory because a home whose
 * units are all single-file would pass every assertion here while being unable
 * to carry a real skill.
 */
public class TicketLifecycleFixtureBuilt {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.fixture.built")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("ticket-lifecycle", "fixture")
            .timeout("600s")
            .output("workspace", "string")
            .output("rootHome", "string")
            .output("checkout", "string")
            .output("projectHome", "string")
            .output("ambientHome", "string")
            .output("sourcesDir", "string")
            .output("scriptsDir", "string")
            .output("sandboxGlobalHome", "string")
            .output("leakBaseline", "string")
            .output("leakRoots", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (sandbox == null) {
                return NodeResult.fail("ticket.lifecycle.fixture.built",
                        "missing env.prepared.home");
            }

            TicketLifecycleSupport.Scripts scripts = TicketLifecycleSupport.scripts(ctx);
            if (!scripts.found()) {
                // A refusal, not a skip. The scripts ARE the subject: a run that
                // could not find them measured nothing, and reporting that as a
                // pass (or as a skip that a tally reads as "fine") is the exact
                // shape this issue was filed about.
                return NodeResult.fail("ticket.lifecycle.fixture.built",
                        "could not locate git-integration-repo's scripts — " + scripts.how()
                                + ". Set $" + TicketLifecycleSupport.SCRIPTS_ENV
                                + " to <git-integration-repo>/scripts and re-run.")
                        .assertion("the_integration_scripts_under_test_were_found", false);
            }

            Path workspace = Path.of(sandbox, "ticket-lifecycle");
            Path sources = workspace.resolve("sources");
            Path rootHome = workspace.resolve("root-home");
            Path checkout = workspace.resolve("proj");
            Path ambient = workspace.resolve("ambient");
            Files.createDirectories(sources);
            Files.createDirectories(checkout);
            Files.createDirectories(ambient);

            // --- the units ------------------------------------------------
            TicketLifecycleSupport.mkSource(sources, TicketLifecycleSupport.SHARED, "shared v1");
            Files.createDirectories(sources.resolve(TicketLifecycleSupport.SHARED)
                    .resolve("references"));
            Files.writeString(sources.resolve(TicketLifecycleSupport.SHARED)
                    .resolve("references/page.md"), "shared reference v1\n");
            TicketLifecycleSupport.mkSource(sources, TicketLifecycleSupport.UNIT_A, "x v1");
            TicketLifecycleSupport.mkSource(sources, TicketLifecycleSupport.UNIT_B, "y v1");

            // --- the root home, installed by the real CLI -------------------
            List<ProcessRecord> installs = new ArrayList<>();
            for (String unit : List.of(TicketLifecycleSupport.SHARED,
                    TicketLifecycleSupport.UNIT_A, TicketLifecycleSupport.UNIT_B)) {
                installs.add(TicketLifecycleSupport.sm(ctx, "install-" + unit, rootHome.toString(),
                        "install", sources.resolve(unit).toString(), "--yes"));
            }
            boolean installsExitedZero = installs.stream().allMatch(p -> p.exitCode() == 0);
            boolean rootHomeHasTheUnits = Files.isRegularFile(
                        TicketLifecycleSupport.unitDir(rootHome, TicketLifecycleSupport.SHARED)
                                .resolve("references/page.md"))
                    && Files.isRegularFile(
                        TicketLifecycleSupport.unitDir(rootHome, TicketLifecycleSupport.UNIT_A)
                                .resolve("SKILL.md"))
                    && Files.isRegularFile(
                        TicketLifecycleSupport.unitDir(rootHome, TicketLifecycleSupport.UNIT_B)
                                .resolve("SKILL.md"));

            // --- the checkout ------------------------------------------------
            //
            // A real git repository, with the home paths gitignored and that
            // .gitignore COMMITTED. new-change.sh calls assert_parent_clean, so
            // a fixture that left `.skill-manager/` untracked would be testing
            // the cleanliness refusal instead of the workflow — and would look
            // like a defect in the script rather than in the fixture.
            List<String> gitFailures = new ArrayList<>();
            git(gitFailures, checkout, "init", "-q", "-b", "main");
            Files.writeString(checkout.resolve(".gitignore"),
                    ".skill-manager/\n.claude/\n.codex/\n.gemini/\n");
            Files.writeString(checkout.resolve("README.md"), "ticket-lifecycle fixture checkout\n");
            git(gitFailures, checkout, "config", "user.email", "graph@localhost");
            git(gitFailures, checkout, "config", "user.name", "graph");
            git(gitFailures, checkout, "add", "-A");
            git(gitFailures, checkout, "commit", "-qm", "fixture checkout");
            boolean checkoutIsAGitRepo = gitFailures.isEmpty()
                    && HomeSyncSupport.git(checkout, "rev-parse", "HEAD").ok();
            boolean checkoutIsClean =
                    HomeSyncSupport.git(checkout, "status", "--porcelain").trimmed().isEmpty();

            // --- the project home, through the script under test -------------
            ProcessRecord bootstrap = TicketLifecycleSupport.script(ctx, "bootstrap-project-home",
                    checkout, scripts.of("bootstrap-home.sh"), ambient.toString(),
                    "--root", checkout.toString(), "--source", rootHome.toString());
            Path projectHome = checkout.resolve(".skill-manager");
            boolean projectHomeBootstrapped = bootstrap.exitCode() == 0
                    && Files.isRegularFile(projectHome.resolve("home.runtime.json"))
                    && Files.isDirectory(TicketLifecycleSupport
                            .unitDir(projectHome, TicketLifecycleSupport.SHARED));
            // A clone, not a symlink or a bind: the three tiers have to be
            // distinguishable on disk before anything downstream can claim that
            // an edit stayed in one of them.
            boolean projectHomeIsItsOwnCopy =
                    TicketLifecycleSupport.inode(projectHome) > 0
                            && TicketLifecycleSupport.inode(projectHome)
                                    != TicketLifecycleSupport.inode(rootHome);
            // The bootstrap must not have dirtied the checkout — the home is
            // gitignored, and an agent's work being invisible to the parent diff
            // is the property the whole close-out gate exists to compensate for.
            boolean bootstrapLeftTheCheckoutClean =
                    HomeSyncSupport.git(checkout, "status", "--porcelain").trimmed().isEmpty();

            Path sandboxGlobalHome = Path.of(sandbox).resolve(".skill-manager");
            boolean noGlobalHomeYet = !Files.exists(sandboxGlobalHome);

            // --- arm the leak oracle -----------------------------------------
            //
            // The operator's four real agent homes, snapshotted at METADATA
            // fidelity BEFORE the workflow runs. Read-only, and taken here
            // rather than in the node that checks it because a baseline taken
            // afterwards can only ever agree with itself.
            //
            // Roots are published rather than re-derived downstream: a checker
            // that recomputed "which roots exist" would compare a home that
            // appeared during the run against nothing at all, and report the
            // most interesting possible finding as zero differences.
            Path leakBaseline = ctx.reportDir().resolve("ticket-lifecycle.leak-baseline.txt");
            List<Path> leakRoots = new ArrayList<>();
            String leakBaselineError = null;
            try {
                Path realHome = TripwireSupport.realHome();
                leakRoots = TripwireSupport.presentRoots(realHome);
                TripwireSupport.writeLines(leakBaseline,
                        TripwireSupport.collectAll(leakRoots, realHome,
                                TripwireSupport.Fidelity.METADATA));
            } catch (RuntimeException e) {
                leakBaselineError = String.valueOf(e.getMessage());
            }
            boolean theLeakOracleIsArmed = leakBaselineError == null && !leakRoots.isEmpty()
                    && Files.isRegularFile(leakBaseline);

            boolean pass = installsExitedZero && rootHomeHasTheUnits && checkoutIsAGitRepo
                    && checkoutIsClean && projectHomeBootstrapped && projectHomeIsItsOwnCopy
                    && bootstrapLeftTheCheckoutClean && noGlobalHomeYet && theLeakOracleIsArmed;

            NodeResult result = pass
                    ? NodeResult.pass("ticket.lifecycle.fixture.built")
                    : NodeResult.fail("ticket.lifecycle.fixture.built",
                            "installs=" + installs.stream().map(ProcessRecord::exitCode).toList()
                                    + " rootHomeHasTheUnits=" + rootHomeHasTheUnits
                                    + " gitFailures=" + gitFailures
                                    + " checkoutIsClean=" + checkoutIsClean
                                    + " bootstrapExit=" + bootstrap.exitCode()
                                    + " projectHomeBootstrapped=" + projectHomeBootstrapped
                                    + " projectHomeIsItsOwnCopy=" + projectHomeIsItsOwnCopy
                                    + " noGlobalHomeYet=" + noGlobalHomeYet
                                    + " leakBaseline=" + leakBaselineError);
            for (ProcessRecord p : installs) result = result.process(p);
            return result.process(bootstrap)
                    .assertion("the_integration_scripts_under_test_were_found", true)
                    .assertion("every_install_into_the_root_home_exited_zero", installsExitedZero)
                    .assertion("the_root_home_holds_all_three_units_including_a_nested_file",
                            rootHomeHasTheUnits)
                    .assertion("the_checkout_is_a_real_git_repository", checkoutIsAGitRepo)
                    .assertion("the_checkout_is_clean_so_new_change_will_not_refuse_it",
                            checkoutIsClean)
                    .assertion("bootstrap_home_sh_gave_the_checkout_a_project_home",
                            projectHomeBootstrapped)
                    .assertion("the_project_home_is_its_own_copy_of_the_root_home",
                            projectHomeIsItsOwnCopy)
                    .assertion("bootstrapping_a_home_leaves_the_checkout_clean",
                            bootstrapLeftTheCheckoutClean)
                    .assertion("no_global_home_exists_in_the_sandbox_yet", noGlobalHomeYet)
                    .assertion("the_leak_oracle_is_armed_over_the_operators_real_homes",
                            theLeakOracleIsArmed)
                    .metric("rootUnits", HomeSyncSupport.names(rootHome.resolve("skills")).size())
                    .log("scripts: " + scripts.how())
                    .publish("workspace", workspace.toString())
                    .publish("rootHome", rootHome.toString())
                    .publish("checkout", checkout.toString())
                    .publish("projectHome", projectHome.toString())
                    .publish("ambientHome", ambient.toString())
                    .publish("sourcesDir", sources.toString())
                    .publish("scriptsDir", scripts.dir().toString())
                    .publish("sandboxGlobalHome", sandboxGlobalHome.toString())
                    .publish("leakBaseline", leakBaseline.toString())
                    .publish("leakRoots", leakRoots.stream().map(Path::toString)
                            .reduce((a, b) -> a + java.io.File.pathSeparator + b).orElse(""));
        });
    }

    /** Run git and record the command when it fails, so setup cannot fail silently. */
    private static void git(List<String> failures, Path dir, String... args) {
        HomeSyncSupport.Capture capture = HomeSyncSupport.git(dir, args);
        if (!capture.ok()) failures.add(String.join(" ", args) + " -> " + capture.trimmed());
    }
}
