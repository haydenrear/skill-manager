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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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
 *
 * <h2>M6 and M7 are one claim and must be read together</h2>
 *
 * <p>M6 plants a git worktree registration inside a unit's checkout — the exact
 * residue issue #47 found in the operator's home, in a home that was neither
 * {@code --home} nor {@code --into} — and asserts it is DETECTED. M7 performs
 * ordinary git bookkeeping in the same {@code .git} — an index rewrite, a moved
 * ref, a new loose object, a reflog append, a pruned packed-refs — and asserts
 * it is NOT.
 *
 * <p>Either one alone is satisfiable by a broken oracle. Deleting the
 * {@code .git} prune passes M6 and fails M7; restoring it passes M7 and fails
 * M6. Only the pair pins the granularity that makes the class detectable
 * without making the tripwire cry wolf, which is the whole content of #47's
 * second defect.
 */
public class HomeTripwireSensitive {
    static final NodeSpec SPEC = NodeSpec.of("home.tripwire.sensitive")
            .kind(NodeSpec.Kind.ASSERTION)
            .tags("tripwire", "mutation", "self-test")
            // Six small decoy trees under a temp dir. Nothing here reads or
            // writes the operator's home, so this node is independent of the
            // rest of the graph and of the standing read-only constraint.
            .timeout("120s");

    /**
     * What a mutation did to the decoy: whether each fidelity saw a difference,
     * and how many git worktree registrations APPEARED.
     *
     * <p>The third field runs the decoy diff through the very function
     * {@code home.tripwire.checked} calls
     * ({@link TripwireSupport#worktreeRegistrationChanges}). Without it, that
     * node's narrow assertion would have no falsification anywhere: it can only
     * fire on a real registration appearing in the operator's home mid-run, and
     * planting one there is exactly what this epic's standing constraint
     * forbids. Proving the filter here is the only honest way to know the
     * assertion there is not vacuous.
     */
    private record Seen(boolean metadata, boolean content, int registrations) {}

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
            Seen plantedWorktree;
            Seen gitChurn;
            Seen removedWorktree;
            boolean unreadableIsNotReportedAsZero;
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

                // M6 — a git worktree registered inside a unit's checkout. The
                // #47 shape, literally: `home close-out` left
                // .git/worktrees/test-graph-5747232 in the operator's home, in
                // a home that was neither --home nor --into, and the tripwire
                // could not see it because `.git` was pruned outright.
                plantedWorktree = probe(home -> {
                    Path reg = home.resolve(
                            ".skill-manager/skills/unit-a/.git/worktrees/planted-5747232");
                    Files.createDirectories(reg);
                    Files.writeString(reg.resolve("gitdir"), "/private/tmp/planted-5747232/.git\n");
                    Files.writeString(reg.resolve("HEAD"), "0".repeat(40) + "\n");
                    Files.writeString(reg.resolve("commondir"), "../..\n");
                });

                // M7 — the OTHER half, and the reason the prune existed. A
                // commit, a fetch and an index rewrite churn a .git on their own
                // schedule; a unit is a git checkout and something touches one
                // on any busy machine. If this is detected the oracle cries
                // wolf and gets switched off, which is how the class became
                // undetectable in the first place. M6 without M7 would be
                // satisfied by simply deleting the prune.
                gitChurn = probe(home -> {
                    Path git = home.resolve(".skill-manager/skills/unit-a/.git");
                    Files.writeString(git.resolve("index"), "a rewritten index\n");
                    Files.writeString(git.resolve("ORIG_HEAD"), "1".repeat(40) + "\n");
                    Files.writeString(git.resolve("logs/HEAD"),
                            "0".repeat(40) + " " + "1".repeat(40) + " commit\n");
                    Files.writeString(git.resolve("refs/heads/main"), "1".repeat(40) + "\n");
                    Files.createDirectories(git.resolve("objects/ab"));
                    Files.writeString(git.resolve("objects/ab/cdef0123"), "a new loose object\n");
                    Files.delete(git.resolve("packed-refs"));
                });

                // M8 -- the other direction. `git worktree remove`, and
                // `git worktree prune` clearing exactly the #47 residue, are
                // writes into the watched home too; a registration quietly
                // leaving the operator's home says as much about who is writing
                // there as one arriving. The broad metadata diff saw both from
                // the start and the named assertion saw only arrivals, which
                // made its name wider than its filter.
                removedWorktree = probe(home -> deleteTree(
                        home.resolve(".skill-manager/skills/unit-a/.git/worktrees/existing-1")));

                // M9 -- not a detection mutation but a VACUITY one, and it is
                // about this node's own new code. `worktrees=N` is counted by
                // listing a directory, and a listing that fails is the §7.4
                // shape: a zero that means "could not look" reported as "looked
                // and found nothing". Reported that way, a home whose
                // registrations became unreadable would satisfy "no
                // registration changed" forever. So the unreadable case gets its
                // own value, and this asserts on the SNAPSHOT TEXT rather than
                // on a diff, because the claim is about what the line says.
                unreadableIsNotReportedAsZero = unreadableIsNotZero();
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
            boolean aPlantedWorktreeRegistrationIsDetected =
                    plantedWorktree.metadata() && plantedWorktree.content()
                            && plantedWorktree.registrations() == 1;
            boolean ordinaryGitChurnIsNotDetected = !gitChurn.metadata() && !gitChurn.content()
                    && gitChurn.registrations() == 0;
            boolean aRemovedWorktreeRegistrationIsDetected =
                    removedWorktree.metadata() && removedWorktree.registrations() == 1;

            if (!anUnmutatedDecoyReportsClean) failures.add("control decoy was not clean");
            if (!aPlantedProjectionIsDetected) failures.add("M1 planted projection not detected");
            if (!aDanglingSymlinkIsDetected) failures.add("M2 dangling symlink not detected");
            if (!silentEditIsInvisibleToMetadata) {
                failures.add("M3 was visible to METADATA — the fidelity split needs re-stating");
            }
            if (!silentEditIsCaughtByContent) failures.add("M3 silent edit not caught by CONTENT");
            if (!aRetargetedSymlinkIsDetected) failures.add("M4 retargeted symlink not detected");
            if (!aDeletionIsDetected) failures.add("M5 deletion not detected");
            if (!aPlantedWorktreeRegistrationIsDetected) {
                failures.add("M6 planted git worktree registration not detected (#47)");
            }
            if (!ordinaryGitChurnIsNotDetected) {
                failures.add("M7 ordinary git churn fired the tripwire — the prune it replaced "
                        + "existed for this reason");
            }
            if (!aRemovedWorktreeRegistrationIsDetected) {
                failures.add("M8 a REMOVED worktree registration not detected");
            }
            if (!unreadableIsNotReportedAsZero) {
                failures.add("M9 an unreadable worktrees/ was reported as worktrees=0, or the "
                        + "fixture could not make it unreadable (running as root?)");
            }

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
                    .assertion("a_planted_git_worktree_registration_is_detected",
                            aPlantedWorktreeRegistrationIsDetected)
                    .assertion("ordinary_git_churn_inside_a_watched_home_is_not_detected",
                            ordinaryGitChurnIsNotDetected)
                    .assertion("a_removed_git_worktree_registration_is_detected",
                            aRemovedWorktreeRegistrationIsDetected)
                    .assertion("an_unreadable_worktrees_directory_is_not_reported_as_zero",
                            unreadableIsNotReportedAsZero)
                    .metric("mutationsPlanted", 9);
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
            List<String> metadataDiff = TripwireSupport.difference(metadataBefore, metadata(decoy));
            return new Seen(
                    !metadataDiff.isEmpty(),
                    !TripwireSupport.difference(contentBefore, content(decoy)).isEmpty(),
                    TripwireSupport.worktreeRegistrationChanges(metadataDiff).size());
        } finally {
            deleteTree(decoy);
        }
    }

    /**
     * Make a decoy's {@code .git/worktrees} unlistable and report whether the
     * snapshot says so instead of saying zero.
     *
     * <p>Returns false if the directory could still be listed after the chmod —
     * that is a broken fixture, not a passing check, and the failure message
     * says which it was.
     */
    private static boolean unreadableIsNotZero() throws Exception {
        Path decoy = Files.createTempDirectory("tripwire-decoy-");
        try {
            build(decoy);
            Path worktrees = decoy.resolve(".skill-manager/skills/unit-a/.git/worktrees");
            Files.setPosixFilePermissions(worktrees, PosixFilePermissions.fromString("---------"));
            try (var probe = Files.list(worktrees)) {
                probe.count();
                return false;   // still listable: the fixture proved nothing
            } catch (java.io.IOException expected) {
                // good — now ask what the snapshot says about it
            }
            List<String> after = metadata(decoy);
            boolean saysZero = after.stream().anyMatch(l -> l.endsWith("\tworktrees=0"));
            boolean saysUnreadable = after.stream().anyMatch(l -> l.contains("\tworktrees=unreadable:"));
            return saysUnreadable && !saysZero;
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

        // unit-a is a git CHECKOUT, because most real units are one and because
        // M6/M7 are both about what a .git contributes. It carries the files
        // ordinary git commands rewrite (index, ORIG_HEAD, logs/HEAD, refs,
        // loose objects, packed-refs) and NO worktrees/ directory, so the
        // baseline records `worktrees=0` and a planted registration is a change
        // to both that line and the set of registration lines.
        Path git = unitA.resolve(".git");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.createDirectories(git.resolve("objects/00"));
        Files.createDirectories(git.resolve("logs"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("config"), "[core]\n\trepositoryformatversion = 0\n");
        Files.writeString(git.resolve("index"), "an index\n");
        Files.writeString(git.resolve("packed-refs"), "# pack-refs with: peeled\n");
        Files.writeString(git.resolve("refs/heads/main"), "0".repeat(40) + "\n");
        Files.writeString(git.resolve("logs/HEAD"), "0".repeat(40) + " init\n");
        Files.writeString(git.resolve("objects/00/11223344"), "a loose object\n");
        // ONE pre-existing registration. Without it a removal has nothing to
        // remove and only the appearing direction could be mutated at all --
        // which is how the filter came to look for `+` lines alone.
        Path existing = git.resolve("worktrees/existing-1");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("gitdir"), "/private/tmp/existing-1/.git\n");
        Files.writeString(existing.resolve("HEAD"), "0".repeat(40) + "\n");

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

    /**
     * Recursive delete that RESTORES permissions on the way down.
     *
     * <p>{@code Files.walk} cannot descend through the directory M9 chmods to
     * {@code 000}, so the previous version aborted there and leaked a temp tree
     * per run. Chmodding each directory back before listing it keeps the decoys
     * disposable, which is the property the whole node rests on — a mutation
     * that shares a decoy with another one stops being an isolated experiment.
     */
    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
                } catch (Exception ignored) {
                    // non-POSIX or already fine; the list below will say
                }
                List<Path> children;
                try (var entries = Files.list(root)) {
                    children = entries.toList();
                } catch (Exception e) {
                    children = List.of();
                }
                for (Path child : children) deleteTree(child);
            }
            Files.deleteIfExists(root);
        } catch (Exception ignored) {
            // best effort; a leftover temp dir is not a finding
        }
    }
}
