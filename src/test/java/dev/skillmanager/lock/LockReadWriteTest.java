package dev.skillmanager.lock;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-10a contract: round-trip {@link UnitsLock} through write + read
 * preserves the data, and writing the same lock twice produces
 * byte-identical output (deterministic ordering / formatting).
 *
 * <p>Determinism matters: the lock is meant to be vendored in a project
 * repo and reviewed in PRs. Non-deterministic output would flap in
 * diffs even when the install set hasn't changed.
 */
public final class LockReadWriteTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LockReadWriteTest");

        suite.test("round-trip with skill + plugin units preserves all fields", () -> {
            UnitsLock before = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.2.3",
                            InstalledUnit.InstallSource.REGISTRY,
                            "https://reg.example/alpha", "main", "deadbeef"),
                    new LockedUnit("beta-plugin", UnitKind.PLUGIN, "0.9.0",
                            InstalledUnit.InstallSource.GIT,
                            "git@github.com:user/beta.git", "v0.9.0", "cafef00d")
            ));

            Path tmp = Files.createTempDirectory("lock-rt-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            UnitsLockWriter.atomicWrite(before, file);
            UnitsLock after = UnitsLockReader.read(file);

            assertEquals(2, after.units().size(), "two units round-tripped");
            assertEquals(before.get("alpha").orElseThrow(),
                    after.get("alpha").orElseThrow(), "alpha row");
            assertEquals(before.get("beta-plugin").orElseThrow(),
                    after.get("beta-plugin").orElseThrow(), "plugin row");
        });

        suite.test("write is deterministic — same lock written twice yields byte-identical output", () -> {
            UnitsLock lock = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("zeta", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u1", "main", "sha-z"),
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u2", "main", "sha-a"),
                    new LockedUnit("middle", UnitKind.PLUGIN, "0.1.0",
                            InstalledUnit.InstallSource.LOCAL_FILE, null, null, null)
            ));

            String first = UnitsLockWriter.render(lock);
            String second = UnitsLockWriter.render(lock);
            assertEquals(first, second, "two renders match");

            // Sort order: rows appear alphabetically by name regardless of
            // input order. alpha < middle < zeta.
            int alphaIdx = first.indexOf("name = \"alpha\"");
            int middleIdx = first.indexOf("name = \"middle\"");
            int zetaIdx = first.indexOf("name = \"zeta\"");
            assertTrue(alphaIdx < middleIdx && middleIdx < zetaIdx,
                    "rows sorted alphabetically by name");
        });

        suite.test("missing optional fields (origin/ref/resolvedSha) are omitted from output, not emitted as empty strings", () -> {
            UnitsLock lock = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("local-only", UnitKind.SKILL, "0.1.0",
                            InstalledUnit.InstallSource.LOCAL_FILE, null, null, null)
            ));
            String rendered = UnitsLockWriter.render(lock);
            assertTrue(!rendered.contains("origin = \"\""), "no empty origin");
            assertTrue(!rendered.contains("ref = \"\""), "no empty ref");
            assertTrue(!rendered.contains("resolved_sha = \"\""), "no empty resolved_sha");
            assertTrue(rendered.contains("name = \"local-only\""), "name still emitted");
        });

        suite.test("missing file → empty lock with current schema", () -> {
            Path tmp = Files.createTempDirectory("lock-missing-");
            UnitsLock lock = UnitsLockReader.read(tmp.resolve("does-not-exist.toml"));
            assertEquals(UnitsLock.CURRENT_SCHEMA, lock.schemaVersion(),
                    "empty lock carries current schema");
            assertEquals(0, lock.units().size(), "no units");
        });

        suite.test("empty lock writes minimal but valid toml; reads back as empty", () -> {
            Path tmp = Files.createTempDirectory("lock-empty-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            UnitsLockWriter.atomicWrite(UnitsLock.empty(), file);

            UnitsLock parsed = UnitsLockReader.read(file);
            assertEquals(0, parsed.units().size(), "no units round-tripped");
            assertEquals(UnitsLock.CURRENT_SCHEMA, parsed.schemaVersion(), "schema version preserved");
        });

        suite.test("CLI lock round-trips scoped npm package keys", () -> {
            SkillStore store = new SkillStore(Files.createTempDirectory("cli-lock-scoped-"));
            store.init();
            CliLock lock = CliLock.load(store);
            lock.recordInstall("npm", "@google/gemini-cli", null,
                    "npm:@google/gemini-cli", null, "gemini-skill");
            lock.save(store);

            String rendered = Files.readString(store.root().resolve(CliLock.FILENAME));
            assertTrue(rendered.contains("[\"npm\".\"@google/gemini-cli\"]"),
                    "scoped npm key is quoted");
            CliLock.Entry entry = CliLock.load(store).get("npm", "@google/gemini-cli");
            assertEquals("npm:@google/gemini-cli", entry.spec(), "spec round-tripped");
            assertEquals(List.of("gemini-skill"), entry.requestedBy(), "requester round-tripped");
        });

        suite.test("CLI lock reads legacy bare scoped npm table keys", () -> {
            SkillStore store = new SkillStore(Files.createTempDirectory("cli-lock-legacy-scoped-"));
            store.init();
            Files.writeString(store.root().resolve(CliLock.FILENAME), """
                    [npm.@google/gemini-cli]
                    spec = "npm:@google/gemini-cli"
                    requested_by = ["gemini-skill"]
                    installed_at = "2026-05-13T00:00:00Z"
                    """);

            CliLock.Entry entry = CliLock.load(store).get("npm", "@google/gemini-cli");
            assertEquals("npm:@google/gemini-cli", entry.spec(), "legacy spec parsed");
            assertEquals(List.of("gemini-skill"), entry.requestedBy(), "legacy requester parsed");
        });

        suite.test("atomicWrite overwrites prior content cleanly (no stale rows)", () -> {
            Path tmp = Files.createTempDirectory("lock-replace-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);

            UnitsLock first = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-1")
            ));
            UnitsLockWriter.atomicWrite(first, file);

            UnitsLock second = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    new LockedUnit("beta", UnitKind.SKILL, "1.0.0",
                            InstalledUnit.InstallSource.GIT, "u", "main", "sha-2")
            ));
            UnitsLockWriter.atomicWrite(second, file);

            UnitsLock parsed = UnitsLockReader.read(file);
            assertEquals(1, parsed.units().size(), "exactly one row");
            assertTrue(parsed.get("beta").isPresent(), "beta survived");
            assertTrue(parsed.get("alpha").isEmpty(), "alpha gone");
        });

        return suite.runAll();
    }
}
