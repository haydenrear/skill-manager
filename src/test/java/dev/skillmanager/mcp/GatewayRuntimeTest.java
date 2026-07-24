package dev.skillmanager.mcp;

import dev.skillmanager._lib.test.Tests;

import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class GatewayRuntimeTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("GatewayRuntimeTest");

        suite.test("POSIX gateway command starts a detached session and preserves argv", () -> {
            List<String> gateway = List.of(
                    "/tmp/gateway-python", "-m", "gateway.server", "--port", "51717");
            java.nio.file.Path pidFile = java.nio.file.Path.of("/tmp/gateway.pid");

            List<String> detached = GatewayRuntime.daemonizedGatewayCommand(
                    gateway, "/tmp/gateway-python", "Mac OS X", pidFile);

            assertEquals("/tmp/gateway-python", detached.get(0), "wrapper interpreter");
            assertEquals("-c", detached.get(1), "wrapper uses Python command mode");
            assertTrue(detached.get(2).contains("os.setsid()"), "wrapper creates a new session");
            assertTrue(detached.get(2).contains("os.fork()"), "wrapper double-forks");
            assertEquals(pidFile.toString(), detached.get(3), "wrapper receives pid file");
            assertEquals(gateway, detached.subList(4, detached.size()), "gateway argv preserved");
        });

        suite.test("non-POSIX gateway command is unchanged", () -> {
            List<String> gateway = List.of("python.exe", "-m", "gateway.server");

            List<String> command = GatewayRuntime.daemonizedGatewayCommand(
                    gateway, "python.exe", "Windows 11",
                    java.nio.file.Path.of("gateway.pid"));

            assertEquals(gateway, command, "non-POSIX command");
        });

        return suite.runAll();
    }
}
