///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES CheapChecksKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * GOAL-checking-costs-what-changed clause (c), #402: {@code sync <unit>} in a home made
 * by {@code home clone} re-runs 0 OTHER units' skill-script installers.
 *
 * <p>Fixture: three units with a skill-script CLI dep whose installer writes a cache
 * tree plus a wrapper exec'ing into it (the deploy-helm shape, so {@code cache/} being
 * skipped by the clone leaves the wrapper unusable) and appends the unit name to a
 * counter file outside the home. Synthetic rather than deploy-helm: a run must count
 * invocations exactly and not depend on network builds.
 *
 * <p>The asserted arm is {@code sync <unit> --from <dir> --yes --skip-mcp}. A second,
 * {@code --force-scripts}, arm is measured only: that flag explicitly asks for a replay,
 * #402's reproduction uses it, and CC-2 must not change its semantics, so what it
 * re-runs is reported (metric) and not targeted.
 *
 * <p>CONTROLS: the source install ran each installer exactly once (the counter counts),
 * the synced unit's new bytes landed in the clone (the sync really ran), and editing the
 * target's script re-runs it (a stale installer is not skipped). The plain sync must also
 * name each other unit's tool it left unbuilt: a skip the home is silent about would
 * pass the count and leave an unreported broken binary.
 */
public class ForeignInstallers {
    static final String ID = "cheap.foreign-installers";
    static final List<String> FOREIGN = List.of("cc-foreign-a", "cc-foreign-b");
    static final String TARGET = "cc-target";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cheap-checks", "sync", "402", "installers")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path source;
            Path clone;
            Path cloneForce;
            Path counter;
            Path targetDir;
            ProcessRecord cloned;
            ProcessRecord clonedForce;
            boolean installs = true;
            try {
                root = CheapChecksKit.scratch(ctx, "foreign-installers");
                source = root.resolve("source");
                clone = root.resolve("clone");
                cloneForce = root.resolve("clone-force");
                counter = root.resolve("installer-runs.log");
                CheapChecksKit.writePolicy(source);
                for (String unit : FOREIGN) {
                    Path d = scaffold(root.resolve("units"), unit, counter, "v1");
                    installs &= CheapChecksKit.sm(ctx, "install_" + unit, root, source,
                            "install", d.toString(), "--yes", "--no-bind-default").exitCode() == 0;
                }
                targetDir = scaffold(root.resolve("units"), TARGET, counter, "v1");
                installs &= CheapChecksKit.sm(ctx, "install_" + TARGET, root, source,
                        "install", targetDir.toString(), "--yes", "--no-bind-default").exitCode() == 0;
                cloned = CheapChecksKit.cloneHome(ctx, "clone", root, source, clone);
                clonedForce = CheapChecksKit.cloneHome(ctx, "clone_force", root, source, cloneForce);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }
            List<String> atInstall = CheapChecksKit.lines(counter);
            boolean controlCounterCounts = atInstall.size() == 3 && atInstall.containsAll(FOREIGN) && atInstall.contains(TARGET);

            long foreignPlain;
            long foreignForce;
            long targetRerunAfterEdit;
            boolean unbuiltNamed;
            ProcessRecord sync;
            ProcessRecord syncForce;
            try {
                Files.writeString(targetDir.resolve("SKILL.md"),
                        Files.readString(targetDir.resolve("SKILL.md")) + "\nsynced-marker\n");
                Files.deleteIfExists(counter);
                sync = CheapChecksKit.sm(ctx, "sync", root, clone,
                        "sync", TARGET, "--from", targetDir.toString(), "--yes", "--skip-mcp");
                foreignPlain = foreignRuns(counter);
                String syncOutput = CheapChecksKit.output(ctx, sync);
                unbuiltNamed = FOREIGN.stream().allMatch(u -> syncOutput.contains(u + "-tool (" + u + ")"));
                Files.deleteIfExists(counter);
                Path script = targetDir.resolve("skill-scripts/install-tool.sh");
                Files.writeString(script, Files.readString(script) + "\n# edited\n");
                CheapChecksKit.sm(ctx, "sync_edited", root, clone,
                        "sync", TARGET, "--from", targetDir.toString(), "--yes", "--skip-mcp");
                targetRerunAfterEdit = CheapChecksKit.lines(counter).stream().filter(TARGET::equals).count();
                Files.deleteIfExists(counter);
                syncForce = CheapChecksKit.sm(ctx, "sync_force", root, cloneForce,
                        "sync", TARGET, "--from", targetDir.toString(), "--yes", "--skip-mcp", "--force-scripts");
                foreignForce = foreignRuns(counter);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed during sync: " + e).assertion("setup_ok", false);
            }
            boolean controlSyncLanded = sync.exitCode() == 0 && CheapChecksKit
                    .read(clone.resolve("skills").resolve(TARGET).resolve("SKILL.md")).contains("synced-marker");
            boolean setup = installs && cloned.exitCode() == 0 && clonedForce.exitCode() == 0;
            boolean shape = foreignPlain == 0;
            String detail = "foreign installers re-run: plain=" + foreignPlain + " force_scripts=" + foreignForce
                    + " (of " + FOREIGN.size() + "); sync rc=" + sync.exitCode() + " force rc=" + syncForce.exitCode()
                    + "; counter at install=" + atInstall
                    + "; target reruns after script edit=" + targetRerunAfterEdit + "; unbuilt named=" + unbuiltNamed;
            boolean controlEditedReruns = targetRerunAfterEdit == 1;
            NodeResult result = setup && controlCounterCounts && controlSyncLanded && shape
                    && unbuiltNamed && controlEditedReruns
                    ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(sync).process(syncForce)
                    .metric("foreign_installers_rerun", foreignPlain)
                    .metric("foreign_installers_rerun_force_scripts", foreignForce)
                    .metric("foreign_units", FOREIGN.size())
                    .assertion("setup_units_installed_and_cloned", setup)
                    .assertion("control_counter_counts_each_installer_once", controlCounterCounts)
                    .assertion("control_sync_landed_on_named_unit", controlSyncLanded)
                    .assertion("shape_no_other_units_installers_rerun", shape)
                    .assertion("control_edited_script_reruns", controlEditedReruns)
                    .assertion("shape_unbuilt_other_unit_tools_are_named", unbuiltNamed)
                    .log(detail);
        });
    }

    static long foreignRuns(Path counter) {
        return CheapChecksKit.lines(counter).stream().filter(FOREIGN::contains).count();
    }

    static Path scaffold(Path parent, String unit, Path counter, String marker) throws Exception {
        Path dir = parent.resolve(unit);
        String tool = unit + "-tool";
        CheapChecksKit.writeSkill(dir, unit, "0.1.0", marker);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "cheap-checks graph fixture"

                [[cli_dependencies]]
                spec = "skill-script:%s"
                on_path = "%s"

                [cli_dependencies.install.any]
                script = "install-tool.sh"
                binary = "%s"
                """.formatted(unit, tool, tool, tool));
        CheapChecksKit.writeExecutable(dir.resolve("skill-scripts/install-tool.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "@UNIT@" >> "@COUNTER@"
                tree="$SKILL_MANAGER_HOME/cache/cc-@UNIT@"
                mkdir -p "$tree/bin" "$SKILL_MANAGER_HOME/bin/cli"
                printf '#!/usr/bin/env bash\\necho ok\\n' > "$tree/bin/@TOOL@"
                chmod +x "$tree/bin/@TOOL@"
                printf '#!/usr/bin/env bash\\nexec "%s/bin/@TOOL@" "$@"\\n' "$tree" > "$SKILL_MANAGER_HOME/bin/cli/@TOOL@"
                chmod +x "$SKILL_MANAGER_HOME/bin/cli/@TOOL@"
                """.replace("@UNIT@", unit).replace("@COUNTER@", counter.toString()).replace("@TOOL@", tool));
        return dir;
    }
}
