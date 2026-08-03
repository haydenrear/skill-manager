///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Installs {@code skill-dev-skill} from the repository checkout.
 *
 * <h2>Why this install exits 11, and why that is the assertion</h2>
 *
 * <p>{@code skill-dev-skill}'s own {@code SKILL.md} declares a markdown
 * skill-import of {@code skill-manager:references/cli.md} — the page that
 * defines the {@code sync --from} / {@code --force-scripts} contract it
 * delegates to. This graph's home is a bare sandbox: the {@code skill-manager}
 * unit is not installed in it, so that reference does not resolve, the install
 * reports it, and the run exits
 * {@code MarkdownImportValidator.EXIT_CODE} (11).
 *
 * <p>The node used to assert {@code rc == 0}, which was asserting the defect:
 * the violation was printed under the success banner and reached nothing, so
 * the reference could rot with the tool reporting success. The contract this
 * now pins is the fixed one — <b>the install still commits</b> (the
 * {@code skill-dev} binary is on disk and executable, which is what every
 * downstream node in this graph needs) and the unresolved reference reaches
 * the exit code and is named in the output.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>Three companions, so "exits 11" cannot be satisfied by a binary that
 * fails everything:
 * <ul>
 *   <li>the {@code skill-dev} bin is executable — the install committed;</li>
 *   <li>the output NAMES the specific unresolved reference
 *       ({@code skill-manager}), so the 11 is this violation and not an
 *       unrelated failure that happens to be non-zero;</li>
 *   <li>the very next node in this graph,
 *       {@code skill-dev.units.installed}, runs five installs of
 *       import-free units into this same home through this same binary and
 *       asserts every one of them exits 0.</li>
 * </ul>
 */
public class SkillDevInstalled {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "registry.up")
            .tags("skill-dev", "install")
            .timeout("120s")
            .output("skillDev", "string");

    /** {@code dev.skillmanager.validation.MarkdownImportValidator.EXIT_CODE}. */
    private static final int MARKDOWN_IMPORT_VIOLATION_EXIT = 11;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null
                    || gatewayPort == null || registryUrl == null) {
                return NodeResult.fail("skill-dev.installed", "missing upstream context");
            }
            Map<String, String> env = SkillDevGraphSupport.env(
                    home, claudeHome, codexHome, geminiHome,
                    "http://127.0.0.1:" + gatewayPort, registryUrl);
            Path repoRoot = SkillDevGraphSupport.repoRoot();
            ProcessRecord proc = SkillDevGraphSupport.run(ctx, "install-skill-dev", env, repoRoot,
                    SkillDevGraphSupport.skillManager().toString(), "install",
                    "file://" + repoRoot.resolve("skill-dev-skill"), "--yes");
            Path bin = SkillDevGraphSupport.skillDev(Path.of(home));
            String log = logBody(ctx, proc);

            boolean committed = Files.isExecutable(bin);
            boolean exitReportsViolation = proc.exitCode() == MARKDOWN_IMPORT_VIOLATION_EXIT;
            boolean violationNamesSkillManager =
                    log.contains("markdown skill-import violations")
                            && log.contains("skill-dev-skill (skill)")
                            && log.contains("references missing unit `skill-manager`");

            boolean installed = committed && exitReportsViolation && violationNamesSkillManager;
            return (installed ? NodeResult.pass("skill-dev.installed")
                    : NodeResult.fail("skill-dev.installed",
                            "rc=" + proc.exitCode() + " bin=" + Files.exists(bin)
                                    + " namesSkillManager=" + violationNamesSkillManager))
                    .process(proc)
                    .assertion("install_committed_skill_dev_bin", committed)
                    .assertion("unresolved_import_reaches_exit_code", exitReportsViolation)
                    .assertion("violation_names_the_missing_unit", violationNamesSkillManager)
                    .metric("exitCode", proc.exitCode())
                    .publish("skillDev", bin.toString());
        });
    }

    private static String logBody(com.hayden.testgraphsdk.sdk.NodeContext ctx,
                                  ProcessRecord proc) {
        if (proc.logPath() == null) return "";
        try {
            return Files.readString(ctx.reportDir().resolve(proc.logPath()));
        } catch (Exception e) {
            return "";
        }
    }
}
