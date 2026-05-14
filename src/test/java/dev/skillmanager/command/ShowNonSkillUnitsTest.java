package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.ShowCommand;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;

public final class ShowNonSkillUnitsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ShowNonSkillUnitsTest");

        suite.test("show renders harness template details", () -> {
            SkillStore store = newStore();
            installHarness(store, "learning-app-coordinator");
            seedRecord(store, "learning-app-coordinator", UnitKind.HARNESS);

            Result result = runShow(store, "learning-app-coordinator");
            assertEquals(0, result.rc, "show harness exit code");
            assertContains(result.out, "HARNESS  learning-app-coordinator@0.1.0",
                    "harness header");
            assertContains(result.out, "units:", "units block");
            assertContains(result.out, "skill:planner", "unit reference");
            assertContains(result.out, "docs:", "docs block");
            assertContains(result.out, "doc:team-prompts/review-stance", "doc reference");
            assertContains(result.out, "mcp_tools:", "mcp tools block");
            assertContains(result.out, "shared-mcp [search,get]", "mcp allowlist");
        });

        suite.test("show renders doc-repo details", () -> {
            SkillStore store = newStore();
            installDocRepo(store, "team-prompts");

            Result result = runShow(store, "team-prompts");
            assertEquals(0, result.rc, "show doc-repo exit code");
            assertContains(result.out, "DOC  team-prompts@0.1.0", "doc header");
            assertContains(result.out, "sources:", "sources block");
            assertContains(result.out, "review-stance", "source id");
            assertContains(result.out, "file=claude-md/review-stance.md", "source file");
        });

        return suite.runAll();
    }

    private static SkillStore newStore() throws Exception {
        SkillStore store = new SkillStore(Files.createTempDirectory("show-non-skill-"));
        store.init();
        return store;
    }

    private static Result runShow(SkillStore store, String name) throws Exception {
        ShowCommand cmd = new ShowCommand(store, name);
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc;
        try {
            System.setOut(new PrintStream(buf));
            rc = cmd.call();
        } finally {
            System.setOut(original);
        }
        return new Result(rc, buf.toString());
    }

    private static void installHarness(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("show-harness-");
        Files.writeString(tmp.resolve("harness.toml"),
                "[harness]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n"
                        + "description = \"coordinates the learning app\"\n\n"
                        + "units = [\"skill:planner\"]\n"
                        + "docs = [\"doc:team-prompts/review-stance\"]\n\n"
                        + "[[mcp_tools]]\n"
                        + "server = \"shared-mcp\"\n"
                        + "tools = [\"search\", \"get\"]\n");
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.HARNESS));
    }

    private static void installDocRepo(SkillStore store, String name) throws Exception {
        Path tmp = Files.createTempDirectory("show-doc-");
        Path md = tmp.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), "review\n");
        Files.writeString(tmp.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n");
        Fs.copyRecursive(tmp, store.unitDir(name, UnitKind.DOC));
    }

    private static void seedRecord(SkillStore store, String name, UnitKind kind) throws Exception {
        new UnitStore(store).write(new InstalledUnit(
                name, "0.1.0",
                InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE,
                "fixture", null, null,
                UnitStore.nowIso(),
                List.of(), kind));
    }

    private record Result(int rc, String out) {}
}
