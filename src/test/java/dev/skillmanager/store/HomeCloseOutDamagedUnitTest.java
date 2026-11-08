package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.source.GitOps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #412: {@code home close-out} and {@code home sync} report a unit whose
 * {@code .git} is missing or partial as {@link SyncStatus#DAMAGED} -- never as a
 * history conflict read through the ENCLOSING repository, never with a
 * {@code unit publish} remedy, and never by writing it anywhere.
 *
 * <p>Both homes live inside a throwaway "application" repository carrying a
 * branch on no remote, which is the consumer geometry: the unit dirs have an
 * enclosing repository for discovery to climb to.
 */
public final class HomeCloseOutDamagedUnitTest {

    private static final String APP_BRANCH = "app-feature-unpublished";

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== HomeCloseOutDamagedUnitTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("HomeCloseOutDamagedUnitTest");

        suite.test("a partial .git is a DAMAGED blocker, not a diverged history to publish", () -> {
            Fixture f = Fixture.create("partial", "u");
            Path wt = f.worktree.skillDir("u");
            Files.delete(wt.resolve(".git/HEAD"));
            Files.delete(wt.resolve(".git/config"));

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            UnitSync unit = only(verdict, "u");
            assertEquals(SyncStatus.DAMAGED, unit.status(), "damaged: " + unit.detail());
            assertFalse(verdict.safe(), "unknown is never safe: " + HomeCloseOut.render(verdict));
            String remedy = verdict.blockers().stream().filter(b -> b.label().equals("skill:u"))
                    .findFirst().orElseThrow().remedy();
            for (String text : List.of(unit.detail(), remedy, String.join(",", unit.conflicts()))) {
                assertFalse(text.contains("unit publish"), "no publish remedy: " + text);
                assertFalse(text.contains(".git (history)"), "no history conflict: " + text);
                assertFalse(text.contains(APP_BRANCH), "no application branch: " + text);
                assertFalse(text.contains(f.appHead.substring(0, 7)), "no application HEAD: " + text);
            }
        });

        suite.test("a real home sync writes nothing for a damaged unit and leaves its .git as found", () -> {
            Fixture f = Fixture.create("sync", "u");
            Path wt = f.worktree.skillDir("u");
            Path pj = f.project.skillDir("u");
            Files.writeString(wt.resolve("SKILL.md"), Files.readString(wt.resolve("SKILL.md")) + "edit\n");
            Files.delete(wt.resolve(".git/HEAD"));
            String projectHead = GitOps.headHash(pj);
            String projectSkill = Files.readString(pj.resolve("SKILL.md"));
            String appRefs = git(f.app, "for-each-ref");

            HomeSync.Report report = HomeSync.run(f.worktree, f.project, new HomeSync.Options(true, false));

            assertEquals(SyncStatus.DAMAGED, report.units().stream()
                    .filter(u -> u.unitName().equals("u")).findFirst().orElseThrow().status(), "damaged");
            assertFalse(report.clean(), "a damaged unit is unresolved");
            assertEquals(projectHead, GitOps.headHash(pj), "project HEAD untouched");
            assertEquals(projectSkill, Files.readString(pj.resolve("SKILL.md")), "project bytes untouched");
            assertFalse(Files.exists(wt.resolve(".git/HEAD")), "the partial .git is not 'repaired'");
            assertTrue(Files.isDirectory(wt.resolve(".git/objects")), "objects kept for recovery");
            assertEquals(appRefs, git(f.app, "for-each-ref"), "application refs untouched");
        });

        suite.test("a .git deleted from a copy whose record says it carried history is DAMAGED", () -> {
            Fixture f = Fixture.create("missing", "u");
            Fs.deleteTree(f.worktree.skillDir("u").resolve(".git"));

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertEquals(SyncStatus.DAMAGED, only(verdict, "u").status(), only(verdict, "u").detail());
            assertFalse(verdict.safe(), "blocks");
        });

        suite.test("damage on the DESTINATION side is reported, and nothing is written over it", () -> {
            Fixture f = Fixture.create("destside", "u");
            Path pj = f.project.skillDir("u");
            Files.delete(pj.resolve(".git/HEAD"));

            HomeSync.Report report = HomeSync.run(f.worktree, f.project, new HomeSync.Options(true, false));

            assertEquals(SyncStatus.DAMAGED, report.units().get(0).status(), report.units().get(0).detail());
            assertFalse(Files.exists(pj.resolve(".git/HEAD")), "destination .git left as found");
        });

        suite.test("controls: a valid nested unit is unchanged, a truly diverged one still conflicts", () -> {
            Fixture f = Fixture.create("controls", "valid", "div");
            Path wtDiv = f.worktree.skillDir("div");
            Path pjDiv = f.project.skillDir("div");
            Files.writeString(wtDiv.resolve("w.txt"), "w\n");
            git(wtDiv, "add", "-A");
            commit(wtDiv, "worktree side");
            Files.writeString(pjDiv.resolve("p.txt"), "p\n");
            git(pjDiv, "add", "-A");
            commit(pjDiv, "project side");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertEquals(SyncStatus.UNCHANGED, only(verdict, "valid").status(), only(verdict, "valid").detail());
            UnitSync div = only(verdict, "div");
            assertEquals(SyncStatus.CONFLICTED, div.status(), div.detail());
            assertTrue(div.conflicts().contains(".git (history)"), "diverged on history: " + div.conflicts());
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    private static final class Fixture {
        Path app;
        String appHead;
        SkillStore worktree;
        SkillStore project;

        static Fixture create(String label, String... units) throws Exception {
            Fixture f = new Fixture();
            Path root = Files.createTempDirectory("close-out-damaged-" + label + "-");
            f.app = root.resolve("app");
            Files.createDirectories(f.app);
            git(f.app, "init", "-q", "-b", "main");
            Files.writeString(f.app.resolve(".gitignore"), "homes/\n");
            git(f.app, "add", "-A");
            commit(f.app, "app main");
            git(f.app, "checkout", "-q", "-b", APP_BRANCH);
            Files.writeString(f.app.resolve("feature.txt"), "x\n");
            git(f.app, "add", "-A");
            commit(f.app, "app feature on no remote");
            git(f.app, "checkout", "-q", "main");
            f.appHead = git(f.app, "rev-parse", "HEAD").trim();

            SkillStore seed = store(f.app.resolve("homes/seed"));
            for (String unit : units) {
                Path staging = root.resolve("staging").resolve(unit);
                Files.createDirectories(staging);
                Files.writeString(staging.resolve("SKILL.md"),
                        "---\nname: " + unit + "\ndescription: fixture\n---\n\nbody\n");
                Files.writeString(staging.resolve("skill-manager.toml"),
                        "[skill]\nname = \"" + unit + "\"\nversion = \"0.1.0\"\n");
                git(staging, "init", "-q", "-b", "main");
                git(staging, "add", "-A");
                commit(staging, "unit " + unit);
                Path origin = root.resolve("origins").resolve(unit + ".git");
                Files.createDirectories(origin.getParent());
                git(root, "clone", "-q", "--bare", staging.toString(), origin.toString());
                Files.createDirectories(seed.skillDir(unit).getParent());
                git(root, "clone", "-q", "--no-local", origin.toString(), seed.skillDir(unit).toString());
            }
            HomeCloner.cloneHome(seed.root(), f.app.resolve("homes/project"));
            HomeCloner.cloneHome(seed.root(), f.app.resolve("homes/worktree"));
            f.project = new SkillStore(f.app.resolve("homes/project"));
            f.worktree = new SkillStore(f.app.resolve("homes/worktree"));
            // What `home clone` does after the copy: stamp each unit's baseline,
            // git history included, which is what the missing-.git case reads.
            dev.skillmanager.bindings.ChildHomeMaterializer.recordCloneBaselines(f.project);
            dev.skillmanager.bindings.ChildHomeMaterializer.recordCloneBaselines(f.worktree);
            return f;
        }
    }

    private static final class Fs {
        static void deleteTree(Path root) throws IOException {
            try (var walk = Files.walk(root)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static UnitSync only(HomeCloseOut.Verdict verdict, String name) {
        return verdict.units().stream()
                .filter(unit -> unit.unitName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + name + ": " + verdict.units()));
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "commit", "-q", "-m", message);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (p.waitFor() != 0) throw new IOException("fixture git " + String.join(" ", args) + ": " + out);
        return out.toString();
    }
}
