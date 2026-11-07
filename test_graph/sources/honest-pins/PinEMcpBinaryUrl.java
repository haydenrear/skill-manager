///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HonestPinsKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pin shape (e), #103: an MCP server's binary install url changes under the
 * same {@code bin_path}, and the gateway must serve the NEW binary.
 *
 * <p>Plant: two builds of one raw binary, {@code probe}, at two file:// urls —
 * v1 prints {@code 1.0}, v2 prints {@code 2.0}. The gateway's own
 * {@code Provisioner} ({@code virtual-mcp-gateway/gateway/provisioning.py},
 * the code a live gateway runs on register) provisions server
 * {@code hp-probe} from v1 into a scratch data dir, then from v2 with the same
 * {@code bin_path}. Each time the returned command is RUN.
 *
 * <p>Driven at the provisioning layer rather than through a live gateway on
 * purpose: the defect is the marker short-circuit in
 * {@code _ensure_binary_installed}, a live gateway adds ports, a registry and a
 * docker-free MCP handshake to reach the same three lines, and this graph has
 * to run anywhere {@code gateway.python.venv.ready} does.
 *
 * <p>HONEST: after the url changes, the provisioned command prints 2.0. At
 * 0.28.2 the marker {@code .skill-manager.installed} still exists, the old
 * {@code bin_path} still exists, and it returns the 1.0 binary.
 *
 * <p>CONTROL: re-provisioning the UNCHANGED v1 spec after its url has gone away
 * still serves 1.0 without downloading — the cache must keep working for a
 * spec that did not change.
 */
public class PinEMcpBinaryUrl {
    static final String ID = "honest.pin-e";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "gateway.python.venv.ready")
            .tags("honest-pins", "pin", "mcp", "gateway", "103")
            .timeout("300s");

    static final String DRIVER = """
            import asyncio, json, subprocess, sys
            from pathlib import Path
            from gateway.provisioning import Provisioner, LoadSpec
            data_dir, url = sys.argv[1], sys.argv[2]
            spec = LoadSpec.model_validate({"type": "binary", "binary": {
                "install": {"any": {"url": url, "archive": "raw"}}, "bin_path": "probe"}})
            try:
                cfg = asyncio.run(Provisioner(Path(data_dir)).provision("hp-probe", spec))
            except Exception as e:
                print("HP_RESULT " + json.dumps({"error": repr(e)}))
                sys.exit(0)
            out = subprocess.run(cfg.command, capture_output=True, text=True).stdout.strip()
            print("HP_RESULT " + json.dumps({"reported": out, "command": cfg.command}))
            """;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String venvPython = ctx.get("gateway.python.venv.ready", "venvPython").orElse(null);
            if (venvPython == null) {
                return NodeResult.fail(ID, "setup failed: missing gateway.python.venv.ready context")
                        .assertion("setup_ok", false);
            }
            Path root;
            Path v1;
            Path v2;
            Path data;
            Path driver;
            try {
                root = HonestPinsKit.scratch(ctx, "pin-e");
                v1 = root.resolve("v1/probe");
                v2 = root.resolve("v2/probe");
                data = root.resolve("vmg-data");
                driver = root.resolve("drive.py");
                Files.createDirectories(v1.getParent());
                Files.createDirectories(v2.getParent());
                Files.createDirectories(data);
                Files.writeString(v1, "#!/bin/sh\necho 1.0\n");
                Files.writeString(v2, "#!/bin/sh\necho 2.0\n");
                Files.writeString(driver, DRIVER);
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            ProcessRecord first = provision(ctx, "provision_v1", venvPython, driver, data, v1);
            String reported1 = reported(ctx, first);
            try {
                Files.delete(v1); // the control: an unchanged spec must not need its url again
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed removing v1: " + e).assertion("setup_ok", false);
            }
            ProcessRecord again = provision(ctx, "provision_v1_unchanged", venvPython, driver, data, v1);
            String reportedAgain = reported(ctx, again);
            ProcessRecord bumped = provision(ctx, "provision_v2_same_bin_path", venvPython, driver, data, v2);
            String reported2 = reported(ctx, bumped);

            boolean setupV1 = "1.0".equals(reported1);
            boolean control = "1.0".equals(reportedAgain);
            boolean shape = "2.0".equals(reported2);
            String detail = "v1 reported=" + reported1 + "; v1 again (url gone) reported=" + reportedAgain
                    + "; v2 (url changed, same bin_path) reported=" + reported2;
            NodeResult result = setupV1 && control && shape ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
            return result.process(first).process(again).process(bumped)
                    .assertion("setup_v1_provisioned_reports_1_0", setupV1)
                    .assertion("control_unchanged_spec_served_from_cache", control)
                    .assertion("shape_changed_url_serves_new_binary", shape)
                    .log(detail);
        });
    }

    private static ProcessRecord provision(com.hayden.testgraphsdk.sdk.NodeContext ctx, String label,
                                           String python, Path driver, Path data, Path binary) {
        Path gatewayDir = SmEnv.repoRoot().resolve("virtual-mcp-gateway");
        ProcessBuilder pb = new ProcessBuilder(python, driver.toString(), data.toString(),
                binary.toUri().toString()).directory(gatewayDir.toFile());
        pb.environment().put("PYTHONPATH", gatewayDir.toString());
        return Procs.run(ctx, label, pb);
    }

    /** The {@code reported} field of the driver's HP_RESULT line, or the error it printed. */
    private static String reported(com.hayden.testgraphsdk.sdk.NodeContext ctx, ProcessRecord record) {
        String out = HonestPinsKit.output(ctx, record);
        for (String line : out.split("\n")) {
            int i = line.indexOf("HP_RESULT ");
            if (i < 0) continue;
            String json = line.substring(i + "HP_RESULT ".length());
            String r = HonestPinsKit.jsonString(json, "reported");
            if (r != null) return r;
            String err = HonestPinsKit.jsonString(json, "error");
            return "(error: " + err + ")";
        }
        return "(no result, rc=" + record.exitCode() + ")";
    }
}
