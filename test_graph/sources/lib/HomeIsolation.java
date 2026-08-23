import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * <h2>TWO controls, because one of them covers the wrong branch</h2>
 *
 * <p>The symlink decoy alone was shipped first and <b>was not enough</b>, which
 * review of #242 caught. The walk's exemption widened the REGULAR-FILE branch —
 * "this filename at this depth is not a leak" — and the symlink decoy is
 * deliberately on a branch {@code verifyRoots} decides <em>before</em> the
 * regular-file walk runs and <em>without</em> consulting descent records or byte
 * accounting. One branch widened, an oracle added for a different one, and the
 * descent-record accounting exercised in neither direction.
 *
 * <p>The scenario that gets through: {@code mentionsOnlyRecordedDescent} is
 * loosened to a filename check — <em>the same shape the graph itself wrote</em> —
 * and a record carrying one extra unaccounted path passes the walk, passes the
 * clean verdict, passes the symlink decoy, and passes {@code home clone}'s own
 * report. Four readers, one wrong answer.
 *
 * <p>{@link #tamperDescentRecord} closes it. Every run also smuggles the other
 * home's path into the record's timestamp — production's own documented failure
 * case, "a path smuggled into a timestamp" — and requires BOTH readers to refuse
 * it, then restores the record byte-for-byte and asserts the restoration.
 *
 * <h2>What happens if a plant ever survives: the law LAUNDERS it</h2>
 *
 * <p>Stated because "removal is asserted" is not the whole story, and a reader
 * should not have to discover this the hard way. If a decoy ever outlived a
 * crashed node, {@code common/HomeFixpointLaw.java} would not report it. Bare
 * {@code home verify} refuses the link as {@code FOREIGN_HOME} and prints
 * {@code complete it with: … sync --force-scripts}; the law PARSES that remedy
 * and RUNS it; {@code CliShimPruner} deletes the decoy; re-verify exits 0; the
 * law records {@code homesRepaired} and PASSES.
 *
 * <p>So the epic's own post-condition would silently delete the evidence — and
 * re-provision the clone's toolchains inside the graph while doing it, which is
 * the ~90 s pruner behaviour {@code GOAL-one-home-one-answer}'s clause 3 exists
 * to stop. That is why removal is asserted in the planting node itself, in a
 * {@code finally}, rather than left to anything downstream to notice.
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

    /**
     * The graph's OWN byte accounting for the descent record — production's
     * {@code HomeProvenance.mentionsOnlyRecordedDescent} rule, re-derived here.
     *
     * <h2>Why a second implementation is right here and wrong elsewhere</h2>
     *
     * <p>This ticket exists because one rule had four spellings. Adding a fifth
     * needs an argument, and it is the same argument
     * {@code HomeCloneSupport.surfaceOf} already makes for re-deriving
     * {@code HomeCloner.classify}: <b>the graph has to be able to disagree.</b>
     * Importing production's answer would make the walk agree with production by
     * construction, and a walk that cannot disagree cannot catch production
     * being wrong — which is the entire reason the private walks were kept
     * rather than deleted.
     *
     * <p>What keeps the two from drifting apart silently is not that there is
     * one of them. It is that the caller asserts they AGREE, on every run, over
     * the same tree, in both directions. A divergence is a red on the day it
     * appears.
     *
     * <p>The rule: every raw occurrence of {@code needle} in the record's bytes
     * must be accounted for by the parsed {@code clonedFrom} and
     * {@code parentStores} values. One occurrence anywhere else — a field a
     * future version adds, a path smuggled into a timestamp — and this is false
     * and the record is a leak like any other file.
     *
     * @return false when the record is absent, unreadable, or mentions
     *         {@code needle} anywhere the recorded descent does not account for
     */
    static boolean mentionsOnlyRecordedDescent(Path record, String needle) {
        if (record == null || needle == null || needle.isBlank()) return false;
        String text;
        try {
            text = Files.readString(record);
        } catch (IOException unreadable) {
            return false;
        }
        int total = countOccurrences(text, needle);
        if (total == 0) return false;
        int accounted = countOccurrences(jsonStringField(text, "clonedFrom"), needle);
        for (String store : jsonStringArrayField(text, "parentStores")) {
            accounted += countOccurrences(store, needle);
        }
        return accounted == total;
    }

    static int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) return 0;
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** {@code "key" : "value"} out of a flat JSON object, tolerating spacing. */
    static String jsonStringField(String json, String key) {
        int at = json.indexOf('"' + key + '"');
        if (at < 0) return "";
        int colon = json.indexOf(':', at);
        if (colon < 0) return "";
        int open = json.indexOf('"', colon);
        if (open < 0) return "";
        return unescapeUntilQuote(json, open + 1);
    }

    /** {@code "key" : [ "a", "b" ]} out of a flat JSON object. */
    static List<String> jsonStringArrayField(String json, String key) {
        List<String> out = new ArrayList<>();
        int at = json.indexOf('"' + key + '"');
        if (at < 0) return out;
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open + 1);
        if (open < 0 || close < 0) return out;
        String body = json.substring(open + 1, close);
        for (int i = body.indexOf('"'); i >= 0; i = body.indexOf('"', i + 1)) {
            String value = unescapeUntilQuote(body, i + 1);
            out.add(value);
            i += value.length() + 1;
        }
        return out;
    }

    private static String unescapeUntilQuote(String s, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { sb.append(s.charAt(++i)); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Smuggle {@code needle} into the descent record's TIMESTAMP, and return the
     * original bytes so the caller can put them back.
     *
     * <h2>Why the timestamp, and not a new field</h2>
     *
     * <p>Production names this exact case in its own javadoc — "a field a future
     * version adds, <b>a path smuggled into a timestamp</b>" — so it is the case
     * the rule was written for rather than one invented for a test.
     *
     * <p>It is also the only tamper that leaves the record fully VALID:
     * {@code clonedAt} is a free {@code String}, {@code Descent.usable()} checks
     * only the schema version, so the record still parses and is still believed.
     * An unknown extra field, or malformed JSON, would make
     * {@code HomeProvenance.read} return null and production would refuse for
     * being unreadable rather than for being unaccounted — a control that passes
     * on the wrong branch, which is the failure this whole class is about.
     *
     * @throws IOException if the record is absent, or if the tamper did not
     *         raise the occurrence count — a control that changed nothing must
     *         fail loudly rather than be asserted around
     */
    static byte[] tamperDescentRecord(Path homeRoot, String needle) throws IOException {
        Path record = homeRoot.resolve(DESCENT_RECORD);
        byte[] original = Files.readAllBytes(record);
        String text = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        int before = countOccurrences(text, needle);
        int at = text.indexOf('"' + CLONED_AT + '"');
        if (at < 0) {
            throw new IOException("no " + CLONED_AT + " field to tamper with in " + record);
        }
        int open = text.indexOf('"', text.indexOf(':', at));
        int close = text.indexOf('"', open + 1);
        if (open < 0 || close < 0) throw new IOException("malformed " + CLONED_AT + " in " + record);
        String tampered = text.substring(0, close) + " " + needle + text.substring(close);
        if (countOccurrences(tampered, needle) != before + 1) {
            throw new IOException("the tamper did not add an occurrence of the needle; the "
                    + "control would test nothing: " + record);
        }
        Files.writeString(record, tampered);
        return original;
    }

    /** Put the record back exactly as it was. Call from a {@code finally}. */
    static void restoreDescentRecord(Path homeRoot, byte[] original) throws IOException {
        if (original == null) return;
        Files.write(homeRoot.resolve(DESCENT_RECORD), original);
    }

    /** True when the record is byte-for-byte what {@code original} held. */
    static boolean descentRecordMatches(Path homeRoot, byte[] original) {
        if (original == null) return false;
        try {
            return java.util.Arrays.equals(
                    Files.readAllBytes(homeRoot.resolve(DESCENT_RECORD)), original);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The record's timestamp field — the one free-text field a tamper can hide in. */
    private static final String CLONED_AT = "clonedAt";
}
