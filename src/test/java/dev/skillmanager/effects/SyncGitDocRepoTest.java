package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import org.eclipse.jgit.api.Git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A DOC unit routed through {@link SkillEffect.SyncGit} moves its checkout
 * AND its installed record (#173). Before the fix, `sync <doc> --git-latest`
 * only re-applied the binding matrix: the docs/ checkout stayed at the old
 * commit, the record kept the stale sha, and every version check reported
 * "new version available" forever — success that changed nothing.
 */
public final class SyncGitDocRepoTest {

    public static int run() throws Exception {
        return Tests.suite("SyncGitDocRepoTest")
                .test("git-latest sync of a DOC unit pulls the checkout and refreshes the record", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        // A "remote" doc repo with two commits.
                        Path remote = Files.createTempDirectory("doc-sync-remote-");
                        Files.writeString(remote.resolve("guide.md"), "v1\n");
                        String shaA;
                        String shaB;
                        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
                            git.add().addFilepattern(".").call();
                            shaA = commit(git, "v1");
                            Files.writeString(remote.resolve("guide.md"), "v2\n");
                            git.add().addFilepattern(".").call();
                            shaB = commit(git, "v2");
                        }

                        // Installed docs/ checkout cloned at the OLD commit.
                        Path docDir = h.store().unitDir("team-docs", UnitKind.DOC);
                        Files.createDirectories(docDir.getParent());
                        try (Git clone = Git.cloneRepository()
                                .setURI(remote.toUri().toString())
                                .setDirectory(docDir.toFile())
                                .call()) {
                            clone.checkout().setName(shaA).call();
                            clone.branchCreate().setName("pin").setStartPoint(shaA).setForce(true).call();
                            clone.checkout().setName("pin").call();
                        }

                        UnitStore units = new UnitStore(h.store());
                        units.write(new InstalledUnit(
                                "team-docs",
                                "0.1.0",
                                InstalledUnit.Kind.GIT,
                                InstalledUnit.InstallSource.GIT,
                                remote.toUri().toString(),
                                shaA,
                                null,
                                UnitStore.nowIso(),
                                List.of(),
                                UnitKind.DOC));

                        EffectContext ctx = new EffectContext(h.store(), null);
                        EffectReceipt receipt = SyncGitHandler.run(
                                new SkillEffect.SyncGit(
                                        "team-docs", UnitKind.DOC,
                                        InstalledUnit.InstallSource.GIT,
                                        /*gitLatest=*/true, /*merge=*/false),
                                ctx);

                        assertTrue(receipt.status() != EffectStatus.FAILED,
                                "doc-unit git sync must not fail: " + receipt.errorMessage());
                        assertEquals(shaB, GitOps.headHash(docDir),
                                "docs checkout pulled to the remote tip");
                        InstalledUnit refreshed = units.read("team-docs").orElseThrow();
                        assertEquals(shaB, refreshed.gitHash(),
                                "installed record refreshed to the new sha");
                    }
                })
                .runAll();
    }

    private static String commit(Git git, String msg) throws Exception {
        return git.commit()
                .setMessage(msg)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .getName();
    }

    private SyncGitDocRepoTest() {}
}
