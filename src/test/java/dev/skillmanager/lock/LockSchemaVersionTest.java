package dev.skillmanager.lock;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-10a contract: an unknown {@code schema_version} fails loudly
 * rather than silently defaulting. The lock is the source-of-truth for
 * vendored installs; if a newer-built lock lands in an older
 * skill-manager and we silently fell back to the old schema, the user
 * would get inconsistent installs without ever knowing the lock
 * disagreed with the binary.
 */
public final class LockSchemaVersionTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LockSchemaVersionTest");

        suite.test("schema_version=1 → reads cleanly", () -> {
            Path tmp = Files.createTempDirectory("lock-sv-1-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            Files.writeString(file, "schema_version = 1\n");
            UnitsLock lock = UnitsLockReader.read(file);
            assertEquals(1, lock.schemaVersion(), "schema 1 round-trips");
        });

        suite.test("schema_version=2 (future) → IOException with explicit message", () -> {
            Path tmp = Files.createTempDirectory("lock-sv-2-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            Files.writeString(file, "schema_version = 2\n");
            try {
                UnitsLockReader.read(file);
                throw new AssertionError("expected IOException for unknown schema_version=2");
            } catch (IOException expected) {
                assertContains(expected.getMessage(), "schema_version=2",
                        "error names the unsupported version");
                assertContains(expected.getMessage(), "1",
                        "error names the supported version");
            }
        });

        suite.test("schema_version=99 → IOException", () -> {
            Path tmp = Files.createTempDirectory("lock-sv-99-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            Files.writeString(file, "schema_version = 99\n");
            try {
                UnitsLockReader.read(file);
                throw new AssertionError("expected IOException");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("99"), "version surfaced");
            }
        });

        suite.test("missing schema_version key → defaults to current (legacy compat)", () -> {
            // Loose-form lock files that pre-date the schema_version field
            // shouldn't blow up. They get read as schema 1 (the original
            // shape), and the next write stamps the version explicitly.
            Path tmp = Files.createTempDirectory("lock-sv-missing-");
            Path file = tmp.resolve(UnitsLockReader.FILENAME);
            Files.writeString(file, "# no schema_version key\n");
            UnitsLock lock = UnitsLockReader.read(file);
            assertEquals(UnitsLock.CURRENT_SCHEMA, lock.schemaVersion(),
                    "absent version defaults to current");
        });

        return suite.runAll();
    }
}
