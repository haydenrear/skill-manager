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
 * <h2>The two plugins in this home, and what HP-11 changed</h2>
 *
 * <p>This node used to assert both plugins carried a
 * {@code NEEDS_GIT_MIGRATION} record ("outstanding errors (2)"), which was
 * asserting the defect: a store directory with no {@code .git} was an
 * outstanding error regardless of how it got that way, so a unit the operator
 * DELIBERATELY installed from a local path appended an error — with a remedy
 * that undoes their own choice — to every subsequent command, forever (D14).
 *
 * <p>Until HP-11 (#426) the home held one of each shape: {@code umbrella-plugin}
 * from a {@code file:} install, and {@code hello-plugin} published to a per-run
 * registry server with {@code --upload-tarball} (not git-tracked, so still an
 * error). The registry server is not how skill-manager is used and the graph no
 * longer boots it, so {@code hello-plugin} now arrives by {@code file:} too.
 * That loses the COMPANION arm (an accidental non-git install still errors);
 * it was only reachable through the server and is recorded as dropped.
 *
 * <p>What is asserted now, on disk (the per-unit {@code installed/<name>.json}
 * records) and not by grepping one banner:
 *
 * <ul>
 *   <li>both records say {@code installSource: LOCAL_FILE} and neither carries
 *       {@code NEEDS_GIT_MIGRATION};</li>
 *   <li>no outstanding-errors banner, and sync's only non-zero cause is
 *       exit 11 for the deliberately broken {@code imports-*} probe skills
 *       sibling nodes install into this home;</li>
 *   <li>the provenance is still REPORTED for BOTH units (a
 *       {@code SyncGitLocalInstall} fact per unit in the run log, and the
 *       console count), which is the non-vacuity control: a handler that
 *       stopped recording anything would not name either unit.</li>
 * </ul>
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

            // ---- D14: two deliberate local installs, both cleared, on disk ----
            Path localRecord = Path.of(home, "installed", pluginName + ".json");
            Path helloRecord = Path.of(home, "installed", "hello-plugin.json");
            String localJson = RunLogText.read(localRecord);
            String helloJson = RunLogText.read(helloRecord);
            // Non-vacuity for the two containment checks below: an absent or
            // empty record file would satisfy "does not contain" for free.
            boolean bothRecordsReadable = localJson.contains("\"" + pluginName + "\"")
                    && helloJson.contains("\"hello-plugin\"");
            boolean localInstallIsLocalFile = localJson.contains("\"installSource\" : \"LOCAL_FILE\"");
            boolean helloInstallIsLocalFile = helloJson.contains("\"installSource\" : \"LOCAL_FILE\"");
            boolean neitherRecordsAnError = bothRecordsReadable
                    && !localJson.contains("NEEDS_GIT_MIGRATION")
                    && !helloJson.contains("NEEDS_GIT_MIGRATION");

            // ...and reported, rather than silently dropped: the count on the
            // console, the sentence in the run log, for EACH unit.
            boolean localOnlyCounted = console.contains("local-only (no shared upstream)");
            boolean localProvenanceInRunLog =
                    body.contains(pluginName + ": installed from a local path");
            boolean helloProvenanceInRunLog =
                    body.contains("hello-plugin: installed from a local path");
            boolean noOutstandingErrors = !body.contains("skills with outstanding errors");
            // Not 0, and correctly so: sibling nodes (plugin.contained.skill.
            // resolves.by.name, plugin.qualified.name.resolves) install probe
            // skills named imports-* whose skill-imports are broken ON PURPOSE,
            // and sync reports them with MarkdownImportValidator.EXIT_CODE (11).
            // Until HP-11 the registry install's NEEDS_GIT_MIGRATION error
            // (exit 1) masked that. What must hold is that 11 is the ONLY
            // cause: every violation names a probe, none names a real unit.
            java.util.List<String> violators = new java.util.ArrayList<>();
            java.util.regex.Matcher vm = java.util.regex.Pattern
                    .compile("(?m)^\\S+\\s+- (\\S+) \\((?:skill|plugin|doc-repo|harness)\\):")
                    .matcher(console);
            while (vm.find()) violators.add(vm.group(1));
            boolean onlyProbesViolate = !violators.isEmpty()
                    && violators.stream().allMatch(v -> v.startsWith("imports-"));
            boolean syncExitIsProbeViolationsOnly = rc == 11 && onlyProbesViolate;

            boolean pass = preSymlinkGone && preManifestStubbed
                    && symlinkRestored && manifestRestored && mcpReRegistered
                    && syncExitIsProbeViolationsOnly && bothRecordsReadable
                    && localInstallIsLocalFile && helloInstallIsLocalFile
                    && neitherRecordsAnError && localOnlyCounted
                    && localProvenanceInRunLog && helloProvenanceInRunLog
                    && noOutstandingErrors;
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
                                    + " helloIsLocalFile=" + helloInstallIsLocalFile
                                    + " neitherErrors=" + neitherRecordsAnError
                                    + " localOnlyCounted=" + localOnlyCounted
                                    + " localInRunLog=" + localProvenanceInRunLog
                                    + " helloInRunLog=" + helloProvenanceInRunLog
                                    + " noOutstanding=" + noOutstandingErrors
                                    + " violators=" + violators))
                    .process(proc)
                    .assertion("marketplace_symlink_was_drifted", preSymlinkGone)
                    .assertion("marketplace_manifest_was_drifted", preManifestStubbed)
                    .assertion("marketplace_symlink_restored", symlinkRestored)
                    .assertion("marketplace_manifest_restored", manifestRestored)
                    .assertion("mcp_register_results_emitted", mcpReRegistered)
                    .assertion("both_install_records_readable", bothRecordsReadable)
                    .assertion("local_install_recorded_as_local_file", localInstallIsLocalFile)
                    .assertion("hello_install_recorded_as_local_file", helloInstallIsLocalFile)
                    .assertion("deliberate_local_installs_record_no_error", neitherRecordsAnError)
                    .assertion("local_only_counted_on_console", localOnlyCounted)
                    .assertion("local_provenance_named_in_run_log", localProvenanceInRunLog)
                    .assertion("hello_provenance_named_in_run_log", helloProvenanceInRunLog)
                    .assertion("no_outstanding_errors", noOutstandingErrors)
                    .assertion("sync_exit_is_only_the_sibling_probes_import_violations",
                            syncExitIsProbeViolationsOnly)
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
