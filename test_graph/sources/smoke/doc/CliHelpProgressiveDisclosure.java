///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end help-output contract for progressive disclosure.
 */
public class CliHelpProgressiveDisclosure {
    static final NodeSpec SPEC = NodeSpec.of("cli.help.progressive")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "cli.metadata.catalog.covered")
            .tags("cli", "help", "progressive-disclosure")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("cli.help.progressive", "missing env.prepared.home");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessRecord root = run(ctx, "root-help", home, repoRoot, sm, "--help");
            String rootOut = readLog(ctx, "root-help");
            boolean rootRc = root.exitCode() == 0;
            boolean rootListsCommands = rootOut.contains("install") && rootOut.contains("sync")
                    && rootOut.contains("project");
            boolean rootOmitsDeepInstall = !rootOut.contains("What install does:")
                    && !rootOut.contains("Plugin vs skill detection");
            boolean rootOmitsDeepSync = !rootOut.contains("Sync modes:");
            boolean rootOmitsExamples = !rootOut.contains("Examples:");
            boolean rootBounded = rootOut.lines().count() < 80;

            ProcessRecord install = run(ctx, "install-help", home, repoRoot, sm, "install", "--help");
            String installOut = readLog(ctx, "install-help");
            boolean installHelp = install.exitCode() == 0
                    && installOut.contains("What install does:")
                    && installOut.contains("--force-scripts")
                    && !installOut.contains("Missing required parameter");

            ProcessRecord sync = run(ctx, "sync-help", home, repoRoot, sm, "sync", "--help");
            String syncOut = readLog(ctx, "sync-help");
            boolean syncHelp = sync.exitCode() == 0
                    && syncOut.contains("Sync modes:")
                    && syncOut.contains("--force-scripts")
                    && !syncOut.contains("Unknown option");

            ProcessRecord nested = run(ctx, "profiles-help", home, repoRoot, sm,
                    "project", "profiles", "list", "--help");
            String nestedOut = readLog(ctx, "profiles-help");
            boolean nestedHelp = nested.exitCode() == 0
                    && nestedOut.contains("List profiles declared in skill-project.toml.")
                    && !nestedOut.contains("Unknown option");

            boolean pass = rootRc && rootListsCommands && rootOmitsDeepInstall
                    && rootOmitsDeepSync && rootOmitsExamples && rootBounded
                    && installHelp && syncHelp && nestedHelp;
            return (pass
                    ? NodeResult.pass("cli.help.progressive")
                    : NodeResult.fail("cli.help.progressive",
                            "rootRc=" + rootRc
                                    + " rootListsCommands=" + rootListsCommands
                                    + " rootOmitsDeepInstall=" + rootOmitsDeepInstall
                                    + " rootOmitsDeepSync=" + rootOmitsDeepSync
                                    + " rootOmitsExamples=" + rootOmitsExamples
                                    + " rootBounded=" + rootBounded
                                    + " installHelp=" + installHelp
                                    + " syncHelp=" + syncHelp
                                    + " nestedHelp=" + nestedHelp))
                    .process(root)
                    .process(install)
                    .process(sync)
                    .process(nested)
                    .assertion("root_help_exit_zero", rootRc)
                    .assertion("root_help_lists_top_level_commands", rootListsCommands)
                    .assertion("root_help_omits_install_details", rootOmitsDeepInstall)
                    .assertion("root_help_omits_sync_details", rootOmitsDeepSync)
                    .assertion("root_help_omits_examples", rootOmitsExamples)
                    .assertion("root_help_line_count_bounded", rootBounded)
                    .assertion("install_help_direct_and_complete", installHelp)
                    .assertion("sync_help_direct_and_complete", syncHelp)
                    .assertion("nested_help_direct", nestedHelp)
                    .metric("rootHelpLines", rootOut.lines().count());
        });
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }
}
