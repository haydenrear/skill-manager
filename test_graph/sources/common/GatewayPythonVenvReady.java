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
 * Materialize the gateway's Python venv at
 * {@code virtual-mcp-gateway/.venv/} via {@code uv sync --all-extras}.
 *
 * <p>Why a node: when {@code skill-manager gateway up} spawns the
 * gateway, it picks the bundled venv interpreter
 * ({@code virtual-mcp-gateway/.venv/bin/python}) when present, falling
 * back to bare {@code python3}. On a fresh checkout the venv doesn't
 * exist, so the bare-Python fallback fires and the gateway dies on
 * {@code import uvicorn}. Running this once before {@code gateway.up}
 * guarantees the venv is populated; uv sync is idempotent so re-runs
 * cost almost nothing.
 *
 * <p>Wired into the graph as a transitive dep of {@code gateway.up} so
 * any future graph that brings the gateway up automatically picks this
 * up — no need to remember to add it to {@code build.gradle.kts}.
 */
public class GatewayPythonVenvReady {
    static final NodeSpec SPEC = NodeSpec.of("gateway.python.venv.ready")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("gateway", "python", "venv")
            .sideEffects("net:remote", "fs:write")
            .timeout("180s")
            .output("venvPython", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path gatewayDir = repoRoot.resolve("virtual-mcp-gateway");
            if (!Files.isDirectory(gatewayDir)) {
                return NodeResult.fail("gateway.python.venv.ready",
                        "virtual-mcp-gateway dir missing: " + gatewayDir);
            }

            ProcessBuilder pb = new ProcessBuilder("uv", "sync", "--all-extras")
                    .directory(gatewayDir.toFile());
            ProcessRecord proc = Procs.run(ctx, "uv-sync", pb);
            int rc = proc.exitCode();
            if (rc != 0) {
                return NodeResult.fail("gateway.python.venv.ready", "uv sync exited " + rc)
                        .process(proc);
            }

            Path venvPython = gatewayDir.resolve(".venv/bin/python");
            boolean ok = Files.isExecutable(venvPython);
            NodeResult result = ok
                    ? NodeResult.pass("gateway.python.venv.ready")
                    : NodeResult.fail("gateway.python.venv.ready",
                            "uv sync succeeded but .venv/bin/python missing at " + venvPython);
            return result
                    .process(proc)
                    .assertion("uv_sync_ok", rc == 0)
                    .assertion("venv_python_present", ok)
                    .publish("venvPython", venvPython.toString());
        });
    }
}
