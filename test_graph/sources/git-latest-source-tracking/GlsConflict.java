///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES GlsFixtureBootstrapped.java
//SOURCES ../lib/SmEnv.java

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
 *   <li>Leave the store <em>exactly where it was</em> — the merge is
 *       rolled back, so there are no conflict markers, no {@code UU}
 *       entry, and the pre-sync bytes survive.</li>
 * </ul>
 *
 * <h2>What this node used to assert, and why it changed — DEF-108</h2>
 *
 * <p>Until HIS-6 this node required the opposite of the third bullet:
 * {@code <<<<<<< / ======= / >>>>>>>} markers in {@code SKILL.md} and
 * {@code UU SKILL.md} in {@code git status --porcelain}. That was the
 * behaviour of the CLI when the node was written, and the node was never
 * revisited when the behaviour changed.
 *
 * <p>It changed on purpose. {@code GOAL-symlink-merge-settles} says in
 * as many words that a sync must not put a unit's repository into a merge
 * conflict its own printed remedy cannot clear, and the epic's baseline for
 * that goal is two units in the operator's project home <em>permanently
 * stuck</em> in {@code MERGE_CONFLICT}. Stranding the working tree is the
 * defect; rolling the merge back and printing a remedy is the fix. So this
 * node was asserting, on a core graph, that the product still had the
 * condition the epic exists to remove.
 *
 * <p>It went unnoticed because {@code run.py --all} had never completed a
 * sweep (DEF-011). The first full sweep that ever ran, on 2026-08-24,
 * reddened it; the terminal sweep reddened it identically, three assertions
 * to the same values, which is what settled that it is a stale assertion
 * rather than a flake. Measured, both rounds:
 * {@code rc=8 conflict=true markers=false UU=false}.
 *
 * <p><b>Two of the four original assertions were always right and are kept
 * unchanged</b> — the exit code and the logged filename. Only the two that
 * encoded the stranding are inverted, and each is paired with a control, so
 * "the tree is clean" cannot pass by the node never having created a
 * conflict at all. That distinction is the whole risk in inverting an
 * assertion: {@code no markers} is exactly what a fixture that never
 * conflicted also reports.
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
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String fixtureDir = ctx.get("gls.fixture.bootstrapped", "skillDir").orElse(null);
            String skillName = ctx.get("gls.fixture.bootstrapped", "skillName").orElse(null);
            String storeDirStr = ctx.get("gls.fixture.installed", "storeDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null || fixtureDir == null
                    || skillName == null || storeDirStr == null) {
                return NodeResult.fail("gls.conflict", "missing upstream context");
            }
            Path storeDir = Path.of(storeDirStr);
            Path fixturePath = Path.of(fixtureDir);

            // Diverging edits to the same file → real merge conflict.
            // The store's pre-sync bytes are captured FIRST: the claim this
            // node now makes is that they survive, and a claim about survival
            // needs the thing that has to survive recorded before the run.
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

            String beforeMd = Files.readString(storeDir.resolve("SKILL.md"));

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", skillName, "--git-latest", "--merge")
                    .redirectErrorStream(true);
            SmEnv.apply(ctx, pb, home);

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

            // PRECONDITION, not a claim. If the two sides were never made to
            // diverge there is no conflict to roll back, and every "clean"
            // assertion below would pass for the wrong reason. This is blind
            // to what the CLI did: it reads only the bytes the fixture wrote.
            boolean divergedOnBothSides = beforeMd.contains("store-conflict-line")
                    && Files.readString(fixturePath.resolve("SKILL.md"))
                            .contains("fixture-conflict-line");

            // THE CLAIM. The store is left exactly as it was: the pre-sync
            // bytes survive byte-for-byte, no markers were written, and git
            // reports no unmerged path. Three readings of one property, kept
            // separate because they fail in different ways -- a partial
            // rollback would satisfy `noMarkers` and fail `storeUnchanged`.
            boolean noMarkers = !afterMd.contains("<<<<<<<")
                    && !afterMd.contains(">>>>>>>");
            boolean storeUnchanged = afterMd.equals(beforeMd);
            boolean wtNotUnmerged = !wtUnmerged;

            // The remedy the CLI printed has to name the store directory, or
            // "nothing was changed" is a dead end for whoever reads it.
            boolean remedyNamesStore = body.contains(storeDir.toString());

            boolean pass = exitedEight && conflictLogged && divergedOnBothSides
                    && noMarkers && storeUnchanged && wtNotUnmerged && remedyNamesStore;
            return (pass
                    ? NodeResult.pass("gls.conflict")
                    : NodeResult.fail("gls.conflict",
                            "rc=" + rc + " conflict=" + conflictLogged
                                    + " diverged=" + divergedOnBothSides
                                    + " noMarkers=" + noMarkers
                                    + " storeUnchanged=" + storeUnchanged
                                    + " notUnmerged=" + wtNotUnmerged
                                    + " remedyNamesStore=" + remedyNamesStore))
                    .assertion("exited_with_rc_8", exitedEight)
                    .assertion("conflict_logged_with_filename", conflictLogged)
                    .assertion("precondition_both_sides_diverged", divergedOnBothSides)
                    .assertion("DEF108_store_holds_no_conflict_markers", noMarkers)
                    .assertion("DEF108_store_bytes_unchanged_by_the_refused_merge", storeUnchanged)
                    .assertion("DEF108_working_tree_has_no_unmerged_path", wtNotUnmerged)
                    .assertion("DEF108_remedy_names_the_store_directory", remedyNamesStore);
        });
    }
}
