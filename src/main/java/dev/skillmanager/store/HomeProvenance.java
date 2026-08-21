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
 * What a copied home records about where it came from — the one durable fact
 * every reader of "is this foreign path sanctioned" answers from.
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
 * HIS-7 made the clone <em>call</em> pass its {@code srcRoot} so the copy could
 * inherit its source's sanction, which works — inside that one call. Afterwards
 * the only way to make the sanction visible again was an operator typing
 * {@code --against}, which is a flag, not a fact.
 *
 * <h2>The evidence chosen, and why this one</h2>
 *
 * <p>Three candidates were available:
 *
 * <ol>
 *   <li><b>A child-home claim written into the parent</b> at clone time. Rejected:
 *       a clone would then WRITE INTO ANOTHER HOME to record its own descent —
 *       the exact class of damage HIS-7 and HIS-9 exist to stop, and it fails
 *       outright when the parent is read-only or gone.</li>
 *   <li><b>The clone's {@code .materialization} records.</b> Already read by
 *       {@link ChildHomeLink}, and they do outlive a harness teardown — but they
 *       describe UNITS, not homes. A worktree home's records name the units'
 *       sources in the home it was materialized from, which for this repository
 *       is the integration checkout, not root. They cannot express "the home I
 *       was copied from was itself a child of X".</li>
 *   <li><b>An explicit ancestry record in the copy</b> — this. It is written by
 *       the operation that creates the relationship ({@code home clone}), it
 *       lives in the home the question is asked about, and it needs no second
 *       home to be present, readable, or even to still exist when the question
 *       is asked.</li>
 * </ol>
 *
 * <p><b>Absent or stale is not "sanctioned".</b> A home with no record grants
 * nothing: {@link #sanctions} returns false, and the pre-existing
 * {@link ChildHomeLink} evidence is the only thing left, exactly as before. A
 * record naming a store that no longer exists still answers — it is a statement
 * about descent, not about what is on disk today — but a shim into a home the
 * record does not name is still a leak. That asymmetry is deliberate: a
 * downgrade needs positive evidence, and this file is the positive evidence.
 *
 * <h2>Why cloning is still not a laundering step</h2>
 *
 * <p>{@link #parentStoresOf} derives the recorded set from the SOURCE, and only
 * from evidence the source already had: a foreign home reached by one of the
 * source's own shims is recorded only when {@link ChildHomeLink#isChildOf} says
 * the source is genuinely that home's child, or when the source's OWN record
 * already named it (which is what makes the relation transitive down a chain of
 * copies). A home whose shims are unsanctioned records an empty set, so its copy
 * is unsanctioned too — asserted by {@code ChildHomeShimIsolationTest}'s
 * laundering case, which this must keep passing.
 */
public final class HomeProvenance {

    private HomeProvenance() {}

    /** The record's name at the home root. */
    public static final String FILENAME = "home.provenance.json";

    /** Bumped when a field's MEANING changes; an unknown newer one reads as absent. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * One home's recorded descent.
     *
     * @param clonedFrom   the home this one was copied from, as it was spelled
     *                     at clone time. Informational: nothing resolves it and
     *                     nothing writes through it — see the class javadoc for
     *                     why it is not what grants the sanction
     * @param parentStores the homes whose provisioned artifacts this home
     *                     legitimately shares. THIS is what every reader
     *                     answers from
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
            // one may act on: same rule CHM-2 applies to an unknown
            // materialization mode. Not being able to tell is not a sanction.
            return descent != null && descent.usable() ? descent : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * True when {@code home}'s recorded descent names {@code foreign} as a store
     * whose artifacts it shares.
     *
     * <p>Compared on the resolved spelling of both sides. {@code foreign}
     * arrives from {@link HomeCloner#foreignHomeReachedBy}, which derives it
     * from {@link Path#toRealPath}; the recorded side is written realized for
     * the same reason. The whole of #206 is what happens when two spellings of
     * one path are compared as text.
     */
    public static boolean sanctions(Path home, Path foreign) {
        if (home == null || foreign == null) return false;
        Descent descent = read(home);
        if (descent == null) return false;
        Path target = realized(foreign);
        for (String recorded : descent.parentStores()) {
            if (recorded == null || recorded.isBlank()) continue;
            if (realized(Path.of(recorded)).equals(target)) return true;
        }
        return false;
    }

    /** The parent stores {@code home} records, resolved, for reporting. */
    public static List<Path> parentStores(Path home) {
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
     * The homes {@code src} may pass on as parent stores.
     *
     * <p>Two sources, unioned, and NEITHER of them is "any home a shim happens
     * to reach":
     *
     * <ol>
     *   <li>whatever {@code src}'s own record already names — this is what makes
     *       the relation transitive, so {@code root -> project -> wt1 -> wt2}
     *       keeps working past the second copy;</li>
     *   <li>every foreign home reached by one of {@code src}'s own shim entries
     *       that {@link ChildHomeLink#isChildOf} confirms {@code src} is a child
     *       of. That is the same evidence {@code home verify} already sanctions
     *       {@code src}'s shims on, so this records a reason that already
     *       existed rather than inventing one.</li>
     * </ol>
     */
    static List<Path> parentStoresOf(Path src) {
        Set<Path> out = new LinkedHashSet<>();
        Descent inherited = read(src);
        if (inherited != null) {
            for (String recorded : inherited.parentStores()) {
                if (recorded == null || recorded.isBlank()) continue;
                out.add(realized(Path.of(recorded)));
            }
        }
        Path srcReal = realized(src);
        for (Path shim : shimEntries(src)) {
            Path foreign = HomeCloner.foreignHomeReachedBy(shim, srcReal);
            if (foreign == null) continue;
            if (out.contains(realized(foreign))) continue;
            if (ChildHomeLink.isChildOf(src, foreign)) out.add(realized(foreign));
        }
        return List.copyOf(out);
    }

    /**
     * Every entry in {@code home}'s two shim directories.
     *
     * <p>{@code bin/cli} and {@code bin/mcp}, one segment deep — the same scope
     * {@code HomeCloner.SHIM_DIRS} sanctions, because a record that named homes
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
     * name the home the copy was made from, so the isolation rule's
     * "nothing here may name the source" would refuse the very evidence it now
     * reads. The exemption is therefore not "this filename is trusted": it is
     * the same byte-accounting {@code mentionIsOnlyDiagnostic} performs. The raw
     * occurrences must be covered exactly by the parsed {@code clonedFrom} and
     * {@code parentStores} fields; one occurrence anywhere else — a field a
     * future version adds, a path smuggled into a timestamp — leaves this false
     * and the finding stays a hard leak.
     *
     * <p>Naming the source here is also not an instruction to touch it. Nothing
     * resolves {@code clonedFrom}, nothing writes through it, and
     * {@link #sanctions} reads only {@code parentStores}. Contrast a projection
     * ledger row, which {@code unbind} executes.
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
