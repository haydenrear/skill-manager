package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.SkillProjectParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #394: a root-home sync reconciles a registered project's child home against
 * the REPOSITORY's manifest, never the registry's cached copy, leaves the child
 * home alone when that manifest cannot be read, and records every removal it
 * makes in the child home's own {@code audit.log}.
 */
public final class RootSyncStaleManifestTest {

    public static int run() throws Exception {
        return Tests.suite("RootSyncStaleManifestTest")
                .test("a stale cached manifest does not remove a unit the repository declares", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "stale-snap");
                        Path child = f.repoRoot.resolve(".skill-manager/skills");
                        assertTrue(Files.isDirectory(child.resolve(f.two))
                                        && Files.isDirectory(child.resolve(f.three)),
                                "precondition: the child home holds all three units");

                        // Repository drops `three`; the cached snapshot ages to `one` only.
                        f.writeManifest(f.one, f.two);
                        Files.writeString(f.snapshot(), f.manifest(f.one));
                        String auditBefore = read(f.repoRoot.resolve(".skill-manager/audit.log"));

                        EffectReceipt receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                List.of(f.one), null, false));

                        assertEquals(EffectStatus.OK, receipt.status(), "sync ok: " + receipt.facts());
                        assertTrue(Files.isDirectory(child.resolve(f.two)),
                                "declared by the repository's manifest, so kept");
                        assertFalse(Files.isDirectory(child.resolve(f.three)),
                                "CONTROL: undeclared everywhere and unmodified, so removed");
                        String audit = read(f.repoRoot.resolve(".skill-manager/audit.log"));
                        String added = audit.substring(Math.min(auditBefore.length(), audit.length()));
                        assertContains(added, "CHILD_HOME_PRUNE", "removal is in the child home's audit log");
                        assertContains(added, f.three, "and names the removed unit");
                        assertFalse(added.contains(f.two), "and nothing about the kept unit");
                        assertContains(read(f.snapshot()), f.two,
                                "the reconcile re-registered from the repository, refreshing the cached copy");
                    }
                })
                .test("an unreadable repository manifest leaves the child home untouched", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Fixture f = Fixture.build(h, "gone-manifest");
                        Path child = f.repoRoot.resolve(".skill-manager/skills");
                        Files.writeString(f.snapshot(), f.manifest(f.one));
                        Files.delete(f.repoRoot.resolve("skill-project.toml"));

                        EffectReceipt receipt = h.run(new SkillEffect.SyncClaimingProjects(
                                List.of(f.one), null, false));

                        assertEquals(EffectStatus.OK, receipt.status(),
                                "a project whose manifest is unreadable does not fail the unit sync");
                        assertTrue(receipt.facts().stream()
                                        .anyMatch(ContextFact.ProjectSyncSkippedManifestUnreadable.class::isInstance),
                                "but is named as skipped; facts were " + receipt.facts());
                        assertTrue(Files.isDirectory(child.resolve(f.two))
                                        && Files.isDirectory(child.resolve(f.three)),
                                "cannot tell is not undeclared: nothing removed against the cached copy");
                    }
                })
                .runAll();
    }

    private static String read(Path p) throws Exception {
        return Files.isRegularFile(p) ? Files.readString(p) : "";
    }

    private static final class Fixture {
        final TestHarness h;
        final Path repoRoot;
        final Path sources;
        final String projectName;
        final String one;
        final String two;
        final String three;

        private Fixture(TestHarness h, Path repoRoot, Path sources, String projectName) {
            this.h = h;
            this.repoRoot = repoRoot;
            this.sources = sources;
            this.projectName = projectName;
            this.one = projectName + "-one";
            this.two = projectName + "-two";
            this.three = projectName + "-three";
        }

        static Fixture build(TestHarness h, String slug) throws Exception {
            Path repoRoot = Files.createTempDirectory(slug + "-");
            Fixture f = new Fixture(h, repoRoot, repoRoot.resolve("src"), slug);
            for (String u : List.of(f.one, f.two, f.three)) {
                Path dir = UnitFixtures.scaffoldSkill(f.sources, u, DepSpec.empty()).sourcePath();
                gitInitCommit(dir);
            }
            f.writeManifest(f.one, f.two, f.three);
            new ProjectDependencyResolver(h.store(), null).resolve(
                    SkillProjectParser.load(repoRoot), new ProjectDependencyResolver.Options(true, false));
            assertTrue(new SkillProjectLockStore(h.store()).projectsClaiming(f.one).contains(slug),
                    "fixture precondition: the project claims the synced unit");
            return f;
        }

        Path snapshot() {
            return h.store().projectsDir().resolve(projectName).resolve("skill-project.toml");
        }

        String manifest(String... units) {
            StringBuilder sb = new StringBuilder("[project]\nname = \"" + projectName + "\"\n");
            for (String u : units) {
                sb.append("\n[skills.").append(u).append("]\nsource = \"git+file://")
                        .append(sources.resolve(u)).append("#main\"\n");
            }
            return sb.toString();
        }

        void writeManifest(String... units) throws Exception {
            Files.writeString(repoRoot.resolve("skill-project.toml"), manifest(units));
        }
    }

    private static void gitInitCommit(Path repo) throws Exception {
        git(repo, "init", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                "commit", "-m", "initial");
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
