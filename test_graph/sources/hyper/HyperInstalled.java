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
 * Installs the just-published {@code hyper-experiments} skill from the
 * registry into the per-run {@code SKILL_MANAGER_HOME}, asserts the SKILL.md
 * + skill-manager.toml landed, and triggers transitive registration of the
 * declared MCP servers (runpod) with the gateway.
 *
 * <p>The install runs from a temp working directory rather than inside the
 * checkout — the user explicitly wanted the install path exercised from a
 * "fresh folder", not the source repo.
 */
public class HyperInstalled {
    static final NodeSpec SPEC = NodeSpec.of("hyper.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.published", "gateway.up")
            .tags("hyper", "registry", "install", "mcp")
            .timeout("120s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("hyper.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Run from a fresh cwd that is not the checkout — exercises the
            // by-name resolution path through the registry.
            Path freshCwd = Path.of(home).resolve("install-cwd");
            Files.createDirectories(freshCwd);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "hyper-experiments",
                    "--registry", registryUrl);
            pb.directory(freshCwd.toFile());
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            // Map X_RUNPOD_KEY -> RUNPOD_API_KEY so McpWriter's env-init
            // scan finds the runpod manifest's required field and the
            // gateway can auto-deploy at install time. CI provisions
            // X_RUNPOD_KEY from secrets.X_RUNPOD_KEY; locally the operator
            // exports it before invoking ./gradlew hyper-experiments.
            String runpodKey = System.getenv("X_RUNPOD_KEY");
            if (runpodKey != null && !runpodKey.isBlank()) {
                pb.environment().put("RUNPOD_API_KEY", runpodKey);
            }

            // Force the pip backend to actually bundle tb-query under
            // $home/bin/cli/ instead of short-circuiting on the host's
            // pre-existing copy. PipBackend.install checks isOnPath(onPath)
            // and bails out when the binary is already reachable; our
            // hyper.cli.tbquery assertion needs the bundled artifact to
            // exist, so we strip the host-tb-query directory from the
            // install subprocess's PATH.
            String hostTbQuery = locateOnPath(System.getenv("PATH"), "tb-query");
            if (hostTbQuery != null) {
                Path tbqDir = Path.of(hostTbQuery).getParent();
                String scrubbed = java.util.Arrays.stream(
                                pb.environment().getOrDefault("PATH", "")
                                        .split(java.io.File.pathSeparator))
                        .filter(p -> tbqDir == null || !tbqDir.toString().equals(p))
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                pb.environment().put("PATH", scrubbed);
            }

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            Path skillDir = Path.of(home).resolve("skills/hyper-experiments");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            boolean pass = rc == 0 && mdOk && tomlOk;
            NodeResult result = pass
                    ? NodeResult.pass("hyper.installed")
                    : NodeResult.fail("hyper.installed",
                            "rc=" + rc + " md=" + mdOk + " toml=" + tomlOk);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }

    /**
     * Resolve {@code tool} against the supplied {@code pathEnv} string.
     * Used (instead of {@link System#getenv}) so the scrubbed PATH the
     * install subprocess will actually run with is deterministic — we
     * read the host PATH once, find the entry to remove, then write the
     * scrubbed version back into {@code pb.environment()}.
     */
    private static String locateOnPath(String pathEnv, String tool) {
        if (pathEnv == null || tool == null) return null;
        for (String part : pathEnv.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(part, tool);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }
}
