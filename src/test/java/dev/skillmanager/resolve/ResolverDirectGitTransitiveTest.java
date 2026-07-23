package dev.skillmanager.resolve;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.SkillStore;
import org.eclipse.jgit.api.Git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ResolverDirectGitTransitiveTest {

    public static int run() {
        return Tests.suite("ResolverDirectGitTransitiveTest")
                .test("pinned direct-git transitive reference resolves and retains its requester", () -> {
                    Path tmp = Files.createTempDirectory("resolver-direct-git-transitive-");
                    Path child = tmp.resolve("child");
                    scaffoldSkill(child, "git-child", null);

                    String childSha;
                    try (Git git = Git.init().setDirectory(child.toFile()).call()) {
                        git.add().addFilepattern(".").call();
                        childSha = git.commit()
                                .setMessage("fixture")
                                .setAuthor("Test", "test@example.com")
                                .setCommitter("Test", "test@example.com")
                                .call()
                                .getName();
                    }

                    String childSource = "git+" + child.toUri();
                    Path parent = tmp.resolve("parent");
                    scaffoldSkill(parent, "git-parent", childSource + "#" + childSha);

                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();
                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(List.of(
                            new Resolver.Coord(parent.toString(), null)));
                    try {
                        assertSize(0, outcome.failures(), "direct-git child resolves");
                        assertSize(0, outcome.cycles(), "no cycle");
                        assertEquals(2, outcome.graph().resolved().size(), "parent and child resolve");
                        assertTrue(outcome.graph().contains("git-parent"), "parent is present");
                        assertTrue(outcome.graph().contains("git-child"), "direct-git child is present");

                        ResolvedGraph.Resolved resolvedChild = outcome.graph().get("git-child");
                        assertEquals(ResolvedGraph.SourceKind.GIT, resolvedChild.sourceKind(),
                                "child source kind");
                        assertEquals(childSource, resolvedChild.source(), "child source");
                        assertEquals(List.of("git-parent"), resolvedChild.requestedBy(),
                                "declaring parent retained");
                        try (Git resolvedGit = Git.open(resolvedChild.unit().sourcePath().toFile())) {
                            assertEquals(childSha,
                                    resolvedGit.getRepository().resolve("HEAD").getName(),
                                    "declared pin is checked out");
                        }
                    } finally {
                        outcome.graph().cleanup();
                    }
                })
                .runAll();
    }

    private static void scaffoldSkill(Path dir, String name, String reference) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: direct-git transitive fixture
                ---
                fixture
                """.formatted(name));
        String references = reference == null
                ? ""
                : "skill_references = [\"" + reference.replace("\\", "\\\\") + "\"]\n\n";
        Files.writeString(dir.resolve("skill-manager.toml"), """
                %s[skill]
                name = "%s"
                version = "0.1.0"
                description = "direct-git transitive fixture"
                """.formatted(references, name));
    }
}
