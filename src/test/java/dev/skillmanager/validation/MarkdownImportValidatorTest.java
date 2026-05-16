package dev.skillmanager.validation;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.EffectContext;
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

        suite.test("validates markdown imports under any unit kind", () -> {
            SkillStore store = store();
            installTargetSkill(store, "shared", "reference.md");

            Path roots = Files.createTempDirectory("md-import-roots-");
            Path skill = roots.resolve("skill-unit");
            Files.createDirectories(skill);
            Files.writeString(skill.resolve("SKILL.md"), mdImport("shared", "reference.md"));

            Path plugin = roots.resolve("plugin-unit");
            Files.createDirectories(plugin.resolve("docs"));
            Files.writeString(plugin.resolve("docs/usage.md"), mdImport("shared", "reference.md"));

            Path doc = roots.resolve("doc-unit");
            Files.createDirectories(doc.resolve("claude-md"));
            Files.writeString(doc.resolve("claude-md/review.md"), mdImport("shared", "reference.md"));

            Path harness = roots.resolve("harness-unit");
            Files.createDirectories(harness);
            Files.writeString(harness.resolve("README.md"), mdImport("shared", "reference.md"));

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

        suite.test("reports missing target skill and missing target path", () -> {
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
            assertContains(rendered, "references missing skill `missing`", "missing skill reported");
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
            assertContains(rendered, "path escapes skill `shared`", "escape rejected");
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
            assertSize(1, skills, "only valid skill listed");
            assertEquals("good", skills.get(0).name(), "good skill remains visible");

            var units = store.listInstalledUnits();
            assertSize(1, units, "only valid unit listed");
            assertEquals("good", units.get(0).name(), "good unit remains visible");
        });

        return suite.runAll();
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

    private static String invalidYamlSkillMd() {
        return """
                ---
                name: [unterminated
                ---
                body
                """;
    }
}
