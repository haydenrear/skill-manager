package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;

public final class ProjectCommandTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectCommandTest")
                .test("relative explicit manifest resolves under project dir", () -> {
                    Path projectRoot = Path.of("/tmp/project-root").toAbsolutePath().normalize();

                    assertEquals(
                            projectRoot.resolve("agent-harness.toml"),
                            ProjectCommand.resolveManifestPath(projectRoot, "agent-harness.toml"),
                            "relative manifest path");
                    assertEquals(
                            projectRoot.resolve("profiles/dev.toml"),
                            ProjectCommand.resolveManifestPath(projectRoot, "profiles/dev.toml"),
                            "nested relative manifest path");
                })
                .test("absolute explicit manifest is preserved", () -> {
                    Path projectRoot = Path.of("/tmp/project-root").toAbsolutePath().normalize();
                    Path manifest = Path.of("/tmp/other/skill-project.toml").toAbsolutePath().normalize();

                    assertEquals(
                            manifest,
                            ProjectCommand.resolveManifestPath(projectRoot, manifest.toString()),
                            "absolute manifest path");
                })
                .runAll();
    }
}
