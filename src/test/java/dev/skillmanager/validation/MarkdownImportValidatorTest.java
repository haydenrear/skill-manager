package dev.skillmanager.validation;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.ConsoleProgramRenderer;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.ProgramRenderer;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class MarkdownImportValidatorTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("MarkdownImportValidatorTest");

        suite.test("validates markdown imports from and to any unit kind", () -> {
            SkillStore store = store();
            installTargetSkill(store, "shared-skill", "reference.md");
            installTargetPlugin(store, "shared-plugin", "docs/reference.md");
            installTargetDocRepo(store, "shared-docs", "claude-md/reference.md");
            installTargetHarness(store, "shared-harness", "reference.md");

            Path roots = Files.createTempDirectory("md-import-roots-");
            Path skill = roots.resolve("skill-unit");
            Files.createDirectories(skill);
            Files.writeString(skill.resolve("SKILL.md"), mdImport("shared-skill", "reference.md"));

            Path plugin = roots.resolve("plugin-unit");
            Files.createDirectories(plugin.resolve("docs"));
            Files.writeString(plugin.resolve("docs/usage.md"),
                    mdImport("shared-plugin", "docs/reference.md"));

            Path doc = roots.resolve("doc-unit");
            Files.createDirectories(doc.resolve("claude-md"));
            Files.writeString(doc.resolve("claude-md/review.md"),
                    mdUnitImport("shared-docs", "claude-md/reference.md"));

            Path harness = roots.resolve("harness-unit");
            Files.createDirectories(harness);
            Files.writeString(harness.resolve("README.md"), mdImport("shared-harness", "reference.md"));

            List<MarkdownImportValidator.Violation> violations = MarkdownImportValidator.validate(
                    store,
                    List.of(
                            new MarkdownImportValidator.UnitRoot("skill-unit", UnitKind.SKILL, skill),
                            new MarkdownImportValidator.UnitRoot("plugin-unit", UnitKind.PLUGIN, plugin),
                            new MarkdownImportValidator.UnitRoot("doc-unit", UnitKind.DOC, doc),
                            new MarkdownImportValidator.UnitRoot("harness-unit", UnitKind.HARNESS, harness)
                    ));

            assertSize(0, violations, "no violations");
        });

        suite.test("reports missing target unit and missing target path", () -> {
            SkillStore store = store();
            installTargetSkill(store, "shared", "reference.md");
            Path unit = Files.createTempDirectory("md-import-bad-");
            Files.writeString(unit.resolve("README.md"),
                    """
                    ---
                    skill-imports:
                      - skill: missing
                        path: reference.md
                        reason: Needed by tests.
                      - skill: shared
                        path: absent.md
                        reason: Needed by tests.
                    ---
                    body
                    """);

            List<MarkdownImportValidator.Violation> violations = MarkdownImportValidator.validate(
                    store,
                    List.of(new MarkdownImportValidator.UnitRoot("widget", UnitKind.PLUGIN, unit)));
            String rendered = MarkdownImportValidator.format(violations);

            assertSize(2, violations, "two violations");
            assertContains(rendered, "references missing unit `missing`", "missing unit reported");
            assertContains(rendered, "references missing path `absent.md`", "missing path reported");
        });

        suite.test("rejects schema problems and escaped paths", () -> {
            SkillStore store = store();
            installTargetSkill(store, "shared", "reference.md");
            Path unit = Files.createTempDirectory("md-import-schema-");
            Files.writeString(unit.resolve("README.md"),
                    """
                    ---
                    skill-imports:
                      - skill: shared
                        path: ../outside.md
                      - nope
                    ---
                    body
                    """);

            List<MarkdownImportValidator.Violation> violations = MarkdownImportValidator.validate(
                    store,
                    List.of(new MarkdownImportValidator.UnitRoot("widget", UnitKind.SKILL, unit)));
            String rendered = MarkdownImportValidator.format(violations);

            assertSize(3, violations, "three violations");
            assertContains(rendered, "missing required `reason`", "reason required");
            assertContains(rendered, "path escapes unit `shared`", "escape rejected");
            assertContains(rendered, "must be a mapping", "non-map rejected");
        });

        suite.test("effect emits violation facts without halting downstream effects", () -> {
            SkillStore store = store();
            Path unit = store.skillDir("widget");
            Files.createDirectories(unit);
            Files.writeString(unit.resolve("SKILL.md"),
                    """
                    ---
                    name: widget
                    skill-imports:
                      - skill: missing
                        path: reference.md
                        reason: Needed by tests.
                    ---
                    body
                    """);

            LiveInterpreter interpreter = new LiveInterpreter(store);
            EffectReceipt receipt = interpreter.runOne(
                    new SkillEffect.ValidateMarkdownImports(List.of("widget")),
                    new EffectContext(store, null, ProgramRenderer.NOOP));

            assertEquals(EffectStatus.PARTIAL, receipt.status(), "receipt partial");
            assertTrue(receipt.continuation() == dev.skillmanager.effects.Continuation.CONTINUE,
                    "validation failure does not halt downstream effects");
            assertContains(receipt.errorMessage(), "1 markdown skill-import violation", "summary");
            assertSize(1, receipt.facts(), "one violation fact");
            assertTrue(receipt.facts().get(0) instanceof dev.skillmanager.effects.ContextFact.MarkdownImportViolation,
                    "typed violation fact");
        });

        suite.test("invalid YAML frontmatter becomes a violation fact", () -> {
            SkillStore store = store();
            Path bad = store.skillDir("bad");
            Path good = store.skillDir("good");
            Files.createDirectories(bad);
            Files.createDirectories(good);
            Files.writeString(bad.resolve("SKILL.md"), invalidYamlSkillMd());
            Files.writeString(good.resolve("SKILL.md"), "---\nname: good\nskill-imports: []\n---\nbody\n");

            LiveInterpreter interpreter = new LiveInterpreter(store);
            EffectReceipt receipt = interpreter.runOne(
                    new SkillEffect.ValidateMarkdownImports(List.of("bad", "good")),
                    new EffectContext(store, null, ProgramRenderer.NOOP));

            assertEquals(EffectStatus.PARTIAL, receipt.status(), "receipt partial");
            assertSize(1, receipt.facts(), "one violation fact");
            assertTrue(receipt.facts().get(0) instanceof dev.skillmanager.effects.ContextFact.MarkdownImportViolation,
                    "typed violation fact");
            var fact = (dev.skillmanager.effects.ContextFact.MarkdownImportViolation) receipt.facts().get(0);
            assertEquals("bad", fact.unitName(), "bad unit reported");
            assertEquals("skill", fact.unitKind(), "kind from store shape");
            assertEquals("SKILL.md", fact.file(), "relative markdown path");
            assertContains(fact.message(), "invalid YAML frontmatter", "yaml parse failure reported");
        });

        suite.test("store listing skips invalid YAML and continues", () -> {
            SkillStore store = store();
            Path bad = store.skillDir("bad");
            Path good = store.skillDir("good");
            Files.createDirectories(bad);
            Files.createDirectories(good);
            Files.writeString(bad.resolve("SKILL.md"), invalidYamlSkillMd());
            Files.writeString(good.resolve("SKILL.md"), "---\nname: good\n---\nbody\n");

            var skills = store.listInstalled();
            assertSize(1, skills.skills(), "only valid skill listed");
            assertSize(1, skills.problems(), "bad skill reported");
            assertEquals("good", skills.skills().get(0).name(), "good skill remains visible");

            var units = store.listInstalledUnits();
            assertSize(1, units.units(), "only valid unit listed");
            assertSize(1, units.problems(), "bad unit reported");
            assertEquals("good", units.units().get(0).name(), "good unit remains visible");
        });

        suite.test("unit read diagnostics dedupe across effect contexts", () -> {
            SkillStore store = store();
            LiveInterpreter interpreter = new LiveInterpreter(store);
            dev.skillmanager.effects.UnitReadProblemReporter.reset();
            EffectContext firstCtx = new EffectContext(store, null, ProgramRenderer.NOOP);
            EffectContext secondCtx = new EffectContext(store, null, ProgramRenderer.NOOP);
            var problem = new dev.skillmanager.store.UnitReadProblem(
                    "bad", UnitKind.SKILL, store.skillDir("bad"), "invalid frontmatter");

            EffectReceipt first = interpreter.runOne(
                    new SkillEffect.ReportUnitReadProblems(List.of(problem)), firstCtx);
            EffectReceipt second = interpreter.runOne(
                    new SkillEffect.ReportUnitReadProblems(List.of(problem)), secondCtx);

            assertEquals(EffectStatus.OK, first.status(), "first receipt ok");
            assertSize(1, first.facts(), "first emits one diagnostic");
            assertTrue(first.facts().get(0) instanceof dev.skillmanager.effects.ContextFact.CantReadUnit,
                    "typed cant-read fact");
            assertEquals(EffectStatus.OK, second.status(), "second receipt ok");
            assertSize(0, second.facts(), "second duplicate suppressed");
        });

        // ------------------------------------------------------------- D6

        suite.test("install exits non-zero when it reports skill-import violations", () -> {
            // Measured: `skill-manager install file://…/acme-broken --yes`
            // exited 0 while printing two violations — at the bottom of a long
            // install, under the success banner and BELOW `ACTION_REQUIRED:
            // Restart Claude / Codex`. An agent that reads the tail concludes
            // success. A printed violation that does not reach an exit code is
            // not a check, it is a comment.
            try (TestHarness h = TestHarness.create()) {
                SkillStore store = h.store();
                installTargetSkill(store, "shared", "reference.md");

                // Companion, mandatory: the home carries no persisted error
                // record before the step, so a non-zero exit can have no other
                // cause. This is the trap `sync` falls into — it exits 7 here
                // for NEEDS_GIT_MIGRATION, so `sync`'s exit code cannot be
                // used as evidence that violations are fatal.
                assertSize(0, unitsWithErrors(store), "precondition: no outstanding error records");

                Path units = Files.createTempDirectory("md-import-install-");
                InstallUseCase.Report report = install(store, brokenUnit(units, "acme-broken"));

                assertTrue(report.committed().contains("acme-broken"),
                        "precondition: the unit did commit, so exit 4 is not what we are reading");
                // The exact count, not a substring: an unrelated error
                // record's remedy text also contains the word "violation".
                assertEquals(2, report.markdownImportViolations(),
                        "both violations counted — a missing unit and a missing path");
                assertEquals(MarkdownImportValidator.EXIT_CODE, report.commandExitCode(),
                        "and they reach the exit code");
                assertTrue(MarkdownImportValidator.EXIT_CODE != 0, "which is not zero");

                // The can-fail companion, inverted: a unit whose imports are
                // valid installs in the same home and exits 0 with no
                // violations. Without this the assertion above is satisfied by
                // an implementation that fails every install.
                InstallUseCase.Report clean = install(store, cleanUnit(units, "acme-lint"));
                assertTrue(clean.committed().contains("acme-lint"), "the clean unit committed");
                assertEquals(0, clean.markdownImportViolations(), "no violations for valid imports");
                assertEquals(0, clean.commandExitCode(), "and it exits 0");
            }
        });

        suite.test("violations are printed before the ACTION_REQUIRED restart banner", () -> {
            // The other half of the same defect: the block landed after the
            // line that reads as the last word on the run. Order is not a
            // substitute for the exit code, but a report whose closing line
            // contradicts its own findings costs a round trip anyway.
            SkillStore store = store();
            ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(
                    store, GatewayConfig.of(java.net.URI.create("http://127.0.0.1:51717")));
            EffectReceipt receipt = EffectReceipt.partial(
                    new SkillEffect.ValidateMarkdownImports(List.of("acme-broken")),
                    List.of(
                            new dev.skillmanager.effects.ContextFact.AgentMcpConfigChanged(
                                    "claude", dev.skillmanager.mcp.McpWriter.ConfigChange.ADDED,
                                    "/tmp/x/.claude/.claude.json"),
                            new dev.skillmanager.effects.ContextFact.MarkdownImportViolation(
                                    "acme-broken", "skill", "SKILL.md",
                                    "skill-imports[0] references missing unit `no-such-unit`"),
                            new dev.skillmanager.effects.ContextFact.MarkdownImportViolation(
                                    "acme-broken", "skill", "SKILL.md",
                                    "skill-imports[1] references missing path `absent.md`")),
                    "2 markdown skill-import violation(s)");

            String out = capture(() -> {
                renderer.onReceipt(receipt);
                renderer.onComplete();
            });

            int violations = out.indexOf("markdown skill-import violations (2)");
            int actionRequired = out.indexOf("ACTION_REQUIRED");
            assertTrue(violations >= 0, "the violation block is printed: " + out);
            assertTrue(actionRequired >= 0,
                    "precondition: the ACTION_REQUIRED banner is printed too, so the "
                            + "ordering assertion is about two lines that both exist");
            assertTrue(violations < actionRequired,
                    "violations come first, not after the line that reads as the last word");
            assertContains(out, "references missing unit `no-such-unit`", "first message named");
            assertContains(out, "references missing path `absent.md`", "second message named");
        });

        suite.test("a named sync answers for the unit it synced, not the whole home", () -> {
            // Measured, and the reason this case exists: `skill-dev sync
            // <unit>` delegates to `skill-manager sync <unit> --from
            // <worktree> --merge`. It printed `✓ synced 1 unit(s) — 1 merged`
            // and then exited 11 for an unresolved import in
            // `skill-dev-skill` — a DIFFERENT unit the command was never asked
            // about. skill-dev reads that exit code, so the product's own
            // documented edit loop failed on content it did not touch, and
            // kept failing until an unrelated unit was fixed.
            //
            // An exit code is a verdict on the run. 11 is only evidence of
            // anything if it is attributable, which is the same argument that
            // gave the violations their own code rather than folding them
            // into the generic 1.
            try (TestHarness h = TestHarness.create()) {
                SkillStore store = h.store();
                installTargetSkill(store, "shared", "reference.md");

                Path work = Files.createTempDirectory("md-import-sync-");
                // The bystander: installed, broken, and not what we sync.
                InstallUseCase.Report seeded = install(store, brokenUnit(work, "acme-broken"));
                assertTrue(seeded.committed().contains("acme-broken"),
                        "precondition: the bystander is installed");
                assertEquals(MarkdownImportValidator.EXIT_CODE, seeded.commandExitCode(),
                        "precondition: and its imports really are unresolved — the install "
                                + "that put it there said so");

                // The unit under sync, whose own imports resolve.
                Path cleanDir = cleanUnit(work, "acme-lint");
                assertTrue(install(store, cleanDir).committed().contains("acme-lint"),
                        "precondition: the synced unit is installed");

                SyncUseCase.Report named = sync(store,
                        List.of(new SyncUseCase.Target.FromDir("acme-lint", cleanDir)));

                assertEquals(0, named.markdownImportViolations(),
                        "a sync of acme-lint reports no violations — acme-broken's reference "
                                + "is not this run's verdict to give");

                // THE COMPANION. Without it, "no violations" is exactly what a
                // build that stopped validating anything would produce, and
                // the case above would pass over a deleted check. A no-name
                // sync targets every installed unit, and it must still find
                // the bystander — both of its violations, counted exactly,
                // because a substring match would also be satisfied by one.
                SyncUseCase.Report wholeHome = sync(store, List.of(
                        new SyncUseCase.Target.Git("acme-broken"),
                        new SyncUseCase.Target.FromDir("acme-lint", cleanDir)));
                assertEquals(2, wholeHome.markdownImportViolations(),
                        "a sync whose targets include acme-broken still reports both of its "
                                + "violations — the scope narrowed, the check did not");
            }
        });

        // OUN-0 BASELINE for GOAL-a-contained-skill-is-addressable. This case
        // asserts the DEFECT, deliberately, and OUN-1 inverts it: a plugin's
        // contained skill must resolve by name exactly as a standalone skill
        // does. Pinning it here means the change shows up as this assertion
        // flipping rather than as a behaviour nobody was watching.
        //
        // Measured on the real tree: skt has contained `unit-authoring` since
        // it shipped, and nothing imports it, because nothing can.
        suite.test("OUN-0 baseline: a plugin-contained skill is NOT addressable by name", () -> {
            SkillStore store = store();
            installTargetPlugin(store, "carrier-plugin", "docs/reference.md");
            // The contained skill: a real unit root, with its own SKILL.md,
            // living inside the plugin exactly as skt carries unit-authoring.
            Path contained = store.pluginsDir().resolve("carrier-plugin")
                    .resolve("skills").resolve("contained-skill");
            Files.createDirectories(contained);
            Files.writeString(contained.resolve("SKILL.md"),
                    "---\nname: contained-skill\n---\nbody\n");
            Files.writeString(contained.resolve("reference.md"), "reference\n");

            Path roots = Files.createTempDirectory("md-import-contained-");
            Path importer = roots.resolve("importer");
            Files.createDirectories(importer);
            Files.writeString(importer.resolve("SKILL.md"),
                    mdUnitImport("contained-skill", "reference.md"));

            List<MarkdownImportValidator.Violation> violations = MarkdownImportValidator.validate(
                    store,
                    List.of(new MarkdownImportValidator.UnitRoot(
                            "importer", UnitKind.SKILL, importer)));

            assertSize(1, violations, "the contained skill is unreachable by name");
            assertContains(violations.get(0).message(), "missing unit `contained-skill`",
                    "the product reports it MISSING, though it is on disk in this very store");
            assertTrue(Files.isRegularFile(contained.resolve("reference.md")),
                    "and the file the import named does exist — the unit is unaddressable, not absent");

            // THE CONTROL. Without it, "one violation" is also what a store
            // with no plugin at all would produce, and the case would pass
            // over a fixture that never installed the carrier.
            Path carrierImporter = roots.resolve("carrier-importer");
            Files.createDirectories(carrierImporter);
            Files.writeString(carrierImporter.resolve("SKILL.md"),
                    mdUnitImport("carrier-plugin", "docs/reference.md"));
            assertSize(0, MarkdownImportValidator.validate(
                            store,
                            List.of(new MarkdownImportValidator.UnitRoot(
                                    "carrier-importer", UnitKind.SKILL, carrierImporter))),
                    "the carrying PLUGIN is addressable by name — only its contained skill is not");
        });

        return suite.runAll();
    }

    /** Run the real sync program over the given targets, no gateway. */
    private static SyncUseCase.Report sync(SkillStore store, List<SyncUseCase.Target> targets)
            throws java.io.IOException {
        return new dev.skillmanager.effects.Executor(store, null)
                .runStaged(SyncUseCase.buildProgram(
                        store, null,
                        new SyncUseCase.Options(null, false, false, false, false, true),
                        targets, List.of()))
                .result();
    }

    /** Names of installed units carrying at least one persisted error record. */
    private static List<String> unitsWithErrors(SkillStore store) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        dev.skillmanager.source.UnitStore sources = new dev.skillmanager.source.UnitStore(store);
        for (var unit : store.listInstalledUnits().units()) {
            sources.read(unit.name()).ifPresent(src -> {
                if (src.hasErrors()) out.add(unit.name());
            });
        }
        return out;
    }

    /** A local unit with exactly two invalid imports: a missing unit and a missing path. */
    private static Path brokenUnit(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s — fixture with two bad imports
                skill-imports:
                  - unit: no-such-unit
                    path: reference.md
                    reason: Needed by tests.
                  - unit: shared
                    path: references/definitely-missing.md
                    reason: Needed by tests.
                ---
                body
                """.formatted(name, name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "%s — fixture"
                """.formatted(name, name));
        return dir;
    }

    /** The control: same shape, one import that resolves. */
    private static Path cleanUnit(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s — fixture with a valid import
                skill-imports:
                  - unit: shared
                    path: reference.md
                    reason: Needed by tests.
                ---
                body
                """.formatted(name, name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "%s — fixture"
                """.formatted(name, name));
        return dir;
    }

    /** Run the real install program over a local directory, no gateway. */
    private static InstallUseCase.Report install(SkillStore store, Path unitDir) {
        var program = InstallUseCase.buildProgram(
                store, null, null, unitDir.toString(), null, true, false, false, true);
        return new dev.skillmanager.effects.Executor(store, null).runStaged(program).result();
    }

    private static String capture(Runnable body) {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previousOut = System.out;
        java.io.PrintStream previousErr = System.err;
        try (java.io.PrintStream capture = new java.io.PrintStream(buf, true)) {
            System.setOut(capture);
            System.setErr(capture);
            body.run();
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        return buf.toString();
    }

    private static SkillStore store() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("md-import-store-"));
        store.init();
        return store;
    }

    private static void installTargetSkill(SkillStore store, String name, String file) throws Exception {
        Path root = store.skillDir(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), "---\nname: " + name + "\n---\nbody\n");
        Files.writeString(root.resolve(file), "reference\n");
    }

    private static void installTargetPlugin(SkillStore store, String name, String file) throws Exception {
        Path root = store.pluginsDir().resolve(name);
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.createDirectories(root.resolve(Path.of(file).getParent()));
        Files.writeString(root.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + name + "\",\"version\":\"0.1.0\",\"description\":\"test\"}\n");
        Files.writeString(root.resolve(file), "reference\n");
    }

    private static void installTargetDocRepo(SkillStore store, String name, String file) throws Exception {
        Path root = store.docsDir().resolve(name);
        Files.createDirectories(root.resolve(Path.of(file).getParent()));
        Files.writeString(root.resolve(file), "reference\n");
        Files.writeString(root.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "%s"
                version = "0.1.0"
                description = "test"

                [[sources]]
                id = "reference"
                file = "%s"
                """.formatted(name, file));
    }

    private static void installTargetHarness(SkillStore store, String name, String file) throws Exception {
        Path root = store.harnessesDir().resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                description = "test"
                """.formatted(name));
        Files.writeString(root.resolve(file), "reference\n");
    }

    private static String mdImport(String skill, String path) {
        return """
                ---
                skill-imports:
                  - skill: %s
                    path: %s
                    reason: Needed by tests.
                ---
                body
                """.formatted(skill, path);
    }

    private static String mdUnitImport(String unit, String path) {
        return """
                ---
                skill-imports:
                  - unit: %s
                    path: %s
                    reason: Needed by tests.
                ---
                body
                """.formatted(unit, path);
    }

    private static String invalidYamlSkillMd() {
        return """
                ---
                name: [unterminated
                ---
                body
                """;
    }
}
