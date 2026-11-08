///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * #404 — bumping a unit's own declared CLI pin changes the installed binary.
 *
 * <p>One node, the issue's harness verbatim:
 * <ol>
 *   <li>Serve two builds of one raw-binary CLI, {@code pin-probe}, from a
 *       loopback server inside this node: {@code /v1} prints {@code 1.0},
 *       {@code /v2} prints {@code 2.0}.</li>
 *   <li>Write a fixture skill declaring {@code tar:pin-probe} at
 *       {@code min_version = "1.0"} (url {@code /v1}), install it into a
 *       private home, and RUN {@code bin/cli/pin-probe}: it must print 1.0.</li>
 *   <li>Edit the fixture to {@code min_version = "2.0"} (url {@code /v2}) and
 *       {@code sync --from} it. Run {@code bin/cli/pin-probe} again: it must
 *       print 2.0, and {@code cli-lock.toml} must record 2.0.</li>
 *   <li>Sync once more with nothing changed: no download (the server's hit
 *       count does not move) — the fix must not reinstall every pass.</li>
 * </ol>
 *
 * <p>Before #404 step 3 printed 1.0: {@code TarBackend} returned
 * ALREADY_PRESENT because {@code bin/cli/pin-probe} was executable, and the
 * lock recorded 2.0 over it.
 *
 * <p>Only {@code tar:} runs here — the one backend that needs no network and no
 * bundled package manager. pip and npm share the gate
 * ({@code InstallerRegistry.installOne}); their bump is pinned by
 * {@code CliPinTest}.
 */
public class CliPinBumpReinstalls {
    static final String ID = "cli.pin.bump.reinstalls";
    static final NodeSpec SPEC = NodeSpec.of(ID)
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cli", "tar", "cli-lock", "404")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) return NodeResult.fail(ID, "missing env.prepared context");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path root = Path.of(envHome).resolve("cli-pin-home");
            Path agents = root.resolve("agents");
            Path fixture = root.resolve("fixture");
            Path home = root.resolve("home");
            Path probe = home.resolve("bin/cli/pin-probe");

            AtomicInteger hits = new AtomicInteger();
            HttpServer server;
            String base;
            byte[] v1 = script("1.0");
            byte[] v2 = script("2.0");
            try {
                Files.createDirectories(agents.resolve("claude"));
                Files.createDirectories(agents.resolve("codex"));
                Files.createDirectories(agents.resolve("gemini"));
                InetAddress loopback = InetAddress.getLoopbackAddress();
                server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
                serve(server, "/v1", v1, hits);
                serve(server, "/v2", v2, hits);
                server.start();
                String host = loopback instanceof Inet6Address
                        ? "[" + loopback.getHostAddress() + "]" : loopback.getHostAddress();
                base = "http://" + host + ":" + server.getAddress().getPort();
                writeFixture(fixture, "1.0", base + "/v1", sha256(v1));
            } catch (Exception e) {
                return NodeResult.fail(ID, "setup failed: " + e);
            }

            try {
                ProcessRecord install = Procs.run(ctx, "install",
                        sm(sm, home, agents, "install", fixture.toString(), "--yes"));
                String reported1 = runProbe(probe);
                int hitsAfterInstall = hits.get();

                writeFixture(fixture, "2.0", base + "/v2", sha256(v2));
                ProcessRecord bump = Procs.run(ctx, "sync_after_bump",
                        sm(sm, home, agents, "sync", "--from", fixture.toString(),
                                "cli-pin-skill", "--yes"));
                String reported2 = runProbe(probe);
                String lock = readOrEmpty(home.resolve("cli-lock.toml"));
                boolean lockSaysTwo = lock.contains("version = \"2.0\"")
                        && !lock.contains("version = \"1.0\"");
                int hitsAfterBump = hits.get();

                ProcessRecord noop = Procs.run(ctx, "sync_noop",
                        sm(sm, home, agents, "sync", "--from", fixture.toString(),
                                "cli-pin-skill", "--yes"));
                String reported3 = runProbe(probe);
                int hitsAfterNoop = hits.get();

                boolean pass = install.exitCode() == 0 && "1.0".equals(reported1)
                        && bump.exitCode() == 0 && "2.0".equals(reported2) && lockSaysTwo
                        && hitsAfterBump == hitsAfterInstall + 1
                        && noop.exitCode() == 0 && "2.0".equals(reported3)
                        && hitsAfterNoop == hitsAfterBump;

                String detail = "install rc=" + install.exitCode() + " reported=" + reported1
                        + "; bump rc=" + bump.exitCode() + " reported=" + reported2
                        + " lockSaysTwo=" + lockSaysTwo
                        + "; noop rc=" + noop.exitCode() + " reported=" + reported3
                        + "; downloads install/bump/noop=" + hitsAfterInstall + "/"
                        + hitsAfterBump + "/" + hitsAfterNoop;
                NodeResult result = pass ? NodeResult.pass(ID) : NodeResult.fail(ID, detail);
                return result.process(install).process(bump).process(noop)
                        .assertion("install_ok", install.exitCode() == 0)
                        .assertion("v1_installed_reports_1_0", "1.0".equals(reported1))
                        .assertion("bump_sync_ok", bump.exitCode() == 0)
                        .assertion("bumped_binary_reports_2_0", "2.0".equals(reported2))
                        .assertion("cli_lock_records_2_0", lockSaysTwo)
                        .assertion("bump_downloaded_once", hitsAfterBump == hitsAfterInstall + 1)
                        .assertion("noop_sync_ok", noop.exitCode() == 0)
                        .assertion("noop_keeps_2_0", "2.0".equals(reported3))
                        .assertion("noop_does_not_redownload", hitsAfterNoop == hitsAfterBump)
                        .metric("downloads", hitsAfterNoop);
            } catch (Exception e) {
                return NodeResult.fail(ID, "run failed: " + e);
            } finally {
                server.stop(0);
            }
        });
    }

    private static byte[] script(String version) {
        return ("#!/bin/sh\necho " + version + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void serve(HttpServer server, String path, byte[] body, AtomicInteger hits) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static void writeFixture(Path dir, String version, String url, String sha)
            throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: cli-pin-skill
                description: Test-graph fixture for #404. Declares one tar CLI dep whose pin the cli-pin graph bumps between an install and a sync.
                skill-imports: []
                ---

                # cli-pin-skill

                Declares `tar:pin-probe`. The cli-pin graph rewrites its pin.
                """);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                # Written by test_graph/sources/cli-pin/CliPinBumpReinstalls.java.
                [[cli_dependencies]]
                spec = "tar:pin-probe"
                min_version = "%s"
                platform_independent = true

                [cli_dependencies.install.any]
                url = "%s"
                binary = "pin-probe"
                sha256 = "%s"

                [skill]
                name = "cli-pin-skill"
                version = "0.1.0"
                """.formatted(version, url, sha));
    }

    /** What bin/cli/pin-probe prints when RUN — the binary's own answer. */
    private static String runProbe(Path probe) {
        if (!Files.isExecutable(probe)) return "(absent)";
        try {
            // Output to a file, then wait: reading the pipe first would block
            // until exit and the timeout could never fire.
            Path out = Files.createTempFile("pin-probe", ".out");
            try {
                Process p = new ProcessBuilder(probe.toString()).redirectErrorStream(true)
                        .redirectOutput(out.toFile()).start();
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "(timeout)";
                }
                return Files.readString(out).trim();
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ProcessBuilder sm(Path sm, Path home, Path agents, String... cliArgs) {
        List<String> argv = new java.util.ArrayList<>();
        argv.add(sm.toString());
        argv.addAll(List.of(cliArgs));
        ProcessBuilder pb = new ProcessBuilder(argv);
        SmEnv.apply(pb, home.toString(), SmEnv.sandbox(
                agents.resolve("claude"), agents.resolve("codex"), agents.resolve("gemini")));
        return pb;
    }
}
