///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uninstall the harness template via {@code skill-manager uninstall
 * smoke-harness}. Verify cleanup contract:
 *
 * <ul>
 *   <li>{@code <home>/harnesses/smoke-harness/} dir removed.</li>
 *   <li>{@code <home>/installed/smoke-harness.json} record removed.</li>
 *   <li>{@code <home>/installed/smoke-harness.projections.json} —
 *       absent both before (no bindings on the template itself) and
 *       after.</li>
 *   <li>Transitive units (pip-cli-skill, hello-plugin, hello-doc-repo)
 *       are NOT removed — uninstall of a harness template does not
 *       cascade. They were installed as transitive deps and remain
 *       available for other templates or explicit binds.</li>
 * </ul>
 */
public class HarnessTemplateUninstalled {
    static final NodeSpec SPEC = NodeSpec.of("harness.template.uninstalled")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("harness.instance.removed")
            .tags("uninstall", "harness", "ticket-47")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("harness.template.uninstalled", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "uninstall", "smoke-harness");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "uninstall", pb);
            int rc = proc.exitCode();

            Path templateDir = Path.of(home, "harnesses", "smoke-harness");
            Path installedRec = Path.of(home, "installed", "smoke-harness.json");
            Path ledger = Path.of(home, "installed", "smoke-harness.projections.json");

            boolean templateGone = !Files.exists(templateDir);
            boolean recGone = !Files.exists(installedRec);
            boolean ledgerAbsent = !Files.exists(ledger);

            // Transitive units stay installed — uninstall scopes to the
            // named template only.
            boolean skillStays = Files.isRegularFile(
                    Path.of(home, "skills", "pip-cli-skill", "SKILL.md"));
            boolean pluginStays = Files.isRegularFile(
                    Path.of(home, "plugins", "hello-plugin", ".claude-plugin/plugin.json"));
            boolean docStays = Files.isRegularFile(
                    Path.of(home, "docs", "hello-doc-repo", "skill-manager.toml"));

            boolean pass = rc == 0 && templateGone && recGone && ledgerAbsent
                    && skillStays && pluginStays && docStays;
            NodeResult result = pass
                    ? NodeResult.pass("harness.template.uninstalled")
                    : NodeResult.fail("harness.template.uninstalled",
                            "rc=" + rc + " templateGone=" + templateGone
                                    + " recGone=" + recGone + " ledgerAbsent=" + ledgerAbsent
                                    + " skillStays=" + skillStays
                                    + " pluginStays=" + pluginStays
                                    + " docStays=" + docStays);
            return result
                    .process(proc)
                    .assertion("uninstall_ok", rc == 0)
                    .assertion("template_dir_removed", templateGone)
                    .assertion("installed_record_removed", recGone)
                    .assertion("ledger_absent", ledgerAbsent)
                    .assertion("transitive_units_survive_uninstall",
                            skillStays && pluginStays && docStays)
                    .metric("exitCode", rc);
        });
    }
}
