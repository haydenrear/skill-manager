///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES GlsFixtureBootstrapped.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Conflict path for {@code sync --git-latest --merge}: edit
 * {@code SKILL.md} on both sides with diverging lines, run
 * {@code sync --git-latest --merge}. The CLI must:
 *
 * <ul>
 *   <li>Exit 8.</li>
 *   <li>Log the conflicted file in its output.</li>
 *   <li>Leave the working tree in the conflicted state with the
 *       standard {@code <<<<<<< / ======= / >>>>>>>} markers.</li>
 * </ul>
 *
 * <p>Aborts the merge before exiting so the next graph run starts
 * clean (not strictly necessary since each run gets a fresh
 * SKILL_MANAGER_HOME via env.prepared, but cheap insurance).
 */
public class GlsConflict {
    static final NodeSpec SPEC = NodeSpec.of("gls.conflict")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gls.merges_after_local_commit")
            .tags("git-latest-source-tracking", "sync", "git-latest", "merge", "conflict")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("gls.fixture.bootstrapped", "skillDir").orElse(null);
            String skillName = ctx.get("gls.fixture.bootstrapped", "skillName").orElse(null);
            String storeDirStr = ctx.get("gls.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDirStr == null) {
                return NodeResult.fail("gls.conflict", "missing upstream context");
            }
            Path storeDir = Path.of(storeDirStr);
            Path fixturePath = Path.of(fixtureDir);

            // Diverging edits to the same file → real merge conflict.
            Files.writeString(storeDir.resolve("SKILL.md"),
                    "\nstore-conflict-line\n", StandardOpenOption.APPEND);
            Files.writeString(fixturePath.resolve("SKILL.md"),
                    "\nfixture-conflict-line\n", StandardOpenOption.APPEND);
            int addRc = GlsFixtureBootstrapped.git(fixturePath, "add", "-A");
            int commitRc = GlsFixtureBootstrapped.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "fixture-conflict");
            if (addRc != 0 || commitRc != 0) {
                return NodeResult.fail("gls.conflict",
                        "fixture-advance failed (add=" + addRc + " commit=" + commitRc + ")");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--git-latest", "--merge")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            String body = out.toString();

            String afterMd = Files.readString(storeDir.resolve("SKILL.md"));
            boolean exitedEight = rc == 8;
            boolean conflictLogged = body.contains("conflict") && body.contains("SKILL.md");
            boolean markersInFile = afterMd.contains("<<<<<<<")
                    && afterMd.contains("=======")
                    && afterMd.contains(">>>>>>>");
            // git status --porcelain should show UU SKILL.md.
            boolean wtUnmerged = false;
            try {
                Process porcelain = new ProcessBuilder("git", "status", "--porcelain")
                        .directory(storeDir.toFile())
                        .redirectErrorStream(true).start();
                StringBuilder stOut = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(porcelain.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) stOut.append(line).append('\n');
                }
                porcelain.waitFor();
                wtUnmerged = stOut.toString().contains("UU SKILL.md");
            } catch (Exception ignored) {}

            // Tidy up so the env teardown step starts clean.
            try {
                new ProcessBuilder("git", "merge", "--abort")
                        .directory(storeDir.toFile()).start().waitFor();
            } catch (Exception ignored) {}

            boolean pass = exitedEight && conflictLogged && markersInFile && wtUnmerged;
            return (pass
                    ? NodeResult.pass("gls.conflict")
                    : NodeResult.fail("gls.conflict",
                            "rc=" + rc + " conflict=" + conflictLogged
                                    + " markers=" + markersInFile + " UU=" + wtUnmerged))
                    .assertion("exited_with_rc_8", exitedEight)
                    .assertion("conflict_logged_with_filename", conflictLogged)
                    .assertion("conflict_markers_in_skill_md", markersInFile)
                    .assertion("working_tree_shows_unmerged", wtUnmerged);
        });
    }
}
