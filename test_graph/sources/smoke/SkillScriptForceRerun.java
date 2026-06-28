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
 * End-to-end coverage for `--force-scripts`: install and sync should rerun a
 * `skill-script:` dep even when the content fingerprint matches and the
 * declared binary already exists.
 */
public class SkillScriptForceRerun {
    static final NodeSpec SPEC = NodeSpec.of("skill.script.force.rerun")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("cli", "skill-script", "force")
            .timeout("180s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            if (envHome == null) {
                return NodeResult.fail("skill.script.force.rerun",
                        "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path srcFixture = repoRoot.resolve("test_graph/fixtures/skill-script-skill");
            Path privateHome = Path.of(envHome).resolve("skill-script-force-home");
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
                return NodeResult.fail("skill.script.force.rerun",
                        "fixture setup failed: " + e.getMessage());
            }

            ProcessRecord install1 = Procs.run(ctx, "install_initial",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "install", fixtureCopy.toString(), "--yes"));
            Path storeHome = privateHome.resolve("home");
            int scriptRunsAfterInstall = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");

            ProcessRecord uninstall = Procs.run(ctx, "uninstall_keep_mcp",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "uninstall", "skill-script-skill", "--keep-mcp"));

            ProcessRecord installForce = Procs.run(ctx, "install_force",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "install", fixtureCopy.toString(), "--yes", "--force-scripts"));
            String installForceLog = readLog(ctx.reportDir(), installForce);
            int scriptRunsAfterForceInstall = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");

            ProcessRecord syncNoop = Procs.run(ctx, "sync_noop",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(),
                            "skill-script-skill", "--yes"));
            String syncNoopLog = readLog(ctx.reportDir(), syncNoop);
            int scriptRunsAfterNoopSync = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");

            ProcessRecord syncForce = Procs.run(ctx, "sync_force",
                    smProc(sm, repoRoot, privateHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(),
                            "skill-script-skill", "--yes", "--force-scripts"));
            String syncForceLog = readLog(ctx.reportDir(), syncForce);
            int scriptRunsAfterForceSync = countOccurrences(
                    readSkillScriptLogs(storeHome), "skill-script-skill: touched");

            boolean initialRan = scriptRunsAfterInstall >= 1;
            boolean forceInstallRan = installForceLog.contains("force rerun requested")
                    && scriptRunsAfterForceInstall >= scriptRunsAfterInstall + 1;
            boolean noopSkipped = syncNoopLog.contains("scripts unchanged since last install")
                    && scriptRunsAfterNoopSync == scriptRunsAfterForceInstall;
            boolean forceSyncRan = syncForceLog.contains("force rerun requested")
                    && scriptRunsAfterForceSync == scriptRunsAfterNoopSync + 1;

            boolean pass = install1.exitCode() == 0
                    && uninstall.exitCode() == 0
                    && installForce.exitCode() == 0
                    && syncNoop.exitCode() == 0
                    && syncForce.exitCode() == 0
                    && initialRan
                    && forceInstallRan
                    && noopSkipped
                    && forceSyncRan;

            NodeResult result = pass
                    ? NodeResult.pass("skill.script.force.rerun")
                    : NodeResult.fail("skill.script.force.rerun",
                            "install1=" + install1.exitCode()
                                    + " uninstall=" + uninstall.exitCode()
                                    + " installForce=" + installForce.exitCode()
                                    + " syncNoop=" + syncNoop.exitCode()
                                    + " syncForce=" + syncForce.exitCode()
                                    + " initialRan=" + initialRan
                                    + " forceInstallRan=" + forceInstallRan
                                    + " noopSkipped=" + noopSkipped
                                    + " forceSyncRan=" + forceSyncRan
                                    + " scriptRunsAfterInstall=" + scriptRunsAfterInstall
                                    + " scriptRunsAfterForceInstall=" + scriptRunsAfterForceInstall
                                    + " scriptRunsAfterNoopSync=" + scriptRunsAfterNoopSync
                                    + " scriptRunsAfterForceSync=" + scriptRunsAfterForceSync);
            return result
                    .process(install1).process(uninstall).process(installForce)
                    .process(syncNoop).process(syncForce)
                    .assertion("initial_install_ran_script", initialRan)
                    .assertion("force_install_ran_script", forceInstallRan)
                    .assertion("noop_sync_skipped_script", noopSkipped)
                    .assertion("force_sync_ran_script", forceSyncRan)
                    .metric("scriptRunsAfterInstall", scriptRunsAfterInstall)
                    .metric("scriptRunsAfterForceInstall", scriptRunsAfterForceInstall)
                    .metric("scriptRunsAfterNoopSync", scriptRunsAfterNoopSync)
                    .metric("scriptRunsAfterForceSync", scriptRunsAfterForceSync);
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

    private static String readLog(Path reportDir, ProcessRecord proc) {
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
        } catch (IOException ignored) {
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
