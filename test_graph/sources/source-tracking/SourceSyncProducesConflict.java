///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SourceFixturePublished.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Conflict path for {@code skill-manager sync … --merge}: edit the
 * same file (SKILL.md) on both sides, run {@code --merge}, and assert
 * the CLI exits {@code 8}, leaves the working tree in the conflicted
 * state with {@code <<<<<<< / >>>>>>>} markers, and lists the
 * conflicted file in its output.
 */
public class SourceSyncProducesConflict {
    static final NodeSpec SPEC = NodeSpec.of("source.sync.produces_conflict")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("source.sync.merges_clean")
            .tags("source-tracking", "sync", "merge", "conflict")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String fixtureDir = ctx.get("source.fixture.published", "skillDir").orElse(null);
            String skillName = ctx.get("source.fixture.published", "skillName").orElse(null);
            String storeDir = ctx.get("source.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || fixtureDir == null
                    || skillName == null || storeDir == null) {
                return NodeResult.fail("source.sync.produces_conflict", "missing upstream context");
            }

            // Edit SKILL.md on both sides with different lines so git
            // can't auto-merge them.
            Path storeMd = Path.of(storeDir).resolve("SKILL.md");
            Files.writeString(storeMd, "\nstore-conflict-line\n", StandardOpenOption.APPEND);

            Path fixturePath = Path.of(fixtureDir);
            Path fixtureMd = fixturePath.resolve("SKILL.md");
            Files.writeString(fixtureMd, "\nfixture-conflict-line\n", StandardOpenOption.APPEND);
            int addRc = SourceFixturePublished.git(fixturePath, "add", "-A");
            int commitRc = SourceFixturePublished.git(fixturePath,
                    "-c", "user.email=fixture@skillmanager.local",
                    "-c", "user.name=fixture",
                    "commit", "--quiet", "-m", "fixture-conflict");
            if (addRc != 0 || commitRc != 0) {
                return NodeResult.fail("source.sync.produces_conflict",
                        "fixture-advance failed (addRc=" + addRc + " commitRc=" + commitRc + ")");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--from", fixtureDir, "--merge")
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

            String afterMd = Files.readString(storeMd);
            boolean exitedEight = rc == 8;
            boolean conflictLogged = body.contains("conflict") && body.contains("SKILL.md");
            boolean markersInFile = afterMd.contains("<<<<<<<")
                    && afterMd.contains("=======")
                    && afterMd.contains(">>>>>>>");
            // After --merge with conflicts, working tree should show UU for SKILL.md.
            boolean workingTreeUnmerged = false;
            try {
                Process porcelain = new ProcessBuilder("git", "status", "--porcelain")
                        .directory(Path.of(storeDir).toFile())
                        .redirectErrorStream(true).start();
                StringBuilder stOut = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(porcelain.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) stOut.append(line).append('\n');
                }
                porcelain.waitFor();
                workingTreeUnmerged = stOut.toString().contains("UU SKILL.md");
            } catch (Exception ignored) {}

            // Clean up so the report node sees the env in a tidy state.
            try {
                new ProcessBuilder("git", "merge", "--abort")
                        .directory(Path.of(storeDir).toFile()).start().waitFor();
            } catch (Exception ignored) {}

            boolean pass = exitedEight && conflictLogged && markersInFile && workingTreeUnmerged;
            return (pass
                    ? NodeResult.pass("source.sync.produces_conflict")
                    : NodeResult.fail("source.sync.produces_conflict",
                            "rc=" + rc + " conflictLogged=" + conflictLogged
                                    + " markers=" + markersInFile + " UU=" + workingTreeUnmerged))
                    .assertion("exited_with_rc_8", exitedEight)
                    .assertion("conflict_logged_with_filename", conflictLogged)
                    .assertion("conflict_markers_in_skill_md", markersInFile)
                    .assertion("working_tree_shows_unmerged", workingTreeUnmerged);
        });
    }
}
