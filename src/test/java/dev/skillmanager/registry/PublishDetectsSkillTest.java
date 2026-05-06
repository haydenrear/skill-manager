package dev.skillmanager.registry;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-13 regression: the existing skill publish flow keeps working
 * after the kind-aware refactor. {@link SkillPackager#pack} on a bare
 * skill dir (no {@code .claude-plugin/}, just {@code SKILL.md} at root)
 * detects {@link SkillPackager.Kind#SKILL} and bundles exactly what it
 * always did.
 */
public final class PublishDetectsSkillTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("PublishDetectsSkillTest");

        suite.test("skill dir → detectKind returns SKILL", () -> {
            Path tmp = Files.createTempDirectory("publish-skill-detect-");
            UnitFixtures.scaffoldSkill(tmp, "widget", DepSpec.empty());
            assertEquals(SkillPackager.Kind.SKILL, SkillPackager.detectKind(tmp.resolve("widget")),
                    "kind detected as SKILL");
        });

        suite.test("skill bundle includes SKILL.md + skill-manager.toml; no plugin paths", () -> {
            Path tmp = Files.createTempDirectory("publish-skill-pack-");
            UnitFixtures.scaffoldSkill(tmp, "widget",
                    DepSpec.of().cli("pip:cowsay==6.0").build());
            Path out = Files.createTempDirectory("publish-skill-out-");
            Path bundle = SkillPackager.pack(tmp.resolve("widget"), out);

            Set<String> entries = PublishDetectsPluginTest.readBundleEntries(bundle);
            assertTrue(entries.contains("SKILL.md"), "SKILL.md included");
            assertTrue(entries.contains("skill-manager.toml"), "skill-manager.toml included");
            assertFalse(entries.stream().anyMatch(e -> e.startsWith(".claude-plugin")),
                    "no plugin manifest dir in skill bundle");
            assertFalse(entries.contains("skill-manager-plugin.toml"),
                    "no plugin toml in skill bundle");
        });

        suite.test("skill bundle still excludes dotfiles (.git, .DS_Store)", () -> {
            Path tmp = Files.createTempDirectory("publish-skill-exclude-");
            UnitFixtures.scaffoldSkill(tmp, "widget", DepSpec.empty());
            Path skillRoot = tmp.resolve("widget");
            Files.createDirectories(skillRoot.resolve(".git"));
            Files.writeString(skillRoot.resolve(".git/HEAD"), "ref: refs/heads/main\n");
            Files.writeString(skillRoot.resolve(".DS_Store"), "macos junk");

            Path out = Files.createTempDirectory("publish-skill-exclude-out-");
            Path bundle = SkillPackager.pack(skillRoot, out);
            Set<String> entries = PublishDetectsPluginTest.readBundleEntries(bundle);

            assertFalse(entries.contains(".git/HEAD"), ".git excluded");
            assertFalse(entries.contains(".DS_Store"), ".DS_Store excluded");
            assertTrue(entries.contains("SKILL.md"), "SKILL.md still included");
        });

        suite.test("dir with neither SKILL.md nor plugin.json → IOException with explicit message", () -> {
            Path tmp = Files.createTempDirectory("publish-neither-");
            try {
                SkillPackager.detectKind(tmp);
                throw new AssertionError("expected IOException for unrecognizable dir");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("not a recognizable unit dir"),
                        "error message names the problem");
            }
        });

        return suite.runAll();
    }
}
