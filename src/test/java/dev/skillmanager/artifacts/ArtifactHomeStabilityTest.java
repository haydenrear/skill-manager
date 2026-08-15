package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.model.UnitKind;
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
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
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

        // ------------------------------------------------ review finding F1
        suite.test("a harness binding's three agent links get three ids", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path outside = ArtifactsFixture.newDir("harness-agent-homes-");
            Path projectDir = outside.resolve("checkout");
            Path claude = outside.resolve("claude");
            Path codex = outside.resolve("codex");
            Path gemini = outside.resolve("gemini");

            // Exactly HarnessInstantiator.plan's shape: ONE binding whose
            // targetRoot is projectDir ("informational", per its own comment at
            // HarnessInstantiator:136-140) and THREE SYMLINK projections into
            // the agent directories, which HarnessCommand resolves from
            // CLAUDE_CONFIG_DIR / CODEX_HOME / GEMINI_HOME and which are
            // therefore under projectDir in no particular case.
            String bindingId = dev.skillmanager.bindings.HarnessInstantiator
                    .unitBindingId("inst-1", "alpha");
            Path source = store.root().resolve("skills/alpha");
            new BindingStore(store).write(new ProjectionLedger("alpha", List.of(
                    new Binding(bindingId, "alpha", UnitKind.SKILL, null, projectDir,
                            ConflictPolicy.OVERWRITE, "2026-01-01T00:00:00Z",
                            BindingSource.HARNESS, List.of(
                            new Projection(bindingId, source,
                                    claude.resolve("skills/alpha"), ProjectionKind.SYMLINK, null),
                            new Projection(bindingId, source,
                                    codex.resolve("skills/alpha"), ProjectionKind.SYMLINK, null),
                            new Projection(bindingId, source,
                                    gemini.resolve("skills/alpha"), ProjectionKind.SYMLINK, null))))));

            TreeSet<String> harnessIds = new TreeSet<>();
            for (Artifact artifact : ArtifactIndex.of(store).artifacts()) {
                if (artifact.id().contains(bindingId)) harnessIds.add(artifact.id());
            }
            assertEquals(3, harnessIds.size(),
                    "three projections, three ids (was 1 of 3): " + harnessIds);
            for (String id : harnessIds) {
                assertFalse(id.contains("~"),
                        "no order-dependent suffix is doing the separating: " + id);
            }
        });

        suite.test("a destination that IS the target root leaks no name", () -> {
            SkillStore store = ArtifactsFixture.seed();
            Path outside = ArtifactsFixture.newDir("target-root-");
            Path targetRoot = outside.resolve("private-checkout");
            Path sibling = ArtifactsFixture.newDir("elsewhere-").resolve("alpha");

            // dest == targetRoot used to return the target root's own directory
            // name, which collided with a sibling of that name AND put an
            // outside-the-home name into an id.
            String atRoot = ArtifactIds.destKey(store.root(), targetRoot, targetRoot);
            String elsewhere = ArtifactIds.destKey(store.root(), targetRoot, sibling);
            String underRoot = ArtifactIds.destKey(store.root(), targetRoot,
                    targetRoot.resolve("alpha"));

            assertFalse(atRoot.contains("private-checkout"),
                    "the target root's own name does not become the id: " + atRoot);
            assertTrue(atRoot.startsWith("ext/"), "it is digested instead: " + atRoot);
            assertFalse(atRoot.equals(elsewhere), "two different places, two keys");
            assertFalse(underRoot.equals(elsewhere),
                    "<targetRoot>/alpha and <elsewhere>/alpha no longer collide: "
                            + underRoot + " vs " + elsewhere);
        });

        // ------------------------------------------------ review finding F2
        suite.test("file:// cannot smuggle an absolute path past the refusal", () -> {
            // file:/abs is a first-class install coordinate (Coord; SyncGitHandler
            // documents source = "file:///abs/path"), so this value is reachable
            // from a real installed/<u>.json — and its empty authority is exactly
            // what a "// after the scheme means it is a URL" test gets wrong.
            String smuggled = "git:file:///Users/somebody/checkouts/omega";
            // Asserted first so a regression names the headline case rather
            // than whichever sibling assertion happens to trip earliest.
            assertNotNull(ArtifactLedger.unsafeReason(smuggled, null),
                    "file:// with an empty authority is a filesystem path: " + smuggled);
            assertNotNull(ArtifactLedger.unsafeReason("git:file:/Users/x/omega", null),
                    "and the single-slash spelling of the same coordinate");
            assertNotNull(ArtifactLedger.unsafeReason("store:../../../etc", null),
                    "a relative path that climbs out of the home names somewhere else too");
            assertNotNull(ArtifactLedger.unsafeReason("git:/Users/x/checkout", null),
                    "the original embedded-absolute case still refused");

            // The controls. A real URL authority and every legitimate scheme
            // this model mints must still be writable, or the fix is a new bug.
            for (String allowed : List.of(
                    "git:https://github.com/haydenrear/skill-manager@main",
                    "git:git@github.com:haydenrear/skill-manager.git@main",
                    "spec:pip:jinja2-cli[yaml]==0.8.2",
                    "store:skills/alpha",
                    "record:installed/alpha.json",
                    "binding:default:claude:alpha",
                    "bin/cli/jinja2",
                    "projection:harness:inst-1:alpha#SYMLINK/home/.claude/skills/alpha")) {
                assertEquals(null, ArtifactLedger.unsafeReason(allowed, null),
                        "still writable: " + allowed);
            }
        });

        suite.test("a file:// origin never reaches the ledger in the first place", () -> {
            SkillStore store = ArtifactsFixture.seed();
            java.nio.file.Files.createDirectories(store.root().resolve("skills/omega"));
            new dev.skillmanager.source.UnitStore(store).write(
                    new dev.skillmanager.source.InstalledUnit("omega", "0.1.0",
                            dev.skillmanager.source.InstalledUnit.Kind.LOCAL_DIR,
                            dev.skillmanager.source.InstalledUnit.InstallSource.LOCAL_FILE,
                            "file:///Users/somebody/checkouts/omega", null, null,
                            "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL));

            Artifact omega = ArtifactIndex.of(store)
                    .byId(ArtifactIds.unitStore("omega")).orElseThrow();
            assertEquals(List.of("record:installed/omega.json"), omega.inputs(),
                    "the claim is referenced through the record that owns it, not copied");

            // And the whole way through a real clone: the ledger writes, the
            // clone verifies clean, and the copy names no foreign checkout.
            ArtifactLedger.of(ArtifactIndex.of(store).artifacts()).save(store);
            String toml = Files.readString(ArtifactLedger.file(cloneOf(store)));
            assertFalse(toml.contains("/Users/somebody/checkouts/omega"),
                    "the copy's ledger does not name a checkout the copy does not own");
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
