///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../lib/SmEnv.java
//SOURCES ../../lib/RunLogText.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * End-to-end exercise of {@code skill-manager sync} for plugins:
 * simulate drift in the skill-manager-owned plugin marketplace
 * ({@code <home>/plugin-marketplace/}), then run {@code sync} and
 * assert the {@link
 * dev.skillmanager.effects.SkillEffect.RefreshHarnessPlugins} effect
 * heals the layout back to install-time invariants.
 *
 * <p>Drift simulated:
 * <ul>
 *   <li>Delete the per-plugin symlink under
 *       {@code plugin-marketplace/plugins/<name>}.</li>
 *   <li>Truncate {@code plugin-marketplace/.claude-plugin/marketplace.json}
 *       so the manifest no longer lists the umbrella plugin.</li>
 * </ul>
 *
 * <p>After {@code sync}, both must be regenerated from the live
 * installed-plugin set. Sync's CLI output must also include the
 * canonical MCP install-results JSON block — proof it re-walked the
 * gateway and re-registered every installed unit's MCP deps (parity
 * with {@link SkillSynced}).
 *
 * <h2>The two plugins in this home are the D14 discriminator</h2>
 *
 * <p>This node used to assert both plugins carried a
 * {@code NEEDS_GIT_MIGRATION} record ("outstanding errors (2)"), which was
 * asserting the defect: a store directory with no {@code .git} was an
 * outstanding error regardless of how it got that way, so a unit the operator
 * DELIBERATELY installed from a local path appended an error — with a remedy
 * that undoes their own choice — to every subsequent command, forever.
 *
 * <p>The home now holds one of each shape, and the assertion is that
 * {@code sync} tells them apart in the same run:
 *
 * <ul>
 *   <li>{@code umbrella-plugin} — {@code installSource: LOCAL_FILE}, a
 *       deliberate {@code file:} install. No error record; the provenance is
 *       still REPORTED (a {@code SyncGitLocalInstall} fact), so "this will
 *       not update" stays visible as a run-log sentence and a console
 *       count.</li>
 *   <li>{@code hello-plugin} — {@code installSource: REGISTRY}, published
 *       with {@code --upload-tarball} and therefore not git-tracked. Still an
 *       error, so sync still exits 1.</li>
 * </ul>
 *
 * <p>Both halves are asserted, and asserted on disk (the per-unit
 * {@code installed/<name>.json} records) rather than by grepping one banner:
 * "no error was recorded" is what a handler that stopped recording anything
 * would also produce, and the record files are the thing every later command
 * reads.
 *
 * <p>The per-unit provenance sentence is DEMOTED to the run log named by the
 * {@code log:} footer, so this node follows that footer through
 * {@link RunLogText#plusNamedLog} rather than asserting against the console
 * half the sentence was moved out of.
 */
public class PluginSynced {
    static final NodeSpec SPEC = NodeSpec.of("plugin.synced")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("umbrella.plugin.installed")
            .tags("plugin", "sync")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String pluginName = ctx.get("umbrella.plugin.installed", "pluginName").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null || pluginName == null) {
                return NodeResult.fail("plugin.synced", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path marketplaceRoot = Path.of(home).resolve("plugin-marketplace");
            Path manifest = marketplaceRoot.resolve(".claude-plugin").resolve("marketplace.json");
            Path symlink = marketplaceRoot.resolve("plugins").resolve(pluginName);

            // Pre: drift the marketplace by deleting the per-plugin
            // symlink AND truncating the manifest. Both should be
            // restored by sync.
            if (Files.exists(symlink, LinkOption.NOFOLLOW_LINKS)) Files.delete(symlink);
            // Truncate manifest by writing a minimal stub that omits the plugin.
            String stubManifest = "{ \"name\": \"skill-manager\", \"plugins\": [] }\n";
            Files.writeString(manifest, stubManifest);

            boolean preSymlinkGone = !Files.exists(symlink, LinkOption.NOFOLLOW_LINKS);
            boolean preManifestStubbed = Files.readString(manifest).equals(stubManifest);

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "sync");
            SmEnv.apply(ctx, pb, home);

            ProcessRecord proc = Procs.run(ctx, "sync", pb);
            int rc = proc.exitCode();
            String console = readLog(ctx, "sync");
            // THROWS if the footer names a log that is not there — see
            // RunLogText. Every absence assertion below depends on this
            // string being the whole transcript.
            String body = RunLogText.plusNamedLog(console);

            // Post: marketplace symlink + manifest entry must be back.
            boolean symlinkRestored = Files.exists(symlink, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(symlink);
            String manifestAfter = Files.readString(manifest);
            boolean manifestRestored = manifestAfter.contains("\"" + pluginName + "\"")
                    && manifestAfter.contains("\"./plugins/" + pluginName + "\"");
            // Sync must have re-walked MCP deps too (parity with SkillSynced).
            boolean mcpReRegistered = console.contains("---MCP-INSTALL-RESULTS-BEGIN---")
                    && console.contains("---MCP-INSTALL-RESULTS-END---");

            // ---- D14: the two provenances, told apart, on disk ----
            Path localRecord = Path.of(home, "installed", pluginName + ".json");
            Path registryRecord = Path.of(home, "installed", "hello-plugin.json");
            String localJson = RunLogText.read(localRecord);
            String registryJson = RunLogText.read(registryRecord);
            // Non-vacuity for the two containment checks below: an absent or
            // empty record file would satisfy "does not contain" for free.
            boolean bothRecordsReadable = localJson.contains("\"" + pluginName + "\"")
                    && registryJson.contains("\"hello-plugin\"");
            boolean localInstallIsLocalFile = localJson.contains("\"installSource\" : \"LOCAL_FILE\"");
            boolean registryInstallIsRegistry = registryJson.contains("\"installSource\" : \"REGISTRY\"");
            boolean deliberateLocalInstallCleared =
                    bothRecordsReadable && !localJson.contains("NEEDS_GIT_MIGRATION");
            boolean accidentalNonGitStillErrors =
                    bothRecordsReadable && registryJson.contains("NEEDS_GIT_MIGRATION");

            // ...and reported, rather than silently dropped: the count on the
            // console, the sentence in the run log.
            boolean localOnlyCounted = console.contains("local-only (no shared upstream)");
            boolean localProvenanceInRunLog =
                    body.contains(pluginName + ": installed from a local path");
            // One remaining outstanding error, and it is the registry one.
            boolean oneOutstandingError = body.contains("skills with outstanding errors (1)")
                    && body.contains("1 distinct cause")
                    && body.contains("hello-plugin");
            boolean expectedMigrationExit = rc == 1;

            boolean pass = preSymlinkGone && preManifestStubbed
                    && symlinkRestored && manifestRestored && mcpReRegistered
                    && expectedMigrationExit && bothRecordsReadable
                    && localInstallIsLocalFile && registryInstallIsRegistry
                    && deliberateLocalInstallCleared && accidentalNonGitStillErrors
                    && localOnlyCounted && localProvenanceInRunLog && oneOutstandingError;
            return (pass
                    ? NodeResult.pass("plugin.synced")
                    : NodeResult.fail("plugin.synced",
                            "rc=" + rc + " preSymlinkGone=" + preSymlinkGone
                                    + " preManifestStubbed=" + preManifestStubbed
                                    + " symlinkRestored=" + symlinkRestored
                                    + " manifestRestored=" + manifestRestored
                                    + " mcpReRegistered=" + mcpReRegistered
                                    + " recordsReadable=" + bothRecordsReadable
                                    + " localIsLocalFile=" + localInstallIsLocalFile
                                    + " registryIsRegistry=" + registryInstallIsRegistry
                                    + " localCleared=" + deliberateLocalInstallCleared
                                    + " registryStillErrors=" + accidentalNonGitStillErrors
                                    + " localOnlyCounted=" + localOnlyCounted
                                    + " localInRunLog=" + localProvenanceInRunLog
                                    + " oneOutstanding=" + oneOutstandingError))
                    .process(proc)
                    .assertion("marketplace_symlink_was_drifted", preSymlinkGone)
                    .assertion("marketplace_manifest_was_drifted", preManifestStubbed)
                    .assertion("marketplace_symlink_restored", symlinkRestored)
                    .assertion("marketplace_manifest_restored", manifestRestored)
                    .assertion("mcp_register_results_emitted", mcpReRegistered)
                    .assertion("both_install_records_readable", bothRecordsReadable)
                    .assertion("local_install_recorded_as_local_file", localInstallIsLocalFile)
                    .assertion("registry_install_recorded_as_registry", registryInstallIsRegistry)
                    .assertion("deliberate_local_install_records_no_error",
                            deliberateLocalInstallCleared)
                    .assertion("COMPANION_non_git_registry_install_still_errors",
                            accidentalNonGitStillErrors)
                    .assertion("local_only_counted_on_console", localOnlyCounted)
                    .assertion("local_provenance_named_in_run_log", localProvenanceInRunLog)
                    .assertion("one_outstanding_error_and_it_is_the_registry_one",
                            oneOutstandingError)
                    .assertion("sync_exit_one_for_remaining_migration", expectedMigrationExit)
                    .metric("exitCode", rc);
        });
    }

    private static String readLog(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }
}
