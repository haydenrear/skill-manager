package dev.skillmanager.lock;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-10a contract: {@link LockDiff#between} surfaces every kind of
 * drift between two locks — added rows, removed rows, and bumped fields
 * (typically resolved-sha drift after a sync).
 */
public final class LockDiffTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LockDiffTest");

        suite.test("equal locks → empty diff", () -> {
            UnitsLock a = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("alpha", "sha-1")));
            UnitsLock b = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("alpha", "sha-1")));
            LockDiff d = LockDiff.between(a, b);
            assertTrue(d.isEmpty(), "no drift");
        });

        suite.test("unit in target but not in current → added", () -> {
            UnitsLock current = UnitsLock.empty();
            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("new", "sha-1")));
            LockDiff d = LockDiff.between(current, target);
            assertEquals(1, d.added().size(), "one added");
            assertEquals("new", d.added().get(0).name(), "added by name");
            assertEquals(0, d.removed().size(), "none removed");
            assertEquals(0, d.bumped().size(), "none bumped");
        });

        suite.test("unit in current but not in target → removed", () -> {
            UnitsLock current = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("gone", "sha-1")));
            UnitsLock target = UnitsLock.empty();
            LockDiff d = LockDiff.between(current, target);
            assertEquals(0, d.added().size(), "none added");
            assertEquals(1, d.removed().size(), "one removed");
            assertEquals("gone", d.removed().get(0).name(), "removed by name");
        });

        suite.test("same name + different sha → bumped (not added/removed)", () -> {
            UnitsLock current = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("alpha", "sha-old")));
            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(row("alpha", "sha-new")));
            LockDiff d = LockDiff.between(current, target);
            assertEquals(0, d.added().size(), "no add");
            assertEquals(0, d.removed().size(), "no remove");
            assertEquals(1, d.bumped().size(), "one bump");
            LockDiff.Bump b = d.bumped().get(0);
            assertEquals("sha-old", b.before().resolvedSha(), "before sha");
            assertEquals("sha-new", b.after().resolvedSha(), "after sha");
        });

        suite.test("same name + different version → bumped", () -> {
            LockedUnit before = new LockedUnit("alpha", UnitKind.SKILL, "1.0.0",
                    InstalledUnit.InstallSource.GIT, "url", "main", "sha-1");
            LockedUnit after = new LockedUnit("alpha", UnitKind.SKILL, "1.1.0",
                    InstalledUnit.InstallSource.GIT, "url", "main", "sha-1");
            LockDiff d = LockDiff.between(
                    new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(before)),
                    new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(after)));
            assertEquals(1, d.bumped().size(), "version drift counts as bumped");
        });

        suite.test("mixed: one added + one removed + one bumped", () -> {
            UnitsLock current = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    row("alpha", "sha-old"),  // bumped
                    row("gone", "sha-x")      // removed
            ));
            UnitsLock target = new UnitsLock(UnitsLock.CURRENT_SCHEMA, List.of(
                    row("alpha", "sha-new"),  // bumped
                    row("new", "sha-y")       // added
            ));
            LockDiff d = LockDiff.between(current, target);
            assertEquals(1, d.added().size(), "one added");
            assertEquals(1, d.removed().size(), "one removed");
            assertEquals(1, d.bumped().size(), "one bumped");
            assertEquals("new", d.added().get(0).name(), "added is 'new'");
            assertEquals("gone", d.removed().get(0).name(), "removed is 'gone'");
            assertEquals("alpha", d.bumped().get(0).before().name(), "bumped is 'alpha'");
        });

        return suite.runAll();
    }

    private static LockedUnit row(String name, String sha) {
        return new LockedUnit(name, UnitKind.SKILL, "1.0.0",
                InstalledUnit.InstallSource.GIT, "url", "main", sha);
    }
}
