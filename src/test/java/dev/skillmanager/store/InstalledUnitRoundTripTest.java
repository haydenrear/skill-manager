package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Pins the {@link InstalledUnit} JSON wire format. Critical contracts:
 *
 * <ul>
 *   <li>Round-tripping a freshly-built unit through {@link UnitStore}
 *       returns the same record, including the new {@code unitKind}
 *       field.</li>
 *   <li>Legacy JSON (no {@code unitKind} field) reads back as
 *       {@link UnitKind#SKILL} — preserves the on-disk shape so
 *       upgraded installs don't silently lose meaning.</li>
 *   <li>Unknown JSON keys are ignored (forward-compat for fields
 *       added by future tickets).</li>
 * </ul>
 */
public final class InstalledUnitRoundTripTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("installed-unit-round-trip-test-");

        return Tests.suite("InstalledUnitRoundTripTest")
                .test("write+read returns the same record (skill)", () -> {
                    SkillStore store = freshStore(tmp.resolve("home-1"));
                    UnitStore us = new UnitStore(store);
                    InstalledUnit unit = new InstalledUnit(
                            "hello", "1.0.0", InstalledUnit.Kind.GIT,
                            InstalledUnit.InstallSource.REGISTRY,
                            "https://github.com/x/y", "abc123def456", "main",
                            "2026-05-05T12:00:00Z", null, UnitKind.SKILL);
                    us.write(unit);
                    InstalledUnit read = us.read("hello").orElseThrow();
                    assertEquals(unit.name(), read.name(), "name");
                    assertEquals(unit.version(), read.version(), "version");
                    assertEquals(unit.kind(), read.kind(), "storage kind");
                    assertEquals(unit.installSource(), read.installSource(), "install source");
                    assertEquals(unit.origin(), read.origin(), "origin");
                    assertEquals(unit.gitHash(), read.gitHash(), "git hash");
                    assertEquals(unit.gitRef(), read.gitRef(), "git ref");
                    assertEquals(UnitKind.SKILL, read.unitKind(), "unitKind");
                })
                .test("write+read returns the same record (plugin)", () -> {
                    SkillStore store = freshStore(tmp.resolve("home-2"));
                    UnitStore us = new UnitStore(store);
                    InstalledUnit unit = new InstalledUnit(
                            "repo-intelligence", "0.4.2", InstalledUnit.Kind.GIT,
                            InstalledUnit.InstallSource.REGISTRY,
                            "https://github.com/x/repo-intel", "fed321", "main",
                            "2026-05-05T12:00:00Z", null, UnitKind.PLUGIN);
                    us.write(unit);
                    InstalledUnit read = us.read("repo-intelligence").orElseThrow();
                    assertEquals(UnitKind.PLUGIN, read.unitKind(), "unitKind round-tripped");
                })
                .test("legacy JSON without unitKind reads as SKILL", () -> {
                    SkillStore store = freshStore(tmp.resolve("home-3"));
                    UnitStore us = new UnitStore(store);
                    Files.writeString(us.file("legacy"),
                            """
                            {
                              "name": "legacy",
                              "version": "1.0.0",
                              "kind": "GIT",
                              "installSource": "REGISTRY",
                              "origin": "https://github.com/x/y",
                              "gitHash": "abc",
                              "gitRef": "main",
                              "installedAt": "2026-05-05T12:00:00Z",
                              "errors": []
                            }
                            """);
                    InstalledUnit read = us.read("legacy").orElseThrow();
                    assertEquals(UnitKind.SKILL, read.unitKind(),
                            "missing unitKind defaults to SKILL");
                    assertEquals("legacy", read.name(), "name preserved");
                    assertEquals(InstalledUnit.Kind.GIT, read.kind(), "storage kind preserved");
                })
                .test("unknown JSON fields are ignored (forward-compat)", () -> {
                    SkillStore store = freshStore(tmp.resolve("home-4"));
                    UnitStore us = new UnitStore(store);
                    Files.writeString(us.file("future"),
                            """
                            {
                              "name": "future",
                              "version": "1.0.0",
                              "futureField": "ignored",
                              "anotherFuture": { "nested": true },
                              "kind": "GIT",
                              "unitKind": "PLUGIN"
                            }
                            """);
                    InstalledUnit read = us.read("future").orElseThrow();
                    assertEquals("future", read.name(), "name parsed past unknowns");
                    assertEquals(UnitKind.PLUGIN, read.unitKind(), "unitKind parsed");
                })
                .test("addError / clearError mutate via the store", () -> {
                    SkillStore store = freshStore(tmp.resolve("home-5"));
                    UnitStore us = new UnitStore(store);
                    us.write(new InstalledUnit("x", "1.0.0", InstalledUnit.Kind.GIT,
                            InstalledUnit.InstallSource.REGISTRY, "o", "h", "main",
                            "2026-05-05T12:00:00Z", null, UnitKind.SKILL));
                    us.addError("x", InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION, "reason");
                    InstalledUnit afterAdd = us.read("x").orElseThrow();
                    assertSize(1, afterAdd.errors(), "one error added");
                    assertTrue(afterAdd.hasError(InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION),
                            "hasError reflects added");
                    us.clearError("x", InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION);
                    InstalledUnit afterClear = us.read("x").orElseThrow();
                    assertSize(0, afterClear.errors(), "error cleared");
                })
                .runAll();
    }

    private static SkillStore freshStore(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }
}
