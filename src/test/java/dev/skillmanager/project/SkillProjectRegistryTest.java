package dev.skillmanager.project;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class SkillProjectRegistryTest {

    public static int run() throws Exception {
        return Tests.suite("SkillProjectRegistryTest")
                .test("register snapshots manifest intent without installing dependencies", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("registered-project-");
                        Files.writeString(projectDir.resolve("skill-project.toml"), """
                                [project]
                                name = "registered"

                                [skills.hello]
                                source = "skill:hello"

                                [envs.dev]
                                dependencies = ["pytest"]

                                [[libs]]
                                name = "lib-a"
                                source = "github:org/lib-a"
                                """);
                        SkillProject project = SkillProjectParser.load(projectDir);

                        SkillProjectRegistration registration =
                                new SkillProjectRegistry(h.store()).register(project);

                        assertEquals("registered", registration.name(), "registration name");
                        assertTrue(Files.isDirectory(h.store().projectsDir().resolve("registered")),
                                "project registry dir exists");
                        assertTrue(Files.isRegularFile(registration.registrationDir().resolve("registration.toml")),
                                "registration metadata exists");
                        assertTrue(Files.isRegularFile(registration.registrationDir().resolve("skill-project.toml")),
                                "manifest snapshot exists");
                        assertFalse(h.store().containsUnit("hello"), "dependency was not installed");

                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        SkillProject snapshot = registry.loadSnapshot("registered").orElseThrow();
                        assertEquals(1, snapshot.skills().size(), "snapshot skill intent");
                        assertEquals(1, snapshot.envs().size(), "snapshot env intent");
                        assertEquals(1, snapshot.libs().size(), "snapshot lib intent");
                    }
                })
                .test("list and read return registered projects", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("listed-project-");
                        Files.writeString(projectDir.resolve("skill-project.toml"), """
                                [project]
                                name = "listed"
                                """);
                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        registry.register(SkillProjectParser.load(projectDir));

                        assertTrue(registry.read("listed").isPresent(), "project read");
                        assertEquals(1, registry.list().size(), "one registered project");
                    }
                })
                .test("loadSnapshot uses recorded custom manifest filename", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("custom-manifest-project-");
                        Path customManifest = projectDir.resolve("agent-harness.toml");
                        Files.writeString(customManifest, """
                                [project]
                                name = "custom-manifest"

                                [skills.current]
                                source = "skill:current"
                                """);
                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        SkillProjectRegistration registration =
                                registry.register(SkillProjectParser.loadManifest(customManifest, projectDir));

                        Files.writeString(registration.registrationDir().resolve("skill-project.toml"), """
                                [project]
                                name = "custom-manifest"

                                [skills.stale]
                                source = "skill:stale"
                                """);

                        SkillProject snapshot = registry.loadSnapshot("custom-manifest").orElseThrow();
                        assertEquals("agent-harness.toml", registration.manifestFile(), "recorded manifest file");
                        assertEquals("current", snapshot.skills().get(0).alias(), "loads recorded manifest snapshot");
                    }
                })
                .test("#394: loadCurrent reads the repository's manifest, not an aged cached copy", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("current-manifest-project-");
                        Files.writeString(projectDir.resolve("skill-project.toml"), """
                                [project]
                                name = "current-manifest"

                                [skills.one]
                                source = "skill:one"

                                [plugins.skt]
                                source = "github:haydenrear/skill-publisher-skill"
                                """);
                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        SkillProjectRegistration registration =
                                registry.register(SkillProjectParser.load(projectDir));
                        // The incident's aged copy: declares no plugins.
                        Files.writeString(registration.registrationDir().resolve("skill-project.toml"), """
                                [project]
                                name = "current-manifest"

                                [skills.one]
                                source = "skill:one"
                                """);

                        assertEquals(0, registry.loadSnapshot("current-manifest").orElseThrow().plugins().size(),
                                "the snapshot is the aged copy");
                        SkillProject current = registry.loadCurrent("current-manifest").orElseThrow();
                        assertEquals(1, current.plugins().size(), "loadCurrent sees the repository's plugin");
                    }
                })
                .test("#394: a registration that points at its own snapshot still reads the repository", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("self-ref-project-");
                        Files.writeString(projectDir.resolve("skill-project.toml"), """
                                [project]
                                name = "self-ref"

                                [plugins.skt]
                                source = "github:haydenrear/skill-publisher-skill"
                                """);
                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        SkillProjectRegistration first = registry.register(SkillProjectParser.load(projectDir));
                        Path snapshot = first.registrationDir().resolve("skill-project.toml");
                        Files.writeString(snapshot, """
                                [project]
                                name = "self-ref"
                                """);
                        // Re-register FROM the snapshot: manifest_path now names the cache.
                        registry.register(SkillProjectParser.loadManifest(snapshot, projectDir));
                        assertTrue(registry.read("self-ref").orElseThrow().manifestPath().startsWith(
                                first.registrationDir()), "registration points at its own snapshot");

                        SkillProject current = registry.loadCurrent("self-ref").orElseThrow();
                        assertEquals(1, current.plugins().size(), "read <project_root>/<manifest_file>");
                    }
                })
                .test("#394: loadCurrent refuses, never falls back, when the repository's manifest is gone", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("gone-manifest-project-");
                        Files.writeString(projectDir.resolve("skill-project.toml"), """
                                [project]
                                name = "gone-manifest"
                                """);
                        SkillProjectRegistry registry = new SkillProjectRegistry(h.store());
                        registry.register(SkillProjectParser.load(projectDir));
                        Files.delete(projectDir.resolve("skill-project.toml"));

                        boolean refused = false;
                        try {
                            registry.loadCurrent("gone-manifest");
                        } catch (ProjectManifestUnreadableException expected) {
                            refused = expected.manifest().endsWith("skill-project.toml");
                        }
                        assertTrue(refused, "unreadable manifest is a typed refusal naming the file");
                        assertTrue(registry.loadSnapshot("gone-manifest").isPresent(),
                                "the snapshot is still there, and still not consulted");
                    }
                })
                .test("register rejects snapshot filename that collides with registry metadata", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path projectDir = Files.createTempDirectory("reserved-manifest-project-");
                        Path reservedManifest = projectDir.resolve("registration.toml");
                        Files.writeString(reservedManifest, """
                                [project]
                                name = "reserved-manifest"

                                [skills.current]
                                source = "skill:current"
                                """);
                        SkillProject project = SkillProjectParser.loadManifest(reservedManifest, projectDir);
                        boolean rejected = false;
                        try {
                            new SkillProjectRegistry(h.store()).register(project);
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("manifest_file");
                        }
                        assertTrue(rejected, "registration.toml is reserved for registry metadata");
                    }
                })
                .runAll();
    }
}
