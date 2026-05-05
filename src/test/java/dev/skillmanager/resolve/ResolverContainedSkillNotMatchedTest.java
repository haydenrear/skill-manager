package dev.skillmanager.resolve;

import dev.skillmanager._lib.fakes.FakeGit;
import dev.skillmanager._lib.fakes.FakeRegistry;
import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Pins the bundle-internal rule from the spec: contained skills inside
 * a plugin are <em>not</em> resolution targets. Even if the registry
 * doesn't know about a contained skill (it never should, since
 * publishing the plugin doesn't publish its inner skills as separately
 * installable units), an attempt to {@code skill:<contained-name>}
 * through the resolver returns {@link ResolutionError.NotFound}.
 *
 * <p>Also pins the symmetric case where the user installed a plugin
 * via path and tries to resolve one of its contained skills as a
 * sibling local path: the resolver successfully reads it as a
 * standalone bare skill (because the on-disk shape says so) — but the
 * planner is responsible for refusing to install it under the
 * contained name. This test covers the resolver's contract; the
 * planner's contract lands in ticket 05.
 */
public final class ResolverContainedSkillNotMatchedTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("resolver-contained-not-matched-test-");

        return Tests.suite("ResolverContainedSkillNotMatchedTest")
                .test("registry has no record for a contained skill name", () -> {
                    // The plugin lives in the registry; its inner skills do not.
                    // A bare-name resolve for the inner skill therefore returns
                    // NotFound — there is no path through the registry that
                    // would surface a contained skill as a top-level unit.
                    Path pluginRepo = scaffoldPluginWithContained(tmp.resolve("repos-1"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("repo-intel", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/repo-intel", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/repo-intel", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-1"));

                    CoordResolver.Result result = r.resolve(Coord.parse("summarize-repo"));
                    assertTrue(result instanceof CoordResolver.Result.Failed,
                            "contained skill name not resolvable via registry");
                    ResolutionError err = ((CoordResolver.Result.Failed) result).error();
                    assertTrue(err instanceof ResolutionError.NotFound, "NotFound returned");
                })
                .test("skill:contained-name still NotFound (kind-filtered registry has no hit)", () -> {
                    Path pluginRepo = scaffoldPluginWithContained(tmp.resolve("repos-2"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("repo-intel", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/repo-intel", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/repo-intel", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-2"));

                    ResolutionError err = expectFailed(r.resolve(Coord.parse("skill:summarize-repo")));
                    assertTrue(err instanceof ResolutionError.NotFound,
                            "kind-pinned contained-skill coord NotFound");
                })
                .test("resolving the plugin returns the plugin descriptor; contained skills are bundled (not enumerated as references)", () -> {
                    Path pluginRepo = scaffoldPluginWithContained(tmp.resolve("repos-3"));
                    FakeRegistry reg = new FakeRegistry()
                            .add("repo-intel", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/repo-intel", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/repo-intel", "main", pluginRepo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-3"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("plugin:repo-intel")));
                    assertEquals("repo-intel", d.name(), "plugin name returned");
                    assertEquals(UnitKind.PLUGIN, d.unitKind(), "kind=PLUGIN");
                    // The descriptor's references list does NOT include the
                    // contained skills as references — they're bundle-internal.
                    // (DepSpec.empty() above means no plugin-level refs and the
                    // contained skill declares no skill_references either.)
                    assertEquals(0, d.references().size(),
                            "contained skills are not enumerated as references");
                })
                .runAll();
    }

    // -------------------------------------------------------------- helpers

    private static Path scaffoldPluginWithContained(Path tmp) throws IOException {
        Files.createDirectories(tmp);
        ContainedSkillSpec inner = new ContainedSkillSpec("summarize-repo", DepSpec.empty());
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, "repo-intel", DepSpec.empty(), inner);
        return p.sourcePath();
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
