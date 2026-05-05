package dev.skillmanager.resolve;

import dev.skillmanager._lib.fakes.FakeGit;
import dev.skillmanager._lib.fakes.FakeRegistry;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * The resolver is pure with respect to its ports: same coord + same
 * registry state + same on-disk shape → same {@link UnitDescriptor}
 * (modulo the scratch path, which is fresh every call). This is the
 * contract that lets the planner cache resolutions and the executor
 * trust the descriptor without re-resolving.
 *
 * <p>Sweep is small ({@code coord form × repeat}); a richer sweep
 * lives in ticket 09's {@code FailureInjectionSweepTest}.
 */
public final class ResolverDeterminismTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("resolver-determinism-test-");

        return Tests.suite("ResolverDeterminismTest")
                .test("registry coord: two resolves produce equal descriptors (modulo scratch)", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-1"), "stable");
                    FakeRegistry reg = new FakeRegistry()
                            .add("stable", UnitKind.SKILL, "1.0.0",
                                    "https://github.com/x/stable", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/stable", "main", repo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-1"));

                    UnitDescriptor a = expect(r.resolve(Coord.parse("stable")));
                    UnitDescriptor b = expect(r.resolve(Coord.parse("stable")));

                    assertDescriptorsMatch(a, b);
                })
                .test("github coord: two resolves produce equal descriptors", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-2"), "stable-gh");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/stable-gh", null, repo);
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(),
                            tmp.resolve("scratch-2"));

                    UnitDescriptor a = expect(r.resolve(Coord.parse("github:x/stable-gh")));
                    UnitDescriptor b = expect(r.resolve(Coord.parse("github:x/stable-gh")));
                    assertDescriptorsMatch(a, b);
                })
                .test("plugin coord: descriptor identity is stable", () -> {
                    Path repo = scaffoldPlugin(tmp.resolve("repos-3"), "stable-p");
                    FakeRegistry reg = new FakeRegistry()
                            .add("stable-p", UnitKind.PLUGIN, "0.1.0",
                                    "https://github.com/x/stable-p", "main");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/stable-p", "main", repo);
                    CoordResolver r = new CoordResolver(git, reg, tmp.resolve("scratch-3"));

                    UnitDescriptor a = expect(r.resolve(Coord.parse("plugin:stable-p")));
                    UnitDescriptor b = expect(r.resolve(Coord.parse("plugin:stable-p")));
                    assertDescriptorsMatch(a, b);
                })
                .test("local coord: descriptor identity is stable across calls", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-4"), "stable-local");
                    CoordResolver r = new CoordResolver(new FakeGit(), new FakeRegistry(),
                            tmp.resolve("scratch-4"));

                    UnitDescriptor a = expect(r.resolve(Coord.parse(repo.toString())));
                    UnitDescriptor b = expect(r.resolve(Coord.parse(repo.toString())));
                    assertDescriptorsMatch(a, b);
                })
                .runAll();
    }

    // -------------------------------------------------------------- helpers

    private static Path scaffoldSkill(Path tmp, String name) throws IOException {
        Files.createDirectories(tmp);
        Skill s = UnitFixtures.scaffoldSkill(tmp, name, DepSpec.empty());
        return s.sourcePath();
    }

    private static Path scaffoldPlugin(Path tmp, String name) throws IOException {
        Files.createDirectories(tmp);
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, name, DepSpec.empty());
        return p.sourcePath();
    }

    private static UnitDescriptor expect(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Resolved r) return r.descriptor();
        throw new AssertionError("expected Resolved, got " + result);
    }

    private static void assertDescriptorsMatch(UnitDescriptor a, UnitDescriptor b) {
        assertEquals(a.name(), b.name(), "name stable");
        assertEquals(a.unitKind(), b.unitKind(), "unitKind stable");
        assertEquals(a.version(), b.version(), "version stable");
        assertEquals(a.sourceId(), b.sourceId(), "sourceId stable");
        assertEquals(a.discoveryKind(), b.discoveryKind(), "discoveryKind stable");
        assertEquals(a.transport(), b.transport(), "transport stable");
        assertEquals(a.origin(), b.origin(), "origin stable");
        assertEquals(a.resolvedSha(), b.resolvedSha(), "resolvedSha stable");
        assertEquals(a.references(), b.references(), "references stable");
        assertEquals(a.cliDependencies(), b.cliDependencies(), "cliDependencies stable");
        assertEquals(a.mcpDependencies(), b.mcpDependencies(), "mcpDependencies stable");
    }
}
