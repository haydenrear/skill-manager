package dev.skillmanager.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.bindings.ChildHomeLink;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a copied home records about where it came from — a POINTER to evidence
 * that is re-derived every time the question is asked, never the evidence
 * itself.
 *
 * <h2>Four readers, three answers, and why a flag was deciding</h2>
 *
 * <p>HIS-10 / issue #227. Measured on epic tip {@code 23e35c7}, on ONE cloned
 * home carrying five inherited {@code bin/cli} shims into the operator's root
 * store:
 *
 * <pre>
 *   home clone                        clean — "no path in it reaches another home"
 *   home verify --home &lt;clone&gt;         exit 1 — 5x FOREIGN_HOME
 *   home verify --home … --against …  exit 0 — "5 sanctioned parent-store shim(s)"
 *   sync (CliShimPruner)              PRUNES all five, then re-provisions ~90s
 * </pre>
 *
 * <p>The three answers all come from one question asked of different subjects.
 * {@link ChildHomeLink#isChildOf} asks "is THIS home a child of the home the
 * shim points into", and it is one level deep. The real chain is
 * {@code root -> project -> worktree}: root holds
 * {@code child-homes/<id>/child-home.json} claiming the PROJECT home, so the
 * project's shims into root are sanctioned; a worktree home is a COPY of the
 * project home, a GRANDCHILD, and nothing in the copy recorded that descent.
 * The only thing that made the sanction visible was an operator typing
 * {@code --against}, which is a flag, not a fact.
 *
 * <h2>A POINTER, NOT A GRANT — and the review that forced this shape</h2>
 *
 * <p>The first version of this class STORED the set of parent stores and
 * {@link #sanctions} answered "is {@code S} in that set". <b>That was a hole,
 * and it was measured</b> on review of #228:
 *
 * <pre>
 *   child is NOT a registered child of parent; child/bin/cli/tool -> parent/bin/cli/tool
 *     home verify --home child   -> exit 1, FOREIGN_HOME              [correct]
 *     echo '{"schemaVersion":1,"clonedFrom":"/nowhere",
 *            "parentStores":["&lt;parent&gt;"]}' > child/home.provenance.json
 *     home verify --home child   -> exit 0                            [gate off]
 * </pre>
 *
 * <p>One file, written into the home being judged, naming a source
 * ({@code /nowhere}) that does not exist, switched that home's isolation gate
 * off — and the verdict then PRINTED the forged descent as authoritative.
 *
 * <p>The same shape produced a second defect with no hand-editing at all,
 * through product operations only: revoke the parent's claim — which is exactly
 * what {@code ChildHomeRegistry.delete} does on teardown — and
 * {@code verify project} refuses while {@code verify worktree} passes, on the
 * same shim and the same parent; the pruner deletes it in one and keeps it in
 * the other; and cloning that worktree again RE-MINTS the deleted claim. That is
 * GOAL-one-home-one-answer's own failure mode, recreated on the tier axis by the
 * mechanism meant to close it.
 *
 * <p>So the record names a CHAIN and grants nothing on its own.
 * {@link #sanctions} walks {@code clonedFrom} hop by hop and asks
 * {@link ChildHomeLink#isChildOf} at each one, live. A forged {@code /nowhere}
 * re-derives to nothing. A revoked claim stops re-deriving the instant it is
 * removed, in every tier at once. {@code parentStores} survives only as a
 * SNAPSHOT for reporting and for repair (HIS-13); it is never consulted to
 * decide anything.
 *
 * <h2>What this costs, stated plainly</h2>
 *
 * <p>An earlier draft of this javadoc justified choosing this evidence over the
 * alternatives because it "needs no second home to be present, readable, or
 * even to still exist when the question is asked". <b>Re-derivation gives that
 * up, and that is the trade.</b> The sanction now depends on the chain still
 * being derivable: the terminal hop's claim must still be in the parent's own
 * store, or an intermediate home must still hold materialization records naming
 * it. When it cannot be re-derived, this class SANCTIONS NOTHING — fail closed.
 * The worst case is a worktree whose project home was deleted reverting to
 * pre-HIS-10 behaviour, which is a bad day and not a breach.
 *
 * <p>The common cases are cheaper than that sounds: {@link ChildHomeLink}
 * reads the PARENT's registry, and that record outlives the directory it names,
 * so a recorded ancestor that has been moved or deleted can still re-derive.
 *
 * <h2>What it is still NOT: forgery-proof</h2>
 *
 * <p>Said out loud so nobody reads a guarantee here. A writer inside a home can
 * still name, as its {@code clonedFrom}, some OTHER home that genuinely is a
 * claimed child; the chain re-derives and the sanction holds. What is gone is
 * the arbitrary grant the review measured — a record can no longer name a store
 * and be believed. It must name a live, independently-claimed chain ENDING at
 * that store, and the shim must still be a structural mirror of that store's own
 * entry ({@code HomeCloner.sanctionedParentShim}'s first two conditions).
 *
 * <p>This adds <b>no new class</b> of in-home forgery: {@link ChildHomeLink}
 * already accepts {@code <child>/.materialization/<kind>/<unit>.json} — a file
 * inside the home being judged — as positive evidence. That pre-existing trust
 * is recorded as a follow-up rather than widened here.
 *
 * <h2>The two alternatives, and why neither</h2>
 *
 * <ol>
 *   <li><b>A child-home claim written into the parent</b> at clone time. The
 *       strongest evidence available — but a clone would then WRITE INTO ANOTHER
 *       HOME to record its own descent, the exact class of damage HIS-7 and
 *       HIS-9 exist to stop, and it fails outright when the parent is read-only
 *       or gone.</li>
 *   <li><b>The clone's {@code .materialization} records.</b> Already read by
 *       {@link ChildHomeLink}, and they outlive a harness teardown — but they
 *       describe UNITS, not homes. A worktree home's records name the units'
 *       sources in the home it was materialized from, which for this repository
 *       is the integration checkout, not root. They cannot express "the home I
 *       was copied from was itself a child of X".</li>
 * </ol>
 */
public final class HomeProvenance {

    private HomeProvenance() {}

    /** The record's name at the home root. */
    public static final String FILENAME = "home.provenance.json";

    /** Bumped when a field's MEANING changes; an unknown newer one reads as absent. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * How many {@code clonedFrom} hops a re-derivation walks.
     *
     * <p>Belt and braces beside the cycle guard: the chain is a filesystem
     * relation that a corrupt or hostile record controls, and an unbounded walk
     * over record-chosen paths is a directory-scan amplifier. Real chains are
     * {@code root -> project -> worktree}, which is two hops.
     */
    private static final int MAX_HOPS = 16;

    /**
     * One home's recorded descent.
     *
     * @param clonedFrom   the home this one was copied from. <b>The only field
     *                     that decides anything</b>, and only as the first hop
     *                     of a chain re-derived live — see {@link #sanctions}.
     *                     Must be ABSOLUTE; a relative spelling would resolve
     *                     against the process working directory, which is
     *                     nobody's home
     * @param parentStores the stores that re-derived at clone time. A SNAPSHOT,
     *                     kept for reporting and repair and <b>never consulted
     *                     to grant a sanction</b>. Trusting it is the hole the
     *                     #228 review measured
     */
    public record Descent(int schemaVersion, String clonedFrom, String clonedAt,
                          List<String> parentStores) {

        public Descent {
            parentStores = parentStores == null ? List.of() : List.copyOf(parentStores);
        }

        /** A record this version knows how to read. */
        public boolean usable() { return schemaVersion > 0 && schemaVersion <= SCHEMA_VERSION; }
    }

    // ------------------------------------------------------------- reading

    /** {@code home}'s record, or null when it has none or it cannot be read. */
    public static Descent read(Path home) {
        if (home == null) return null;
        Path file = home.toAbsolutePath().normalize().resolve(FILENAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            Descent descent = mapper().readValue(file.toFile(), Descent.class);
            // A record written by a NEWER skill-manager is not evidence this
            // one may act on: the same rule CHM-2 applies to an unknown
            // materialization mode. Not being able to tell is not a sanction.
            return descent != null && descent.usable() ? descent : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * True when {@code foreign} is re-derivable, RIGHT NOW, as an ancestor store
     * of {@code home} along the descent {@code home} records.
     *
     * <p>Each hop asks {@link ChildHomeLink#isChildOf} — the parent's own
     * registry, or that hop's own materialization records. The recorded
     * {@code parentStores} are not read here at all. See the class javadoc for
     * the forgery and revocation defects that shape this.
     */
    public static boolean sanctions(Path home, Path foreign) {
        if (home == null || foreign == null) return false;
        return rederive(home.toAbsolutePath().normalize(), foreign, new LinkedHashSet<>(), 0);
    }

    private static boolean rederive(Path home, Path foreign, Set<Path> seen, int hops) {
        if (hops >= MAX_HOPS) return false;
        Descent descent = read(home);
        if (descent == null) return false;
        Path from = recordedSource(descent);
        if (from == null) return false;
        // A chain that revisits a home already walked cannot produce new
        // evidence, and unguarded, two records naming each other never return.
        if (!seen.add(realized(from))) return false;
        if (ChildHomeLink.isChildOf(from, foreign)) return true;
        return rederive(from, foreign, seen, hops + 1);
    }

    /**
     * The recorded source as an ABSOLUTE path, or null when the record does not
     * name one this class may act on.
     *
     * <p>Relative is refused rather than resolved. {@link Path#toAbsolutePath}
     * resolves against the process working directory, so one record would name
     * different homes depending on where the command was typed — in the ticket
     * whose second clause is that a verdict must not depend on how a path is
     * spelled.
     */
    private static Path recordedSource(Descent descent) {
        String from = descent.clonedFrom();
        if (from == null || from.isBlank()) return null;
        Path path;
        try {
            path = Path.of(from);
        } catch (RuntimeException notAPath) {
            return null;
        }
        return path.isAbsolute() ? path.normalize() : null;
    }

    /**
     * The recorded stores that STILL re-derive, for reporting.
     *
     * <p>Kept apart from {@link #recordedParentStores} on purpose: a reader has
     * to be able to tell a CLAIM from a FACT, and printing the snapshot as
     * though it were the verdict is what made the forged record read as
     * authoritative.
     */
    public static List<Path> verifiedParentStores(Path home) {
        List<Path> out = new ArrayList<>();
        for (Path candidate : recordedParentStores(home)) {
            if (sanctions(home, candidate)) out.add(candidate);
        }
        return List.copyOf(out);
    }

    /** The stores the record NAMES, re-derived or not. A claim, not a fact. */
    public static List<Path> recordedParentStores(Path home) {
        Descent descent = read(home);
        if (descent == null) return List.of();
        List<Path> out = new ArrayList<>();
        for (String recorded : descent.parentStores()) {
            if (recorded != null && !recorded.isBlank()) out.add(Path.of(recorded));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------- writing

    /**
     * Write {@code dst}'s record of descending from {@code src}, returning it.
     *
     * <p>Called by {@link HomeCloner} once per clone, BEFORE the copy is
     * verified and before its drift baseline is taken — so the copy's own
     * {@code home clone} verdict already reads the record every later reader
     * will read, and the baseline describes the bytes the home actually holds.
     */
    public static Descent recordDescent(Path src, Path dst) throws IOException {
        Path source = src.toAbsolutePath().normalize();
        Path dest = dst.toAbsolutePath().normalize();
        List<String> stores = new ArrayList<>();
        for (Path store : parentStoresOf(source)) stores.add(store.toString());
        Descent descent = new Descent(SCHEMA_VERSION, source.toString(),
                Instant.now().toString(), stores);
        write(dest, descent);
        return descent;
    }

    /** Write {@code descent} into {@code home}, replacing any record there. */
    public static void write(Path home, Descent descent) throws IOException {
        Path file = home.toAbsolutePath().normalize().resolve(FILENAME);
        Files.createDirectories(file.getParent());
        mapper().writeValue(file.toFile(), descent);
    }

    /**
     * The stores {@code src} can show, TODAY, that it legitimately shares.
     *
     * <p>Every entry is derived live, and the source's own recorded set is
     * deliberately NOT copied forward. Copying it was the second half of the
     * review's finding: a revoked parent claim stayed true forever, because each
     * clone carried the previous clone's assertion, so cloning RE-MINTED a claim
     * that had been deleted.
     *
     * <p>Candidates come from {@code src}'s own shim entries — a store is only
     * interesting here if something in {@code src} actually reaches it — and
     * each must pass {@link ChildHomeLink#isChildOf} directly or
     * {@link #sanctions}, which re-derives {@code src}'s own chain. Both are the
     * evidence {@code home verify} already sanctions {@code src}'s shims on, so
     * this records a reason that exists rather than inventing one.
     *
     * <p>Note this is a SNAPSHOT of that derivation, written for reporting. It
     * is not what a later reader trusts, so a stale entry here misleads a human
     * at worst; it cannot grant anything.
     */
    static List<Path> parentStoresOf(Path src) {
        Set<Path> out = new LinkedHashSet<>();
        Path srcReal = realized(src);
        for (Path shim : shimEntries(src)) {
            Path foreign = HomeCloner.foreignHomeReachedBy(shim, srcReal);
            if (foreign == null) continue;
            Path key = realized(foreign);
            if (out.contains(key)) continue;
            if (ChildHomeLink.isChildOf(src, foreign) || sanctions(src, foreign)) out.add(key);
        }
        return List.copyOf(out);
    }

    /**
     * Every entry in {@code home}'s two shim directories.
     *
     * <p>{@code bin/cli} and {@code bin/mcp}, one segment deep — the same scope
     * {@code HomeCloner.SHIM_DIRS} sanctions, because a record naming homes
     * reached from anywhere else would grant sanction in places the isolation
     * rule refuses.
     */
    private static List<Path> shimEntries(Path home) {
        List<Path> out = new ArrayList<>();
        for (String dir : List.of("bin/cli", "bin/mcp")) {
            Path root = home.resolve(dir);
            if (!Files.isDirectory(root)) continue;
            try (var stream = Files.list(root)) {
                for (Path entry : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                    out.add(entry);
                }
            } catch (IOException cannotList) {
                Log.detail("provenance: could not list %s (%s)", root, cannotList.getMessage());
            }
        }
        return out;
    }

    // ------------------------------------------------------- the leak check

    /** True when {@code rel} is the provenance record at a home's root. */
    public static boolean isProvenanceRecord(String rel) {
        if (rel == null) return false;
        return FILENAME.equals(rel.replace(java.io.File.separatorChar, '/'));
    }

    /**
     * True when every mention of {@code needle} in this record is accounted for
     * by the descent it declares.
     *
     * <p>The provenance record is the one file in a copy whose PURPOSE is to
     * name the home the copy was made from, so the isolation rule's "nothing
     * here may name the source" would refuse the very evidence it now reads. The
     * exemption is therefore not "this filename is trusted": it is the same
     * byte-accounting {@code mentionIsOnlyDiagnostic} performs. The raw
     * occurrences must be covered exactly by the parsed {@code clonedFrom} and
     * {@code parentStores} fields; one occurrence anywhere else — a field a
     * future version adds, a path smuggled into a timestamp — leaves this false
     * and the finding stays a hard leak.
     *
     * <p>Naming the source here is also not an instruction to touch it. Nothing
     * writes through {@code clonedFrom}; it is read only in order to ask another
     * home a question. Contrast a projection ledger row, which {@code unbind}
     * executes.
     */
    public static boolean mentionsOnlyRecordedDescent(Path file, String needle) {
        if (file == null || needle == null || needle.isBlank()) return false;
        Descent descent = read(file.getParent());
        if (descent == null) return false;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int total = countOccurrences(text, needle);
            if (total == 0) return false;
            int accounted = countOccurrences(descent.clonedFrom(), needle);
            for (String store : descent.parentStores()) {
                accounted += countOccurrences(store, needle);
            }
            return accounted == total;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    // ------------------------------------------------------------ plumbing

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) return 0;
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * {@code path} with its deepest EXISTING ancestor resolved and the missing
     * tail re-appended — {@link ChildHomeLink}'s rule, for its reason: a
     * recorded store that has since been deleted is still what this home
     * descends from, so a missing directory must not turn into "not my parent".
     */
    private static Path realized(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        java.util.Deque<Path> tail = new java.util.ArrayDeque<>();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) tail.addFirst(name);
            existing = existing.getParent();
        }
        if (existing == null) return abs;
        Path out;
        try {
            out = existing.toRealPath();
        } catch (IOException | RuntimeException notResolvable) {
            out = existing;
        }
        for (Path segment : tail) out = out.resolve(segment);
        return out;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
