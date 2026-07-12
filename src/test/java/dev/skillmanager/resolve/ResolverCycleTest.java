package dev.skillmanager.resolve;

import dev.skillmanager._lib.test.Tests;
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
                .runAll();
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
