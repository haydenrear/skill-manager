package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.GatewayCommand;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;

public final class GatewayStatusCommandTest {

    public static int run() throws Exception {
        return Tests.suite("GatewayStatusCommandTest")
                .test("unreachable gateway reports down and points at gateway up", () -> {
                    SkillStore store = new SkillStore(Files.createTempDirectory("gateway-status-"));
                    store.init();
                    GatewayConfig.persist(store, "http://127.0.0.1:1");

                    PrintStream original = System.out;
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    int rc;
                    try {
                        System.setOut(new PrintStream(buf));
                        rc = new GatewayCommand.Status(store).call();
                    } finally {
                        System.setOut(original);
                    }

                    String out = buf.toString();
                    assertEquals(2, rc, "unreachable status exit code");
                    assertContains(out, "health:       unreachable", "health is explicit");
                    assertContains(out, "status:       down", "down summary is explicit");
                    assertContains(out, "run `skill-manager gateway up` to initialize",
                            "recovery command is printed");
                })
                .test("F6: a binary a failed reprovision kept serving is named by status", () -> {
                    com.sun.net.httpserver.HttpServer gateway =
                            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                    respond(gateway, "/health", "{\"status\":\"ok\"}");
                    respond(gateway, "/servers", """
                            {"items":[{"server_id":"probe","stale_binary":"URLError: offline"},
                                      {"server_id":"fine"}],"count":2}""");
                    gateway.start();
                    try {
                        SkillStore store = new SkillStore(Files.createTempDirectory("gateway-status-stale-"));
                        store.init();
                        GatewayConfig.persist(store, "http://127.0.0.1:" + gateway.getAddress().getPort());
                        PrintStream original = System.out;
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        int rc;
                        try {
                            System.setOut(new PrintStream(buf));
                            rc = new GatewayCommand.Status(store).call();
                        } finally {
                            System.setOut(original);
                        }
                        String out = buf.toString();
                        assertEquals(0, rc, "a stale binary is still an up gateway");
                        assertContains(out, "stale binary: probe", "names the server");
                        assertContains(out, "URLError: offline", "and why");
                        assertEquals(false, out.contains("stale binary: fine"), "and only that server");
                    } finally {
                        gateway.stop(0);
                    }
                })
                .runAll();
    }

    private static void respond(com.sun.net.httpserver.HttpServer server, String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
