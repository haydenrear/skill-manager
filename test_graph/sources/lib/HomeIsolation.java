import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The ONE place a test-graph node asks <em>"does anything in this home name
 * another home"</em> — by asking PRODUCTION, and by proving on every run that
 * production could still have said no.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>HIS-17 / issue #238. That question had <b>four</b> answers in this tree:
 * {@code HomeCloner.verifyRoots} in production, and three bespoke re-spellings
 * in the graphs — {@code TicketLifecycleSupport.filesNaming},
 * {@code HomeCloneSupport.referencesTo} (reached by two graphs), and
 * {@code artifact-dag}'s reading of the verify report. That is
 * {@code GOAL-one-home-one-answer}'s own failure mode with the epic's
 * instruments as the subject.
 *
 * <p>It cost a wave, twice. HIS-10 (#227) made a correct clone record its
 * DESCENT — {@code home.provenance.json}, naming the home it was copied from,
 * on purpose — and exempted exactly that file from production's isolation rule
 * under byte accounting. Each private copy of the rule then went red on the day
 * HIS-10 merged and stayed red until somebody happened to run its graph:
 * {@code ticket-lifecycle} took 11 skipped nodes with it and was fixed alone in
 * {@code a4a95cb}; nobody checked for other copies; {@code home-clone} and
 * {@code checkout-home} took nine more, four waves later.
 *
 * <h2>What the bespoke scans stay for, and what moves here</h2>
 *
 * <p>The independent walks are NOT deleted. A node whose only reading of the
 * filesystem is production's own report cannot catch production being wrong,
 * and {@code home clone} exiting 0 is a claim. So each graph keeps its walk as
 * a second opinion, and gains — from here — production's verdict on the same
 * tree. The walk fails when the filesystem is wrong. The cross-check fails
 * <b>on the day the contract moves</b>, instead of a wave later.
 *
 * <h2>Reading a verdict out of text, and why that is not the artifact-dag trap</h2>
 *
 * <p>{@code home verify} has no {@code --json}, so the verdict is read from the
 * report. A text expectation that silently stops matching reads exactly like a
 * fixed defect — which is how {@code artifact-dag}'s
 * {@code home_verify_names_the_build_that_completes_the_clone} came to assert
 * an outcome production had stopped producing.
 *
 * <p>So no caller of this class is allowed to read the clean verdict alone.
 * {@link #plantDecoy} exists to make the pair mandatory: every run plants one
 * real path into the other home, requires production to REFUSE it and to NAME
 * it, and removes it again. If the sentence in {@link #ISOLATION_VERDICT} ever
 * moves, that assertion goes red the same day rather than the clean half going
 * quietly green forever.
 *
 * <h2>Why not the exit code</h2>
 *
 * <p>{@code home verify} exits 1 for {@code !clean() || !unresolved.isEmpty()}
 * — the isolation half AND the provisioning half. Measured on {@code
 * home-clone}'s own clone: exit 1 on {@code bin/cli/hc-venv-tool}, the dangling
 * shim that fixture plants on purpose so "a skipped toolchain root is reported"
 * has something to report. Binding an isolation cross-check to the exit code
 * would redden it for a reason the node is not about, and the repair for that
 * is always to widen the fixture until it stops saying anything. The verdict
 * these helpers read is the isolation half, which is the half the private walks
 * duplicate. A node that also wants the exit code should assert it separately
 * and say which half it means.
 */
final class HomeIsolation {

    /**
     * The ONE file a correct copy may name the home it came from in, since
     * HIS-10 — {@code HomeProvenance.FILENAME}, at the home root.
     *
     * <p>Spelled here rather than imported because a graph must be able to
     * disagree with production; spelled ONCE because four disagreeing copies is
     * the defect this class closes.
     */
    static final String DESCENT_RECORD = "home.provenance.json";

    /**
     * Production's own sentence for an isolation refusal —
     * {@code HomeCommand.VerifyCmd}: {@code "%d path(s) in %s resolve into
     * another Skill Manager home"}.
     *
     * <p>Note the singular verb. The tolerated-only branch prints "…no path in
     * %s <em>resolves</em> into another Skill Manager home", which does not
     * contain this substring, so the two verdicts stay distinguishable.
     */
    static final String ISOLATION_VERDICT = " resolve into another Skill Manager home";

    /**
     * The one path a cross-check plants, and removes again.
     *
     * <p>Named with its ticket in it so a decoy that somehow survives a crashed
     * run is attributable on sight rather than mistaken for a product defect by
     * the next node that walks the tree.
     */
    static final String DECOY_LINK = "his17-decoy-into-source";

    private HomeIsolation() {}

    /**
     * True when {@code verifyOutput} is a report about {@code homeRoot} that
     * records no isolation failure.
     *
     * <p>The first half is not decoration: an empty capture, a crashed CLI or a
     * mistyped label all produce a string with no verdict in it, and "no
     * refusal was printed" would read as "clean". That is the vacuity ledger's
     * mechanism C — the check never reached the code — and it is the failure
     * this class is most likely to be mis-wired into.
     */
    static boolean verdictIsClean(String verifyOutput, Path homeRoot) {
        return verifyOutput != null
                && verifyOutput.contains(homeRoot.toString())
                && !verifyOutput.contains(ISOLATION_VERDICT);
    }

    /**
     * True when {@code verifyOutput} refuses on isolation AND names
     * {@code path} while doing it.
     *
     * <p>Both halves, because a refusal for some other reference would satisfy
     * the first alone — and the point of the control is that production saw
     * THIS path.
     */
    static boolean verdictRefusesNaming(String verifyOutput, String path) {
        return verifyOutput != null
                && verifyOutput.contains(ISOLATION_VERDICT)
                && verifyOutput.contains(path);
    }

    /** Where {@link #plantDecoy} puts its link inside {@code homeRoot}. */
    static Path decoyIn(Path homeRoot) {
        return homeRoot.resolve("bin").resolve("cli").resolve(DECOY_LINK);
    }

    /**
     * Plant one absolute symlink in {@code homeRoot} pointing at
     * {@code intoOtherHome}, and return it.
     *
     * <p>An absolute symlink into the other home is the one leak class
     * {@code verifyRoots} decides without consulting descent records,
     * child-home claims or byte accounting — {@code srcPaths.isInsideHome} ⇒
     * {@code SYMLINK_TARGET}. A decoy whose verdict depended on any of those
     * would be testing the sanction machinery rather than the gate.
     *
     * @throws IOException if {@code intoOtherHome} does not exist. A decoy
     *         pointing at nothing is reported as dangling rather than as a
     *         leak, which would make the control pass for the wrong reason —
     *         so it is refused loudly instead of asserted around.
     */
    static Path plantDecoy(Path homeRoot, Path intoOtherHome) throws IOException {
        if (!Files.exists(intoOtherHome, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("decoy target does not exist, so the control would test "
                    + "the dangling-reference branch instead of the isolation gate: "
                    + intoOtherHome);
        }
        Path decoy = decoyIn(homeRoot);
        Files.createDirectories(decoy.getParent());
        Files.deleteIfExists(decoy);
        Files.createSymbolicLink(decoy, intoOtherHome.toAbsolutePath().normalize());
        return decoy;
    }

    /**
     * Remove the decoy. Call from a {@code finally}: downstream nodes read
     * these homes, and a decoy left behind is a leak the graph itself created.
     */
    static void removeDecoy(Path homeRoot) throws IOException {
        Files.deleteIfExists(decoyIn(homeRoot));
    }

    /** True when no decoy remains — asserted by the caller, never assumed. */
    static boolean decoyIsGone(Path homeRoot) {
        return !Files.exists(decoyIn(homeRoot), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * True when {@code homeRoot} carries a descent record that names
     * {@code sourceHome}.
     *
     * <p>A PRECONDITION, and deliberately blind to the exemption under test: it
     * reads the filesystem only, so removing the exemption from production
     * cannot move it. Without it, "the exemption is narrow" is
     * indistinguishable from "there was nothing to exempt" — a clone that
     * recorded no descent at all also yields zero leaks. The vacuity ledger's
     * mechanism B, and its rule: a fixture asserts its own preconditions.
     */
    static boolean recordsDescentNaming(Path homeRoot, String sourceHome) {
        Path record = homeRoot.resolve(DESCENT_RECORD);
        if (!Files.isRegularFile(record)) return false;
        try {
            return Files.readString(record).contains(sourceHome);
        } catch (IOException unreadable) {
            return false;
        }
    }
}
