package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OHV-3 (c), DEF-OHV-004: a record whose hash is the checkout's HEAD states the
 * checkout's manifest version — and a record whose hash is anywhere else is left
 * exactly as it is.
 */
public final class RecordVersionRefreshTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("RecordVersionRefreshTest");

        suite.test("same hash, newer manifest: the record's version is restated", () -> {
            SkillStore store = home();
            String head = checkout(store, "vskill", "0.8.2");
            InstalledUnit record = record("vskill", "0.8.1", head);
            Optional<InstalledUnit> out = RecordVersionRefresh.refreshed(store, record);
            assertTrue(out.isPresent(), "a stale version at the same hash is refreshed");
            assertEquals("0.8.2", out.get().version(), "from the manifest");
            assertEquals(head, out.get().gitHash(), "and nothing else moves");
        });

        suite.test("a hash that is not HEAD changes nothing", () -> {
            SkillStore store = home();
            checkout(store, "vskill", "0.8.2");
            InstalledUnit record = record("vskill", "0.8.1",
                    "0000000000000000000000000000000000000000");
            assertTrue(RecordVersionRefresh.refreshed(store, record).isEmpty(),
                    "the manifest on disk is not the one this record describes");
        });

        suite.test("an agreeing version, or no hash, changes nothing", () -> {
            SkillStore store = home();
            String head = checkout(store, "vskill", "0.8.2");
            assertTrue(RecordVersionRefresh.refreshed(store, record("vskill", "0.8.2", head)).isEmpty(),
                    "already agrees");
            assertTrue(RecordVersionRefresh.refreshed(store, record("vskill", "0.8.1", null)).isEmpty(),
                    "no hash, no claim about which checkout");
        });

        suite.test("HP-4: after a wholesale write, a plain directory's manifest is restated", () -> {
            SkillStore store = home();
            plain(store, "vskill", "0.3.0");
            InstalledUnit record = record("vskill", "0.1.0", null);
            assertTrue(RecordVersionRefresh.refreshed(store, record).isEmpty(),
                    "the git-guarded refresh declines a record with no hash");
            Optional<InstalledUnit> out = RecordVersionRefresh.restatedAfterWrite(store, record);
            assertTrue(out.isPresent(), "the write path restates it");
            assertEquals("0.3.0", out.get().version(), "from the manifest just written");
        });

        suite.test("HP-4: restateAfterWrite rewrites an existing record and invents none", () -> {
            SkillStore store = home();
            plain(store, "vskill", "0.2.0");
            assertTrue(!RecordVersionRefresh.restateAfterWrite(store, "vskill"),
                    "no installed record: nothing is created");
            assertTrue(new UnitStore(store).read("vskill").isEmpty(), "still none");
            new UnitStore(store).write(record("vskill", "0.1.0", null));
            assertTrue(RecordVersionRefresh.restateAfterWrite(store, "vskill"), "rewritten");
            assertEquals("0.2.0", new UnitStore(store).read("vskill").orElseThrow().version(),
                    "the record on disk now names the manifest's version");
            assertTrue(!RecordVersionRefresh.restateAfterWrite(store, "vskill"),
                    "a second pass agrees and writes nothing");
        });

        suite.test("HP-4: a manifest of another kind, or no version, changes nothing", () -> {
            SkillStore store = home();
            plain(store, "vskill", "0.2.0");
            InstalledUnit plugin = new InstalledUnit("vskill", "0.1.0", InstalledUnit.Kind.GIT,
                    InstalledUnit.InstallSource.GIT, "x", null, "main", "2026-01-01T00:00:00Z",
                    List.of(), UnitKind.PLUGIN);
            assertTrue(RecordVersionRefresh.restatedAfterWrite(store, plugin).isEmpty(),
                    "a record of another kind is not described by this manifest");
            SkillStore bare = home();
            Path dir = bare.skillDir("vskill");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: vskill\ndescription: fixture\n---\nbody\n");
            assertTrue(RecordVersionRefresh.restatedAfterWrite(bare, record("vskill", "0.1.0", null))
                    .isEmpty(), "no declared version, no claim");
        });

        return suite.runAll();
    }

    /** A skill in the store that is not a repository. */
    private static void plain(SkillStore store, String name, String version) throws Exception {
        Path dir = store.skillDir(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: fixture\n---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + name + "\"\nversion = \"" + version + "\"\n"
                        + "description = \"fixture\"\n");
    }

    private static SkillStore home() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("record-version-home-"));
        store.init();
        return store;
    }

    private static InstalledUnit record(String name, String version, String hash) {
        return new InstalledUnit(name, version, InstalledUnit.Kind.GIT,
                InstalledUnit.InstallSource.GIT, "https://example.invalid/" + name + ".git",
                hash, "main", "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL);
    }

    /** A committed skill checkout in the store; returns HEAD. */
    private static String checkout(SkillStore store, String name, String version) throws Exception {
        Path dir = store.skillDir(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: fixture\n---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + name + "\"\nversion = \"" + version + "\"\n"
                        + "description = \"fixture\"\n");
        git(dir, "init", "-q");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
        return GitOps.headHash(dir);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new AssertionError("git " + String.join(" ", args) + ": " + out);
    }
}
