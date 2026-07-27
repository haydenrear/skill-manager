///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../home-clone/HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The launch-environment half of the per-checkout home contract, formerly the
 * back half of {@code assert-home.sh}.
 *
 * <p>The epic's central claim is that a home override actually holds. The
 * environment a launch produces is where that claim is either true or not, and
 * {@code exec --print-env} is the one place it can be read without launching an
 * agent.
 *
 * <h2>Foreign-home stripping is asserted at two depths, deliberately</h2>
 *
 * {@code LaunchEnv.isForeignHomeBin} walks UP from a PATH entry looking for a
 * store root, bounded to three levels because "{@code <store>/bin},
 * {@code <store>/bin/cli}, {@code <store>/bin/mcp}, {@code <store>/bin/launch}
 * — three levels is the whole shape". That enumeration is correct for the
 * shapes it imagined and misses one that exists:
 * {@code <store>/plugin-marketplace/plugins/<name>/bin} sits FOUR levels below
 * the store root, so the walk gives up before it recognises the store and the
 * foreign home's plugin bin survives on every launch PATH.
 *
 * <p>"Enumeration correct for imagined shapes" is a named recurring failure in
 * this epic — a skip list of directory names that missed a cache file, a shim
 * relativizer that assumed symlinks and met scripts, a binary sniffer reading
 * only the first 8 KB. The lesson recorded against it was: for every list of
 * names, ask what shape is missing. This node asks with an assertion.
 *
 * <p>Both depths are asserted, and the shallow one is the control. If only the
 * deep assertion existed and the whole stripping mechanism regressed, the deep
 * case would still "fail correctly" and nobody would learn that the shallow case
 * had broken too.
 */
public class CheckoutHomeLaunchIsolated {
    static final NodeSpec SPEC = NodeSpec.of("checkout.home.launch.isolated")
            .kind(NodeSpec.Kind.ASSERTION)
            // Deliberately NOT dependent on checkout.home.contract. The two
            // nodes assert independent facts about the same home, and chaining
            // them means one open defect hides the other's verdict — which is
            // how "16 of 20 graphs were unverified" happened in this epic.
            .dependsOn("home.cloned.into.project")
            .tags("checkout-home", "launch", "isolation")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String store = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String projectDir = ctx.get("home.clone.fixture.built", "projectDir").orElse(null);
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (store == null || projectDir == null || sandbox == null) {
                return NodeResult.fail("checkout.home.launch.isolated", "missing upstream context");
            }

            Path shallowForeignBin;
            Path deepForeignBin;
            try {
                Path foreign = Files.createTempDirectory("foreign-home-").resolve(".skill-manager");
                // looksLikeStoreRoot() recognises a directory by the
                // installed/ + skills/ pair, so the decoy needs both to be a
                // store at all. Matching on the literal name .skill-manager
                // would miss a home cloned to a differently named directory,
                // which is the normal case for a per-project home.
                Files.createDirectories(foreign.resolve("installed"));
                Files.createDirectories(foreign.resolve("skills"));
                shallowForeignBin = foreign.resolve("bin/cli");
                deepForeignBin = foreign.resolve("plugin-marketplace/plugins/demo-plugin/bin");
                Files.createDirectories(shallowForeignBin);
                Files.createDirectories(deepForeignBin);
            } catch (Exception e) {
                return NodeResult.error("checkout.home.launch.isolated", e);
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize()
                    .toAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                    repoRoot.resolve("skill-manager").toString(),
                    "exec", "--home", store, "--no-reconcile", "--ack-drift", "--print-env");
            pb.environment().put("SKILL_MANAGER_HOME", store);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("HOME", sandbox);
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + sandbox);
            // Both foreign bins lead the inherited PATH. Anything that survives
            // into the launch PATH did so because stripping did not recognise it.
            pb.environment().put("PATH",
                    shallowForeignBin + ":" + deepForeignBin + ":" + System.getenv("PATH"));

            ProcessRecord proc = Procs.run(ctx, "print-env", pb);
            boolean printEnvExitsZero = proc.exitCode() == 0;
            String out = HomeCloneSupport.log(ctx, "print-env");

            String smHome = envValue(out, "SKILL_MANAGER_HOME");
            String claudeConfigDir = envValue(out, "CLAUDE_CONFIG_DIR");
            String claudeHome = envValue(out, "CLAUDE_HOME");
            String launchPath = envValue(out, "PATH");
            List<String> entries = launchPath.isBlank()
                    ? List.of() : Arrays.asList(launchPath.split(":"));

            boolean skillManagerHomeIsThisCheckouts = store.equals(smHome);
            boolean claudeConfigDirIsInsideTheCheckout =
                    !claudeConfigDir.isBlank() && claudeConfigDir.startsWith(projectDir);
            boolean claudeConfigDirIsNotTheRealOne = !claudeConfigDir.isBlank()
                    && !claudeConfigDir.equals(
                            Path.of(System.getProperty("user.home")).resolve(".claude").toString());
            boolean claudeHomeAgreesWithClaudeConfigDir =
                    !claudeHome.isBlank() && claudeHome.equals(claudeConfigDir);

            boolean aForeignHomesBinIsStripped = !entries.contains(shallowForeignBin.toString());
            boolean aForeignHomesPluginBinIsStripped = !entries.contains(deepForeignBin.toString());

            boolean pass = printEnvExitsZero && skillManagerHomeIsThisCheckouts
                    && claudeConfigDirIsInsideTheCheckout && claudeConfigDirIsNotTheRealOne
                    && claudeHomeAgreesWithClaudeConfigDir
                    && aForeignHomesBinIsStripped && aForeignHomesPluginBinIsStripped;

            return (pass
                    ? NodeResult.pass("checkout.home.launch.isolated")
                    : NodeResult.fail("checkout.home.launch.isolated",
                            "exit=" + proc.exitCode()
                                    + " SKILL_MANAGER_HOME=[" + smHome + "] expected=[" + store + "]"
                                    + " CLAUDE_CONFIG_DIR=[" + claudeConfigDir + "]"
                                    + " CLAUDE_HOME=[" + claudeHome + "]"
                                    + " shallowForeignSurvived=" + entries.contains(shallowForeignBin.toString())
                                    + " deepForeignSurvived=" + entries.contains(deepForeignBin.toString())))
                    .assertion("print_env_exits_zero", printEnvExitsZero)
                    .assertion("skill_manager_home_is_this_checkouts", skillManagerHomeIsThisCheckouts)
                    .assertion("claude_config_dir_is_inside_the_checkout",
                            claudeConfigDirIsInsideTheCheckout)
                    .assertion("claude_config_dir_is_not_the_operators_real_one",
                            claudeConfigDirIsNotTheRealOne)
                    .assertion("claude_home_agrees_with_claude_config_dir",
                            claudeHomeAgreesWithClaudeConfigDir)
                    .assertion("a_foreign_homes_bin_is_stripped_from_the_launch_path",
                            aForeignHomesBinIsStripped)
                    .assertion("a_foreign_homes_plugin_bin_is_stripped_from_the_launch_path",
                            aForeignHomesPluginBinIsStripped)
                    .metric("launchPathEntries", entries.size())
                    .process(proc);
        });
    }

    /** Value of {@code NAME=VALUE} in {@code exec --print-env} output. */
    private static String envValue(String out, String name) {
        String prefix = name + "=";
        String found = "";
        for (String line : out.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(prefix)) found = trimmed.substring(prefix.length());
        }
        return found;
    }
}
