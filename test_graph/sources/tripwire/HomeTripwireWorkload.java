///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The work the tripwire brackets: a real {@code skill-manager install} of a
 * local unit into a sandboxed home, which is the operation that projects a unit
 * into {@code $CLAUDE_CONFIG_DIR/skills}, {@code $CODEX_HOME} and
 * {@code $GEMINI_HOME}.
 *
 * <p>Without a workload the check node would compare a baseline to itself and
 * report CLEAN forever — the vacuous check this graph exists to prevent. Install
 * is chosen because agent projection is the exact operation issue #18 caught
 * escaping.
 *
 * <h2>The env here is a deliberate choice, and it is the graph's one lever</h2>
 *
 * This node passes the FULL sandbox: the five home variables plus {@code HOME}
 * and {@code JAVA_TOOL_OPTIONS=-Duser.home=...}. On macOS the JVM derives
 * {@code user.home} from the OS and ignores {@code $HOME}, so without the last
 * one an unanticipated {@code user.home} read still lands in the real home.
 *
 * <p>Issue #18 reports a leak that survives even when the five variables ARE set
 * — {@code MarkdownImportFixture.install} sets all five and skill projection
 * still reached the real home, which is why #18 is a production defect in home
 * resolution rather than a fixture bug. Dropping {@code HOME} and
 * {@code JAVA_TOOL_OPTIONS} below is therefore the reproduction lever for #18,
 * and the assertions in {@code home.tripwire.checked} are what would catch it.
 * It is left at full strength here so this graph guards against regression
 * rather than encoding a known-open defect as a red build.
 */
public class HomeTripwireWorkload {
    static final NodeSpec SPEC = NodeSpec.of("home.tripwire.workload")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("home.tripwire.armed")
            .tags("tripwire", "install", "sandbox")
            .timeout("180s")
            .output("unitName", "string");

    /** Named so a leak is attributable to this graph and to no other. */
    static final String UNIT = "tw-probe-skill";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null) {
                return NodeResult.fail("home.tripwire.workload", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize()
                    .toAbsolutePath();
            Path sm = repoRoot.resolve("skill-manager");

            Path unitDir;
            try {
                unitDir = Files.createTempDirectory("tw-probe-").resolve(UNIT);
                Files.createDirectories(unitDir);
                Files.writeString(unitDir.resolve("SKILL.md"), """
                        ---
                        name: %s
                        description: home tripwire probe unit
                        ---
                        A unit whose only job is to be projected into agent homes.
                        """.formatted(UNIT));
                Files.writeString(unitDir.resolve("skill-manager.toml"), """
                        [skill]
                        name = "%s"
                        version = "0.1.0"
                        description = "home tripwire probe unit"
                        """.formatted(UNIT));
            } catch (Exception e) {
                return NodeResult.error("home.tripwire.workload", e);
            }

            // No --skip-gateway: `install` does not accept it (picocli exits 2
            // and prints usage). The probe unit declares no MCP dependency, so
            // there is no gateway to skip.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file://" + unitDir, "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CLAUDE_CONFIG_DIR", Path.of(claudeHome).resolve(".claude").toString());
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("GEMINI_HOME", geminiHome);
            pb.environment().put("HOME", home);
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=" + home);

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            boolean installExitsZero = proc.exitCode() == 0;

            Path installed = Path.of(home).resolve("skills").resolve(UNIT);
            boolean theUnitLandedInTheSandboxStore = Files.isDirectory(installed);

            // The other half of a non-vacuous bracket: the projection actually
            // happened somewhere. A workload that silently did nothing would
            // leave the checker comparing a baseline to itself.
            List<String> sandboxClaude =
                    namesOf(Path.of(claudeHome).resolve(".claude").resolve("skills"));
            boolean theProjectionLandedInTheSandboxAgentHome = sandboxClaude.contains(UNIT);

            boolean pass = installExitsZero && theUnitLandedInTheSandboxStore
                    && theProjectionLandedInTheSandboxAgentHome;
            return (pass
                    ? NodeResult.pass("home.tripwire.workload")
                    : NodeResult.fail("home.tripwire.workload",
                            "exit=" + proc.exitCode() + " installed=" + theUnitLandedInTheSandboxStore
                                    + " sandboxClaudeSkills=" + sandboxClaude))
                    .assertion("install_exits_zero", installExitsZero)
                    .assertion("the_unit_landed_in_the_sandbox_store", theUnitLandedInTheSandboxStore)
                    .assertion("the_projection_landed_in_the_sandbox_agent_home",
                            theProjectionLandedInTheSandboxAgentHome)
                    .process(proc)
                    .publish("unitName", UNIT);
        });
    }

    private static List<String> namesOf(Path dir) {
        List<String> out = new java.util.ArrayList<>();
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return out;
        try (var entries = Files.list(dir)) {
            entries.map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (Exception e) {
            return out;
        }
        return out;
    }
}
