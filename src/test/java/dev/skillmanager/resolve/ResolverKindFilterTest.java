package dev.skillmanager.resolve;

import dev.skillmanager._lib.fakes.FakeGit;
import dev.skillmanager._lib.fakes.FakeRegistry;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Sweeps the kind-filter rules: bare names accept any kind; {@code skill:}
 * narrows to skills; {@code plugin:} narrows to plugins. Multi-kind
 * collisions (same bare name resolves as both) surface as
 * {@link ResolutionError.MultiKindCollision}; kind mismatches (e.g.
 * {@code skill:foo} when foo is registered as a plugin) surface as
 * {@link ResolutionError.KindMismatch}.
 */
public final class ResolverKindFilterTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("resolver-kind-filter-test-");

        return Tests.suite("ResolverKindFilterTest")
                .test("bare name resolves to a skill when only a skill is registered", () -> {
                    Path repo = makeSkillRepo(tmp.resolve("repos-1"), "hello");
                    FakeRegistry reg = new FakeRegistry()
                            .add("hello", UnitKind.SKILL, "1.0.0", "https://github.com/x/hello", "main");
                    FakeGit git = new FakeGit().register("https://github.com/x/hello", "main", repo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-1"));

                    CoordResolver.Result result = r.resolve(Coord.parse("hello"));
                    UnitDescriptor d = expectResolved(result);
                    assertEquals(UnitKind.SKILL, d.unitKind(), "kind from registry");
                    assertEquals("hello", d.name(), "name");
                    assertEquals(DiscoveryKind.REGISTRY, d.discoveryKind(), "discovery");
                    assertEquals(Transport.GIT, d.transport(), "transport");
                })
                .test("bare name with multi-kind hits surfaces MultiKindCollision", () -> {
                    Path skillRepo = makeSkillRepo(tmp.resolve("repos-2-skill"), "ambig");
                    Path pluginRepo = makePluginRepo(tmp.resolve("repos-2-plugin"), "ambig");
                    FakeRegistry reg = new FakeRegistry()
                            .add("ambig", UnitKind.SKILL, "1.0.0", "https://github.com/x/ambig-s", "main")
                            .add("ambig", UnitKind.PLUGIN, "1.0.0", "https://github.com/x/ambig-p", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/ambig-s", "main", skillRepo)
                            .register("https://github.com/x/ambig-p", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-2"));

                    CoordResolver.Result result = r.resolve(Coord.parse("ambig"));
                    ResolutionError err = expectFailed(result);
                    assertTrue(err instanceof ResolutionError.MultiKindCollision,
                            "MultiKindCollision returned");
                    ResolutionError.MultiKindCollision mkc = (ResolutionError.MultiKindCollision) err;
                    assertEquals(2, mkc.candidates().size(), "two candidates");
                    assertTrue(mkc.candidates().contains(UnitKind.SKILL), "skill candidate listed");
                    assertTrue(mkc.candidates().contains(UnitKind.PLUGIN), "plugin candidate listed");
                    assertTrue(mkc.message().contains("ambig"), "message names the coord");
                    assertTrue(mkc.message().contains("skill: or plugin:"),
                            "message hints at the disambiguation prefixes");
                })
                .test("skill:name disambiguates a multi-kind registry", () -> {
                    Path skillRepo = makeSkillRepo(tmp.resolve("repos-3-skill"), "twins");
                    Path pluginRepo = makePluginRepo(tmp.resolve("repos-3-plugin"), "twins");
                    FakeRegistry reg = new FakeRegistry()
                            .add("twins", UnitKind.SKILL, "1.0.0", "https://github.com/x/twins-s", "main")
                            .add("twins", UnitKind.PLUGIN, "1.0.0", "https://github.com/x/twins-p", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/twins-s", "main", skillRepo)
                            .register("https://github.com/x/twins-p", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-3"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("skill:twins")));
                    assertEquals(UnitKind.SKILL, d.unitKind(), "skill: pinned to SKILL");
                })
                .test("plugin:name disambiguates a multi-kind registry", () -> {
                    Path skillRepo = makeSkillRepo(tmp.resolve("repos-4-skill"), "twins");
                    Path pluginRepo = makePluginRepo(tmp.resolve("repos-4-plugin"), "twins");
                    FakeRegistry reg = new FakeRegistry()
                            .add("twins", UnitKind.SKILL, "1.0.0", "https://github.com/x/twins-s", "main")
                            .add("twins", UnitKind.PLUGIN, "1.0.0", "https://github.com/x/twins-p", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/twins-s", "main", skillRepo)
                            .register("https://github.com/x/twins-p", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-4"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("plugin:twins")));
                    assertEquals(UnitKind.PLUGIN, d.unitKind(), "plugin: pinned to PLUGIN");
                })
                .test("skill:name returns NotFound when only the plugin is registered", () -> {
                    Path pluginRepo = makePluginRepo(tmp.resolve("repos-5-plugin"), "only-plugin");
                    FakeRegistry reg = new FakeRegistry()
                            .add("only-plugin", UnitKind.PLUGIN, "1.0.0",
                                    "https://github.com/x/only-plugin", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/only-plugin", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-5"));

                    ResolutionError err = expectFailed(r.resolve(Coord.parse("skill:only-plugin")));
                    assertTrue(err instanceof ResolutionError.NotFound,
                            "kind-pinned coord finds no skill");
                })
                .test("unknown name returns NotFound", () -> {
                    FakeRegistry reg = new FakeRegistry();
                    FakeGit git = new FakeGit();
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-6"));

                    ResolutionError err = expectFailed(r.resolve(Coord.parse("absent")));
                    assertTrue(err instanceof ResolutionError.NotFound, "NotFound returned");
                    assertNotNull(err.message(), "has a message");
                    assertTrue(err.message().contains("absent"), "names the coord");
                })
                .runAll();
    }

    // -------------------------------------------------------------- helpers

    private static Path makeSkillRepo(Path root, String name) throws IOException {
        Files.createDirectories(root);
        Skill s = UnitFixtures.scaffoldSkill(root, name, DepSpec.empty());
        return s.sourcePath();
    }

    private static Path makePluginRepo(Path root, String name) throws IOException {
        Files.createDirectories(root);
        return UnitFixtures.scaffoldPlugin(root, name, DepSpec.empty()).sourcePath();
    }

    private static UnitDescriptor expectResolved(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Resolved r) return r.descriptor();
        throw new AssertionError("expected Resolved, got " + result);
    }

    private static ResolutionError expectFailed(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Failed f) return f.error();
        throw new AssertionError("expected Failed, got " + result);
    }
}
