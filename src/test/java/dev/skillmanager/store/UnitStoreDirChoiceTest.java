package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The store routes per-unit directories by {@link UnitKind}: skills
 * land under {@code skills/<name>}, plugins under {@code plugins/<name>}.
 * This is the only kind-aware code path in the store today; later
 * tickets (08+) will route through {@link SkillStore#unitDir(String, UnitKind)}
 * from effect handlers, but the routing itself is pinned here.
 *
 * <p>Also exercises {@link SkillStore#init()} creating the new
 * {@code plugins/} and {@code installed/} directories alongside the
 * existing layout, and confirms the deprecated {@code sourcesDir()}
 * accessor returns the same path as {@link SkillStore#installedDir()}
 * for back-compat.
 */
public final class UnitStoreDirChoiceTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("unit-store-dir-choice-test-");

        return Tests.suite("UnitStoreDirChoiceTest")
                .test("unitDir(SKILL) routes to skills/<name>", () -> {
                    SkillStore store = new SkillStore(tmp.resolve("home-1"));
                    Path d = store.unitDir("hello", UnitKind.SKILL);
                    assertEquals(store.skillsDir().resolve("hello"), d, "skill dir under skills/");
                })
                .test("unitDir(PLUGIN) routes to plugins/<name>", () -> {
                    SkillStore store = new SkillStore(tmp.resolve("home-2"));
                    Path d = store.unitDir("repo-intelligence", UnitKind.PLUGIN);
                    assertEquals(store.pluginsDir().resolve("repo-intelligence"), d, "plugin dir under plugins/");
                })
                .test("init() creates plugins/ and installed/ alongside skills/", () -> {
                    Path home = tmp.resolve("home-3");
                    SkillStore store = new SkillStore(home);
                    store.init();
                    assertTrue(Files.isDirectory(store.skillsDir()), "skills/ created");
                    assertTrue(Files.isDirectory(store.pluginsDir()), "plugins/ created");
                    assertTrue(Files.isDirectory(store.installedDir()), "installed/ created");
                })
                .test("sourcesDir() is a deprecated alias for installedDir()", () -> {
                    SkillStore store = new SkillStore(tmp.resolve("home-4"));
                    @SuppressWarnings("deprecation")
                    Path legacyAccessor = store.sourcesDir();
                    assertEquals(store.installedDir(), legacyAccessor,
                            "sourcesDir() returns installedDir()");
                })
                .test("pluginsDir() resolves under root", () -> {
                    Path home = tmp.resolve("home-5");
                    SkillStore store = new SkillStore(home);
                    assertEquals(home.resolve("plugins"), store.pluginsDir(), "plugins/ under root");
                })
                .runAll();
    }
}
