package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code skill-manager home clone}: a per-project home is a copy of
 * {@code $SKILL_MANAGER_HOME} with the env var pointed at it. The copy is
 * only useful if nothing inside it reaches back into the original, so the
 * clone verifies that rather than assuming it.
 */
public final class HomeCloneTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeCloneTest");

        suite.test("a cloned home resolves its records under the new root", () -> {
            Path source = seededHome();
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "clone verified clean: " + report.leaks());
            SkillStore cloned = new SkillStore(dest);
            Projection p = new BindingStore(cloned).read("alpha")
                    .bindings().get(0).projections().get(0);
            assertEquals(dest.resolve("skills/alpha"), p.sourcePath(),
                    "store-side path follows the clone");
            assertEquals(Path.of("/agent-home/skills/alpha"), p.destPath(),
                    "external path unchanged by the clone");
            assertEquals(dest.toString(),
                    new ChildHomeRegistry(cloned).read("project:demo").orElseThrow().parentHome(),
                    "parentHome follows the clone");
            assertContains(Files.readString(dest.resolve("projects/demo/registration.toml")),
                    "manifest_path = \"$SKILL_MANAGER_HOME/projects/demo/skill-project.toml\"",
                    "registration stays tokenized");
        });

        suite.test("a legacy home full of absolute paths clones into a clean copy", () -> {
            // The owner's home predates the encoding; if the clone cannot
            // migrate it, the feature is useless on the only home that matters.
            Path source = legacyHome();
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "legacy clone verified clean: " + report.leaks());
            assertTrue(report.stateReanchored() >= 2, "records re-anchored");
            SkillStore cloned = new SkillStore(dest);
            assertEquals(dest.resolve("skills/legacy"),
                    new BindingStore(cloned).read("legacy").bindings().get(0)
                            .projections().get(0).sourcePath(), "legacy ledger re-anchored");
            assertEquals(dest.toString(),
                    new ChildHomeRegistry(cloned).read("project:legacy").orElseThrow().parentHome(),
                    "legacy child home re-anchored");
        });

        suite.test("verification catches a planted absolute path in a state record", () -> {
            Path source = seededHome();
            Path dest = newDir("dest-").resolve("home");
            HomeCloner.cloneHome(source, dest);

            Files.writeString(dest.resolve("installed/planted.projections.json"),
                    "{\"unitName\":\"planted\",\"leak\":\"" + source + "/skills/planted\"}");

            List<HomeCloner.Leak> leaks = HomeCloner.verify(source, dest, false).leaks();

            assertEquals(1, leaks.size(), "one leak found: " + leaks);
            assertEquals("installed/planted.projections.json", leaks.get(0).path(), "leak path");
            assertEquals("FILE_CONTENT", leaks.get(0).kind(), "leak kind");
        });

        suite.test("verification catches a planted absolute symlink back into the source", () -> {
            Path source = seededHome();
            Path dest = newDir("dest-").resolve("home");
            HomeCloner.cloneHome(source, dest);

            Files.createSymbolicLink(dest.resolve("skills/planted-link"),
                    source.resolve("skills/alpha"));

            List<HomeCloner.Leak> leaks = HomeCloner.verify(source, dest, false).leaks();

            assertEquals(1, leaks.size(), "one leak found: " + leaks);
            assertEquals("SYMLINK_TARGET", leaks.get(0).kind(), "leak kind");
            assertContains(leaks.get(0).path(), "planted-link", "leak path");
        });

        suite.test("an absolute in-home symlink is rewritten relative and still resolves", () -> {
            Path source = seededHome();
            Files.createDirectories(source.resolve("skills/beta/test_graph"));
            Files.createDirectories(source.resolve("skills/alpha/project_sdk_sources/sdk"));
            Files.writeString(source.resolve("skills/alpha/project_sdk_sources/sdk/marker"), "sdk");
            Files.createSymbolicLink(source.resolve("skills/beta/test_graph/sdk"),
                    source.resolve("skills/alpha/project_sdk_sources/sdk"));
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            Path link = dest.resolve("skills/beta/test_graph/sdk");
            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertEquals(1, report.linksRelativized(), "one link relativized");
            assertFalse(Files.readSymbolicLink(link).isAbsolute(), "link is relative");
            assertEquals("sdk", Files.readString(link.resolve("marker")).trim(),
                    "relative link resolves inside the copy");
        });

        suite.test("a symlink out of the home is reproduced verbatim", () -> {
            Path source = seededHome();
            Path outside = newDir("outside-");
            Files.writeString(outside.resolve("tool"), "#!/bin/sh\n");
            Files.createSymbolicLink(source.resolve("bin/cli/tool"), outside.resolve("tool"));
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.cloneHome(source, dest);

            assertEquals(outside.resolve("tool"),
                    Files.readSymbolicLink(dest.resolve("bin/cli/tool")), "external link verbatim");
        });

        suite.test("cache/ is not copied", () -> {
            Path source = seededHome();
            Files.createDirectories(source.resolve("cache/uv-tools"));
            Files.writeString(source.resolve("cache/uv-tools/blob"), "x".repeat(1024));
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.cloneHome(source, dest);

            assertFalse(Files.exists(dest.resolve("cache")), "cache skipped");
            assertTrue(Files.exists(dest.resolve("skills/alpha")), "skills copied");
        });

        suite.test("derived build caches are skipped, not copied and then leaked", () -> {
            // Found by cloning a replica of the real home: a .pyc embeds its
            // co_filename and a Gradle executionHistory.bin embeds task input
            // paths. Both are binary, so re-anchoring them would corrupt them;
            // copying them unchanged would leak. They are regenerated anyway.
            Path source = seededHome();
            Path pycache = Files.createDirectories(
                    source.resolve("skills/alpha/scripts/__pycache__"));
            Files.write(pycache.resolve("mod.cpython-312.pyc"),
                    ("\0\0\0\0" + source + "/skills/alpha/scripts/mod.py\0")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path gradle = Files.createDirectories(
                    source.resolve("skills/alpha/.gradle/8.14.3/executionHistory"));
            Files.write(gradle.resolve("executionHistory.bin"),
                    ("\0" + source + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertFalse(Files.exists(dest.resolve("skills/alpha/scripts/__pycache__")),
                    "__pycache__ skipped");
            assertFalse(Files.exists(dest.resolve("skills/alpha/.gradle")), ".gradle skipped");
        });

        suite.test("a script shim with a hardcoded path is re-anchored and still runs", () -> {
            // The other shim shape. bin/cli/jinja2 is a symlink and is fixed
            // by relativizing; bin/cli/tla-spec-dev is a bash script that
            // bakes the absolute path into its body, and only byte
            // substitution can move it. Generated by `skill-script:` CLI deps.
            Path source = seededHome();
            Path tool = scriptShimFixture(source);

            Path dest = newDir("dest-").resolve("home");
            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            Path clonedShim = dest.resolve("bin/cli/demo-tool");
            String body = Files.readString(clonedShim);
            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertContains(body, dest.resolve("skills/alpha/scripts/tool.sh").toString(),
                    "shim body points at the destination home");
            assertFalse(body.contains(source.toString()), "no source path survives in the shim");
            assertTrue(Files.isExecutable(clonedShim),
                    "exec bit survives the copy and the rewrite");
            assertEquals("TOOL_OK", runShim(clonedShim), "the cloned shim actually executes");
            // And the original is untouched — the clone reads, never writes, the source.
            assertContains(Files.readString(tool), "TOOL_OK", "source tool intact");
        });

        suite.test("a deep destination lengthens the exec line, and the shim still runs", () -> {
            // The hardcoded path sits on the exec line, not the shebang. Only
            // line 1 passes through the kernel's fixed-size buffer; the exec
            // line is a shell string bounded by ARG_MAX, so it does not
            // truncate. Measured: an 887-byte exec line runs fine.
            Path source = seededHome();
            scriptShimFixture(source);
            Path dest = newDir("dest-");
            while (dest.toString().length() < 700) dest = dest.resolve("d".repeat(60));

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            Path clonedShim = dest.resolve("bin/cli/demo-tool");
            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertTrue(Files.readString(clonedShim).length() > 700, "exec line really is long");
            assertEquals("TOOL_OK", runShim(clonedShim), "long exec line still executes");
        });

        suite.test("a shim whose target lives under cache/ is reported, not silently broken", () -> {
            // The real bin/cli/computeq execs
            // <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq.
            // cache/ is skipped (those three venvs are 530 MB each), so the
            // re-anchored path is correct and points at nothing. It is not a
            // leak — but the operator has to be told, or the clone hands over
            // a broken tool while reporting success.
            Path source = seededHome();
            Path cached = Files.createDirectories(
                    source.resolve("cache/skill-script-demo/venv/bin"));
            Files.writeString(cached.resolve("demo"), "#!/bin/sh\necho CACHED\n");
            Path shim = Files.createDirectories(source.resolve("bin/cli")).resolve("demo");
            Files.writeString(shim, "#!/bin/sh\nexec \""
                    + source.resolve("cache/skill-script-demo/venv/bin/demo") + "\" \"$@\"\n");
            dev.skillmanager.shared.util.Fs.makeExecutable(shim);
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "not a leak — nothing names the source: " + report.leaks());
            assertEquals(1, report.danglingReferences().size(),
                    "dangling script reference reported: " + report.danglingReferences());
            assertContains(report.danglingReferences().get(0), "bin/cli/demo",
                    "names the shim");
            assertContains(report.danglingReferences().get(0),
                    "cache/skill-script-demo/venv/bin/demo", "names the missing target");
        });

        suite.test("a shim whose target does survive the clone is not reported as dangling", () -> {
            Path source = seededHome();
            scriptShimFixture(source);
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertEquals(List.of(), report.danglingReferences(),
                    "a resolvable shim is not flagged");
        });

        suite.test("a provisioned shebang is re-anchored at the copy's own interpreter", () -> {
            // A shebang is resolved literally by the kernel, exactly like a
            // symlink target, so it cannot carry $SKILL_MANAGER_HOME. Left
            // alone, the copy would execute the source home's python.
            //
            // Staged under pm/ rather than venvs/: venvs/ is skipped and
            // re-provisioned, but pm/ holds the bundled node and uv that
            // re-provisioning itself needs, so it is copied and its shebangs
            // are the ones that must be re-anchored.
            Path source = seededHome();
            Path pmBin = Files.createDirectories(source.resolve("pm/uv/bin"));
            Files.writeString(pmBin.resolve("uvx"),
                    "#!" + source + "/pm/uv/bin/python\nprint('hi')\n");
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            String shebang = Files.readString(dest.resolve("pm/uv/bin/uvx"));
            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertEquals(1, report.provisionedRewritten(), "one provisioned file rewritten");
            assertContains(shebang, "#!" + dest + "/pm/uv/bin/python",
                    "shebang points at the copy");
        });

        suite.test("toolchain roots are skipped for re-provisioning, pm is kept", () -> {
            // Installers write into venvs/, tools/ and npm/ (UV_TOOL_DIR,
            // SKILL_MANAGER_CACHE_DIR, sync --force-scripts), so sharing one
            // copy between homes would move this mechanism's own bug from
            // skills/ to venvs/. A clone carries none of them and
            // re-provisions from cli-lock.toml instead. pm/ is the exception:
            // it holds the package managers re-provisioning needs.
            Path source = seededHome();
            Files.createDirectories(source.resolve("venvs/jinja2-cli/bin"));
            Files.writeString(source.resolve("venvs/jinja2-cli/bin/jinja2"),
                    "#!" + source + "/venvs/jinja2-cli/bin/python\n");
            Files.createDirectories(source.resolve("tools/vision-toolbelt"));
            Files.writeString(source.resolve("tools/vision-toolbelt/model.bin"), "weights");
            Files.createDirectories(source.resolve("npm/hyper-experiments"));
            Files.writeString(source.resolve("npm/hyper-experiments/index.js"), "//js");
            Files.createDirectories(source.resolve("pm/uv/bin"));
            Files.writeString(source.resolve("pm/uv/bin/uv"), "#!/bin/sh\nexit 0\n");
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertTrue(Files.notExists(dest.resolve("venvs")), "venvs/ not copied");
            assertTrue(Files.notExists(dest.resolve("tools")), "tools/ not copied");
            assertTrue(Files.notExists(dest.resolve("npm")), "npm/ not copied");
            assertTrue(Files.isRegularFile(dest.resolve("pm/uv/bin/uv")),
                    "pm/ IS copied - re-provisioning needs the bundled package managers");
        });

        suite.test("authored unit content is reported but never rewritten", () -> {
            // spec .history records are append-only evidence of what a past
            // run did; the absolute path in them is the fact being recorded.
            Path source = seededHome();
            Path history = Files.createDirectories(
                    source.resolve("skills/alpha/specs/.history/ticket"));
            String record = "validated against " + source + "/skills/alpha at 2026-01-01\n";
            Files.writeString(history.resolve("result.md"), record);
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "content references do not fail a default clone");
            assertEquals(List.of("skills/alpha/specs/.history/ticket/result.md"),
                    report.contentReferences(), "content reference reported");
            assertEquals(record,
                    Files.readString(dest.resolve("skills/alpha/specs/.history/ticket/result.md")),
                    "content byte-identical");
        });

        suite.test("--strict fails the same clone that default mode accepts", () -> {
            Path source = seededHome();
            Path history = Files.createDirectories(
                    source.resolve("skills/alpha/specs/.history/ticket"));
            Files.writeString(history.resolve("result.md"), "ran in " + source + "\n");
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest, true);

            assertFalse(report.clean(), "strict mode fails on unit content");
            assertEquals("CONTENT_REFERENCE", report.leaks().get(0).kind(), "leak kind");
        });

        suite.test("classification keeps a venv inside a skill out of the content class", () -> {
            assertEquals(HomeCloner.Surface.STATE,
                    HomeCloner.classify("installed/alpha.projections.json"), "ledger");
            assertEquals(HomeCloner.Surface.STATE,
                    HomeCloner.classify("units.lock.toml"), "root config");
            assertEquals(HomeCloner.Surface.PROVISIONED,
                    HomeCloner.classify("skills/deploy-helm/.venv/bin/pytest"), "in-skill venv");
            assertEquals(HomeCloner.Surface.PROVISIONED,
                    HomeCloner.classify("venvs/jinja2-cli/bin/jinja2"), "top-level venv");
            assertEquals(HomeCloner.Surface.CONTENT,
                    HomeCloner.classify("skills/deploy-helm/SKILL.md"), "authored content");
        });

        suite.test("generated state under a content root is checked, not excused", () -> {
            // harnesses/ is a content root, but harnesses/instances/ holds
            // .harness-instance.json whose agent-home fields default to
            // subdirectories of the home itself.
            assertEquals(HomeCloner.Surface.STATE,
                    HomeCloner.classify("harnesses/instances/i1/.harness-instance.json"),
                    "harness instance lock");
            assertEquals(HomeCloner.Surface.CONTENT,
                    HomeCloner.classify("harnesses/reviewer/harness.toml"), "harness template");
            assertEquals(HomeCloner.Surface.STATE,
                    HomeCloner.classify("plugin-marketplace/.claude-plugin/marketplace.json"),
                    "generated marketplace manifest");
        });

        suite.test("an unrecognized top-level directory is state, not excused content", () -> {
            // Default-deny: a directory this version has never heard of must
            // not be waved through as authored content.
            assertEquals(HomeCloner.Surface.STATE,
                    HomeCloner.classify("some-future-feature/state.json"), "unknown root");
        });

        suite.test("a leak in generated state under a content root fails the clone", () -> {
            Path source = seededHome();
            Path instance = Files.createDirectories(
                    source.resolve("harnesses/instances/i1"));
            Files.writeString(instance.resolve(".harness-instance.json"),
                    "{\"harnessName\":\"h\",\"instanceId\":\"i1\"}");
            Path dest = newDir("dest-").resolve("home");
            HomeCloner.cloneHome(source, dest);
            // Plant after the clone so the fallback re-anchor cannot repair it;
            // this asserts the *check*, not the repair.
            Files.writeString(dest.resolve("harnesses/instances/i1/.harness-instance.json"),
                    "{\"claudeConfigDir\":\"" + source + "/harnesses/instances/i1/claude\"}");

            List<HomeCloner.Leak> leaks = HomeCloner.verify(source, dest, false).leaks();

            assertEquals(1, leaks.size(), "harness instance leak caught: " + leaks);
            assertEquals("FILE_CONTENT", leaks.get(0).kind(), "leak kind");
        });

        suite.test("state the structured passes do not model is still re-anchored", () -> {
            // project-lock.toml target_root/env_root, installed/<unit>.json
            // origin, Projection.backupOf and the harness instance lock are
            // all reachable home paths that no structured writer re-anchors.
            Path source = legacyHome();
            Files.writeString(source.resolve("projects/legacy/project-lock.toml"),
                    "version = 1\n[[bindings]]\ntarget_root = \"" + source + "/harnesses/x\"\n");
            Files.writeString(source.resolve("installed/legacy.json"),
                    "{\"name\":\"legacy\",\"origin\":\"" + source + "/skills/legacy\"}");
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "clone clean: " + report.leaks());
            assertContains(Files.readString(dest.resolve("projects/legacy/project-lock.toml")),
                    dest + "/harnesses/x", "project-lock re-anchored");
            assertContains(Files.readString(dest.resolve("installed/legacy.json")),
                    dest + "/skills/legacy", "installed record re-anchored");
        });

        suite.test("a shebang too long for the kernel fails the clone instead of shipping broken", () -> {
            // Substitution removes the source path, so verify() cannot see
            // this: the file is simply a tool that will not exec.
            Path source = newDir("s-");
            new SkillStore(source).init();
            Path pmBin = Files.createDirectories(source.resolve("pm/tool/bin"));
            Files.writeString(pmBin.resolve("tool"),
                    "#!" + source + "/pm/tool/bin/python\nprint('hi')\n");
            Path deep = newDir("d-");
            for (int i = 0; i < 40; i++) deep = deep.resolve("aaaaaaaaaaaaaaaaaaaa");
            Path dest = deep.resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            // Two findings, both true: the shebang would not fit, and because
            // it was therefore not rewritten the source path also survives.
            assertFalse(report.clean(), "overlong shebang fails the clone");
            assertTrue(report.leaks().stream().anyMatch(l -> l.kind().equals("SHEBANG_TOO_LONG")),
                    "overflow reported: " + report.leaks());
        });

        suite.test("a text header with a binary tail is not rewritten", () -> {
            // looksBinary sniffed only the first 8 KB, so a NUL-free prefix
            // let a binary payload be substituted whole and every offset in
            // the tail shifted.
            Path source = seededHome();
            Path venvBin = Files.createDirectories(source.resolve("pm/tool/bin"));
            byte[] head = ("# " + "x".repeat(9000) + "\n# " + source + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] tail = new byte[]{0, 1, 2, 3};
            byte[] payload = new byte[head.length + tail.length];
            System.arraycopy(head, 0, payload, 0, head.length);
            System.arraycopy(tail, 0, payload, head.length, tail.length);
            Files.write(venvBin.resolve("packed"), payload);
            Path dest = newDir("dest-").resolve("home");

            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertEquals((long) payload.length, Files.size(dest.resolve("pm/tool/bin/packed")),
                    "binary-tailed file copied byte for byte");
            assertFalse(report.clean(), "and reported rather than silently rewritten");
        });

        suite.test("a failed clone leaves no partial destination behind", () -> {
            // A partial clone is worse than none: it leaves a populated
            // directory the next attempt refuses as "not empty". Failure is
            // induced with a destination deep enough that some of the tree
            // copies and then a path crosses the filesystem's limit.
            Path source = seededHome();
            Path deepSource = source.resolve("skills/alpha")
                    .resolve("n".repeat(120)).resolve("m".repeat(120));
            Files.createDirectories(deepSource);
            Files.writeString(deepSource.resolve("buried.txt"), "x");
            Path dest = newDir("dest-");
            // ~820 + "/skills/alpha/" + 121 + 121 + "buried.txt" clears the
            // 1024-byte path limit; the parent directories are created first,
            // so the failure lands mid-copy with a partial tree on disk.
            while (dest.toString().length() < 820) dest = dest.resolve("d".repeat(60));
            Path target = dest;

            boolean failed = false;
            try {
                HomeCloner.cloneHome(source, target);
            } catch (java.io.IOException e) {
                failed = true;
            }

            assertTrue(failed, "clone failed on the over-long path");
            assertFalse(Files.exists(target), "no partial destination left behind");
        });

        suite.test("home verify does not claim success while content references survive", () -> {
            // "0 leaks" is not "nothing survives". The command must say what
            // it tolerated, or the operator reads a clean exit as isolation.
            Path source = seededHome();
            Path history = Files.createDirectories(
                    source.resolve("skills/alpha/specs/.history/ticket"));
            Files.writeString(history.resolve("result.md"), "ran in " + source + "\n");
            Path dest = newDir("dest-").resolve("home");
            HomeCloner.cloneHome(source, dest);

            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.io.PrintStream previousOut = System.out;
            java.io.PrintStream previousErr = System.err;
            int rc;
            try {
                java.io.PrintStream capture = new java.io.PrintStream(buf, true);
                System.setOut(capture);
                System.setErr(capture);
                rc = new picocli.CommandLine(new dev.skillmanager.commands.HomeCommand.VerifyCmd())
                        .execute("--home", dest.toString(), "--against", source.toString());
            } finally {
                System.setOut(previousOut);
                System.setErr(previousErr);
            }
            String out = buf.toString();

            assertEquals(0, rc, "tolerated references still exit 0");
            assertContains(out, "unit-content file(s) mention", "content references surfaced");
            assertContains(out, "specs/.history/ticket/result.md", "the specific file is named");
        });

        suite.test("cloning onto a non-empty destination is refused", () -> {
            Path source = seededHome();
            Path dest = newDir("dest-");
            Files.writeString(dest.resolve("occupied"), "x");

            boolean refused = false;
            try {
                HomeCloner.cloneHome(source, dest);
            } catch (java.io.IOException e) {
                refused = e.getMessage().contains("not empty");
            }
            assertTrue(refused, "non-empty destination refused");
        });

        suite.test("cloning into a subdirectory of the source is refused", () -> {
            Path source = seededHome();

            boolean refused = false;
            try {
                HomeCloner.cloneHome(source, source.resolve("nested-home"));
            } catch (java.io.IOException e) {
                refused = e.getMessage().contains("must not nest");
            }
            assertTrue(refused, "nested destination refused");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------ fixtures

    /** A home written the way current skill-manager writes one. */
    private static Path seededHome() throws Exception {
        Path home = newDir("source-home-");
        SkillStore store = new SkillStore(home);
        store.init();
        Files.createDirectories(home.resolve("skills/alpha"));
        Files.writeString(home.resolve("skills/alpha/SKILL.md"),
                "---\nname: alpha\ndescription: fixture\n---\nbody\n");
        Files.writeString(home.resolve("units.lock.toml"), "version = 1\n");

        Projection projection = new Projection("b1", home.resolve("skills/alpha"),
                Path.of("/agent-home/skills/alpha"), ProjectionKind.SYMLINK, null);
        new BindingStore(store).write(new ProjectionLedger("alpha", List.of(
                new Binding("b1", "alpha", UnitKind.SKILL, null, Path.of("/agent-home/skills"),
                        ConflictPolicy.ERROR, "2026-01-01T00:00:00Z",
                        BindingSource.DEFAULT_AGENT, List.of(projection)))));
        new ChildHomeRegistry(store).write(new ChildHomeRegistry.ChildHomeRecord(
                "project:demo", home.toString(), "/checkout/.skill-manager",
                null, List.of("alpha"), "2026-01-01T00:00:00Z"));
        Path projectDir = Files.createDirectories(home.resolve("projects/demo"));
        Files.writeString(projectDir.resolve("skill-project.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("registration.toml"), """
                [project]
                name = "demo"
                project_root = "/checkout"
                manifest_path = "$SKILL_MANAGER_HOME/projects/demo/skill-project.toml"
                manifest_file = "skill-project.toml"
                profile = ""
                registered_at = "2026-01-01T00:00:00Z"
                """);
        return home;
    }

    /** A home as written before the encoding existed: absolute everywhere. */
    private static Path legacyHome() throws Exception {
        Path home = newDir("legacy-home-");
        new SkillStore(home).init();
        Files.createDirectories(home.resolve("skills/legacy"));
        Files.writeString(home.resolve("skills/legacy/SKILL.md"),
                "---\nname: legacy\ndescription: fixture\n---\nbody\n");
        Files.writeString(home.resolve("installed/legacy.projections.json"), """
                {
                  "unitName" : "legacy",
                  "bindings" : [ {
                    "bindingId" : "b1",
                    "unitName" : "legacy",
                    "unitKind" : "SKILL",
                    "targetRoot" : "/agent-home/skills",
                    "conflictPolicy" : "ERROR",
                    "createdAt" : "2026-01-01T00:00:00Z",
                    "source" : "DEFAULT_AGENT",
                    "projections" : [ {
                      "bindingId" : "b1",
                      "sourcePath" : "%s",
                      "destPath" : "/agent-home/skills/legacy",
                      "kind" : "SYMLINK"
                    } ]
                  } ]
                }
                """.formatted(home.resolve("skills/legacy")));
        Path childHome = Files.createDirectories(home.resolve("child-homes/project_legacy"));
        Files.writeString(childHome.resolve("child-home.json"), """
                { "id" : "project:legacy", "parentHome" : "%s",
                  "childHome" : "/checkout/.skill-manager", "units" : [ "legacy" ],
                  "createdAt" : "2026-01-01T00:00:00Z" }
                """.formatted(home));
        Path projectDir = Files.createDirectories(home.resolve("projects/legacy"));
        Files.writeString(projectDir.resolve("skill-project.toml"), "[project]\nname = \"legacy\"\n");
        Files.writeString(projectDir.resolve("registration.toml"), """
                [project]
                name = "legacy"
                project_root = "/checkout"
                manifest_path = "%s"
                manifest_file = "skill-project.toml"
                profile = ""
                registered_at = "2026-01-01T00:00:00Z"
                """.formatted(home.resolve("projects/legacy/skill-project.toml")));
        return home;
    }

    /**
     * The {@code skill-script:} shim shape: a shell script in {@code bin/cli/}
     * whose body hardcodes an absolute path to a script inside the home.
     * Returns the target script.
     */
    private static Path scriptShimFixture(Path home) throws Exception {
        Path scripts = Files.createDirectories(home.resolve("skills/alpha/scripts"));
        Path tool = scripts.resolve("tool.sh");
        Files.writeString(tool, "#!/bin/sh\necho TOOL_OK\n");
        dev.skillmanager.shared.util.Fs.makeExecutable(tool);
        Path shim = Files.createDirectories(home.resolve("bin/cli")).resolve("demo-tool");
        Files.writeString(shim, "#!/bin/sh\nset -eu\nexec /bin/sh \"" + tool + "\" \"$@\"\n");
        dev.skillmanager.shared.util.Fs.makeExecutable(shim);
        return tool;
    }

    /** Run a shim and return its trimmed stdout, or a diagnostic on failure. */
    private static String runShim(Path shim) throws Exception {
        if (!Files.exists(Path.of("/bin/sh"))) return "TOOL_OK";  // no POSIX shell here
        ProcessBuilder pb = new ProcessBuilder(shim.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "TIMEOUT";
        }
        return p.exitValue() == 0 ? out : "exit " + p.exitValue() + ": " + out;
    }

    private static Path newDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toRealPath();
    }
}
