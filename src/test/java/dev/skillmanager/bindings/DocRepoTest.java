package dev.skillmanager.bindings;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocSource;
import dev.skillmanager.model.DocUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Layer-2 contract tests for the ticket-48 doc-repo subsystem:
 * Sha256 + SyncDecision matrix, ManagedImports section editor,
 * DocRepoParser, DocRepoBinder fan-out, and the DocSync four-state
 * routing end-to-end.
 */
public final class DocRepoTest {

    public static int run() throws Exception {
        return Tests.suite("DocRepoTest")
                .test("Sha256 hashFile round-trip + missing file → null", () -> {
                    Path tmp = Files.createTempDirectory("sha-");
                    Path f = tmp.resolve("a");
                    Files.writeString(f, "hello");
                    String h = Sha256.hashFile(f);
                    assertNotNull(h, "hash present");
                    assertEquals(64, h.length(), "64 hex chars");
                    // Same content → same hash
                    Path g = tmp.resolve("b");
                    Files.writeString(g, "hello");
                    assertEquals(h, Sha256.hashFile(g), "content equality");
                    // Different content → different hash
                    Files.writeString(g, "world");
                    assertFalse(h.equals(Sha256.hashFile(g)), "content inequality");
                    // Missing file → null
                    assertEquals(null, Sha256.hashFile(tmp.resolve("missing")), "missing → null");
                })
                .test("SyncDecision matrix — all 4 + 2 cells", () -> {
                    // bound = "BOUND"
                    // up-to-date: source=BOUND, dest=BOUND
                    assertEquals(SyncDecision.State.UP_TO_DATE,
                            SyncDecision.decide("BOUND", "BOUND", "BOUND"), "up-to-date");
                    // upgrade: source moved, dest = bound
                    assertEquals(SyncDecision.State.UPGRADE_AVAILABLE,
                            SyncDecision.decide("BOUND", "SOURCE2", "BOUND"), "upgrade");
                    // locally edited: source = bound, dest moved
                    assertEquals(SyncDecision.State.LOCALLY_EDITED,
                            SyncDecision.decide("BOUND", "BOUND", "DEST2"), "locally edited");
                    // conflict: both moved
                    assertEquals(SyncDecision.State.CONFLICT,
                            SyncDecision.decide("BOUND", "SOURCE2", "DEST2"), "conflict");
                    // orphan source: source missing wins over everything else
                    assertEquals(SyncDecision.State.ORPHAN_SOURCE,
                            SyncDecision.decide("BOUND", null, "DEST2"), "orphan source");
                    assertEquals(SyncDecision.State.ORPHAN_SOURCE,
                            SyncDecision.decide("BOUND", null, null), "orphan source > orphan dest");
                    // orphan dest: source ok, dest missing
                    assertEquals(SyncDecision.State.ORPHAN_DEST,
                            SyncDecision.decide("BOUND", "SOURCE2", null), "orphan dest");
                })
                .test("ManagedImports upsertLine creates section on first call", () -> {
                    String content = "# Project notes\n\nuser-owned content\n";
                    String next = ManagedImports.upsertLine(content, "@docs/agents/foo.md");
                    assertTrue(next.contains("<!-- skill-manager:imports start -->"),
                            "start marker present");
                    assertTrue(next.contains("<!-- skill-manager:imports end -->"),
                            "end marker present");
                    assertTrue(next.contains("# skill-manager-imports"), "heading present");
                    assertTrue(next.contains("@docs/agents/foo.md"), "import line present");
                    assertTrue(next.startsWith("# Project notes"),
                            "user content preserved at top");
                })
                .test("ManagedImports upsertLine is idempotent", () -> {
                    String original = "user\n";
                    String a = ManagedImports.upsertLine(original, "@docs/agents/foo.md");
                    String b = ManagedImports.upsertLine(a, "@docs/agents/foo.md");
                    assertEquals(a, b, "second upsert is a no-op");
                })
                .test("ManagedImports removeLine drops section when empty", () -> {
                    String withOne = ManagedImports.upsertLine("user\n", "@docs/agents/foo.md");
                    String afterRemove = ManagedImports.removeLine(withOne, "@docs/agents/foo.md");
                    assertFalse(afterRemove.contains("<!-- skill-manager:imports"),
                            "markers gone");
                    assertFalse(afterRemove.contains("# skill-manager-imports"),
                            "heading gone");
                    assertTrue(afterRemove.contains("user"), "user content preserved");
                })
                .test("ManagedImports preserves unknown lines and reports them", () -> {
                    String body = "<!-- skill-manager:imports start -->\n"
                            + "# skill-manager-imports\n\n"
                            + "@docs/agents/foo.md\n"
                            + "# extra heading the user wrote here\n"
                            + "<!-- skill-manager:imports end -->\n";
                    var unknowns = ManagedImports.unknownLines(body);
                    assertEquals(1, unknowns.size(), "one unknown line");
                    assertTrue(unknowns.get(0).contains("extra heading"), "captured content");
                    String reroundtrip = ManagedImports.upsertLine(body, "@docs/agents/bar.md");
                    assertTrue(reroundtrip.contains("extra heading"),
                            "unknown line still in section after upsert");
                })
                .test("DocRepoParser parses [doc-repo] + [[sources]] with defaults", () -> {
                    Path repo = scaffoldDocRepo();
                    DocUnit unit = DocRepoParser.load(repo);
                    assertEquals("team-prompts", unit.name(), "name");
                    assertEquals(UnitKind.DOC, unit.kind(), "kind");
                    assertEquals(2, unit.sources().size(), "two sources");
                    DocSource a = unit.sources().get(0);
                    assertEquals("review-stance", a.id(), "first id = file stem");
                    assertEquals(List.of("claude", "codex"), a.agents(), "default agents");
                    DocSource b = unit.sources().get(1);
                    assertEquals("custom-id", b.id(), "second id = explicit");
                    assertEquals(List.of("claude"), b.agents(), "explicit agents");
                })
                .test("DocRepoParser rejects duplicate source ids", () -> {
                    Path repo = Files.createTempDirectory("dup-");
                    Path claudeMd = repo.resolve("claude-md");
                    Files.createDirectories(claudeMd);
                    Files.writeString(claudeMd.resolve("a.md"), "a");
                    Files.writeString(claudeMd.resolve("b.md"), "b");
                    Files.writeString(repo.resolve("skill-manager.toml"),
                            "[doc-repo]\nname = \"dup\"\nversion = \"0.1\"\n"
                                    + "[[sources]]\nfile = \"claude-md/a.md\"\nid = \"same\"\n"
                                    + "[[sources]]\nfile = \"claude-md/b.md\"\nid = \"same\"\n");
                    try {
                        DocRepoParser.load(repo);
                        throw new AssertionError("expected IOException for duplicate id");
                    } catch (IOException expected) {
                        assertTrue(expected.getMessage().contains("duplicate"),
                                "message mentions duplicate: " + expected.getMessage());
                    }
                })
                .test("DocRepoBinder plan: one binding per source, MANAGED_COPY + IMPORT_DIRECTIVE pairs", () -> {
                    Path repo = scaffoldDocRepo();
                    DocUnit unit = DocRepoParser.load(repo);
                    Path targetRoot = Files.createTempDirectory("target-");
                    DocRepoBinder.Plan plan = DocRepoBinder.plan(
                            unit, targetRoot, null, ConflictPolicy.RENAME_EXISTING,
                            BindingSource.EXPLICIT);
                    assertEquals(2, plan.bindings().size(), "one binding per source");
                    Binding b0 = plan.bindings().get(0);
                    // First source has agents=[claude,codex] → 1 copy + 2 imports
                    long copies = b0.projections().stream()
                            .filter(p -> p.kind() == ProjectionKind.MANAGED_COPY).count();
                    long imports = b0.projections().stream()
                            .filter(p -> p.kind() == ProjectionKind.IMPORT_DIRECTIVE).count();
                    assertEquals(1L, copies, "one MANAGED_COPY for review-stance");
                    assertEquals(2L, imports, "one IMPORT_DIRECTIVE per agent (claude+codex)");
                    Binding b1 = plan.bindings().get(1);
                    long copies1 = b1.projections().stream()
                            .filter(p -> p.kind() == ProjectionKind.MANAGED_COPY).count();
                    long imports1 = b1.projections().stream()
                            .filter(p -> p.kind() == ProjectionKind.IMPORT_DIRECTIVE).count();
                    assertEquals(1L, copies1, "one MANAGED_COPY for custom-id");
                    assertEquals(1L, imports1, "one IMPORT_DIRECTIVE for claude-only");
                })
                .test("DocRepoBinder plan with selectedSourceId binds just that source", () -> {
                    Path repo = scaffoldDocRepo();
                    DocUnit unit = DocRepoParser.load(repo);
                    Path targetRoot = Files.createTempDirectory("target-one-");
                    DocRepoBinder.Plan plan = DocRepoBinder.plan(
                            unit, targetRoot, "review-stance", ConflictPolicy.RENAME_EXISTING,
                            BindingSource.EXPLICIT);
                    assertEquals(1, plan.bindings().size(), "one binding");
                    assertEquals("review-stance", plan.bindings().get(0).subElement(), "sub-element");
                })
                .test("End-to-end bind doc-repo → file copy + CLAUDE.md import + ledger", () -> {
                    Path repo = scaffoldDocRepo();
                    Path targetRoot = Files.createTempDirectory("e2e-");
                    Path home = Files.createTempDirectory("e2e-home-");
                    SkillStore store = new SkillStore(home);
                    store.init();
                    // Put doc-repo bytes in the store as if installed.
                    Path storeDoc = store.unitDir("team-prompts", UnitKind.DOC);
                    dev.skillmanager.shared.util.Fs.copyRecursive(repo, storeDoc);
                    DocUnit unit = DocRepoParser.load(storeDoc);

                    DocRepoBinder.Plan plan = DocRepoBinder.plan(
                            unit, targetRoot, "review-stance", ConflictPolicy.RENAME_EXISTING,
                            BindingSource.EXPLICIT);
                    Binding b = plan.bindings().get(0);
                    java.util.List<SkillEffect> effects = new java.util.ArrayList<>();
                    for (Projection p : b.projections()) {
                        effects.add(new SkillEffect.MaterializeProjection(p, ConflictPolicy.RENAME_EXISTING));
                    }
                    effects.add(new SkillEffect.CreateBinding(b));
                    Executor.Outcome<Void> o = new Executor(store, null)
                            .run(new Program<>("bind-doc", effects, r -> null));
                    assertFalse(o.rolledBack(), "no rollback");
                    Path tracked = targetRoot.resolve("docs/agents/review-stance.md");
                    assertTrue(Files.exists(tracked), "tracked copy landed at " + tracked);
                    Path claudeMd = targetRoot.resolve("CLAUDE.md");
                    assertTrue(Files.exists(claudeMd), "CLAUDE.md created");
                    String mdContent = Files.readString(claudeMd);
                    assertTrue(mdContent.contains("@docs/agents/review-stance.md"),
                            "import line present");
                    BindingStore bs = new BindingStore(store);
                    assertTrue(bs.read("team-prompts").findById(b.bindingId()).isPresent(),
                            "ledger row present");
                })
                .test("DocSync upgrade: source changed, dest unchanged → rewrites + bumps boundHash", () -> {
                    var fix = newDocFixture("upgrade");
                    // Modify the source in the store.
                    Path src = fix.storeRepo.resolve("claude-md/review-stance.md");
                    Files.writeString(src, "UPGRADED CONTENT\n");
                    DocSync.Outcome out = DocSync.run(fix.store, fix.unitName, false);
                    assertEquals(0, out.errors(), "no errors");
                    // The tracked copy should now have the new content.
                    Path tracked = fix.targetRoot.resolve("docs/agents/review-stance.md");
                    assertEquals("UPGRADED CONTENT\n", Files.readString(tracked),
                            "dest matches new source");
                    // Ledger boundHash bumped.
                    BindingStore bs = new BindingStore(fix.store);
                    var ledger = bs.read(fix.unitName);
                    Projection mc = ledger.bindings().get(0).projections().stream()
                            .filter(p -> p.kind() == ProjectionKind.MANAGED_COPY)
                            .findFirst().orElseThrow();
                    assertEquals(Sha256.hashFile(src), mc.boundHash(),
                            "boundHash matches new source hash");
                })
                .test("DocSync locally-edited warns without --force; clobbers with --force", () -> {
                    var fix = newDocFixture("local-edit");
                    Path tracked = fix.targetRoot.resolve("docs/agents/review-stance.md");
                    Files.writeString(tracked, "USER EDIT\n");
                    DocSync.Outcome out = DocSync.run(fix.store, fix.unitName, false);
                    assertEquals(0, out.errors(), "no errors");
                    assertTrue(out.warnings() >= 1, "at least one warning");
                    assertEquals("USER EDIT\n", Files.readString(tracked),
                            "user edit preserved without --force");
                    // Now run with --force.
                    DocSync.Outcome forced = DocSync.run(fix.store, fix.unitName, true);
                    assertTrue(forced.warnings() >= 1, "force still reports warning");
                    assertEquals("REVIEW STANCE BODY\n", Files.readString(tracked),
                            "user edit clobbered by --force");
                })
                .test("DocSync orphan dest recreates the tracked copy", () -> {
                    var fix = newDocFixture("orphan-dest");
                    Path tracked = fix.targetRoot.resolve("docs/agents/review-stance.md");
                    Files.delete(tracked);
                    DocSync.Outcome out = DocSync.run(fix.store, fix.unitName, false);
                    assertEquals(0, out.errors(), "no errors");
                    assertTrue(Files.exists(tracked), "tracked copy recreated");
                    assertEquals("REVIEW STANCE BODY\n", Files.readString(tracked),
                            "recreated from source");
                })
                .test("DocSync orphan source surfaces as error", () -> {
                    var fix = newDocFixture("orphan-source");
                    Files.delete(fix.storeRepo.resolve("claude-md/review-stance.md"));
                    DocSync.Outcome out = DocSync.run(fix.store, fix.unitName, false);
                    assertTrue(out.errors() >= 1, "at least one error");
                    boolean foundOrphan = out.actions().stream()
                            .anyMatch(a -> a.description().contains("orphan source"));
                    assertTrue(foundOrphan, "orphan-source action emitted");
                })
                .test("SyncDocRepo effect emits DocBindingSynced facts via the handler", () -> {
                    var fix = newDocFixture("effect");
                    // Drive an upgrade through the effect pipeline.
                    Files.writeString(fix.storeRepo.resolve("claude-md/review-stance.md"),
                            "UPGRADED VIA EFFECT\n");
                    java.util.List<dev.skillmanager.effects.EffectReceipt> receipts =
                            new java.util.ArrayList<>();
                    Program<Void> p = new Program<>("sync-effect",
                            java.util.List.of(new SkillEffect.SyncDocRepo(fix.unitName, false)),
                            r -> { receipts.addAll(r); return null; });
                    new LiveInterpreter(fix.store).run(p);
                    assertEquals(1, receipts.size(), "one receipt");
                    var facts = receipts.get(0).facts();
                    // Two MANAGED_COPY facts? No — one binding, one
                    // MANAGED_COPY action. IMPORT_DIRECTIVE rows are
                    // idempotent re-applies that don't always emit a
                    // fact unless there's an unknown line. So expect
                    // at least one DocBindingSynced.
                    long synced = facts.stream()
                            .filter(f -> f instanceof dev.skillmanager.effects.ContextFact.DocBindingSynced)
                            .count();
                    assertTrue(synced >= 1, "at least one DocBindingSynced fact, got " + synced);
                    // Verify dest got rewritten.
                    Path tracked = fix.targetRoot.resolve("docs/agents/review-stance.md");
                    assertEquals("UPGRADED VIA EFFECT\n", Files.readString(tracked),
                            "effect handler rewrote dest");
                })
                .runAll();
    }

    /**
     * Build a minimal valid doc-repo:
     * <pre>
     *   team-prompts/
     *     skill-manager.toml
     *     claude-md/
     *       review-stance.md       (id defaults to file stem; agents=default)
     *       build-instructions.md  (explicit id, agents=["claude"])
     * </pre>
     */
    private static Path scaffoldDocRepo() throws IOException {
        Path repo = Files.createTempDirectory("docrepo-");
        Path md = repo.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), "REVIEW STANCE BODY\n");
        Files.writeString(md.resolve("build-instructions.md"), "BUILD INSTRUCTIONS BODY\n");
        Files.writeString(repo.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"team-prompts\"\n"
                        + "version = \"0.1.0\"\n"
                        + "description = \"team prompts fixture\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/build-instructions.md\"\n"
                        + "id = \"custom-id\"\n"
                        + "agents = [\"claude\"]\n");
        return repo;
    }

    private record DocFixture(SkillStore store, String unitName, Path storeRepo, Path targetRoot) {}

    /** End-to-end fixture: scaffold, install, bind one source. */
    private static DocFixture newDocFixture(String tag) throws IOException {
        Path home = Files.createTempDirectory("sync-fix-" + tag + "-");
        Path target = Files.createTempDirectory("sync-target-" + tag + "-");
        SkillStore store = new SkillStore(home);
        store.init();
        Path scaffold = scaffoldDocRepo();
        Path storeDoc = store.unitDir("team-prompts", UnitKind.DOC);
        dev.skillmanager.shared.util.Fs.copyRecursive(scaffold, storeDoc);
        DocUnit unit = DocRepoParser.load(storeDoc);
        DocRepoBinder.Plan plan = DocRepoBinder.plan(
                unit, target, "review-stance", ConflictPolicy.RENAME_EXISTING,
                BindingSource.EXPLICIT);
        Binding b = plan.bindings().get(0);
        java.util.List<SkillEffect> effects = new java.util.ArrayList<>();
        for (Projection p : b.projections()) {
            effects.add(new SkillEffect.MaterializeProjection(p, ConflictPolicy.RENAME_EXISTING));
        }
        effects.add(new SkillEffect.CreateBinding(b));
        new LiveInterpreter(store).run(new Program<>("fixture-bind", effects, r -> null));
        return new DocFixture(store, "team-prompts", storeDoc, target);
    }
}
