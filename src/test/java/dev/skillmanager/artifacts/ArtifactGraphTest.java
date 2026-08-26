package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.SkillStore;

import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-05: the edges, and the containment rule they turn on.
 *
 * <p>The cases here are the ones the resolution rule was WRITTEN for rather
 * than the ones it happens to pass. Three of them would each have shipped a
 * plausible, partial, silently-wrong graph:
 *
 * <ul>
 *   <li>a shim depending on a FILE INSIDE a tree whose artifact records the
 *       tree ROOT — exact-match resolution finds no edge for the one case the
 *       edge exists for;</li>
 *   <li>{@code venvs/x-old} against {@code venvs/x} — plain {@code startsWith}
 *       says the first is inside the second and names an unrelated tree in a
 *       stale report;</li>
 *   <li>an id that embeds a path for one kind and not for two others —
 *       resolution by id passes for {@code provisioned-tree:…} and fails for
 *       {@code cli-shim:brew/opentofu}, whose shim is {@code bin/cli/tofu}.</li>
 * </ul>
 */
public final class ArtifactGraphTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactGraphTest");

        suite.test("a shim's edge to its tree is found through a SUBPATH, not an exact match",
                () -> {
                    SkillStore store = ArtifactsFixture.seed();
                    ArtifactIndex index = ArtifactIndex.of(store);
                    ArtifactGraph graph = ArtifactGraph.of(index);

                    String shim = ArtifactIds.cliShim("skill-script", "alpha-script");
                    Artifact artifact = index.byId(shim).orElseThrow();

                    // The wrapper's exec target is
                    // cache/uv-tools/alpha/bin/alpha-script; the tree artifact's
                    // sole output path is cache/uv-tools. Neither string is the
                    // other, and the whole rule exists for that.
                    assertTrue(artifact.observedInputs().stream()
                                    .anyMatch(i -> i.startsWith("store:cache/skill-script-alpha-alpha-script")),
                            "the shim declares the path it actually runs, not the tree root: "
                                    + artifact.observedInputs());
                    assertContains(graph.dependsOn(shim).toString(),
                            ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"),
                            "and it resolves to the tree that contains it");
                });

        suite.test("a moved unit reaches its shims and the trees they run out of", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactGraph graph = ArtifactGraph.of(index);

            // The sentence the whole epic exists to make sayable.
            var downstream = graph.downstreamOf(ArtifactIds.unitStore("alpha"));
            assertContains(downstream.toString(),
                    ArtifactIds.cliShim("skill-script", "alpha-script"),
                    "alpha moving reaches the shim it declares");
            assertContains(downstream.toString(),
                    ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"),
                    "and the tree that shim runs out of");
        });

        suite.test("containment stops at a segment boundary", () -> {
            // venvs/jinja2-cli-old is NOT inside venvs/jinja2-cli. A plain
            // startsWith says it is, and a stale report then names a tree the
            // artifact has nothing to do with.
            Artifact tree = tree("venvs", "jinja2-cli");
            Artifact sibling = tree("venvs", "jinja2-cli-old");
            Artifact consumer = new Artifact("cli-shim:pip/jinja2-cli", ArtifactKind.CLI_SHIM,
                    null, List.of(), List.of(), null, Map.of(), Map.of(), null, null,
                    List.of(ArtifactIds.storeInput("venvs/jinja2-cli-old/bin/jinja2")));

            ArtifactGraph graph = ArtifactGraph.of(List.of(tree, sibling, consumer));
            assertEquals(List.of(sibling.id()), graph.dependsOn(consumer.id()),
                    "the sibling, and only the sibling");
        });

        suite.test("the LONGEST containing output wins, so a nested tree beats its parent", () -> {
            Artifact outer = tree("cache", "uv-tools");
            Artifact inner = new Artifact("provisioned-tree:cache/uv-tools/skill-dev",
                    ArtifactKind.PROVISIONED_TREE, null, List.of(),
                    List.of(Artifact.Output.inHome("cache/uv-tools/skill-dev",
                            Artifact.Presence.PRESENT)),
                    null, Map.of(), Map.of(), null, null, List.of());
            Artifact consumer = new Artifact("cli-shim:skill-script/skill-dev",
                    ArtifactKind.CLI_SHIM, null, List.of(), List.of(), null, Map.of(), Map.of(),
                    null, null,
                    List.of(ArtifactIds.storeInput("cache/uv-tools/skill-dev/bin/skill-dev")));

            ArtifactGraph graph = ArtifactGraph.of(List.of(outer, inner, consumer));
            assertEquals(List.of(inner.id()), graph.dependsOn(consumer.id()),
                    "credited to the nearer tree, not to whichever the map held first");
        });

        suite.test("containment runs one way only — a tree does not depend on what is in it",
                () -> {
                    Artifact tree = tree("venvs", "x");
                    Artifact inside = new Artifact("cli-shim:pip/x", ArtifactKind.CLI_SHIM, null,
                            List.of(), List.of(Artifact.Output.inHome("venvs/x/bin/x",
                                    Artifact.Presence.PRESENT)),
                            null, Map.of(), Map.of(), null, null, List.of());
                    // The tree references a file that the shim artifact happens
                    // to output. Reversing containment here invents a cycle in a
                    // graph that has none.
                    Artifact treeReferencing = new Artifact(tree.id(), tree.kind(), null,
                            List.of(), tree.outputs(), null, Map.of(), Map.of(), null, null,
                            List.of(ArtifactIds.storeInput("venvs/x")));

                    ArtifactGraph graph = ArtifactGraph.of(List.of(treeReferencing, inside));
                    assertEquals(List.of(), graph.dependsOn(treeReferencing.id()),
                            "an output inside the referenced directory is not its producer");
                });

        suite.test("an artifact is never its own producer, and is not 'unresolved' either", () -> {
            Artifact self = new Artifact("provisioned-tree:venvs/x", ArtifactKind.PROVISIONED_TREE,
                    null, List.of(ArtifactIds.storeInput("venvs/x")),
                    List.of(Artifact.Output.inHome("venvs/x", Artifact.Presence.PRESENT)),
                    null, Map.of(), Map.of(), null, null, List.of());
            ArtifactGraph graph = ArtifactGraph.of(List.of(self));
            assertEquals(List.of(), graph.dependsOn(self.id()),
                    "a self-edge is one thing described twice, not a loop");
            // Dropped is not dangling. Reporting it unresolved would tell
            // ArtifactFreshness this home does not hold a path the artifact
            // demonstrably produces, and downgrade a current verdict on it.
            assertEquals(List.of(), graph.unresolvedInputs(self.id()),
                    "and it is not reported as an input this home cannot account for");
        });

        suite.test("a dangling unit: reference is unresolved, so no verdict can skip it", () -> {
            // The shape a lock row takes once its declaring unit is
            // uninstalled. `unit:` is deliberately not terminal, and the
            // verdict layer used to consider only `store:` unresolved inputs —
            // so this one was dropped and the artifact was decided as though
            // the input were satisfied.
            Artifact orphan = new Artifact("cli-shim:pip/orphan", ArtifactKind.CLI_SHIM, null,
                    List.of(ArtifactIds.unitInput("uninstalled-unit")), List.of(), null,
                    Map.of(), Map.of(), null, null, List.of());
            ArtifactGraph graph = ArtifactGraph.of(List.of(orphan));
            assertEquals(List.of("unit:uninstalled-unit"), graph.unresolvedInputs(orphan.id()),
                    "an input naming a unit this home does not hold is a gap, not a leaf");
        });

        suite.test("a cycle is a named plan error, not a stack overflow", () -> {
            Artifact a = node("provisioned-tree:venvs/a", "venvs/a", "store:venvs/b");
            Artifact b = node("provisioned-tree:venvs/b", "venvs/b", "store:venvs/a");
            ArtifactCycleException caught = null;
            try {
                ArtifactGraph.of(List.of(a, b));
            } catch (ArtifactCycleException thrown) {
                caught = thrown;
            }
            assertTrue(caught != null, "a cycle must refuse rather than be walked");
            {
                ArtifactCycleException e = caught;
                assertTrue(e.chain().size() >= 3, "the chain names the loop: " + e.chain());
                assertEquals(e.chain().get(0), e.chain().get(e.chain().size() - 1),
                        "and it closes on the id it started at");
                assertContains(e.getMessage(), "cycle", "and says so");
            }
        });

        suite.test("terminal schemes are leaves, and a missing store: path is not", () -> {
            Artifact declared = new Artifact("cli-shim:pip/ghost", ArtifactKind.CLI_SHIM, null,
                    List.of(ArtifactIds.specInput("pip:ghost"),
                            ArtifactIds.gitInput("https://example.invalid/g.git", "main"),
                            ArtifactIds.bindingInput("default:claude:ghost")),
                    List.of(), null, Map.of(), Map.of(), null, null,
                    List.of(ArtifactIds.storeInput("venvs/nothing-made-this/bin/ghost")));
            ArtifactGraph graph = ArtifactGraph.of(List.of(declared));

            assertEquals(List.of(), graph.dependsOn(declared.id()), "no producer for any of them");
            // spec:/git:/binding: are terminal BY CONSTRUCTION — nothing in a
            // home produces a package spec — so they are not "unresolved".
            assertEquals(List.of("store:venvs/nothing-made-this/bin/ghost"),
                    graph.unresolvedInputs(declared.id()),
                    "only the store: reference is an input this home cannot account for");
        });

        suite.test("record: resolves only when exactly one artifact owns that record", () -> {
            Artifact one = new Artifact("unit-store:alpha", ArtifactKind.UNIT_STORE, "alpha",
                    List.of(), List.of(), "installed/alpha.json", Map.of(), Map.of(), null, null,
                    List.of());
            Artifact shimA = new Artifact("cli-shim:pip/a", ArtifactKind.CLI_SHIM, null,
                    List.of(ArtifactIds.recordInput("cli-lock.toml")), List.of(), "cli-lock.toml",
                    Map.of(), Map.of(), null, null, List.of());
            Artifact shimB = new Artifact("cli-shim:pip/b", ArtifactKind.CLI_SHIM, null,
                    List.of(ArtifactIds.recordInput("installed/alpha.json")), List.of(),
                    "cli-lock.toml", Map.of(), Map.of(), null, null, List.of());

            ArtifactGraph graph = ArtifactGraph.of(List.of(one, shimA, shimB));
            assertEquals(List.of(), graph.dependsOn(shimA.id()),
                    "cli-lock.toml is the source of two artifacts, so it produces neither");
            assertEquals(List.of(one.id()), graph.dependsOn(shimB.id()),
                    "installed/alpha.json is owned by exactly one, so it resolves");
        });

        suite.test("edges are derived on read and never written to the ledger", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactIndex index = ArtifactIndex.of(store);
            ArtifactLedger.of(index.artifacts()).save(store);

            String written = java.nio.file.Files.readString(ArtifactLedger.file(store));
            assertFalse(written.contains("cache/skill-script-alpha-alpha-script/venv"),
                    "the observed path the shim runs is NOT stamped into the ledger — a "
                            + "persisted edge is a fact that can disagree with the disk");

            // And it is still an edge on the next read, because it is re-read.
            ArtifactGraph graph = ArtifactGraph.of(ArtifactIndex.of(store));
            assertContains(graph.dependsOn(ArtifactIds.cliShim("skill-script", "alpha-script"))
                            .toString(),
                    ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"),
                    "derived from the same call on every read");
        });

        return suite.runAll();
    }

    private static Artifact tree(String root, String name) {
        return new Artifact(ArtifactIds.provisionedTree(root, name),
                ArtifactKind.PROVISIONED_TREE, null, List.of(),
                List.of(Artifact.Output.inHome(root + "/" + name, Artifact.Presence.PRESENT)),
                null, Map.of(), Map.of(), null, null, List.of());
    }

    private static Artifact node(String id, String output, String input) {
        return new Artifact(id, ArtifactKind.PROVISIONED_TREE, null, List.of(input),
                List.of(Artifact.Output.inHome(output, Artifact.Presence.PRESENT)),
                null, Map.of(), Map.of(), null, null, List.of());
    }
}
