package dev.skillmanager.resolve;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.CoordSource;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ResolverCycleTest {

    public static int run() {
        return Tests.suite("ResolverCycleTest")
                .test("name-mismatched file reference cycle resolves once and reports its path", () -> {
                    Path tmp = Files.createTempDirectory("resolver-cycle-test-");
                    Path repoA = tmp.resolve("repo-a-dir").toAbsolutePath();
                    Path repoB = tmp.resolve("repo-b-dir").toAbsolutePath();
                    scaffoldSkill(repoA, "alpha-unit", "file:" + repoB);
                    scaffoldSkill(repoB, "beta-unit", repoA.toString());

                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();
                    Resolver resolver = new Resolver(store);

                    var executor = Executors.newSingleThreadExecutor();
                    Resolver.ResolveOutcome outcome;
                    try {
                        outcome = executor.submit(() -> resolver.resolveAll(List.of(
                                        new Resolver.Coord("file:" + repoA, null))))
                                .get(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS);
                    } finally {
                        executor.shutdownNow();
                    }

                    assertSize(0, outcome.failures(), "cycle is recoverable");
                    assertEquals(2, outcome.graph().resolved().size(), "each unit resolved once");
                    assertEquals(2L, stageDirCount(store.cacheDir()), "one staging dir per fetched coord");
                    assertSize(1, outcome.cycles(), "cycle reported once");
                    assertEquals(List.of("alpha-unit", "beta-unit", "alpha-unit"),
                            outcome.cycles().getFirst().path(), "cycle path uses unit names");
                    assertTrue(outcome.graph().get("alpha-unit").requestedBy().contains("beta-unit"),
                            "closing requester merged into existing unit");

                    outcome.graph().cleanup();
                    assertEquals(0L, stageDirCount(store.cacheDir()), "cycle staging dirs cleaned");
                })
                .test("distinct coords with the same unit name discard redundant staging", () -> {
                    Path tmp = Files.createTempDirectory("resolver-duplicate-name-test-");
                    Path first = tmp.resolve("first-repo").toAbsolutePath();
                    Path second = tmp.resolve("second-repo").toAbsolutePath();
                    scaffoldSkill(first, "shared-unit", null);
                    scaffoldSkill(second, "shared-unit", null);

                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();
                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(List.of(
                            new Resolver.Coord(first.toString(), null),
                            new Resolver.Coord(second.toString(), null)));

                    assertEquals(1, outcome.graph().resolved().size(), "duplicate name kept once");
                    assertEquals(1L, stageDirCount(store.cacheDir()),
                            "redundant post-fetch staging deleted");
                    outcome.graph().cleanup();
                    assertEquals(0L, stageDirCount(store.cacheDir()), "retained staging cleaned");
                })
                .test("equivalent Git spellings share a key while different revisions do not", () -> {
                    assertEquals(
                            Resolver.coordKey("github:Owner/Repo#v1", null),
                            Resolver.coordKey("git+https://github.com/owner/repo.git#v1", null),
                            "GitHub shorthand and URL normalize uniformly");
                    assertTrue(!Resolver.coordKey("github:owner/repo#v1", null).equals(
                                    Resolver.coordKey("git+https://github.com/owner/repo.git#v2", null)),
                            "different revisions remain distinct");
                })
                .test("a git-coordinate reference edge keys onto its top-level coord", () -> {
                    // The reference edge the resolver used to drop: a `github:`
                    // child has no registry name and no path, so it must be
                    // dispatched from the coord itself.
                    UnitReference ref = UnitReference.parse("github:owner/repo#v1");
                    CoordSource child = CoordSource.of(ref.coord(), Path.of("/unused"));

                    assertEquals("git+https://github.com/owner/repo", child.source(),
                            "git reference projects to a clonable source");
                    assertEquals("v1", child.version(),
                            "revision travels as the version — Fetcher cannot clone a #fragment");
                    assertEquals(
                            Resolver.coordKey("github:owner/repo#v1", null),
                            Resolver.coordKey(child.source(), child.version()),
                            "reaching a unit by reference edge and as a top-level coord is one node");
                    assertTrue(!Resolver.coordKey(child.source(), child.version())
                                    .equals(Resolver.coordKey("github:owner/repo#v2", null)),
                            "a reference edge stays revision-aware");
                })
                .test("direct-git reference is traversed into a transitive install", () -> {
                    Path tmp = Files.createTempDirectory("resolver-direct-git-test-");
                    Path leaf = tmp.resolve("leaf-repo").toAbsolutePath();
                    scaffoldSkill(leaf, "leaf-unit", null);
                    gitInitCommit(leaf);

                    Path root = tmp.resolve("root-repo").toAbsolutePath();
                    scaffoldSkill(root, "root-unit", "git+file://" + leaf + "#main");

                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();
                    Resolver.ResolveOutcome outcome = resolveWithTimeout(
                            store, new Resolver.Coord("file:" + root, null));

                    assertSize(0, outcome.failures(), "git reference resolves");
                    assertSize(0, outcome.cycles(), "an acyclic git edge reports no cycle");
                    assertEquals(2, outcome.graph().resolved().size(),
                            "the git-referenced unit is installed transitively");
                    assertTrue(outcome.graph().contains("leaf-unit"),
                            "unit named by its manifest, not by its repo directory");
                    assertTrue(outcome.graph().get("leaf-unit").requestedBy().contains("root-unit"),
                            "git edge attributes its requester");
                    outcome.graph().cleanup();
                })
                .test("mutual direct-git reference cycle resolves once and reports its path", () -> {
                    Path tmp = Files.createTempDirectory("resolver-git-cycle-test-");
                    Path repoA = tmp.resolve("git-a-dir").toAbsolutePath();
                    Path repoB = tmp.resolve("git-b-dir").toAbsolutePath();
                    // Each side names the other by git coordinate — the mutual
                    // `github:`-style back-edge from the ticket, in a form that
                    // clones offline.
                    scaffoldSkill(repoA, "git-alpha-unit", "git+file://" + repoB + "#main");
                    scaffoldSkill(repoB, "git-beta-unit", "git+file://" + repoA + "#main");
                    gitInitCommit(repoA);
                    gitInitCommit(repoB);

                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();
                    Resolver.ResolveOutcome outcome = resolveWithTimeout(
                            store, new Resolver.Coord("git+file://" + repoA, "main"));

                    assertSize(0, outcome.failures(), "a git-coordinate cycle is recoverable");
                    assertEquals(2, outcome.graph().resolved().size(), "each unit resolved once");
                    assertSize(1, outcome.cycles(), "cycle reported once");
                    assertEquals(List.of("git-alpha-unit", "git-beta-unit", "git-alpha-unit"),
                            outcome.cycles().getFirst().path(), "cycle path uses unit names");
                    assertTrue(outcome.graph().get("git-alpha-unit").requestedBy().contains("git-beta-unit"),
                            "closing git requester merged into existing unit");

                    outcome.graph().cleanup();
                    assertEquals(0L, stageDirCount(store.cacheDir()), "cycle staging dirs cleaned");
                })
                .runAll();
    }

    /**
     * Resolve under a wall-clock bound so a walk that fails to terminate fails
     * the test instead of hanging it. Generous enough to absorb the local git
     * clones the direct-git fixtures perform.
     */
    private static Resolver.ResolveOutcome resolveWithTimeout(SkillStore store, Resolver.Coord coord)
            throws Exception {
        Resolver resolver = new Resolver(store);
        var executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> resolver.resolveAll(List.of(coord)))
                    .get(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Make {@code dir} a git repo on {@code main} so it can be cloned over {@code git+file://}. */
    private static void gitInitCommit(Path dir) throws Exception {
        git(dir, "init", "-b", "main", "--quiet");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=fixture@skillmanager.local", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", "fixture");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + p.exitValue());
        }
    }

    private static void scaffoldSkill(Path dir, String name, String reference) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: cycle fixture
                ---
                fixture
                """.formatted(name));
        String references = reference == null ? "" : "skill_references = [\"%s\"]\n"
                .formatted(reference.replace("\\", "\\\\"));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "cycle fixture"

                %s
                """.formatted(name, references));
    }

    private static long stageDirCount(Path cacheDir) throws Exception {
        try (var entries = Files.list(cacheDir)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("stage-")).count();
        }
    }
}
