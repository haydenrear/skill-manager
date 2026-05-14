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
                .runAll();
    }
}
