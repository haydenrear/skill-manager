package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>Every sync path asks ONE question about local work, or this fails.</b>
 *
 * <h2>What it is guarding</h2>
 *
 * <p>There are two sync paths and there will be a third.
 * {@code SyncGitHandler} syncs a store copy against a git remote,
 * {@code SyncFromLocalDirHandler} against a local directory
 * ({@code sync --from}). <b>Both refuse with the same sentence</b> — "extra
 * local changes (working tree edits, or commits ahead of the installed
 * baseline) — sync would overwrite them" — and before HIS-4 both reached that
 * sentence through {@code GitOps.isDirty} directly.
 *
 * <p>HIS-4 taught that question that a dereferenced in-unit store link is the
 * materializer's work and not an author's. Teaching only ONE of the two would
 * have left {@code sync --from} refusing a materialized child copy forever, in
 * identical words, for a reason already fixed next door — and nothing would
 * have detected it, because both paths would still look correct in isolation.
 *
 * <p>That is <b>two readings of one rule</b>: CHM-15's shape, DEF-004's shape
 * (one home, two verdicts, on the same five paths), and the shape this epic has
 * met in every wave. The fix is one definition —
 * {@link DereferencedStoreLinks#isAuthoredDirty} — and this is the oracle that
 * keeps it one.
 *
 * <h2>Why a source scan and not a behavioural test</h2>
 *
 * <p>Because the failure being prevented is <em>a path that does not exist
 * yet</em>. A behavioural test can only cover the handlers somebody remembered
 * to write a case for, which is exactly the gap that let the second path keep
 * the old question.
 *
 * <p>Modelled on {@code sources/sandbox/SandboxEnvContract.java}, which does the
 * same for {@code SKILL_MANAGER_HOME} — including its discipline of proving
 * itself sensitive every run, so a scan that silently stopped matching anything
 * cannot pass as compliance.
 *
 * <h2 id="scope">WHAT THIS SCAN CANNOT SEE, and why that is written down here</h2>
 *
 * <p>An earlier version of this comment claimed the scan "fails on the FOURTH
 * handler nobody has written". <b>That was wrong twice over</b>, and an
 * adversarial review of #231 measured it: the scan is scoped to the
 * {@code effects} package, and there are already a THIRD and a FOURTH caller
 * outside it that somebody did write.
 *
 * <ul>
 *   <li>{@code UnitTrunkPull:187} — the {@code project sync} path. It asks
 *       {@code GitOps.hasWorktreeChanges} directly, and its {@code pullTarget}
 *       returns <b>the child home's own checkout</b> for CHECKOUT-mode units,
 *       which is exactly the tier HIS-4 is about. A materialized copy is
 *       therefore {@code HELD_BACK} there, forever, for the reason HIS-4
 *       fixed everywhere else.</li>
 *   <li>{@code UnitPublisher:169} — publish. It reads the same dirtiness as
 *       "there is local work to commit", so from a child home it would
 *       <b>commit the materialization artifact upstream</b>.</li>
 * </ul>
 *
 * <p>Neither is in HIS-4's slice and neither is fixed here. They are
 * <b>DEF-016</b>, filed rather than left to a scan that provably cannot see
 * them — a guard whose own documentation overstates its reach is worse than no
 * guard, because it stops the next person looking. The third case below keeps
 * that ledger honest: it fails if the set of out-of-scope callers changes, so
 * either the finding gets updated or somebody discovers a fifth.
 */
public final class SyncPathsAgreeAboutDirtyTest {

    /**
     * The raw predicates a sync path must not ask. They are not deprecated —
     * {@code project/}, {@code bindings/} and the tests below legitimately ask
     * them, because "has this working tree changed at all" is a real and
     * different question from "is there work here a sync would destroy". The
     * ban is scoped to the handlers that refuse a sync.
     */
    private static final List<String> BANNED =
            List.of("GitOps.isDirty(", "GitOps.hasWorktreeChanges(");

    private static final Path EFFECTS =
            Path.of("src/main/java/dev/skillmanager/effects");

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SyncPathsAgreeAboutDirtyTest");

        suite.test("no effects handler asks the raw dirty question", () -> {
            List<Path> sources = sources();
            // SENSITIVITY, asserted before the scan is trusted. A scan that
            // walked zero files would report compliance over an empty set,
            // which is the vacuous-pass failure mode this epic keeps meeting.
            assertTrue(sources.size() >= 5,
                    "the scan really walked the effects package, found " + sources.size()
                            + " source file(s) — a scan over nothing always passes");

            List<String> offenders = new ArrayList<>();
            for (Path p : sources) {
                String body = Files.readString(p);
                for (String banned : BANNED) {
                    if (body.contains(banned)) {
                        offenders.add(p.getFileName() + " asks " + banned);
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "a sync path is asking the raw dirty question instead of "
                            + "DereferencedStoreLinks.isAuthoredDirty, so it will refuse a "
                            + "materialized child home forever while its sibling does not: "
                            + offenders);
        });

        suite.test("the scan can actually see a violation", () -> {
            // Proves the matcher, not the codebase. Without this the case above
            // passes identically whether the ban holds or the matcher broke.
            String planted = "        boolean dirty = GitOps.isDirty(storeDir, baseline);";
            boolean seen = false;
            for (String banned : BANNED) {
                if (planted.contains(banned)) seen = true;
            }
            assertTrue(seen, "the matcher recognises the shape it is meant to ban");
        });

        suite.test("both sync paths reach the one definition", () -> {
            String git = Files.readString(EFFECTS.resolve("SyncGitHandler.java"));
            String local = Files.readString(EFFECTS.resolve("SyncFromLocalDirHandler.java"));
            assertTrue(git.contains("DereferencedStoreLinks.isAuthoredDirty")
                            || git.contains("isAuthoredDirty("),
                    "SyncGitHandler reaches the shared definition");
            assertTrue(local.contains("isAuthoredDirty"),
                    "SyncFromLocalDirHandler reaches the shared definition — this is the one "
                            + "that was left behind, and the case that would have caught it");
        });

        suite.test("the ledger of callers this scan cannot see is accurate", () -> {
            // The scan is scoped to `effects`. These are the callers OUTSIDE it,
            // named in DEF-016. Asserted rather than described, so this fails
            // when somebody fixes one (update DEF-016) or adds a fifth (file it)
            // -- which is the only way a documented blind spot stays honest.
            List<String> outside = new ArrayList<>();
            for (String rel : List.of("project/UnitTrunkPull.java",
                                      "project/UnitPublisher.java",
                                      "project/ProjectLibResolver.java",
                                      "bindings/ChildHomeMaterializer.java")) {
                Path p = Path.of("src/main/java/dev/skillmanager").resolve(rel);
                if (!Files.isRegularFile(p)) continue;
                String body = Files.readString(p);
                for (String banned : BANNED) {
                    if (body.contains(banned)) { outside.add(rel); break; }
                }
            }
            assertTrue(outside.size() == 4,
                    "DEF-016 names four readers of the raw dirty question outside the effects "
                            + "package; the scan sees none of them. Found " + outside.size()
                            + ": " + outside + ". If this changed, update DEF-016 rather than "
                            + "this number");
        });

        return suite.runAll();
    }

    private static List<Path> sources() throws Exception {
        if (!Files.isDirectory(EFFECTS)) {
            throw new IllegalStateException("effects package not found at " + EFFECTS.toAbsolutePath()
                    + " — this suite must run from the repository root");
        }
        try (Stream<Path> s = Files.list(EFFECTS)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }
}
