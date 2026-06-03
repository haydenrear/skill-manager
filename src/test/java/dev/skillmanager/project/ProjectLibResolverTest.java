package dev.skillmanager.project;

import dev.skillmanager._lib.fakes.FakeGit;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ProjectLibResolverTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectLibResolverTest")
                .test("resolves github and generic git libs into libs directory and lock", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-libs-");
                        Path githubSource = sourceTree(repoRoot.resolve("fixtures/github-lib"), "github");
                        Path genericSource = sourceTree(repoRoot.resolve("fixtures/generic-lib"), "generic");
                        FakeGit git = new FakeGit()
                                .register("https://github.com/acme/github-lib", "main", githubSource)
                                .registerSha(githubSource, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .register("ssh://git@example.com/acme/generic-lib.git", "v1", genericSource)
                                .registerSha(genericSource, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "libs-project"

                                [[libs]]
                                name = "github-lib"
                                source = "github:acme/github-lib#main"

                                [[libs]]
                                name = "generic-lib"
                                source = "git+ssh://git@example.com/acme/generic-lib.git"
                                ref = "v1"
                                """);

                        ProjectLibResolver.Result result = new ProjectLibResolver(h.store(), git)
                                .resolve(project);

                        assertTrue(Files.isRegularFile(repoRoot.resolve("libs/github-lib/marker.txt")),
                                "github lib checkout materialized");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("libs/generic-lib/marker.txt")),
                                "generic git lib checkout materialized");
                        assertTrue(Files.readString(repoRoot.resolve(".gitignore")).contains("libs/"),
                                "libs directory is gitignored");
                        assertEquals(2, result.libs().size(), "two lib lock rows");
                        SkillProjectLock lock = new SkillProjectLockStore(h.store())
                                .read("libs-project").orElseThrow();
                        assertEquals(2, lock.libs().size(), "lock persists lib rows");
                        assertTrue(lock.libs().stream().anyMatch(l ->
                                        l.name().equals("github-lib")
                                                && l.resolvedSha().equals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                                && l.checkoutDir().endsWith("libs/github-lib")),
                                "github lib resolved sha recorded");
                        assertTrue(lock.libs().stream().anyMatch(l ->
                                        l.name().equals("generic-lib")
                                                && l.ref().equals("v1")
                                                && l.resolvedSha().equals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")),
                                "generic git lib ref and sha recorded");
                    }
                })
                .test("rejects lib checkout whose resolved sha differs from manifest sha", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-libs-sha-");
                        Path source = sourceTree(repoRoot.resolve("fixtures/pinned-lib"), "pinned");
                        FakeGit git = new FakeGit()
                                .register("https://github.com/acme/pinned-lib", null, source)
                                .registerSha(source, "cccccccccccccccccccccccccccccccccccccccc");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "pinned-project"

                                [[libs]]
                                name = "pinned-lib"
                                source = "github:acme/pinned-lib"
                                sha = "dddddddddddddddddddddddddddddddddddddddd"
                                """);

                        boolean rejected = false;
                        try {
                            new ProjectLibResolver(h.store(), git).resolve(project);
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("requested");
                        }
                        assertTrue(rejected, "mismatched manifest sha rejected");
                    }
                })
                .test("rejects lib names that escape the project libs directory", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-libs-escape-");
                        Path source = sourceTree(repoRoot.resolve("fixtures/escape-lib"), "escape");
                        FakeGit git = new FakeGit()
                                .register("https://github.com/acme/escape-lib", null, source)
                                .registerSha(source, "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "escape-project"

                                [[libs]]
                                name = "../escape-lib"
                                source = "github:acme/escape-lib"
                                """);

                        boolean rejected = false;
                        try {
                            new ProjectLibResolver(h.store(), git).resolve(project);
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("safe single path segment");
                        }
                        assertTrue(rejected, "escaping lib name rejected");
                        assertEquals(0, git.calls().size(), "unsafe lib name is rejected before clone");
                    }
                })
                .test("existing clean checkout moves to changed manifest ref", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-libs-existing-ref-");
                        Path upstream = Files.createTempDirectory("project-lib-upstream-");
                        Files.writeString(upstream.resolve("marker.txt"), "main\n");
                        git(upstream, "init");
                        git(upstream, "checkout", "-b", "main");
                        git(upstream, "add", ".");
                        git(upstream, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "main");
                        String mainSha = readCommand(upstream, "git", "rev-parse", "HEAD").trim();
                        git(upstream, "checkout", "-b", "release");
                        Files.writeString(upstream.resolve("marker.txt"), "release\n");
                        git(upstream, "add", ".");
                        git(upstream, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "release");
                        String releaseSha = readCommand(upstream, "git", "rev-parse", "HEAD").trim();

                        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                                [project]
                                name = "existing-ref-project"

                                [[libs]]
                                name = "ref-lib"
                                source = "git+file://%s"
                                ref = "main"
                                """.formatted(upstream));
                        ProjectLibResolver resolver = new ProjectLibResolver(h.store());
                        SkillProject mainProject = SkillProjectParser.load(repoRoot);
                        resolver.resolve(mainProject);
                        assertEquals(mainSha,
                                readCommand(repoRoot.resolve("libs/ref-lib"), "git", "rev-parse", "HEAD").trim(),
                                "initial checkout is at main");

                        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                                [project]
                                name = "existing-ref-project"

                                [[libs]]
                                name = "ref-lib"
                                source = "git+file://%s"
                                ref = "release"
                                """.formatted(upstream));
                        SkillProject releaseProject = SkillProjectParser.load(repoRoot);
                        ProjectLibResolver.Result result = resolver.resolve(releaseProject);

                        assertEquals(releaseSha,
                                readCommand(repoRoot.resolve("libs/ref-lib"), "git", "rev-parse", "HEAD").trim(),
                                "existing checkout moves to release ref");
                        assertTrue(result.libs().stream().anyMatch(l ->
                                        l.name().equals("ref-lib")
                                                && l.ref().equals("release")
                                                && l.resolvedSha().equals(releaseSha)),
                                "lock records release ref and sha");
                    }
                })
                .test("existing checkout without matching origin is rejected", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-libs-no-origin-");
                        Path upstream = Files.createTempDirectory("project-lib-origin-upstream-");
                        Files.writeString(upstream.resolve("marker.txt"), "upstream\n");
                        git(upstream, "init");
                        git(upstream, "checkout", "-b", "main");
                        git(upstream, "add", ".");
                        git(upstream, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "upstream");

                        Path checkout = repoRoot.resolve("libs/orphan-lib");
                        Files.createDirectories(checkout);
                        Files.writeString(checkout.resolve("marker.txt"), "orphan\n");
                        git(checkout, "init");
                        git(checkout, "checkout", "-b", "main");
                        git(checkout, "add", ".");
                        git(checkout, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "orphan");

                        Files.writeString(repoRoot.resolve("skill-project.toml"), """
                                [project]
                                name = "no-origin-project"

                                [[libs]]
                                name = "orphan-lib"
                                source = "git+file://%s"
                                """.formatted(upstream));

                        boolean rejected = false;
                        try {
                            new ProjectLibResolver(h.store()).resolve(SkillProjectParser.load(repoRoot));
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("has no origin");
                        }
                        assertTrue(rejected, "existing checkout with no origin is rejected");
                    }
                })
                .runAll();
    }

    private static SkillProject project(Path root, String manifest) throws Exception {
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
    }

    private static Path sourceTree(Path dir, String marker) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("marker.txt"), marker + "\n");
        return dir;
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException("git " + String.join(" ", args)
                    + " failed with " + rc + ": " + output);
        }
    }

    private static String readCommand(Path repo, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException(String.join(" ", args) + " failed with " + rc + ": " + output);
        }
        return output;
    }
}
