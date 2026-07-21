package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import org.eclipse.jgit.api.Git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ResolveGraphDirectGitSyncTest {

    public static int run() throws Exception {
        return Tests.suite("ResolveGraphDirectGitSyncTest")
                .test("matching provenance makes a direct-git transitive sync idempotent", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path sourceRoot = Files.createTempDirectory("sync-direct-git-source-");
                        Path sourceChild = sourceRoot.resolve("acp-cdc-ai-python-skill");
                        scaffoldSkill(sourceChild, "acp-cdc-ai-python", null);
                        String childSha;
                        try (Git git = Git.init().setDirectory(sourceChild.toFile()).call()) {
                            git.add().addFilepattern(".").call();
                            childSha = git.commit()
                                    .setMessage("fixture")
                                    .setAuthor("Test", "test@example.com")
                                    .setCommitter("Test", "test@example.com")
                                    .call()
                                    .getName();
                        }
                        String childOrigin = sourceChild.toUri().toString();

                        Path installedChild = h.store().skillDir("acp-cdc-ai-python");
                        scaffoldSkill(installedChild, "acp-cdc-ai-python", null);
                        UnitStore installedUnits = new UnitStore(h.store());
                        installedUnits.write(new InstalledUnit(
                                "acp-cdc-ai-python",
                                "0.1.0",
                                InstalledUnit.Kind.GIT,
                                InstalledUnit.InstallSource.GIT,
                                childOrigin,
                                childSha,
                                null,
                                UnitStore.nowIso(),
                                List.of(),
                                dev.skillmanager.model.UnitKind.SKILL));

                        Path parentDir = h.store().skillDir("hyper-experiments");
                        Skill parent = scaffoldSkill(
                                parentDir,
                                "hyper-experiments",
                                "git+" + childOrigin + "#" + childSha);
                        String recordBefore = Files.readString(
                                installedUnits.file("acp-cdc-ai-python"));

                        // A repo-basename lookup would now try to clone this
                        // missing source and fail. Provenance identity makes
                        // the steady-state resolve a no-op.
                        Fs.deleteRecursive(sourceChild);
                        EffectReceipt receipt = h.run(
                                new SkillEffect.BuildResolveGraphFromUnmetReferences(List.of(parent)));

                        assertEquals(EffectStatus.OK, receipt.status(), "sync resolve succeeds");
                        assertTrue(receipt.facts().stream().anyMatch(f ->
                                        f instanceof ContextFact.GraphResolved g
                                                && g.resolved() == 0
                                                && g.failures() == 0),
                                "steady-state sync resolves no replacement");
                        ResolvedGraph graph = h.context().resolvedGraph().orElseThrow();
                        assertSize(0, graph.resolved(), "resolved graph is empty");
                        assertEquals(recordBefore,
                                Files.readString(installedUnits.file("acp-cdc-ai-python")),
                                "installed provenance remains byte-for-byte unchanged");
                        assertTrue(h.store().containsUnit("acp-cdc-ai-python"),
                                "manifest-named child remains installed");
                        assertFalse(h.store().containsUnit("acp-cdc-ai-python-skill"),
                                "repository basename is never used as unit identity");
                    }
                })
                .test("same repository with different refs attributes only the failing parent", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path sourceRoot = Files.createTempDirectory("sync-direct-git-attribution-");
                        Path sourceChild = sourceRoot.resolve("shared-child-repository");
                        scaffoldSkill(sourceChild, "shared-child", null);
                        String goodSha;
                        try (Git git = Git.init().setDirectory(sourceChild.toFile()).call()) {
                            git.add().addFilepattern(".").call();
                            goodSha = git.commit()
                                    .setMessage("fixture")
                                    .setAuthor("Test", "test@example.com")
                                    .setCommitter("Test", "test@example.com")
                                    .call()
                                    .getName();
                        }
                        String childOrigin = sourceChild.toUri().toString();
                        Skill goodParent = scaffoldSkill(
                                h.store().skillDir("good-parent"),
                                "good-parent",
                                "git+" + childOrigin + "#" + goodSha);
                        Skill badParent = scaffoldSkill(
                                h.store().skillDir("bad-parent"),
                                "bad-parent",
                                "git+" + childOrigin + "#missing-ref");
                        h.seedUnit("good-parent", dev.skillmanager.model.UnitKind.SKILL);
                        h.seedUnit("bad-parent", dev.skillmanager.model.UnitKind.SKILL);

                        EffectReceipt receipt = h.run(
                                new SkillEffect.BuildResolveGraphFromUnmetReferences(
                                        List.of(goodParent, badParent)));
                        try {
                            assertEquals(EffectStatus.PARTIAL, receipt.status(),
                                    "one good and one bad ref is partial");
                            assertTrue(receipt.facts().stream().anyMatch(f ->
                                            f instanceof ContextFact.TransitiveFailed failed
                                                    && failed.coord().endsWith("#missing-ref")),
                                    "failure fact includes the exact failing ref");
                            assertFalse(h.sourceOf("good-parent")
                                            .map(u -> u.hasError(
                                                    InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                            .orElse(true),
                                    "successful ref parent has no transitive error");
                            assertTrue(h.sourceOf("bad-parent")
                                            .map(u -> u.hasError(
                                                    InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED)
                                                    && u.errors().stream().anyMatch(error ->
                                                            error.message().contains("#missing-ref")))
                                            .orElse(false),
                                    "only failing ref parent records the exact transitive error");
                        } finally {
                            h.context().resolvedGraph().ifPresent(ResolvedGraph::cleanup);
                        }
                    }
                })
                .test("removing the offending reference clears the stale error on the next sync", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path missingRepo = Files.createTempDirectory("sync-direct-git-removed-")
                                .resolve("never-a-repository");
                        Skill broken = scaffoldSkill(
                                h.store().skillDir("ref-removed-parent"),
                                "ref-removed-parent",
                                "git+" + missingRepo.toUri() + "#deadbeef");
                        h.seedUnit("ref-removed-parent", dev.skillmanager.model.UnitKind.SKILL);

                        h.run(new SkillEffect.BuildResolveGraphFromUnmetReferences(List.of(broken)));
                        h.context().resolvedGraph().ifPresent(ResolvedGraph::cleanup);
                        assertTrue(h.sourceOf("ref-removed-parent")
                                        .map(u -> u.hasError(
                                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                        .orElse(false),
                                "failing sync records the transitive error");

                        // The user's fix: REMOVE the offending (only) reference.
                        Skill fixed = scaffoldSkill(
                                h.store().skillDir("ref-removed-parent"),
                                "ref-removed-parent",
                                null);
                        EffectReceipt receipt = h.run(
                                new SkillEffect.BuildResolveGraphFromUnmetReferences(List.of(fixed)));
                        assertEquals(EffectStatus.OK, receipt.status(),
                                "sync with the ref removed is clean");
                        assertFalse(h.sourceOf("ref-removed-parent")
                                        .map(u -> u.hasError(
                                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                        .orElse(true),
                                "stale error clears once the offending ref is removed");
                    }
                })
                .test("reconcile validateAndClear clears the error only once refs are met or removed", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path missingRepo = Files.createTempDirectory("sync-direct-git-reconcile-")
                                .resolve("never-a-repository");
                        Skill broken = scaffoldSkill(
                                h.store().skillDir("reconcile-parent"),
                                "reconcile-parent",
                                "git+" + missingRepo.toUri() + "#deadbeef");
                        h.seedUnit("reconcile-parent", dev.skillmanager.model.UnitKind.SKILL);
                        h.run(new SkillEffect.BuildResolveGraphFromUnmetReferences(List.of(broken)));
                        h.context().resolvedGraph().ifPresent(ResolvedGraph::cleanup);
                        assertTrue(h.sourceOf("reconcile-parent")
                                        .map(u -> u.hasError(
                                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                        .orElse(false),
                                "failing sync records the transitive error");

                        // Ref still declared and still unmet: reconcile must NOT clear.
                        EffectReceipt kept = h.run(new SkillEffect.ValidateAndClearError(
                                "reconcile-parent",
                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED));
                        assertTrue(kept.facts().stream().anyMatch(f ->
                                        f instanceof ContextFact.ErrorValidated ev && !ev.cleared()),
                                "reconcile keeps the error while the ref is still unmet");
                        assertTrue(h.sourceOf("reconcile-parent")
                                        .map(u -> u.hasError(
                                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                        .orElse(false),
                                "error still persisted while the ref is still unmet");

                        // Remove the offending reference, reconcile again: clears.
                        scaffoldSkill(
                                h.store().skillDir("reconcile-parent"),
                                "reconcile-parent",
                                null);
                        EffectReceipt receipt = h.run(new SkillEffect.ValidateAndClearError(
                                "reconcile-parent",
                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED));
                        assertTrue(receipt.facts().stream().anyMatch(f ->
                                        f instanceof ContextFact.ErrorValidated ev && ev.cleared()),
                                "reconcile reports the error as cleared");
                        assertFalse(h.sourceOf("reconcile-parent")
                                        .map(u -> u.hasError(
                                                InstalledUnit.ErrorKind.TRANSITIVE_RESOLVE_FAILED))
                                        .orElse(true),
                                "reconcile-only path clears the persisted error after ref removal");
                    }
                })
                .runAll();
    }

    private static Skill scaffoldSkill(Path dir, String name, String reference) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkillParser.SKILL_FILENAME), """
                ---
                name: %s
                description: direct-git sync fixture
                ---
                fixture
                """.formatted(name));
        String references = reference == null
                ? ""
                : "skill_references = [\"" + reference.replace("\\", "\\\\") + "\"]\n\n";
        Files.writeString(dir.resolve(SkillParser.TOML_FILENAME), """
                %s[skill]
                name = "%s"
                version = "0.1.0"
                description = "direct-git sync fixture"
                """.formatted(references, name));
        return SkillParser.load(dir);
    }
}
