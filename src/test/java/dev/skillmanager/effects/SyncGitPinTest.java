package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.lock.LockedUnit;
import dev.skillmanager.lock.UnitsLock;
import dev.skillmanager.lock.UnitsLockReader;
import dev.skillmanager.lock.UnitsLockWriter;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * HP-2 (#406, #407): a pinned sync lands on the pin or refuses by name, against
 * a REAL local git remote — commits A, B, C on {@code main}, each with its own
 * marker in SKILL.md.
 *
 * <p>The handler cases drive {@link SyncGitHandler#run} with a pinned
 * {@link SkillEffect.SyncGit}; the CLI cases drive {@link SyncCommand} itself
 * ({@code sync --lock}, {@code sync <unit> --to}) in-process, and read HEAD, the
 * installed record, the regenerated units lock and the source bytes — the four
 * observations #406 asks for. Before the fix, the {@code --lock} case ended at C
 * with exit 0, and the older-pin cases were "already up to date" at B.
 */
public final class SyncGitPinTest {

    private static final String UNIT = "pin-unit";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SyncGitPinTest");
        if (!gitAvailable()) {
            System.out.println("== SyncGitPinTest — SKIPPED, git is not on PATH");
            return 0;
        }

        suite.test("pin NEWER than HEAD but older than main's tip lands on the pin, not the tip", () -> {
            Fixture f = Fixture.create("newer", "A");
            EffectReceipt r = pinned(f, f.b);
            assertEquals(EffectStatus.OK, r.status(), "moved: " + r.errorMessage());
            f.assertAt(f.b, "marker B");
            assertEquals(f.b, f.record().gitHash(), "record names the pin");
        });

        suite.test("pin OLDER than HEAD moves HEAD back (no edit)", () -> {
            Fixture f = Fixture.create("older", "B");
            EffectReceipt r = pinned(f, f.a);
            assertEquals(EffectStatus.OK, r.status(), "moved: " + r.errorMessage());
            f.assertAt(f.a, "marker A");
        });

        suite.test("pin OLDER than HEAD with an edit that applies keeps the edit", () -> {
            Fixture f = Fixture.create("older-edit", "B");
            Files.writeString(f.unit.resolve("NOTES.md"), "notes\nLOCAL EDIT\n");
            EffectReceipt r = pinned(f, f.a);
            assertEquals(EffectStatus.OK, r.status(), "moved: " + r.errorMessage());
            f.assertAt(f.a, "marker A");
            assertEquals("notes\nLOCAL EDIT\n", Files.readString(f.unit.resolve("NOTES.md")),
                    "the edit was carried onto the pin");
        });

        suite.test("an edit that CONFLICTS with the pin refuses by name and changes nothing", () -> {
            Fixture f = Fixture.create("conflict", "B");
            String edited = Files.readString(f.unit.resolve("SKILL.md")).replace("marker B", "marker LOCAL");
            Files.writeString(f.unit.resolve("SKILL.md"), edited);
            EffectReceipt r = pinned(f, f.a);
            assertEquals(EffectStatus.PARTIAL, r.status(), "refused");
            assertTrue(r.facts().stream().anyMatch(x -> x instanceof ContextFact.SyncGitFailed g
                    && g.skillName().equals(UNIT)), "the refusal names the unit");
            assertEquals(f.b, GitOps.headHash(f.unit), "HEAD unchanged");
            assertContains(Files.readString(f.unit.resolve("SKILL.md")), "marker LOCAL", "edit intact");
            assertEquals(f.b, f.record().gitHash(), "record unchanged");
        });

        suite.test("a local COMMIT the pin does not contain refuses (moving would discard it)", () -> {
            Fixture f = Fixture.create("local-commit", "B");
            Files.writeString(f.unit.resolve("LOCAL.md"), "mine\n");
            git(f.unit, "add", "-A");
            commit(f.unit, "local");
            String local = GitOps.headHash(f.unit);
            EffectReceipt r = pinned(f, f.a);
            assertEquals(EffectStatus.PARTIAL, r.status(), "refused");
            assertEquals(local, GitOps.headHash(f.unit), "the local commit is still HEAD");
        });

        suite.test("an UNAVAILABLE sha fails closed and changes nothing", () -> {
            Fixture f = Fixture.create("missing", "A");
            EffectReceipt r = pinned(f, "0123456789abcdef0123456789abcdef01234567");
            assertEquals(EffectStatus.PARTIAL, r.status(), "refused");
            assertTrue(r.facts().stream().anyMatch(x -> x instanceof ContextFact.SyncGitFailed),
                    "typed refusal");
            f.assertAt(f.a, "marker A");
        });

        suite.test("ordinary (unpinned) git-latest sync still follows the ref to main's tip", () -> {
            Fixture f = Fixture.create("ordinary", "A");
            EffectReceipt r = SyncGitHandler.run(new SkillEffect.SyncGit(UNIT, UnitKind.SKILL,
                    InstalledUnit.InstallSource.GIT, true, false), new EffectContext(f.h.store(), null));
            assertEquals(EffectStatus.OK, r.status(), "synced: " + r.errorMessage());
            f.assertAt(f.c, "marker C");
        });

        suite.test("lock row with resolved_sha → pinned target carrying origin/ref/sha together", () -> {
            LockedUnit row = new LockedUnit(UNIT, UnitKind.SKILL, "0.1.0", InstalledUnit.InstallSource.GIT,
                    "file:///x", "abc", "abcdef0123456789abcdef0123456789abcdef01");
            SyncUseCase.Target t = SyncCommand.lockTarget(row);
            assertTrue(t instanceof SyncUseCase.Target.Git g && g.pin() != null
                    && "file:///x".equals(g.pin().origin()) && "abc".equals(g.pin().ref())
                    && row.resolvedSha().equals(g.pin().sha()), "identity carried: " + t);
            LockedUnit bare = new LockedUnit(UNIT, UnitKind.SKILL, "0.2.0", InstalledUnit.InstallSource.REGISTRY,
                    null, null, null);
            assertTrue(SyncCommand.lockTarget(bare) instanceof SyncUseCase.Target.Git g2 && g2.pin() == null,
                    "a row with no identity stays an ordinary sync");
        });

        suite.test("CLI: sync --lock with a bumped resolved_sha lands on B (HEAD, record, lock, bytes); "
                + "second run is a no-op; dry-run writes nothing", () -> {
            Fixture f = Fixture.create("cli-lock", "A");
            Path lockPath = UnitsLockReader.defaultPath(f.h.store());
            UnitsLockWriter.atomicWrite(dev.skillmanager.commands.LockCommand.readLiveState(f.h.store()).lock(),
                    lockPath);
            UnitsLock live = UnitsLockReader.read(lockPath);
            List<LockedUnit> rows = new ArrayList<>();
            for (LockedUnit u : live.units()) {
                rows.add(u.name().equals(UNIT)
                        ? new LockedUnit(u.name(), u.kind(), u.version(), u.installSource(), u.origin(), f.b, f.b)
                        : u);
            }
            Path desired = f.root.resolve("desired.lock.toml");
            UnitsLockWriter.atomicWrite(new UnitsLock(live.schemaVersion(), rows), desired);

            SyncCommand dry = command(f, desired);
            dry.dryRun = true;
            assertEquals(0, dry.call(), "dry-run rc");
            f.assertAt(f.a, "marker A");

            assertEquals(0, command(f, desired).call(), "sync --lock rc");
            f.assertAt(f.b, "marker B");
            assertEquals(f.b, f.record().gitHash(), "record at B");
            assertEquals(f.b, f.record().gitRef(), "record tracks the lock's ref");
            LockedUnit after = UnitsLockReader.read(lockPath).units().stream()
                    .filter(u -> u.name().equals(UNIT)).findFirst().orElseThrow();
            assertEquals(f.b, after.resolvedSha(), "regenerated lock at B");

            assertEquals(0, command(f, desired).call(), "second sync --lock rc");
            f.assertAt(f.b, "marker B");
        });

        suite.test("CLI: sync <unit> --to <older sha> moves back and a plain sync then stays", () -> {
            Fixture f = Fixture.create("cli-to", "B");
            SyncCommand to = new SyncCommand(f.h.store());
            to.name = UNIT;
            to.toRevision = f.a;
            to.skipMcp = true;
            to.skipAgents = true;
            assertEquals(0, to.call(), "sync --to rc");
            f.assertAt(f.a, "marker A");
            SyncCommand plain = new SyncCommand(f.h.store());
            plain.name = UNIT;
            plain.skipMcp = true;
            plain.skipAgents = true;
            assertEquals(0, plain.call(), "plain sync rc");
            f.assertAt(f.a, "marker A");
        });

        return suite.runAll();
    }

    private static SyncCommand command(Fixture f, Path lock) {
        SyncCommand c = new SyncCommand(f.h.store());
        c.lockPath = lock;
        c.skipMcp = true;
        c.skipAgents = true;
        c.yes = true;
        return c;
    }

    private static EffectReceipt pinned(Fixture f, String sha) throws Exception {
        return SyncGitHandler.run(new SkillEffect.SyncGit(UNIT, UnitKind.SKILL,
                        InstalledUnit.InstallSource.GIT, false, false,
                        new SkillEffect.SyncGit.Pin(null, null, sha)),
                new EffectContext(f.h.store(), null));
    }

    private static final class Fixture {
        final TestHarness h;
        final Path root;
        final Path unit;
        final String a, b, c;

        private Fixture(TestHarness h, Path root, Path unit, String a, String b, String c) {
            this.h = h; this.root = root; this.unit = unit; this.a = a; this.b = b; this.c = c;
        }

        /** Remote with A, B, C on main; the unit cloned and recorded at {@code at} (A or B). */
        static Fixture create(String name, String at) throws Exception {
            TestHarness h = TestHarness.create();
            Path root = Files.createTempDirectory("syncgitpin-" + name);
            Path remote = root.resolve("remote");
            Files.createDirectories(remote);
            git(remote, "init", "-b", "main", "--quiet");
            Files.writeString(remote.resolve("NOTES.md"), "notes\n");
            String a = commitMarker(remote, "A");
            String b = commitMarker(remote, "B");
            String c = commitMarker(remote, "C");
            Path unit = h.store().unitDir(UNIT, UnitKind.SKILL);
            Files.createDirectories(unit.getParent());
            git(unit.getParent(), "clone", "--quiet", remote.toUri().toString(), unit.toString());
            String start = at.equals("A") ? a : b;
            git(unit, "reset", "--hard", "--quiet", start);
            new UnitStore(h.store()).write(new InstalledUnit(UNIT, "0.1.0",
                    InstalledUnit.Kind.GIT, InstalledUnit.InstallSource.GIT,
                    remote.toUri().toString(), start, "main", UnitStore.nowIso(), List.of(),
                    UnitKind.SKILL));
            return new Fixture(h, root, unit, a, b, c);
        }

        InstalledUnit record() {
            return new UnitStore(h.store()).read(UNIT).orElseThrow();
        }

        void assertAt(String sha, String marker) throws Exception {
            assertEquals(sha, GitOps.headHash(unit), "HEAD");
            assertContains(Files.readString(unit.resolve("SKILL.md")), marker, "SKILL.md bytes");
            assertFalse(Files.readString(unit.resolve("SKILL.md")).contains("<<<<<<<"), "no conflict markers");
        }

        private static String commitMarker(Path remote, String marker) throws Exception {
            Files.writeString(remote.resolve("SKILL.md"),
                    "---\nname: " + UNIT + "\ndescription: x\nversion: 0.1.0\n---\nmarker " + marker + "\n");
            git(remote, "add", "-A");
            commit(remote, marker);
            return GitOps.headHash(remote);
        }
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "-c", "maintenance.auto=false", "-c", "gc.auto=0", "commit", "--quiet", "-m", message);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " in " + dir + ": " + out);
        }
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
