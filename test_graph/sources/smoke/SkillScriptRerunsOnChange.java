///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Regression test for the skill-script content-fingerprint rerun gate.
 *
 * <p>Three steps in one node:
 * <ol>
 *   <li>Copy the skill-script fixture into a writable temp dir,
 *       install it under a private SKILL_MANAGER_HOME, assert the
 *       initial sentinel was dropped.</li>
 *   <li>Run {@code skill-manager sync --from <copy>} with no changes,
 *       assert the script DID NOT run again (we look for the diagnostic
 *       "skipping" line and confirm the script log count did not grow).</li>
 *   <li>Edit the install.sh in the temp copy to touch a distinct
 *       sentinel, sync again, assert the new sentinel landed under
 *       bin/cli/. Proves a script edit re-fires the install even
 *       when the skill name + CLI dep list haven't changed.</li>
 * </ol>
 *
 * <p>Uses a private SKILL_MANAGER_HOME so we don't pollute the home
 * the rest of the smoke graph shares — and so the fingerprint state
 * starts empty for the test, regardless of which other smoke nodes
 * ran first.
 */
public class SkillScriptRerunsOnChange {
    static final NodeSpec SPEC = NodeSpec.of("skill.script.reruns.on.change")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cli", "skill-script", "rerun")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail("skill.script.reruns.on.change",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path srcFixture = repoRoot.resolve("test_graph/fixtures/skill-script-skill");

            // Private HOME under env.prepared's tree (which is itself
            // a per-run temp dir) — keeps every other smoke node's
            // fingerprint state out of the way.
            Path privateHome = Path.of(envHome).resolve("skill-script-rerun-home");
            Path privateClaude = privateHome.resolve("claude");
            Path privateCodex = privateHome.resolve("codex");
            Path privateGemini = privateHome.resolve("gemini");
            Path fixtureCopy = privateHome.resolve("fixture");
            try {
                Files.createDirectories(privateHome);
                Files.createDirectories(privateClaude);
                Files.createDirectories(privateCodex);
                Files.createDirectories(privateGemini);
                copyDir(srcFixture, fixtureCopy);
            } catch (IOException e) {
                return NodeResult.fail("skill.script.reruns.on.change",
                        "fixture setup failed: " + e.getMessage());
            }

            // STEP 1 — initial install drops `skill-script-touched`.
            ProcessRecord proc1 = Procs.run(ctx, "install",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "install", fixtureCopy.toString(), "--yes"));
            int rc1 = proc1.exitCode();
            Path storeHome = privateHome.resolve("home");
            Path binCli = storeHome.resolve("bin/cli");
            boolean initialTouchOk = Files.isRegularFile(binCli.resolve("skill-script-touched"));
            int scriptRunsAfterInstall = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");

            // STEP 2 — sync with NO script changes; backend should skip.
            ProcessRecord proc2 = Procs.run(ctx, "sync_noop",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(),
                            "skill-script-skill", "--yes"));
            int rc2 = proc2.exitCode();
            String stdout2 = readLogTail(ctx.reportDir(), proc2);
            boolean noopSkipped = stdout2.contains("scripts unchanged since last install");
            int scriptRunsAfterNoop = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");
            boolean noopDidNotRun = scriptRunsAfterNoop == scriptRunsAfterInstall;

            // STEP 3 — edit the script, sync again; backend should rerun.
            Path script = fixtureCopy.resolve("skill-scripts/install.sh");
            try {
                String contents = Files.readString(script);
                String edited = contents.replace("skill-script-touched",
                        "skill-script-rerun-marker");
                Files.writeString(script, edited);
            } catch (IOException e) {
                return NodeResult.fail("skill.script.reruns.on.change",
                        "edit step failed: " + e.getMessage());
            }
            ProcessRecord proc3 = Procs.run(ctx, "sync_after_edit",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(),
                            "skill-script-skill", "--yes"));
            int rc3 = proc3.exitCode();
            boolean rerunSentinelOk = Files.isRegularFile(binCli.resolve("skill-script-rerun-marker"));
            int scriptRunsAfterEdit = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");
            boolean postEditRan = scriptRunsAfterEdit == scriptRunsAfterNoop + 1;

            boolean pass = rc1 == 0 && initialTouchOk
                    && rc2 == 0 && noopSkipped && noopDidNotRun
                    && rc3 == 0 && rerunSentinelOk && postEditRan;

            NodeResult result = pass
                    ? NodeResult.pass("skill.script.reruns.on.change")
                    : NodeResult.fail("skill.script.reruns.on.change",
                            "rc1=" + rc1 + " initialTouch=" + initialTouchOk
                                    + " rc2=" + rc2 + " noopSkipped=" + noopSkipped
                                    + " noopDidNotRun=" + noopDidNotRun
                                    + " rc3=" + rc3 + " rerunSentinel=" + rerunSentinelOk
                                    + " postEditRan=" + postEditRan
                                    + " scriptRunsAfterInstall=" + scriptRunsAfterInstall
                                    + " scriptRunsAfterNoop=" + scriptRunsAfterNoop
                                    + " scriptRunsAfterEdit=" + scriptRunsAfterEdit);
            return result
                    .process(proc1).process(proc2).process(proc3)
                    .assertion("install_ok", rc1 == 0)
                    .assertion("initial_sentinel_dropped", initialTouchOk)
                    .assertion("initial_install_logged_script_run", scriptRunsAfterInstall >= 1)
                    .assertion("noop_sync_ok", rc2 == 0)
                    .assertion("noop_sync_skipped_script", noopSkipped)
                    .assertion("noop_sync_did_not_rerun_script", noopDidNotRun)
                    .assertion("post_edit_sync_ok", rc3 == 0)
                    .assertion("post_edit_sentinel_dropped", rerunSentinelOk)
                    .assertion("post_edit_logged_script_run", postEditRan)
                    .metric("scriptRunsAfterInstall", scriptRunsAfterInstall)
                    .metric("scriptRunsAfterNoop", scriptRunsAfterNoop)
                    .metric("scriptRunsAfterEdit", scriptRunsAfterEdit);
        });
    }

    private static ProcessBuilder smProc(Path sm, Path repoRoot, Path privateHome,
                                         Path privateClaude, Path privateCodex,
                                         Path privateGemini,
                                         String... cliArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(sm.toString());
        for (String a : cliArgs) argv.add(a);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("SKILL_MANAGER_HOME", privateHome.resolve("home").toString());
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", privateClaude.toString());
        pb.environment().put("CODEX_HOME", privateCodex.toString());
        pb.environment().put("GEMINI_HOME", privateGemini.toString());
        return pb;
    }

    private static String readLogTail(Path reportDir, ProcessRecord proc) {
        // ProcessRecord.logPath() is RELATIVE to the run's reportDir
        // (Procs.java does that on purpose so envelope JSON stays
        // portable). Resolve against reportDir before reading; falling
        // back to the absolute interpretation in case a future Procs
        // change emits absolute paths.
        try {
            String log = proc.logPath();
            if (log == null || log.isBlank()) return "";
            Path p = Path.of(log);
            if (!p.isAbsolute() && reportDir != null) p = reportDir.resolve(p);
            if (!Files.isRegularFile(p)) return "";
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readSkillScriptLogs(Path storeHome) {
        Path dir = storeHome.resolve("logs").resolve("skill-scripts");
        if (!Files.isDirectory(dir)) return "";
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.sorted().toList()) {
                if (Files.isRegularFile(p)) out.append(Files.readString(p)).append('\n');
            }
        } catch (IOException e) {
            return "";
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) {
            // start fresh so a re-run inside the same env is deterministic
            try (var s = Files.walk(dst)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a)
                    throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                    throws IOException {
                Path target = dst.resolve(src.relativize(file));
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
