///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Proves the tripwire can FAIL. This is the discipline the epic ran by hand,
 * made a standing assertion.
 *
 * <p>Every ticket in this epic shipped a fully green suite that was hiding a
 * defect, and the practice that caught them was: break something, watch the
 * NAMED assertion fail, revert. That was an agent's habit backed by scratchpad
 * scripts. A habit is not a regression guard — the next agent inherits it only
 * by reading a handoff, and an oracle nobody re-proves decays into a check that
 * passes because it cannot see.
 *
 * <p>{@code home.tripwire.checked} asserting CLEAN is worth exactly as much as
 * this node's evidence that CLEAN was falsifiable. A tripwire that returned
 * CLEAN unconditionally would pass every real run too.
 *
 * <h2>Method</h2>
 *
 * One mutation per defect class this epic actually observed, each applied to its
 * own FRESH decoy home built under a temp directory in the same shape as a real
 * one. A fresh decoy per mutation is not tidiness: mutations that share a decoy
 * have to be reverted, and a revert cannot restore the mtime of a directory
 * whose child was recreated, so "did the revert work" becomes an assertion about
 * the test harness competing with the assertion about the oracle. Isolating by
 * construction removes the question.
 *
 * <p>The control — a decoy with no mutation at all — is asserted CLEAN in the
 * same run. Without it a checker that always reported TRIPPED would "kill" every
 * mutant below and mean nothing. That is the same reasoning the kill-test
 * harness uses when it runs an unmutated corpus first.
 *
 * <h2>Why the size-and-mtime-preserving edit is the load-bearing one</h2>
 *
 * M3 rewrites a file's bytes while restoring its exact length and modification
 * time. {@link TripwireSupport.Fidelity#METADATA} is blind to it by
 * construction, and {@link TripwireSupport.Fidelity#CONTENT} catches it. Both
 * halves are asserted. It is the only evidence that the expensive fidelity is
 * not redundant with the cheap one — without it, "we also hash the bytes" is an
 * unfalsified claim about cost, and the honest move would be to delete the
 * content sweep rather than pay for it every run.
 */
public class HomeTripwireSensitive {
    static final NodeSpec SPEC = NodeSpec.of("home.tripwire.sensitive")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("tripwire", "mutation", "self-test")
            // Six small decoy trees under a temp dir. Nothing here reads or
            // writes the operator's home, so this node is independent of the
            // rest of the graph and of the standing read-only constraint.
            .timeout("120s");

    /** What a mutation did to each fidelity's view of the decoy. */
    private record Seen(boolean metadata, boolean content) {}

    /** A change planted into a decoy home. */
    private interface Mutation {
        void apply(Path home) throws Exception;
    }

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            List<String> failures = new ArrayList<>();
            Seen control;
            Seen newFile;
            Seen danglingLink;
            Seen silentEdit;
            Seen retargetedLink;
            Seen deletion;
            try {
                // Control: a decoy nothing was done to.
                control = probe(home -> { });

                // M1 — a projection lands in an agent home. The #18 shape.
                newFile = probe(home -> {
                    Path planted = home.resolve(".claude/skills/leaked-unit/SKILL.md");
                    Files.createDirectories(planted.getParent());
                    Files.writeString(planted, "leaked\n");
                });

                // M2 — a dangling symlink into a deleted temp dir. The exact
                // residue #18 found fifteen of in the operator's agent homes.
                danglingLink = probe(home -> {
                    Path link = home.resolve(".codex/skills/dangling-unit");
                    Files.createDirectories(link.getParent());
                    Files.createSymbolicLink(link, Path.of("/private/tmp/sm-testgraph-deleted/unit"));
                });

                // M3 — same length, same mtime, different bytes.
                silentEdit = probe(home -> {
                    Path victim = home.resolve(".skill-manager/skills/unit-a/SKILL.md");
                    byte[] bytes = Files.readAllBytes(victim);
                    FileTime when = Files.getLastModifiedTime(victim, LinkOption.NOFOLLOW_LINKS);
                    int last = bytes.length - 2;
                    bytes[last] = (byte) (bytes[last] == 'X' ? 'Y' : 'X');
                    Files.write(victim, bytes);
                    Files.setLastModifiedTime(victim, when);
                });

                // M4 — a symlink retargeted in place. The target string is the
                // fact that matters, and the shell tripwire did not record it
                // at the cheap fidelity at all.
                retargetedLink = probe(home -> {
                    Path link = home.resolve(".skill-manager/skills/linked-unit");
                    Files.delete(link);
                    Files.createSymbolicLink(link, Path.of("/somewhere/else/entirely"));
                });

                // M5 — something the operator had is gone. Destruction is a
                // finding too, and a diff that only looked for additions would
                // report this one clean.
                deletion = probe(home -> Files.delete(home.resolve(".gemini/skills/keeper")));
            } catch (Exception e) {
                return NodeResult.error("home.tripwire.sensitive", e);
            }

            boolean anUnmutatedDecoyReportsClean = !control.metadata() && !control.content();
            boolean aPlantedProjectionIsDetected = newFile.metadata();
            boolean aDanglingSymlinkIsDetected = danglingLink.metadata();
            boolean silentEditIsInvisibleToMetadata = !silentEdit.metadata();
            boolean silentEditIsCaughtByContent = silentEdit.content();
            boolean aRetargetedSymlinkIsDetected = retargetedLink.metadata();
            boolean aDeletionIsDetected = deletion.metadata();

            if (!anUnmutatedDecoyReportsClean) failures.add("control decoy was not clean");
            if (!aPlantedProjectionIsDetected) failures.add("M1 planted projection not detected");
            if (!aDanglingSymlinkIsDetected) failures.add("M2 dangling symlink not detected");
            if (!silentEditIsInvisibleToMetadata) {
                failures.add("M3 was visible to METADATA — the fidelity split needs re-stating");
            }
            if (!silentEditIsCaughtByContent) failures.add("M3 silent edit not caught by CONTENT");
            if (!aRetargetedSymlinkIsDetected) failures.add("M4 retargeted symlink not detected");
            if (!aDeletionIsDetected) failures.add("M5 deletion not detected");

            boolean pass = failures.isEmpty();
            return (pass
                    ? NodeResult.pass("home.tripwire.sensitive")
                    : NodeResult.fail("home.tripwire.sensitive", String.join("; ", failures)))
                    .assertion("an_unmutated_decoy_reports_clean", anUnmutatedDecoyReportsClean)
                    .assertion("a_planted_projection_is_detected", aPlantedProjectionIsDetected)
                    .assertion("a_dangling_symlink_is_detected", aDanglingSymlinkIsDetected)
                    .assertion("a_size_and_mtime_preserving_edit_is_invisible_to_metadata",
                            silentEditIsInvisibleToMetadata)
                    .assertion("a_size_and_mtime_preserving_edit_is_caught_by_content",
                            silentEditIsCaughtByContent)
                    .assertion("a_retargeted_symlink_is_detected", aRetargetedSymlinkIsDetected)
                    .assertion("a_deletion_is_detected", aDeletionIsDetected)
                    .metric("mutationsPlanted", 5);
        });
    }

    /**
     * Build a fresh decoy, baseline it, apply {@code mutation}, and report which
     * fidelities saw a difference.
     */
    private static Seen probe(Mutation mutation) throws Exception {
        Path decoy = Files.createTempDirectory("tripwire-decoy-");
        try {
            build(decoy);
            List<String> metadataBefore = metadata(decoy);
            List<String> contentBefore = content(decoy);
            mutation.apply(decoy);
            return new Seen(
                    !TripwireSupport.difference(metadataBefore, metadata(decoy)).isEmpty(),
                    !TripwireSupport.difference(contentBefore, content(decoy)).isEmpty());
        } finally {
            deleteTree(decoy);
        }
    }

    /** A decoy in the same shape as a real home, small enough to hash instantly. */
    private static void build(Path home) throws Exception {
        Path unitA = home.resolve(".skill-manager/skills/unit-a");
        Files.createDirectories(unitA);
        // The trailing 'X' gives M3 a byte to flip without changing the length.
        Files.writeString(unitA.resolve("SKILL.md"), "---\nname: unit-a\n---\nbodyX\n");
        Files.writeString(unitA.resolve("skill-manager.toml"), "[skill]\nname = \"unit-a\"\n");

        Files.createDirectories(home.resolve(".skill-manager/installed"));
        Files.writeString(home.resolve(".skill-manager/installed/unit-a.json"), "{\"name\":\"unit-a\"}\n");

        Files.createSymbolicLink(
                home.resolve(".skill-manager/skills/linked-unit"), Path.of("../../elsewhere/unit"));

        for (String agent : new String[] {".claude", ".codex", ".gemini"}) {
            Files.createDirectories(home.resolve(agent).resolve("skills"));
        }
        Files.writeString(home.resolve(".gemini/skills/keeper"), "do not lose me\n");
    }

    private static List<String> metadata(Path home) throws Exception {
        return TripwireSupport.collectAll(
                TripwireSupport.presentRoots(home), home, TripwireSupport.Fidelity.METADATA);
    }

    private static List<String> content(Path home) throws Exception {
        List<Path> roots = new ArrayList<>();
        for (String surface : TripwireSupport.CONTENT_SURFACES) {
            Path path = home.resolve(".skill-manager").resolve(surface);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) roots.add(path);
        }
        return TripwireSupport.collectAll(roots, home, TripwireSupport.Fidelity.CONTENT);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort; a leftover temp dir is not a finding
                }
            });
        } catch (Exception ignored) {
            // ditto
        }
    }
}
