package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-03's load-bearing property: <b>an artifact is the same artifact in a
 * home and in a clone of it.</b>
 *
 * <p>Without that, the DAG cannot cross a tier and ARTI-07 has nothing to
 * reason with — "this ticket home is missing {@code provisioned-tree:cache/…}"
 * is only a sentence if the tree has the same id in the home that has it. The
 * clone here is the real {@code HomeCloner}, not a copy, so the test also
 * covers the thing the ticket asks about: whether {@code artifacts.lock.toml}
 * survives a class that re-anchors {@code Surface.STATE} through the production
 * serde and byte-substitutes everything it does not model.
 */
public final class ArtifactHomeStabilityTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactHomeStabilityTest");

        suite.test("the ledger crosses a clone byte-identical and fails nothing", () -> {
            SkillStore source = ArtifactsFixture.seed();
            ArtifactLedger.of(ArtifactIndex.of(source).artifacts()).save(source);
            byte[] before = Files.readAllBytes(ArtifactLedger.file(source));

            SkillStore clone = cloneOf(source);

            assertTrue(Arrays.equals(before, Files.readAllBytes(ArtifactLedger.file(clone))),
                    "no byte moved: the ledger holds no absolute path, so the cloner's"
                            + " substitution pass has no needle to find");
        });

        suite.test("every id survives the clone unchanged", () -> {
            SkillStore source = ArtifactsFixture.seed();
            ArtifactIndex before = ArtifactIndex.of(source);
            ArtifactLedger.of(before.artifacts()).save(source);

            ArtifactIndex after = ArtifactIndex.of(cloneOf(source));

            assertEquals(ids(before.artifacts()), ids(after.artifacts()),
                    "the id set is a property of the artifacts, not of the root they sit under");
        });

        suite.test("what a clone skips is DECLARED and not materialized", () -> {
            SkillStore source = ArtifactsFixture.seed();
            ArtifactLedger.of(ArtifactIndex.of(source).artifacts()).save(source);

            // The source home HAS both trees; a clone carries neither, by the
            // deliberate design of HomeCloner.SKIPPED_DIRS.
            Artifact sourceTree = ArtifactIndex.of(source)
                    .byId(ArtifactIds.provisionedTree("venvs", "alpha-venv")).orElseThrow();
            assertEquals(Artifact.Materialization.MATERIALIZED, sourceTree.materialization(),
                    "materialized where it was provisioned");

            Artifact clonedTree = ArtifactIndex.of(cloneOf(source))
                    .byId(ArtifactIds.provisionedTree("venvs", "alpha-venv")).orElseThrow();
            assertEquals(Artifact.Origin.LEDGER, clonedTree.origin(),
                    "the clone knows about it only because the ledger declared it");
            assertEquals(Artifact.Materialization.DECLARED_ONLY, clonedTree.materialization(),
                    "declared here, materialized nowhere — the row ARTI-07 needs and"
                            + " that nothing in a home has today");
        });

        suite.test("a wrapper a clone re-anchored into nothing reports as broken", () -> {
            SkillStore source = ArtifactsFixture.seed();
            ArtifactLedger.of(ArtifactIndex.of(source).artifacts()).save(source);

            String id = ArtifactIds.cliShim("skill-script", "alpha-script");
            assertEquals(Artifact.Materialization.MATERIALIZED,
                    ArtifactIndex.of(source).byId(id).orElseThrow().materialization(),
                    "the wrapper runs in the home that provisioned it");

            Artifact cloned = ArtifactIndex.of(cloneOf(source)).byId(id).orElseThrow();
            // The measured defect: the wrapper is copied and re-anchored, the
            // cache/ tree it execs into is skipped, and every presence check in
            // the system passes on an executable file.
            assertEquals(Artifact.Materialization.DECLARED_ONLY, cloned.materialization(),
                    "the shim is there and it will not run");
            assertContains(cloned.actual().get("unusable_because"),
                    "which this home does not hold", "and the listing says why");
        });

        suite.test("the ledger refuses to write an absolute path", () -> {
            SkillStore store = ArtifactsFixture.seed();
            ArtifactLedger poisoned = ArtifactLedger.of(List.of(new Artifact(
                    "provisioned-tree:venvs/evil", ArtifactKind.PROVISIONED_TREE, null,
                    // The reachable shape: installed/<u>.json's `origin` is
                    // usually a git URL and may be a local directory.
                    List.of("git:" + store.root().resolve("elsewhere")),
                    List.of(), null, java.util.Map.of(), java.util.Map.of(),
                    Artifact.Agreement.UNRECORDED, Artifact.Origin.HOME)));
            try {
                poisoned.save(store);
                throw new AssertionError("expected a refusal, got a written ledger");
            } catch (IOException refused) {
                assertContains(refused.getMessage(), "refusing to write",
                        "the refusal names what it refused");
                assertContains(refused.getMessage(), "home-relative",
                        "and why, so the caller knows what to hand over instead");
            }
        });

        return suite.runAll();
    }

    private static SkillStore cloneOf(SkillStore source) throws Exception {
        Path dest = Files.createTempDirectory("artifacts-clone-").resolve("home");
        HomeCloner.Report report = HomeCloner.cloneHome(source.root(), dest);
        assertTrue(report.clean(), "clone verified clean: " + report.leaks());
        return new SkillStore(dest);
    }

    private static TreeSet<String> ids(List<Artifact> artifacts) {
        TreeSet<String> out = new TreeSet<>();
        for (Artifact artifact : artifacts) out.add(artifact.id());
        return out;
    }
}
