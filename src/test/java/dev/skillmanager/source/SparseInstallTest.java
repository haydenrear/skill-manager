package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.InstallExclusion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/** #261: the manifest field, and what each copy of a sparse install keeps. */
public final class SparseInstallTest {

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== SparseInstallTest — SKIPPED, git is not on PATH");
            return 0;
        }
        return Tests.suite("SparseInstallTest")

                .test("each manifest kind declares under its own table", () -> {
                    Path d = Files.createTempDirectory("excl-");
                    Files.writeString(d.resolve("skill-manager.toml"),
                            "[skill]\nname = \"a\"\nexcluded_from_install = [\"specs/.history/\", \"big\"]\n");
                    assertEquals(List.of("specs/.history", "big"), InstallExclusion.declaredIn(d), "skill");
                    Files.delete(d.resolve("skill-manager.toml"));
                    Files.writeString(d.resolve("skill-manager-plugin.toml"),
                            "[plugin]\nname = \"a\"\nexcluded_from_install = [\"x\"]\n");
                    assertEquals(List.of("x"), InstallExclusion.declaredIn(d), "plugin");
                    Files.delete(d.resolve("skill-manager-plugin.toml"));
                    Files.writeString(d.resolve("harness.toml"), "[harness]\nexcluded_from_install = [\"y\"]\n");
                    assertEquals(List.of("y"), InstallExclusion.declaredIn(d), "harness");
                })

                .test("nothing declared is nothing excluded", () -> {
                    Path d = Files.createTempDirectory("excl-");
                    Files.writeString(d.resolve("skill-manager.toml"), "[skill]\nname = \"a\"\n");
                    assertTrue(InstallExclusion.declaredIn(d).isEmpty(), "no key");
                })

                .test("a path that cannot mean what it says is refused", () -> {
                    for (String bad : List.of("/abs", "a/../b", "..", "*.md", "!x", ".git", ".git/hooks",
                            "SKILL.md", "skill-manager.toml", ".claude-plugin/plugin.json", "")) {
                        Path d = Files.createTempDirectory("excl-");
                        Files.writeString(d.resolve("skill-manager.toml"),
                                "[skill]\nexcluded_from_install = [\"" + bad + "\"]\n");
                        boolean refused = false;
                        try {
                            InstallExclusion.declaredIn(d);
                        } catch (IOException e) {
                            refused = true;
                        }
                        assertTrue(refused, "should refuse '" + bad + "'");
                    }
                })

                .test("cloning a sparse install keeps it sparse", () -> {
                    Path r = sparse();
                    Path dest = Files.createTempDirectory("clone-parent-").resolve("copy");
                    assertTrue(GitOps.clone(dest, r.toString(), null), "clone");
                    assertFalse(Files.exists(dest.resolve("excluded")), "stays excluded");
                    assertTrue(Files.isRegularFile(dest.resolve("a.txt")), "kept file");
                    assertEquals(Set.of("excluded"), SparseInstall.managedExclusions(dest).orElseThrow(), "same set");
                    assertTrue(git(dest, "status", "--porcelain").isBlank(), "clean");
                    assertTrue(git(dest, "ls-tree", "-r", "HEAD", "--name-only").contains("excluded/x.txt"),
                            "still in the tree");
                })

                .test("a changed declaration is followed; a plain clone is left alone", () -> {
                    Path r = sparse();
                    Files.createDirectories(r.resolve("also"));
                    Files.writeString(r.resolve("also/y.txt"), "y\n");
                    Files.writeString(r.resolve("skill-manager.toml"),
                            "[skill]\nname = \"a\"\nexcluded_from_install = [\"excluded\", \"also\"]\n");
                    git(r, "add", "-A");
                    git(r, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "B");
                    SparseInstall.reconcile(r);
                    assertFalse(Files.exists(r.resolve("also")), "newly declared path left the tree");

                    Path full = Files.createTempDirectory("full-");
                    Files.writeString(full.resolve("skill-manager.toml"),
                            "[skill]\nname = \"a\"\nexcluded_from_install = [\"keep\"]\n");
                    Files.createDirectories(full.resolve("keep"));
                    Files.writeString(full.resolve("keep/z.txt"), "z\n");
                    git(full, "init", "-b", "main", "--quiet");
                    git(full, "add", "-A");
                    git(full, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "A");
                    SparseInstall.reconcile(full);
                    assertTrue(Files.isRegularFile(full.resolve("keep/z.txt")), "an existing full clone keeps its shape");
                })

                .test("a tree with no .git just loses the excluded paths", () -> {
                    Path d = Files.createTempDirectory("excl-");
                    Files.writeString(d.resolve("skill-manager.toml"),
                            "[skill]\nexcluded_from_install = [\"gone\"]\n");
                    Files.createDirectories(d.resolve("gone"));
                    Files.writeString(d.resolve("gone/f"), "f");
                    SparseInstall.applyDeclared(d);
                    assertFalse(Files.exists(d.resolve("gone")), "deleted");
                })

                .runAll();
    }

    private static Path sparse() throws Exception {
        Path r = Files.createTempDirectory("sparse-install-");
        Files.writeString(r.resolve("a.txt"), "a\n");
        Files.writeString(r.resolve("skill-manager.toml"),
                "[skill]\nname = \"a\"\nexcluded_from_install = [\"excluded\"]\n");
        Files.createDirectories(r.resolve("excluded"));
        Files.writeString(r.resolve("excluded/x.txt"), "x\n");
        git(r, "init", "-b", "main", "--quiet");
        git(r, "add", "-A");
        git(r, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "A");
        assertTrue(SparseInstall.apply(r, List.of("excluded")), "sparse-checkout set");
        return r;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "git " + argv + ": " + out);
        return out;
    }
}
