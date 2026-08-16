///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every {@code cli-lock.toml} row is claimed by a unit that declares it today.
 *
 * <p>#124's defect 6: three orphan {@code npm} rows in the operator's project
 * home — {@code npm:gemini-cli}, {@code npm:google},
 * {@code npm:google-gemini-cli} — left behind by earlier guesses at a package
 * name, declared by nothing, and unreachable by
 * {@code CliDependencyCleaner.pruneIfOrphan}, which only runs from an uninstall
 * of a declaring unit. The fix is #109's. This node owns the assertion.
 *
 * <h2>The mutation that matters is the second one</h2>
 *
 * <p>Planting a row nobody ever mentioned is the easy case and any check
 * catches it. The <b>orphan-by-rename</b> case is the one that actually
 * happened and the one a naive check misses: all three real orphans carry
 * {@code requested_by = ["acp-cdc-ai-python", …]}, and
 * {@code acp-cdc-ai-python} <em>is installed</em>. A check that reads the row's
 * own {@code requested_by} against the installed set therefore reports zero
 * orphans over the exact three rows the defect is about.
 * {@link HomeIntegrity#everyLockRowHasAClaimant} computes the claimant from
 * what units declare now, and the second mutation here — leave
 * {@code requested_by} pointing at a live unit, change the spec so the unit no
 * longer declares it — is what proves it.
 *
 * <p>The third mutation asserts a non-detection, and it is not hypothetical: a
 * first draft of the checker read only {@code skill-manager.toml} and reported
 * {@code skill-script:skt} as a fourth orphan in the operator's home. The
 * {@code skt} plugin declares it correctly, in
 * {@code skill-manager-plugin.toml}, which skills do not have. Reading one
 * filename produces a false accusation against a healthy row, so a
 * plugin-declared dependency is planted here and must not be reported.
 */
public class EveryLockRowHasAClaimant {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.lock.row.has.claimant")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "cli-lock")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
            String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
            if (homeStr == null || scratchStr == null) return unproven("missing fixture context");
            Path home = Path.of(homeStr);
            Path scratch = Path.of(scratchStr);

            HomeIntegrity.Report fresh = HomeIntegrity.everyLockRowHasAClaimant(home);

            HomeIntegritySupport.Mutation unclaimed;
            HomeIntegritySupport.Mutation renamed;
            HomeIntegritySupport.Mutation pluginDeclared;
            try {
                unclaimed = HomeIntegritySupport.probe(home, scratch, "unclaimed_lock_row",
                        new AppendRow("hi-orphan", "npm:hi-orphan", "[]"),
                        HomeIntegrity::everyLockRowHasAClaimant);
                renamed = HomeIntegritySupport.probe(home, scratch, "orphan_by_rename",
                        new AppendRow("hi-renamed", "npm:hi-renamed",
                                "[\"" + HomeIntegritySupport.UNIT + "\"]"),
                        HomeIntegrity::everyLockRowHasAClaimant);
                pluginDeclared = HomeIntegritySupport.probe(home, scratch,
                        "row_declared_by_a_plugin", new PluginDeclaredRow(),
                        HomeIntegrity::everyLockRowHasAClaimant);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }

            boolean freshHolds = fresh.holdsNonVacuously();
            boolean pluginRowTolerated = !pluginDeclared.caught();

            boolean pass = freshHolds
                    && unclaimed.caught() && unclaimed.repaired()
                    && renamed.caught() && renamed.repaired()
                    && pluginRowTolerated;

            NodeResult result = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(),
                            "freshHolds=" + freshHolds
                                    + " examined=" + fresh.examined()
                                    + " unclaimedCaught=" + unclaimed.caught()
                                    + " unclaimedRepaired=" + unclaimed.repaired()
                                    + " renamedCaught=" + renamed.caught()
                                    + " renamedRepaired=" + renamed.repaired()
                                    + " pluginRowTolerated=" + pluginRowTolerated
                                    + " | " + fresh.describe());

            result = HomeIntegritySupport.withReport(result, fresh)
                    .assertion("every_lock_row_in_a_fresh_home_is_declared_by_an_installed_unit",
                            freshHolds);
            result = HomeIntegritySupport.withMutation(result, unclaimed);
            result = HomeIntegritySupport.withMutation(result, renamed);
            return result
                    .assertion("a_row_a_plugin_declares_is_not_reported_as_an_orphan",
                            pluginRowTolerated)
                    .log(pluginDeclared.name() + ": " + pluginDeclared.evidence());
        });
    }

    /**
     * Append a lock row, with whatever {@code requested_by} the caller wants.
     *
     * <p>{@code requested_by = ["hi-unit"]} with a spec {@code hi-unit} does not
     * declare is the orphan-by-rename shape: a live claimant named in history,
     * and no current claim.
     */
    private record AppendRow(String tool, String spec, String requestedBy)
            implements HomeIntegritySupport.Damage {

        @Override
        public void plant(Path home) throws IOException {
            Path lock = home.resolve("cli-lock.toml");
            Files.writeString(lock, Files.readString(lock) + """

                    ["npm"."%s"]
                    spec = "%s"
                    requested_by = %s
                    installed_at = "2026-08-16T00:00:00Z"
                    """.formatted(tool, spec, requestedBy));
        }

        @Override
        public void repair(Path home) throws IOException {
            Path lock = home.resolve("cli-lock.toml");
            String body = Files.readString(lock);
            int cut = body.indexOf("[\"npm\"." + "\"" + tool + "\"]");
            if (cut >= 0) Files.writeString(lock, body.substring(0, cut).stripTrailing() + "\n");
        }
    }

    /**
     * A row whose only claimant is a <em>plugin</em>, declaring it in
     * {@code skill-manager-plugin.toml}.
     *
     * <p>Installing a real plugin here would drag the whole plugin lifecycle
     * into a check about lock rows. What is under test is whether the claimant
     * scan reads the plugin manifest at all, so the plugin is written where an
     * installed one would be, with the one record that makes it installed.
     */
    private record PluginDeclaredRow() implements HomeIntegritySupport.Damage {
        private static final String PLUGIN = "hi-plugin";
        private static final String TOOL = "hi-plugin-tool";

        @Override
        public void plant(Path home) throws IOException {
            Path dir = home.resolve("plugins").resolve(PLUGIN);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                    [[cli_dependencies]]
                    spec = "skill-script:%s"
                    on_path = "%s"

                    [plugin]
                    name = "%s"
                    version = "0.1.0"
                    """.formatted(TOOL, TOOL, PLUGIN));
            Files.writeString(home.resolve("installed").resolve(PLUGIN + ".json"), """
                    {
                      "name" : "%s",
                      "version" : "0.1.0",
                      "kind" : "LOCAL",
                      "installedAt" : "2026-08-16T00:00:00Z",
                      "errors" : [ ],
                      "unitKind" : "PLUGIN"
                    }
                    """.formatted(PLUGIN));
            Path lock = home.resolve("cli-lock.toml");
            Files.writeString(lock, Files.readString(lock) + """

                    ["skill-script"."%s"]
                    spec = "skill-script:%s"
                    requested_by = ["%s"]
                    installed_at = "2026-08-16T00:00:00Z"
                    """.formatted(TOOL, TOOL, PLUGIN));
        }

        @Override
        public void repair(Path home) throws IOException {
            Path lock = home.resolve("cli-lock.toml");
            String body = Files.readString(lock);
            int cut = body.indexOf("[\"skill-script\"." + "\"" + TOOL + "\"]");
            if (cut >= 0) Files.writeString(lock, body.substring(0, cut).stripTrailing() + "\n");
            Files.deleteIfExists(home.resolve("installed").resolve(PLUGIN + ".json"));
            HomeIntegritySupport.deleteRecursively(home.resolve("plugins").resolve(PLUGIN));
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("every_lock_row_in_a_fresh_home_is_declared_by_an_installed_unit",
                        false)
                .assertion("the_planted_unclaimed_lock_row_is_caught", false)
                .assertion("repairing_the_planted_unclaimed_lock_row_clears_it", false)
                .assertion("the_planted_orphan_by_rename_is_caught", false)
                .assertion("repairing_the_planted_orphan_by_rename_clears_it", false)
                .assertion("a_row_a_plugin_declares_is_not_reported_as_an_orphan", false);
    }
}
