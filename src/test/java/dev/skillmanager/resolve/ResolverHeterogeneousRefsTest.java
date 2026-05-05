package dev.skillmanager.resolve;

import dev.skillmanager._lib.fakes.FakeGit;
import dev.skillmanager._lib.fakes.FakeRegistry;
import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitKindFilter;
import dev.skillmanager.model.UnitReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The descriptor's {@code references} field carries every reference
 * the unit declared, with its {@link UnitKindFilter} preserved. The
 * resolver does not (yet) walk transitively — that's the planner's
 * job in ticket 05. For now we pin the contract that a skill
 * referencing a plugin produces a descriptor whose references list
 * includes a {@link UnitKindFilter#PLUGIN_ONLY} entry, and the
 * symmetric case for a plugin referencing a skill.
 */
public final class ResolverHeterogeneousRefsTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("resolver-hetero-refs-test-");

        return Tests.suite("ResolverHeterogeneousRefsTest")
                .test("skill referencing a plugin: kindFilter survives into descriptor", () -> {
                    Path skillRepo = scaffoldSkillReferencingPlugin(tmp.resolve("repos-1"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("ref-er", UnitKind.SKILL, "0.1.0",
                                    "https://github.com/x/ref-er", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/ref-er", "main", skillRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-1"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("ref-er")));
                    assertSize(1, d.references(), "one reference");
                    UnitReference ref = d.references().get(0);
                    assertEquals(UnitKindFilter.PLUGIN_ONLY, ref.kindFilter(),
                            "skill→plugin reference kindFilter preserved");
                    assertEquals("repo-intel", ref.name(), "ref name");
                })
                .test("plugin referencing a skill: kindFilter survives into descriptor", () -> {
                    Path pluginRepo = scaffoldPluginReferencingSkill(tmp.resolve("repos-2"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("plugin-x", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/plugin-x", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/plugin-x", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-2"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("plugin:plugin-x")));
                    assertEquals(UnitKind.PLUGIN, d.unitKind(), "kind=PLUGIN");
                    assertSize(1, d.references(), "one reference");
                    UnitReference ref = d.references().get(0);
                    assertEquals(UnitKindFilter.SKILL_ONLY, ref.kindFilter(),
                            "plugin→skill reference kindFilter preserved");
                    assertEquals("hello", ref.name(), "ref name");
                })
                .test("plugin with contained skill that references another plugin: refs unioned", () -> {
                    // The contained skill carries skill_references = ["plugin:other-plugin"].
                    // After PluginParser unions, the resulting plugin descriptor's
                    // references list contains the contained-skill ref with the
                    // PLUGIN_ONLY filter intact.
                    Path pluginRepo = scaffoldPluginWithContainedRef(tmp.resolve("repos-3"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("p-with-contained-ref", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/pcr", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/pcr", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-3"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("plugin:p-with-contained-ref")));
                    assertSize(1, d.references(), "one ref unioned from contained skill");
                    assertEquals(UnitKindFilter.PLUGIN_ONLY, d.references().get(0).kindFilter(),
                            "contained skill→plugin filter preserved through union");
                })
                .runAll();
    }

    // -------------------------------------------------------------- helpers

    private static Path scaffoldSkillReferencingPlugin(Path tmp) throws IOException {
        Files.createDirectories(tmp);
        Skill s = UnitFixtures.scaffoldSkill(tmp, "ref-er",
                DepSpec.of().ref("plugin:repo-intel").build());
        return s.sourcePath();
    }

    private static Path scaffoldPluginReferencingSkill(Path tmp) throws IOException {
        Files.createDirectories(tmp);
        DepSpec pluginLevel = DepSpec.of().ref("skill:hello").build();
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, "plugin-x", pluginLevel);
        return p.sourcePath();
    }

    private static Path scaffoldPluginWithContainedRef(Path tmp) throws IOException {
        Files.createDirectories(tmp);
        ContainedSkillSpec contained = new ContainedSkillSpec(
                "inner",
                DepSpec.of().ref("plugin:other-plugin").build()
        );
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, "p-with-contained-ref",
                DepSpec.empty(), contained);
        return p.sourcePath();
    }

    private static UnitDescriptor expectResolved(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Resolved r) return r.descriptor();
        throw new AssertionError("expected Resolved, got " + result);
    }
}
