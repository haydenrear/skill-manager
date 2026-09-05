package dev.skillmanager.resolve;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/** The reverse edge: both mechanisms, the whole chain, and no loops. */
public final class UnitEdgeGraphTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("UnitEdgeGraphTest");

        suite.test("both mechanisms reach one unit, and each edge names its file", () -> {
            SkillStore store = store();
            skill(store, "target", null);
            skill(store, "by-name", imports("target"));
            skill(store, "by-coord", null);
            references(store, "by-coord", "github:owner/target-repo");
            // THE JOIN NOBODY DOES: the coordinate says `target-repo`, the
            // installed unit is called `target`. Only installed/<name>.json
            // knows they are the same thing.
            installedRecord(store, "target", "https://github.com/owner/target-repo");

            UnitEdgeGraph graph = UnitEdgeGraph.of(store);
            List<UnitEdgeGraph.Edge> direct = graph.directImporters("target");
            assertSize(2, direct, "both mechanisms found");
            assertEquals("by-coord", direct.get(0).from(), "coordinate importer");
            assertEquals(UnitEdgeGraph.Mechanism.SKILL_REFERENCES,
                    direct.get(0).mechanism(), "resolved through the origin join");
            assertEquals("by-name", direct.get(1).from(), "name importer");
            assertContains(direct.get(1).source().toString(), "SKILL.md",
                    "the edge names the file carrying it");

            // THE CONTROL for the join: taking the coordinate's last segment
            // would yield `target-repo`, a unit no home contains.
            assertSize(0, graph.directImporters("target-repo"),
                    "the repository name is not a unit and nothing imports it");
        });

        suite.test("the whole chain, not just the direct importers", () -> {
            SkillStore store = store();
            skill(store, "leaf", null);
            skill(store, "middle", imports("leaf"));
            skill(store, "top", imports("middle"));

            UnitEdgeGraph graph = UnitEdgeGraph.of(store);
            assertSize(1, graph.directImporters("leaf"), "one direct importer");
            assertEquals(List.of("middle", "top"), graph.transitiveImporters("leaf"),
                    "and `top` reaches it through `middle`");
        });

        // THE CONSTRAINT THIS TICKET WAS GIVEN. A cycle is not hypothetical:
        // in the operator's root home git-epic-workflow imports
        // git-issue-workflow and git-issue-workflow imports it back. A walk
        // that does not mark before expanding never returns.
        //
        // The assertion is bounded in TIME, deliberately. "It returns" cannot
        // be tested by calling it and seeing whether the test finishes —
        // that is how a looping implementation hangs a whole suite with no
        // failure to read. This fails loudly instead.
        suite.test("a cycle terminates, and the test fails rather than hangs if it does not", () -> {
            SkillStore store = store();
            skill(store, "alpha", imports("beta"));
            skill(store, "beta", imports("alpha"));
            skill(store, "outsider", imports("alpha"));

            List<String> importers = withDeadline(() -> {
                UnitEdgeGraph graph = UnitEdgeGraph.of(store);
                return graph.transitiveImporters("alpha");
            });
            assertEquals(List.of("beta", "outsider"), importers,
                    "the cycle is walked once; `alpha` is not its own importer");

            List<String> other = withDeadline(() -> {
                UnitEdgeGraph graph = UnitEdgeGraph.of(store);
                return graph.transitiveImporters("beta");
            });
            assertEquals(List.of("alpha", "outsider"), other,
                    "and from the other side of the loop, `outsider` still reaches it");
        });

        suite.test("a coordinate naming nothing installed is reported, never guessed", () -> {
            SkillStore store = store();
            skill(store, "importer", null);
            references(store, "importer", "github:owner/never-installed");

            UnitEdgeGraph graph = UnitEdgeGraph.of(store);
            assertSize(1, graph.unresolvedCoordinates(), "the coordinate is reported");
            assertContains(graph.unresolvedCoordinates().get(0), "never-installed",
                    "with the spelling the manifest used");
            assertSize(0, graph.directImporters("never-installed"),
                    "and NOT invented as an edge to a unit that is not here");
        });

        suite.test("a contained skill is its own importer; its plugin does not inherit the edge", () -> {
            SkillStore store = store();
            skill(store, "target", null);
            plugin(store, "carrier");
            containedSkill(store, "carrier", "inside", imports("target"));

            UnitEdgeGraph graph = UnitEdgeGraph.of(store);
            assertTrue(graph.nodes().containsKey("inside"), "the contained skill is a node");
            List<UnitEdgeGraph.Edge> direct = graph.directImporters("target");
            assertSize(1, direct, "exactly one importer");
            assertEquals("inside", direct.get(0).from(),
                    "the contained skill imports it — not `carrier`, which merely carries the file");
        });

        suite.test("a plugin's entry skill of its own name is not a second node", () -> {
            SkillStore store = store();
            skill(store, "target", null);
            plugin(store, "twin");
            containedSkill(store, "twin", "twin", imports("target"));

            UnitEdgeGraph graph = UnitEdgeGraph.of(store);
            assertSize(1, graph.directImporters("target"), "one importer");
            assertEquals("twin", graph.directImporters("target").get(0).from(),
                    "and it is the plugin — one unit under one name, not a plugin plus a skill");
        });

        return suite.runAll();
    }

    /** Run {@code body}, failing if it has not finished in ten seconds. */
    private static <T> T withDeadline(Callable<T> body) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unit-edge-graph-deadline");
            t.setDaemon(true);   // a hung walk must not keep the JVM alive
            return t;
        });
        try {
            Future<T> future = pool.submit(body);
            try {
                return future.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw new AssertionError(
                        "the reverse walk did not terminate within 10s — a cycle is "
                                + "being expanded forever");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static SkillStore store() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("unit-edge-graph-"));
        store.init();
        return store;
    }

    private static String imports(String unit) {
        return """
                skill-imports:
                  - unit: %s
                    path: SKILL.md
                    reason: Needed by tests.
                """.formatted(unit);
    }

    private static void skill(SkillStore store, String name, String frontmatterExtra) throws Exception {
        write(store.skillDir(name), name, frontmatterExtra);
    }

    private static void plugin(SkillStore store, String name) throws Exception {
        Path root = store.pluginsDir().resolve(name);
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.writeString(root.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + name + "\",\"version\":\"0.1.0\",\"description\":\"test\"}\n");
    }

    private static void containedSkill(SkillStore store, String plugin, String name,
                                       String frontmatterExtra) throws Exception {
        write(store.pluginsDir().resolve(plugin).resolve("skills").resolve(name),
                name, frontmatterExtra);
    }

    private static void write(Path dir, String name, String frontmatterExtra) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: fixture\n"
                        + (frontmatterExtra == null ? "" : frontmatterExtra) + "---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "%s — fixture"
                """.formatted(name, name));
    }

    private static void references(SkillStore store, String name, String coord) throws Exception {
        Path toml = store.skillDir(name).resolve("skill-manager.toml");
        Files.writeString(toml, Files.readString(toml)
                + "\nskill_references = [\n  \"" + coord + "\",\n]\n");
    }

    private static void installedRecord(SkillStore store, String name, String origin) throws Exception {
        new UnitStore(store).write(new InstalledUnit(
                name, "0.1.0", InstalledUnit.Kind.GIT, InstalledUnit.InstallSource.GIT,
                origin, null, null, UnitStore.nowIso(), List.of(),
                dev.skillmanager.model.UnitKind.SKILL));
    }
}
