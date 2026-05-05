package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Pins the legacy {@code sources/} → {@code installed/} migration. The
 * reconciler runs this on first startup after upgrade — it's the
 * single hand-off that lets users keep their install state without
 * manual intervention.
 *
 * <p>Contracts:
 * <ul>
 *   <li>Every {@code sources/<name>.json} moves to
 *       {@code installed/<name>.json}; legacy file is deleted.</li>
 *   <li>{@code unitKind} defaults to {@link UnitKind#SKILL} on
 *       migrated records (since legacy installs predate plugins).</li>
 *   <li>Idempotent — second run finds no work, returns 0, leaves
 *       state untouched.</li>
 *   <li>Pre-existing {@code installed/<name>.json} blocks
 *       overwrite — the legacy file is dropped without clobbering
 *       the modern record.</li>
 *   <li>No-op when {@code sources/} doesn't exist (fresh installs).</li>
 *   <li>Empty {@code sources/} dir is removed after migration.</li>
 * </ul>
 *
 * <p>Note: ticket 03's substrate uses real temp directories; the
 * {@code InMemoryFs} fake noted in the ticket is deferred to
 * tickets 06+ where failure-injection sweeps need it.
 */
public final class MigrationFromSkillSourceTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("migration-from-skill-source-test-");

        return Tests.suite("MigrationFromSkillSourceTest")
                .test("migrates legacy records and deletes originals", () -> {
                    Path home = tmp.resolve("home-1");
                    seedLegacy(home, "alpha", legacyRecord("alpha"));
                    seedLegacy(home, "beta", legacyRecord("beta"));

                    SkillStore store = new SkillStore(home);
                    int moved = UnitStore.migrateFromLegacy(store);
                    assertEquals(2, moved, "two records migrated");

                    UnitStore us = new UnitStore(store);
                    InstalledUnit alpha = us.read("alpha").orElseThrow();
                    InstalledUnit beta = us.read("beta").orElseThrow();
                    assertEquals(UnitKind.SKILL, alpha.unitKind(), "alpha → SKILL");
                    assertEquals(UnitKind.SKILL, beta.unitKind(), "beta → SKILL");
                    assertEquals("alpha", alpha.name(), "alpha name preserved");
                    assertEquals(InstalledUnit.Kind.GIT, alpha.kind(), "alpha storage kind preserved");

                    assertFalse(Files.exists(home.resolve("sources/alpha.json")),
                            "legacy alpha removed");
                    assertFalse(Files.exists(home.resolve("sources/beta.json")),
                            "legacy beta removed");
                })
                .test("idempotent: second call is a no-op", () -> {
                    Path home = tmp.resolve("home-2");
                    seedLegacy(home, "x", legacyRecord("x"));

                    SkillStore store = new SkillStore(home);
                    int firstRun = UnitStore.migrateFromLegacy(store);
                    int secondRun = UnitStore.migrateFromLegacy(store);
                    assertEquals(1, firstRun, "first run migrates");
                    assertEquals(0, secondRun, "second run is a no-op");
                })
                .test("preserves existing installed/ records", () -> {
                    Path home = tmp.resolve("home-3");
                    SkillStore store = new SkillStore(home);
                    store.init();

                    // Pre-existing modern record
                    UnitStore us = new UnitStore(store);
                    us.write(new InstalledUnit("collide", "2.0.0", InstalledUnit.Kind.GIT,
                            InstalledUnit.InstallSource.GIT, "newer-origin", "newhash", "main",
                            "2026-05-05T12:00:00Z", null, UnitKind.PLUGIN));

                    // Legacy record with the same name — represents a corrupt
                    // post-migration state we must not silently overwrite
                    seedLegacy(home, "collide", legacyRecord("collide"));

                    int moved = UnitStore.migrateFromLegacy(store);
                    assertEquals(0, moved, "nothing migrated when target exists");

                    InstalledUnit kept = us.read("collide").orElseThrow();
                    assertEquals("2.0.0", kept.version(), "modern record preserved");
                    assertEquals(UnitKind.PLUGIN, kept.unitKind(), "modern unitKind preserved");
                    assertFalse(Files.exists(home.resolve("sources/collide.json")),
                            "legacy file dropped after no-op skip");
                })
                .test("no-op when sources/ does not exist", () -> {
                    Path home = tmp.resolve("home-4");
                    SkillStore store = new SkillStore(home);
                    int moved = UnitStore.migrateFromLegacy(store);
                    assertEquals(0, moved, "nothing to migrate");
                })
                .test("empty sources/ dir is removed after migration", () -> {
                    Path home = tmp.resolve("home-5");
                    Path legacy = home.resolve("sources");
                    Files.createDirectories(legacy);
                    Path nonJson = legacy.resolve("README.txt");
                    // The migration only globs *.json, so non-JSON files are
                    // ignored. The legacy dir should not be deleted while
                    // unrelated files remain.
                    Files.writeString(nonJson, "stray");

                    SkillStore store = new SkillStore(home);
                    int moved = UnitStore.migrateFromLegacy(store);
                    assertEquals(0, moved, "no JSON files to migrate");
                    assertTrue(Files.exists(legacy), "non-empty legacy dir is preserved");
                    assertTrue(Files.exists(nonJson), "stray file untouched");

                    Files.delete(nonJson);
                    UnitStore.migrateFromLegacy(store);
                    assertFalse(Files.exists(legacy), "empty legacy dir is cleaned up");
                })
                .test("post-migration: read goes through UnitStore", () -> {
                    Path home = tmp.resolve("home-6");
                    seedLegacy(home, "echo", legacyRecord("echo"));

                    SkillStore store = new SkillStore(home);
                    UnitStore.migrateFromLegacy(store);

                    UnitStore us = new UnitStore(store);
                    InstalledUnit read = us.read("echo").orElseThrow();
                    assertEquals("echo", read.name(), "name parsed");
                    assertEquals(UnitKind.SKILL, read.unitKind(), "unitKind defaulted");
                })
                .runAll();
    }

    // -------------------------------------------------------------- helpers

    private static String legacyRecord(String name) {
        // Wire format predating ticket 03 — no unitKind field.
        return """
                {
                  "name": "%s",
                  "version": "1.0.0",
                  "kind": "GIT",
                  "installSource": "REGISTRY",
                  "origin": "https://github.com/x/%s",
                  "gitHash": "abc123def456",
                  "gitRef": "main",
                  "installedAt": "2025-12-01T00:00:00Z",
                  "errors": []
                }
                """.formatted(name, name);
    }

    private static void seedLegacy(Path home, String unitName, String json) throws IOException {
        Path legacy = home.resolve("sources");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve(unitName + ".json"), json);
    }
}
