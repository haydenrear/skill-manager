package dev.skillmanager.bindings;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.HarnessUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Layer-2 contract tests for ticket-47 harness templates: parser,
 * instantiator fan-out, end-to-end sandbox materialization, and the
 * SyncHarness effect's reconcile path (idempotent apply + orphan
 * binding teardown).
 */
public final class HarnessTest {

    public static int run() throws Exception {
        return Tests.suite("HarnessTest")
                .test("HarnessParser reads [harness] + units + docs + mcp_tools", () -> {
                    Path repo = scaffoldHarnessTemplate("reviewer-harness", false);
                    HarnessUnit h = HarnessParser.load(repo);
                    assertEquals("reviewer-harness", h.name(), "name");
                    assertEquals(UnitKind.HARNESS, h.kind(), "kind");
                    assertEquals(2, h.units().size(), "two units");
                    assertEquals(1, h.docs().size(), "one doc");
                    assertEquals(1, h.mcpTools().size(), "one mcp_tools row");
                    assertEquals("shared-mcp", h.mcpTools().get(0).server(), "mcp server");
                    assertEquals(List.of("search", "get"),
                            h.mcpTools().get(0).tools(), "mcp tools allowlist");
                    // references() = units + docs combined
                    assertEquals(3, h.references().size(), "references is union");
                })
                .test("HarnessParser handles `tools = []` (allowlist nothing) vs omitted (allowlist all)", () -> {
                    Path repo = Files.createTempDirectory("harness-mcp-");
                    Files.writeString(repo.resolve("harness.toml"),
                            "[harness]\nname = \"x\"\nversion = \"0.1\"\n\n"
                                    + "[[mcp_tools]]\nserver = \"open-all\"\n\n"
                                    + "[[mcp_tools]]\nserver = \"open-none\"\ntools = []\n");
                    HarnessUnit h = HarnessParser.load(repo);
                    assertTrue(h.mcpTools().get(0).exposesAllTools(),
                            "no tools key → exposes all");
                    assertFalse(h.mcpTools().get(1).exposesAllTools(),
                            "empty tools = [] → exposes nothing");
                })
                .test("HarnessParser rejects file without [harness] table", () -> {
                    Path repo = Files.createTempDirectory("not-harness-");
                    Files.writeString(repo.resolve("harness.toml"),
                            "[skill]\nname = \"x\"\n");
                    try {
                        HarnessParser.load(repo);
                        throw new AssertionError("expected IOException");
                    } catch (IOException expected) {
                        assertTrue(expected.getMessage().contains("[harness]"),
                                "error message points at missing table: " + expected.getMessage());
                    }
                })
                .test("HarnessInstantiator plans one Binding per referenced unit + doc-source", () -> {
                    var fix = newHarnessFixture("plan-only", false);
                    HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                            fix.template, "inst-a",
                            fix.claudeConfigDir, fix.codexHome, fix.projectDir,
                            fix.store);
                    // 2 units + 1 doc source = 3 bindings
                    assertEquals(3, plan.bindings().size(), "one Binding per ref");
                    // All harness bindings have source=HARNESS and stable ids.
                    for (Binding b : plan.bindings()) {
                        assertEquals(BindingSource.HARNESS, b.source(), "source=HARNESS");
                        assertTrue(b.bindingId().startsWith("harness:inst-a:"),
                                "stable id prefix: " + b.bindingId());
                    }
                    // Skill bindings carry TWO projections (claude + codex);
                    // doc bindings have their own count from DocRepoBinder.
                    Binding skillBinding = plan.bindings().stream()
                            .filter(b -> b.unitKind() == dev.skillmanager.model.UnitKind.SKILL)
                            .findFirst().orElseThrow();
                    assertEquals(2, skillBinding.projections().size(),
                            "skill binding carries one projection per agent (claude + codex)");
                })
                .test("End-to-end instantiate: per-agent symlinks + doc into project + ledger", () -> {
                    var fix = newHarnessFixture("e2e", false);
                    HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                            fix.template, "e2e-instance",
                            fix.claudeConfigDir, fix.codexHome, fix.projectDir,
                            fix.store);
                    java.util.List<SkillEffect> effects = new java.util.ArrayList<>();
                    for (Binding b : plan.bindings()) {
                        for (Projection p : b.projections()) {
                            effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
                        }
                        effects.add(new SkillEffect.CreateBinding(b));
                    }
                    Executor.Outcome<Void> o = new Executor(fix.store, null)
                            .run(new Program<>("harness-e2e", effects, r -> null));
                    assertFalse(o.rolledBack(), "no rollback");
                    // Skill landed at BOTH agent paths — not in a sandbox
                    // dead-letter dir.
                    Path claudeSkillLink = fix.claudeConfigDir.resolve("skills/widget");
                    Path codexSkillLink = fix.codexHome.resolve("skills/widget");
                    assertTrue(Files.isSymbolicLink(claudeSkillLink),
                            "claude skill symlink at " + claudeSkillLink);
                    assertTrue(Files.isSymbolicLink(codexSkillLink),
                            "codex skill symlink at " + codexSkillLink);
                    // Doc lands in projectDir (the project root), with
                    // CLAUDE.md / AGENTS.md gaining import lines.
                    Path tracked = fix.projectDir.resolve("docs/agents/review-stance.md");
                    assertTrue(Files.exists(tracked), "tracked doc copy");
                    Path md = fix.projectDir.resolve("CLAUDE.md");
                    assertTrue(Files.readString(md).contains("@docs/agents/review-stance.md"),
                            "@-import present in CLAUDE.md");
                    // Ledger has 3 harness:* bindings.
                    BindingStore bs = new BindingStore(fix.store);
                    long harnessRows = bs.listAll().stream()
                            .filter(b -> b.source() == BindingSource.HARNESS)
                            .filter(b -> b.bindingId().startsWith("harness:e2e-instance:"))
                            .count();
                    assertEquals(3L, harnessRows, "three harness ledger rows");
                })
                .test("SyncHarness effect: idempotent re-apply (UPGRADED facts)", () -> {
                    var fix = newHarnessFixture("idempotent", false);
                    instantiate(fix, "idem-inst");
                    // Run sync again — all bindings already present.
                    java.util.List<dev.skillmanager.effects.EffectReceipt> receipts =
                            new java.util.ArrayList<>();
                    new LiveInterpreter(fix.store).run(new Program<>("re-sync",
                            java.util.List.of(new SkillEffect.SyncHarness(
                                    fix.template.name(), "idem-inst")),
                            r -> { receipts.addAll(r); return null; }));
                    var facts = receipts.get(0).facts();
                    long upgraded = facts.stream()
                            .filter(f -> f instanceof ContextFact.HarnessBindingSynced hbs
                                    && hbs.action() == ContextFact.HarnessBindingSynced.Action.UPGRADED)
                            .count();
                    assertEquals(3L, upgraded, "all 3 bindings reported as UPGRADED (already present)");
                })
                .test("SyncHarness effect: removed unit from template → orphan binding torn down", () -> {
                    var fix = newHarnessFixture("orphan", false);
                    instantiate(fix, "orphan-inst");
                    // Pre-condition: 3 harness bindings present.
                    BindingStore bs = new BindingStore(fix.store);
                    long before = bs.listAll().stream()
                            .filter(b -> b.bindingId().startsWith("harness:orphan-inst:"))
                            .count();
                    assertEquals(3L, before, "3 bindings before template change");

                    // Mutate the template in the store: drop the second unit.
                    rewriteHarnessToml(fix.store, fix.template.name(),
                            new String[] {"skill:widget"},     // only one unit now
                            new String[] {"doc:org-prompts/review-stance"});

                    // Run SyncHarness — expect 2 bindings (skill + doc), one removed.
                    java.util.List<dev.skillmanager.effects.EffectReceipt> receipts =
                            new java.util.ArrayList<>();
                    new LiveInterpreter(fix.store).run(new Program<>("sync",
                            java.util.List.of(new SkillEffect.SyncHarness(
                                    fix.template.name(), "orphan-inst")),
                            r -> { receipts.addAll(r); return null; }));

                    long after = bs.listAll().stream()
                            .filter(b -> b.bindingId().startsWith("harness:orphan-inst:"))
                            .count();
                    assertEquals(2L, after, "2 bindings after orphan teardown");
                    long removed = receipts.get(0).facts().stream()
                            .filter(f -> f instanceof ContextFact.HarnessBindingSynced hbs
                                    && hbs.action() == ContextFact.HarnessBindingSynced.Action.REMOVED)
                            .count();
                    assertEquals(1L, removed, "one REMOVED fact for the dropped unit");
                })
                .runAll();
    }

    // -------------------------------------------------------- fixtures

    /**
     * Per-test fixture. Carries the three target paths the new
     * instantiator API requires; in the fixture defaults they all
     * point at sandbox subdirs so test runs don't pollute the
     * developer's real {@code ~/.claude/} or {@code ~/.codex/}.
     */
    private record HarnessFixture(SkillStore store, HarnessUnit template,
                                   Path claudeConfigDir, Path codexHome, Path projectDir) {}

    /**
     * Scaffold a full harness fixture: a SkillStore with two skills,
     * a doc-repo, and a harness template referencing them. Returns
     * everything callers need to plan / instantiate / sync.
     *
     * <p>{@code multiAgentDoc=true} configures the doc source to
     * export to both claude+codex; default is claude-only.
     */
    private static HarnessFixture newHarnessFixture(String tag, boolean multiAgentDoc) throws IOException {
        Path home = Files.createTempDirectory("harness-fix-" + tag + "-");
        SkillStore store = new SkillStore(home);
        store.init();
        UnitStore us = new UnitStore(store);

        // skill widget
        var widget = UnitFixtures.scaffoldSkill(Files.createTempDirectory("scaf-widget-"),
                "widget", DepSpec.empty());
        Path widgetDst = store.unitDir("widget", UnitKind.SKILL);
        Fs.copyRecursive(widget.sourcePath(), widgetDst);
        us.write(installedRec("widget", UnitKind.SKILL));

        // skill helper
        var helper = UnitFixtures.scaffoldSkill(Files.createTempDirectory("scaf-helper-"),
                "helper", DepSpec.empty());
        Path helperDst = store.unitDir("helper", UnitKind.SKILL);
        Fs.copyRecursive(helper.sourcePath(), helperDst);
        us.write(installedRec("helper", UnitKind.SKILL));

        // doc-repo org-prompts
        Path docRepo = scaffoldDocRepoFixture(multiAgentDoc);
        Path docDst = store.unitDir("org-prompts", UnitKind.DOC);
        Fs.copyRecursive(docRepo, docDst);
        us.write(installedRec("org-prompts", UnitKind.DOC));

        // harness template reviewer-harness
        Path tplDir = scaffoldHarnessTemplate("reviewer-harness", false);
        Path tplDst = store.unitDir("reviewer-harness", UnitKind.HARNESS);
        Fs.copyRecursive(tplDir, tplDst);
        us.write(installedRec("reviewer-harness", UnitKind.HARNESS));

        HarnessUnit harness = HarnessParser.load(tplDst);
        // Sandbox subdirs default — keeps tests off the developer's
        // real ~/.claude/ and ~/.codex/. End-to-end tests that exercise
        // custom paths pass them explicitly to HarnessInstantiator.plan.
        Path sandboxRoot = Files.createTempDirectory("harness-sandbox-" + tag + "-");
        Path claudeConfigDir = sandboxRoot.resolve("claude");
        Path codexHome = sandboxRoot.resolve("codex");
        Path projectDir = sandboxRoot.resolve("project");
        return new HarnessFixture(store, harness, claudeConfigDir, codexHome, projectDir);
    }

    /** Build the standard test harness template referencing widget + helper + review-stance. */
    private static Path scaffoldHarnessTemplate(String name, boolean dropHelper) throws IOException {
        Path dir = Files.createTempDirectory("harness-tpl-" + name + "-");
        String units = dropHelper ? "\"skill:widget\"" : "\"skill:widget\", \"skill:helper\"";
        Files.writeString(dir.resolve("harness.toml"),
                "[harness]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n"
                        + "description = \"test fixture\"\n\n"
                        + "units = [" + units + "]\n"
                        + "docs = [\"doc:org-prompts/review-stance\"]\n\n"
                        + "[[mcp_tools]]\n"
                        + "server = \"shared-mcp\"\n"
                        + "tools = [\"search\", \"get\"]\n");
        return dir;
    }

    /** Scaffold an org-prompts doc-repo with a single review-stance source. */
    private static Path scaffoldDocRepoFixture(boolean multiAgent) throws IOException {
        Path dir = Files.createTempDirectory("doc-org-prompts-");
        Path md = dir.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), "REVIEW STANCE FIXTURE\n");
        String agentsLine = multiAgent
                ? "agents = [\"claude\", \"codex\"]\n"
                : "agents = [\"claude\"]\n";
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"org-prompts\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n"
                        + agentsLine);
        return dir;
    }

    /** Run the instantiator + executor for a fixture, producing a live sandbox + ledger. */
    private static void instantiate(HarnessFixture fix, String instanceId) throws IOException {
        HarnessInstantiator.Plan plan = HarnessInstantiator.plan(
                fix.template, instanceId,
                fix.claudeConfigDir, fix.codexHome, fix.projectDir,
                fix.store);
        java.util.List<SkillEffect> effects = new java.util.ArrayList<>();
        for (Binding b : plan.bindings()) {
            for (Projection p : b.projections()) {
                effects.add(new SkillEffect.MaterializeProjection(p, b.conflictPolicy()));
            }
            effects.add(new SkillEffect.CreateBinding(b));
        }
        new LiveInterpreter(fix.store).run(new Program<>("inst-" + instanceId, effects, r -> null));
        // Mirror the CLI's lock-file write so SyncHarness can recover
        // the same target paths on re-plan.
        Path sandboxRoot = fix.store.harnessesDir()
                .resolve(dev.skillmanager.commands.HarnessCommand.INSTANCES_DIR);
        new HarnessInstanceLock(
                fix.template.name(), instanceId,
                fix.claudeConfigDir, fix.codexHome, fix.projectDir,
                BindingStore.nowIso())
                .write(sandboxRoot);
    }

    /** Rewrite the harness template's toml in the store to drop / add references. */
    private static void rewriteHarnessToml(SkillStore store, String harnessName,
                                           String[] units, String[] docs) throws IOException {
        Path tpl = store.unitDir(harnessName, UnitKind.HARNESS);
        StringBuilder sb = new StringBuilder();
        sb.append("[harness]\nname = \"").append(harnessName).append("\"\n");
        sb.append("version = \"0.1.0\"\n\n");
        sb.append("units = [");
        for (int i = 0; i < units.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(units[i]).append('"');
        }
        sb.append("]\n");
        sb.append("docs = [");
        for (int i = 0; i < docs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(docs[i]).append('"');
        }
        sb.append("]\n");
        Files.writeString(tpl.resolve("harness.toml"), sb.toString());
    }

    private static InstalledUnit installedRec(String name, UnitKind kind) {
        return new InstalledUnit(name, "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE,
                "fixture", null, null,
                "2026-05-12T00:00:00Z",
                List.of(), kind);
    }
}
