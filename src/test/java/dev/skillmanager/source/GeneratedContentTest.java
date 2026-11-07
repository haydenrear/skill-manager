package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #400 / DEF-457: generated Python bytecode is never published and never
 * counts as a local edit, and both halves ask the one definition in
 * {@link GeneratedContent}.
 *
 * <p>Each git case first asserts that git itself really sees the bytecode
 * (untracked, so {@code git add -A} would stage it and {@code git status}
 * reports it) — without that the case would pass over a tree with nothing to
 * exclude.
 */
public final class GeneratedContentTest {

    private static final byte[] PYC = {(byte) 0xcb, 0x0d, 0x0d, 0x0a, 0, 0, 0, 0, 'b', 'y', 't', 'e'};

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("GeneratedContentTest");

        suite.test("the definition names bytecode and nothing an author writes", () -> {
            for (String p : List.of("__pycache__/", "__pycache__", "__pycache__/tool.cpython-312.pyc",
                    "scripts/__pycache__/", "a/b/__pycache__/x.pyc", "tool.pyc", "pkg/mod.pyo",
                    "\"dir with space/__pycache__/\"", ".pytest_cache/", "x/.gradle/")) {
                assertTrue(GeneratedContent.isGenerated(p), "generated: " + p);
            }
            for (String p : List.of("SKILL.md", "tool.py", "pkg/", "pyc", "notes.pyc.md",
                    "pycache/x.py", "__pycache__.md", "build/", "venv/", "", "/")) {
                assertFalse(GeneratedContent.isGenerated(p), "authored: " + p);
            }
            assertFalse(GeneratedContent.isGenerated(null), "null is not generated");
        });

        if (!GitOps.isAvailable()) {
            System.out.println("== GeneratedContentTest — git cases SKIPPED, git is not on PATH");
            return suite.runAll();
        }

        suite.test("commitAll commits the real edit and leaves untracked bytecode out", () -> {
            Path repo = seededRepo("commit");
            Files.writeString(repo.resolve("SKILL.md"), "---\nname: u\n---\nedited\n");
            plantBytecode(repo);
            assertContains(git(repo, "status", "--porcelain", "-uall"), "__pycache__/tool.cpython-312.pyc",
                    "git really sees the stray bytecode as untracked");

            String head = GitOps.commitAll(repo, "publish");
            assertTrue(head != null, "the real edit is committed");
            String tree = git(repo, "ls-tree", "-r", "--name-only", "HEAD");
            assertContains(tree, "SKILL.md", "the edited file is in the commit");
            assertFalse(tree.contains("__pycache__") || tree.contains(".pyc") || tree.contains(".pyo"),
                    "no bytecode in the committed tree, got: " + tree);
            assertContains(git(repo, "show", "HEAD:SKILL.md"), "edited", "the edit landed");
            assertTrue(Files.exists(repo.resolve("__pycache__/tool.cpython-312.pyc")),
                    "the bytecode is left on disk, not deleted");
        });

        suite.test("commitAll with ONLY bytecode commits nothing", () -> {
            Path repo = seededRepo("only");
            String before = GitOps.headHash(repo);
            plantBytecode(repo);
            assertTrue(GitOps.commitAll(repo, "publish") == null, "nothing to commit");
            assertTrue(before.equals(GitOps.headHash(repo)), "HEAD did not move");
        });

        suite.test("commitAll stays repository-wide when run from a subdirectory", () -> {
            Path repo = seededRepo("subdir");
            Path sub = Files.createDirectories(repo.resolve("sub"));
            Files.writeString(repo.resolve("top.txt"), "top\n");
            Files.writeString(sub.resolve("s.txt"), "s\n");
            Files.createDirectories(sub.resolve("__pycache__"));
            Files.write(sub.resolve("__pycache__/s.cpython-312.pyc"), PYC);
            Files.createDirectories(sub.resolve(".pytest_cache/v"));
            Files.writeString(sub.resolve(".pytest_cache/v/lastfailed"), "{}\n");
            assertTrue(GitOps.commitAll(sub, "publish") != null, "committed");
            String tree = git(repo, "ls-tree", "-r", "--name-only", "HEAD");
            assertContains(tree, "top.txt", "a change outside the cwd is still staged");
            assertContains(tree, "sub/s.txt", "the subdirectory change is staged");
            assertFalse(tree.contains(".pyc"), "bytecode excluded from a subdirectory too: " + tree);
            assertFalse(tree.contains(".pytest_cache"), "every Rederivable cache is excluded: " + tree);
        });

        suite.test("bytecode alone is not a local edit; a real edit still is", () -> {
            Path repo = seededRepo("dirty");
            plantBytecode(repo);
            assertTrue(GitOps.hasWorktreeChanges(repo),
                    "git really reports the bytecode — the raw question would refuse the sync");
            assertFalse(DereferencedStoreLinks.hasAuthoredWorktreeChanges(repo),
                    "untracked bytecode is not something a sync would destroy");
            assertFalse(DereferencedStoreLinks.isAuthoredDirty(repo, GitOps.headHash(repo)),
                    "so the sync's dirty gate does not fire on it");

            Files.writeString(repo.resolve("SKILL.md"), "---\nname: u\n---\nreal edit\n");
            List<String> left = DereferencedStoreLinks.worktreeChangesBeyond(repo, Set.of());
            assertTrue(left.size() == 1 && left.get(0).endsWith("SKILL.md"),
                    "the real edit is still reported, and only it: " + left);
            assertTrue(DereferencedStoreLinks.isAuthoredDirty(repo, GitOps.headHash(repo)),
                    "a real edit next to bytecode still refuses the sync");
        });

        suite.test("an untracked package directory holding bytecode AND a new file is still a change", () -> {
            Path repo = seededRepo("pkg");
            Path pkg = Files.createDirectories(repo.resolve("pkg/__pycache__"));
            Files.write(pkg.resolve("m.cpython-312.pyc"), PYC);
            Files.writeString(repo.resolve("pkg/m.py"), "x = 1\n");
            assertTrue(DereferencedStoreLinks.hasAuthoredWorktreeChanges(repo),
                    "pkg/ holds an authored m.py, so the collapsed status line must still count");
        });

        return suite.runAll();
    }

    private static void plantBytecode(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("__pycache__"));
        Files.write(repo.resolve("__pycache__/tool.cpython-312.pyc"), PYC);
        Files.write(repo.resolve("stray.pyo"), PYC);
    }

    private static Path seededRepo(String tag) throws Exception {
        Path repo = Files.createTempDirectory("generated-content-" + tag + "-");
        Files.writeString(repo.resolve("SKILL.md"), "---\nname: u\n---\n");
        Files.writeString(repo.resolve("tool.py"), "print('tool')\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "seed");
        return repo;
    }

    private static String git(Path cwd, String... argv) throws Exception {
        String[] cmd = new String[argv.length + 1];
        cmd[0] = "git";
        System.arraycopy(argv, 0, cmd, 1, argv.length);
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.getInputStream().transferTo(out);
        int rc = p.waitFor();
        String s = out.toString();
        if (rc != 0) throw new IOException("git " + String.join(" ", argv) + " rc=" + rc + ": " + s);
        return s;
    }
}
