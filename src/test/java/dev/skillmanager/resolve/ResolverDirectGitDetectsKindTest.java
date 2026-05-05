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
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Direct-git ({@code github:user/repo}, {@code git+url}) and local
 * ({@code file:///abs}, {@code ./rel}) coords don't go through the
 * registry. The resolver clones (or reads) and inspects the on-disk
 * shape: {@code .claude-plugin/plugin.json} → PLUGIN, {@code SKILL.md}
 * at root → SKILL, neither → {@link ResolutionError.UnknownLayout}.
 */
public final class ResolverDirectGitDetectsKindTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("resolver-direct-git-test-");

        return Tests.suite("ResolverDirectGitDetectsKindTest")
                .test("github:user/repo of a skill → SKILL descriptor", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-1"), "echo");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/user/echo", null, repo);
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-1"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("github:user/echo")));
                    assertEquals(UnitKind.SKILL, d.unitKind(), "kind from on-disk shape");
                    assertEquals(DiscoveryKind.DIRECT, d.discoveryKind(), "discovery=DIRECT");
                    assertEquals(Transport.GIT, d.transport(), "transport=GIT");
                    assertEquals("https://github.com/user/echo", d.origin(), "origin url");
                    assertSize(1, git.calls(), "one clone call");
                })
                .test("github:user/repo of a plugin → PLUGIN descriptor", () -> {
                    Path repo = scaffoldPlugin(tmp.resolve("repos-2"), "repo-intel");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/user/repo-intel", null, repo);
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-2"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("github:user/repo-intel")));
                    assertEquals(UnitKind.PLUGIN, d.unitKind(), "kind from on-disk shape");
                    assertEquals(DiscoveryKind.DIRECT, d.discoveryKind(), "discovery=DIRECT");
                })
                .test("git+url with a #ref pins the clone target", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-3"), "pinned");
                    FakeGit git = new FakeGit()
                            .register("https://gitlab.example.com/x/pinned.git", "v1.2.3", repo);
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-3"));

                    UnitDescriptor d = expectResolved(
                            r.resolve(Coord.parse("git+https://gitlab.example.com/x/pinned.git#v1.2.3")));
                    assertEquals(UnitKind.SKILL, d.unitKind(), "kind detected");
                    assertEquals("v1.2.3", git.calls().get(0).ref(), "FakeGit received the pinned ref");
                })
                .test("file:./rel resolves to local path; no clone", () -> {
                    Path repo = scaffoldSkill(tmp.resolve("repos-4"), "local-skill");
                    FakeGit git = new FakeGit();   // no register — should not be called
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-4"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse(repo.toString())));
                    assertEquals(UnitKind.SKILL, d.unitKind(), "kind=SKILL from local path");
                    assertEquals(Transport.LOCAL, d.transport(), "transport=LOCAL");
                    assertSize(0, git.calls(), "no git clone for local coord");
                })
                .test("file:./ pointing at a plugin → PLUGIN", () -> {
                    Path repo = scaffoldPlugin(tmp.resolve("repos-5"), "local-plugin");
                    FakeGit git = new FakeGit();
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-5"));

                    UnitDescriptor d = expectResolved(r.resolve(Coord.parse("file://" + repo)));
                    assertEquals(UnitKind.PLUGIN, d.unitKind(), "PLUGIN detected via .claude-plugin/");
                    assertEquals(Transport.LOCAL, d.transport(), "transport=LOCAL");
                })
                .test("clone target with neither plugin.json nor SKILL.md → UnknownLayout", () -> {
                    Path empty = tmp.resolve("empty-repo");
                    Files.createDirectories(empty);
                    Files.writeString(empty.resolve("readme.txt"), "not a unit");
                    FakeGit git = new FakeGit()
                            .register("https://github.com/x/empty", null, empty);
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-6"));

                    ResolutionError err = expectFailed(r.resolve(Coord.parse("github:x/empty")));
                    assertTrue(err instanceof ResolutionError.UnknownLayout,
                            "UnknownLayout for unrecognized directory");
                })
                .test("clone failure surfaces FetchFailed", () -> {
                    FakeGit git = new FakeGit().failNextClone("network is down");
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-7"));

                    ResolutionError err = expectFailed(r.resolve(Coord.parse("github:x/anywhere")));
                    assertTrue(err instanceof ResolutionError.FetchFailed,
                            "FetchFailed for io error");
                    assertTrue(err.message().contains("network is down"),
                            "underlying cause preserved");
                })
                .test("local path does not exist → NotFound", () -> {
                    FakeGit git = new FakeGit();
                    CoordResolver r = new CoordResolver(git, new FakeRegistry(), tmp.resolve("scratch-8"));
                    Path missing = tmp.resolve("does-not-exist");

                    ResolutionError err = expectFailed(r.resolve(Coord.parse(missing.toString())));
                    assertTrue(err instanceof ResolutionError.NotFound, "NotFound for missing dir");
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

    private static UnitDescriptor expectResolved(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Resolved r) return r.descriptor();
        throw new AssertionError("expected Resolved, got " + result);
    }

    private static ResolutionError expectFailed(CoordResolver.Result result) {
        if (result instanceof CoordResolver.Result.Failed f) return f.error();
        throw new AssertionError("expected Failed, got " + result);
    }
}
