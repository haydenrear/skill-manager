///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HonestPinsKit.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Record shape (c), #409: a CLI the system provides is reported as a stale
 * cli-shim, while a deleted shim skill-manager installed must still be.
 *
 * <p>Plant: one unit declaring two {@code tar:} CLI deps.
 * <ul>
 *   <li>{@code tar:sh} with {@code on_path = "sh"}: {@code /bin/sh} is on every
 *       PATH, so {@code CliPresence.alreadyProvided} answers ALREADY_PRESENT
 *       and nothing is linked into {@code bin/cli} — correct, and its url is
 *       never fetched.</li>
 *   <li>{@code tar:hp-own-probe}: served from a loopback server this node
 *       starts, so skill-manager really installs {@code bin/cli/hp-own-probe}.</li>
 * </ul>
 *
 * <p>HONEST: {@code artifacts stale --json} does not list
 * {@code cli-shim:tar/sh}. At 0.28.2 it does — ArtifactBackfill.cliShims
 * expects {@code bin/cli/<binary>} for every cli-lock row with a binary.
 *
 * <p>CONTROL: with the installed probe present it is not stale; once
 * {@code bin/cli/hp-own-probe} is deleted, {@code cli-shim:tar/hp-own-probe}
 * IS listed stale. The fix must not get there by ignoring cli-shims.
 */
public class RecordCSystemCliShim {
    static final String ID = "honest.record-c";
    static final String UNIT = "hp-record-c-unit";
    static final String SYSTEM_ID = "cli-shim:tar/sh";
    static final String OWN_ID = "cli-shim:tar/hp-own-probe";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("honest-pins", "record", "cli-shim", "artifacts-stale", "409")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path root;
            Path fixture;
            Path home;
            HttpServer server;
            try {
                root = HonestPinsKit.scratch(ctx, "record-c");
                fixture = root.resolve("fixture").resolve(UNIT);
                home = root.resolve("home");
                Files.createDirectories(home);
                byte[] probe = "#!/bin/sh\necho own\n".getBytes(StandardCharsets.UTF_8);
                InetAddress loopback = InetAddress.getLoopbackAddress();
                server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
                server.createContext("/own", exchange -> {
                    exchange.sendResponseHeaders(200, probe.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(probe);
                    }
                });
                server.start();
                String host = loopback instanceof Inet6Address
                        ? "[" + loopback.getHostAddress() + "]" : loopback.getHostAddress();
                String base = "http://" + host + ":" + server.getAddress().getPort();
                HonestPinsKit.writeSkill(fixture, UNIT, "0.1.0", "declares one system CLI and one installed CLI");
                Files.writeString(fixture.resolve("skill-manager.toml"), """
                        # Written by test_graph/sources/honest-pins/RecordCSystemCliShim.java.
                        [[cli_dependencies]]
                        spec = "tar:sh"
                        on_path = "sh"
                        platform_independent = true

                        [cli_dependencies.install.any]
                        url = "%s/never-fetched"
                        binary = "sh"

                        [[cli_dependencies]]
                        spec = "tar:hp-own-probe"
                        platform_independent = true

                        [cli_dependencies.install.any]
                        url = "%s/own"
                        binary = "hp-own-probe"

                        [skill]
                        name = "%s"
                        version = "0.1.0"
                        """.formatted(base, base, UNIT));
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e).assertion("setup_ok", false);
            }

            try {
                Path ownShim = home.resolve("bin/cli/hp-own-probe");
                ProcessRecord install = HonestPinsKit.sm(ctx, "install", root, home,
                        "install", fixture.toString(), "--yes", "--no-bind-default");
                String cliLock = HonestPinsKit.read(home.resolve("cli-lock.toml"));
                boolean setup = install.exitCode() == 0
                        && Files.isExecutable(ownShim)
                        && !Files.exists(home.resolve("bin/cli/sh"))
                        && cliLock.contains("[\"tar\".\"sh\"]")
                        && cliLock.contains("[\"tar\".\"hp-own-probe\"]");

                ProcessRecord stale1 = HonestPinsKit.sm(ctx, "artifacts_stale_installed", root, home,
                        "artifacts", "stale", "--json");
                String out1 = HonestPinsKit.output(ctx, stale1);
                boolean reportRead = out1.contains("\"stale_by_kind\"");
                boolean shape = stale1.exitCode() == 0 && reportRead && !staleListContains(out1, SYSTEM_ID);
                boolean controlPresentNotStale = stale1.exitCode() == 0 && reportRead && !staleListContains(out1, OWN_ID);

                Files.deleteIfExists(ownShim);
                ProcessRecord stale2 = HonestPinsKit.sm(ctx, "artifacts_stale_after_delete", root, home,
                        "artifacts", "stale", "--json");
                boolean controlDeletedIsStale = stale2.exitCode() == 0
                        && staleListContains(HonestPinsKit.output(ctx, stale2), OWN_ID);

                String detail = "system sh listed stale=" + staleListContains(out1, SYSTEM_ID)
                        + "; own probe present listed stale=" + staleListContains(out1, OWN_ID)
                        + "; own probe deleted listed stale=" + staleListContains(HonestPinsKit.output(ctx, stale2), OWN_ID)
                        + "; reportRead=" + reportRead + "; setup=" + setup;
                NodeResult result = setup && shape && controlPresentNotStale && controlDeletedIsStale
                        ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
                return result.process(install).process(stale1).process(stale2)
                        .assertion("setup_system_cli_unlinked_and_own_cli_installed", setup)
                        .assertion("control_installed_shim_present_is_not_stale", controlPresentNotStale)
                        .assertion("control_deleted_installed_shim_is_stale", controlDeletedIsStale)
                        .assertion("shape_system_cli_not_reported_stale", shape)
                        .log(detail);
            } catch (Exception e) {
                return NodeResult.fail(ID, "run failed: " + e);
            } finally {
                server.stop(0);
            }
        });
    }

    /** Whether the {@code "stale"} array of an {@code artifacts stale --json} report names {@code id}. */
    static boolean staleListContains(String report, String id) {
        int stale = report.indexOf("\"stale\" : [");
        if (stale < 0) stale = report.indexOf("\"stale\":[");
        if (stale < 0) return false;
        int end = report.indexOf("\"unverifiable\"", stale);
        String section = end < 0 ? report.substring(stale) : report.substring(stale, end);
        return section.contains("\"" + id + "\"");
    }
}
