package dev.skillmanager.bindings;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Layer-2 contract tests for the ticket-49 binding subsystem:
 * ledger round-trip, conflict policies, projection materialization
 * + reversal, and default-agent binding id determinism.
 */
public final class BindingsTest {

    public static int run() throws Exception {
        return Tests.suite("BindingsTest")
                .test("ProjectionLedger round-trips through Jackson", () -> {
                    Path tmp = Files.createTempDirectory("ledger-roundtrip-");
                    SkillStore store = newStore(tmp);
                    BindingStore bs = new BindingStore(store);
                    Projection p = new Projection(
                            "b1",
                            tmp.resolve("source"),
                            tmp.resolve("dest"),
                            ProjectionKind.SYMLINK,
                            null);
                    Binding b = new Binding(
                            "b1", "hello", UnitKind.SKILL, null,
                            tmp.resolve("root"), ConflictPolicy.ERROR,
                            "2026-05-12T00:00:00Z",
                            BindingSource.EXPLICIT,
                            List.of(p));
                    bs.write(new ProjectionLedger("hello", List.of(b)));
                    ProjectionLedger reread = bs.read("hello");
                    assertEquals(1, reread.bindings().size(), "one binding");
                    Binding rb = reread.bindings().get(0);
                    assertEquals("b1", rb.bindingId(), "id");
                    assertEquals("hello", rb.unitName(), "unit");
                    assertEquals(UnitKind.SKILL, rb.unitKind(), "kind");
                    assertEquals(ConflictPolicy.ERROR, rb.conflictPolicy(), "policy");
                    assertEquals(BindingSource.EXPLICIT, rb.source(), "source");
                    assertEquals(1, rb.projections().size(), "one projection");
                    Projection rp = rb.projections().get(0);
                    assertEquals(ProjectionKind.SYMLINK, rp.kind(), "projection kind");
                    assertEquals(tmp.resolve("source"), rp.sourcePath(), "source path");
                    assertEquals(tmp.resolve("dest"), rp.destPath(), "dest path");
                })
                .test("Empty ledger writes drop the file", () -> {
                    Path tmp = Files.createTempDirectory("ledger-empty-");
                    SkillStore store = newStore(tmp);
                    BindingStore bs = new BindingStore(store);
                    bs.write(new ProjectionLedger("ghost", List.of(
                            new Binding("g1", "ghost", UnitKind.SKILL, null,
                                    tmp, ConflictPolicy.ERROR, "now",
                                    BindingSource.EXPLICIT, List.of()))));
                    assertTrue(Files.exists(bs.file("ghost")), "file written");
                    bs.write(new ProjectionLedger("ghost", List.of()));
                    assertFalse(Files.exists(bs.file("ghost")), "file deleted when empty");
                })
                .test("withBinding replaces by id; withoutBinding drops by id", () -> {
                    Binding b1 = new Binding("id1", "u", UnitKind.SKILL, null,
                            Path.of("/r1"), ConflictPolicy.ERROR, "t",
                            BindingSource.EXPLICIT, List.of());
                    Binding b2 = new Binding("id2", "u", UnitKind.SKILL, null,
                            Path.of("/r2"), ConflictPolicy.ERROR, "t",
                            BindingSource.EXPLICIT, List.of());
                    ProjectionLedger l = ProjectionLedger.empty("u")
                            .withBinding(b1)
                            .withBinding(b2);
                    assertEquals(2, l.bindings().size(), "2 bindings");
                    // replace id1 with a new shape — count stays 2
                    Binding b1prime = new Binding("id1", "u", UnitKind.SKILL, "sub",
                            Path.of("/r1b"), ConflictPolicy.SKIP, "t",
                            BindingSource.EXPLICIT, List.of());
                    l = l.withBinding(b1prime);
                    assertEquals(2, l.bindings().size(), "still 2 after replace");
                    assertEquals("sub", l.findById("id1").get().subElement(), "id1 updated");
                    l = l.withoutBinding("id2");
                    assertEquals(1, l.bindings().size(), "1 after drop");
                    assertTrue(l.findById("id2").isEmpty(), "id2 gone");
                })
                .test("findById across multiple unit ledgers", () -> {
                    Path tmp = Files.createTempDirectory("ledger-find-");
                    SkillStore store = newStore(tmp);
                    BindingStore bs = new BindingStore(store);
                    Binding ba = new Binding("ba", "alpha", UnitKind.SKILL, null,
                            tmp, ConflictPolicy.ERROR, "t",
                            BindingSource.EXPLICIT, List.of());
                    Binding bb = new Binding("bb", "beta", UnitKind.PLUGIN, null,
                            tmp, ConflictPolicy.ERROR, "t",
                            BindingSource.DEFAULT_AGENT, List.of());
                    bs.write(new ProjectionLedger("alpha", List.of(ba)));
                    bs.write(new ProjectionLedger("beta", List.of(bb)));
                    var loc = bs.findById("bb");
                    assertTrue(loc.isPresent(), "bb located");
                    assertEquals("beta", loc.get().unitName(), "via beta ledger");
                    assertEquals(BindingSource.DEFAULT_AGENT, loc.get().binding().source(), "source preserved");
                    assertEquals(2, bs.listAll().size(), "listAll across both files");
                })
                .test("MaterializeProjection SYMLINK creates the link", () -> {
                    Path tmp = Files.createTempDirectory("mat-symlink-");
                    SkillStore store = newStore(tmp);
                    Path src = tmp.resolve("src");
                    Files.createDirectories(src);
                    Files.writeString(src.resolve("README"), "hi");
                    Path dest = tmp.resolve("dest");
                    Projection p = new Projection("b", src, dest, ProjectionKind.SYMLINK, null);
                    Program<Void> program = new Program<>("mat-1",
                            List.of(new SkillEffect.MaterializeProjection(p, ConflictPolicy.ERROR)),
                            r -> null);
                    new LiveInterpreter(store).run(program);
                    assertTrue(Files.exists(dest), "dest exists");
                    assertTrue(Files.isSymbolicLink(dest), "dest is a symlink");
                    assertEquals(src, Files.readSymbolicLink(dest), "symlink target");
                })
                .test("MaterializeProjection ERROR policy fails when dest exists", () -> {
                    Path tmp = Files.createTempDirectory("mat-error-");
                    SkillStore store = newStore(tmp);
                    Path src = tmp.resolve("src");
                    Files.createDirectories(src);
                    Path dest = tmp.resolve("dest");
                    Files.createDirectories(dest);
                    Projection p = new Projection("b", src, dest, ProjectionKind.SYMLINK, null);
                    Program<Void> program = new Program<>("mat-err",
                            List.of(new SkillEffect.MaterializeProjection(p, ConflictPolicy.ERROR)),
                            r -> null);
                    Executor.Outcome<Void> outcome = new Executor(store, null).run(program);
                    assertTrue(outcome.rolledBack(), "rolled back");
                })
                .test("MaterializeProjection SKIP leaves dest alone", () -> {
                    Path tmp = Files.createTempDirectory("mat-skip-");
                    SkillStore store = newStore(tmp);
                    Path src = tmp.resolve("src");
                    Files.createDirectories(src);
                    Path dest = tmp.resolve("dest");
                    Files.writeString(dest, "preexisting");
                    Projection p = new Projection("b", src, dest, ProjectionKind.SYMLINK, null);
                    Program<Void> program = new Program<>("mat-skip",
                            List.of(new SkillEffect.MaterializeProjection(p, ConflictPolicy.SKIP)),
                            r -> null);
                    new LiveInterpreter(store).run(program);
                    assertEquals("preexisting", Files.readString(dest), "dest untouched");
                    assertFalse(Files.isSymbolicLink(dest), "no symlink");
                })
                .test("RENAMED_ORIGINAL_BACKUP move + reverse round-trip", () -> {
                    Path tmp = Files.createTempDirectory("mat-rename-");
                    SkillStore store = newStore(tmp);
                    Path original = tmp.resolve("CLAUDE.md");
                    Files.writeString(original, "user content");
                    Path backup = tmp.resolve("CLAUDE.md.skill-manager-backup-X");
                    Projection backupProj = new Projection(
                            "b", null, backup, ProjectionKind.RENAMED_ORIGINAL_BACKUP, original.toString());

                    new LiveInterpreter(store).run(new Program<>("rn-fwd",
                            List.of(new SkillEffect.MaterializeProjection(backupProj, ConflictPolicy.ERROR)),
                            r -> null));
                    assertTrue(Files.exists(backup), "backup created");
                    assertFalse(Files.exists(original), "original moved out");
                    assertEquals("user content", Files.readString(backup), "backup keeps bytes");

                    new LiveInterpreter(store).run(new Program<>("rn-rev",
                            List.of(new SkillEffect.UnmaterializeProjection(backupProj)),
                            r -> null));
                    assertTrue(Files.exists(original), "original restored");
                    assertFalse(Files.exists(backup), "backup gone");
                    assertEquals("user content", Files.readString(original), "bytes preserved");
                })
                .test("CreateBinding writes the row; RemoveBinding drops it", () -> {
                    Path tmp = Files.createTempDirectory("createbind-");
                    SkillStore store = newStore(tmp);
                    Binding b = new Binding(
                            "id-x", "uX", UnitKind.SKILL, null,
                            tmp, ConflictPolicy.ERROR, "now",
                            BindingSource.EXPLICIT, List.of());
                    new LiveInterpreter(store).run(new Program<>("cb",
                            List.of(new SkillEffect.CreateBinding(b)), r -> null));
                    BindingStore bs = new BindingStore(store);
                    assertNotNull(bs.read("uX").findById("id-x").orElse(null), "binding written");

                    new LiveInterpreter(store).run(new Program<>("rb",
                            List.of(new SkillEffect.RemoveBinding("uX", "id-x")), r -> null));
                    assertTrue(bs.read("uX").findById("id-x").isEmpty(), "binding removed");
                })
                .test("Executor rolls back CreateBinding on downstream failure", () -> {
                    Path tmp = Files.createTempDirectory("rollback-");
                    SkillStore store = newStore(tmp);
                    Binding b = new Binding(
                            "id-r", "uR", UnitKind.SKILL, null,
                            tmp, ConflictPolicy.ERROR, "now",
                            BindingSource.EXPLICIT, List.of());
                    BindingStore bs = new BindingStore(store);
                    // Two-step program: CreateBinding succeeds, then a
                    // MaterializeProjection with a missing source fails.
                    Path missing = tmp.resolve("does-not-exist");
                    Path dest = tmp.resolve("dest-r");
                    Projection bad = new Projection("id-r", missing, dest, ProjectionKind.SYMLINK, null);
                    Program<Void> program = new Program<>("rb-roll",
                            List.of(
                                    new SkillEffect.CreateBinding(b),
                                    // ERROR policy on a path with a non-existent parent
                                    new SkillEffect.MaterializeProjection(
                                            new Projection("id-r", missing,
                                                    Path.of("/this/path/is/not/writable/dest"),
                                                    ProjectionKind.SYMLINK, null),
                                            ConflictPolicy.ERROR)),
                            r -> null);
                    Executor.Outcome<Void> outcome = new Executor(store, null).run(program);
                    assertTrue(outcome.rolledBack(), "rolled back");
                    // Ledger restored to pre-state (= empty) because the
                    // pre-state snapshot captured the empty ledger before
                    // CreateBinding wrote it.
                    assertTrue(bs.read("uR").findById("id-r").isEmpty(),
                            "binding removed by RestoreProjectionLedger");
                })
                .test("defaultBindingId is deterministic per (agent, unit)", () -> {
                    String a = LiveInterpreter.defaultBindingId("claude", "hello");
                    String b = LiveInterpreter.defaultBindingId("claude", "hello");
                    assertEquals(a, b, "same inputs → same id");
                    String c = LiveInterpreter.defaultBindingId("codex", "hello");
                    assertFalse(a.equals(c), "different agent → different id");
                })
                .test("newBindingId produces 26-char ULID-shaped strings", () -> {
                    String id = BindingStore.newBindingId();
                    assertEquals(26, id.length(), "26 chars");
                    for (char ch : id.toCharArray()) {
                        boolean valid = (ch >= '0' && ch <= '9')
                                || (ch >= 'A' && ch <= 'Z');
                        assertTrue(valid, "Crockford-base32 only: " + ch);
                    }
                })
                .runAll();
    }

    private static SkillStore newStore(Path root) throws java.io.IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        SkillStore s = new SkillStore(home);
        s.init();
        return s;
    }
}
