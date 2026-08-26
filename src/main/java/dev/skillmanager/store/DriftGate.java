package dev.skillmanager.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.shared.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The change-awareness gate: a pull that moved a unit blocks the next launch
 * until somebody has read what moved.
 *
 * <h2>What this is defending against</h2>
 *
 * <p>An agent reads a skill, then works for twenty minutes on the strength of
 * it. A sync in between replaces that skill. Nothing fails, nothing warns, and
 * the agent keeps following instructions that no longer exist. The only moment
 * at which this can be caught is the boundary between "the home changed" and
 * "something starts using the home", which is a launch.
 *
 * <h2>Why the record survives a refreshed digest</h2>
 *
 * <p>{@link #record} writes the pending drift, and only {@link #acknowledge}
 * removes it. Recording a <em>new</em> digest does not clear a pending one, and
 * that is the whole design.
 *
 * <p>The spec work for this epic proved why. Running the old overwrite policy
 * with only a reporting invariant — "every pass reports exactly the units it held
 * back" — produced "No error has been found", because after an overwrite the unit
 * is no longer modified and both sides of the equivalence go false. The graph work
 * reproduced it: with the hold-back check disabled,
 * {@code prune_did_not_destroy_the_agent_edit} failed while
 * {@code child_home_holds_only_claimed_or_held_back_units} passed
 * <em>vacuously</em>.
 *
 * <p>A gate that cleared itself when the digest was refreshed would have exactly
 * that shape: the second sync recomputes, finds the home consistent with itself,
 * and reports nothing to acknowledge — while the change the first sync made is
 * still unread. So the pending record is a fact about a change that happened, not
 * a statement about the home's current self-consistency, and nothing but an
 * explicit acknowledgement retires it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "detectedAt", "operation", "acknowledged",
        "acknowledgedAt", "report"})
public record DriftGate(
        int schemaVersion,
        String detectedAt,
        String operation,
        boolean acknowledged,
        String acknowledgedAt,
        int surfacedCount,
        DriftReport report
) {

    public static final String FILENAME = "home.drift.json";

    /**
     * 2 since HIS-3 added {@link #surfacedCount}.
     *
     * <p>No migration, and none is needed: a v1 record simply has no field,
     * Jackson yields {@code 0}, and {@code 0} means "not yet surfaced". A home
     * carrying an old record therefore behaves exactly as it did before — full
     * report once, collapsed after — which is the safe direction. The version
     * is bumped anyway so the record says what shape it is, rather than leaving
     * a reader to infer it from a field's absence.
     */
    public static final int SCHEMA_VERSION = 2;

    /** Exit code for a launch refused because drift is unacknowledged. */
    public static final int EXIT_CODE = 8;

    public static Path file(SkillStore store) {
        return store.root().resolve(FILENAME);
    }

    /**
     * Record {@code report} as awaiting acknowledgement.
     *
     * <p>An empty report writes nothing, so a sync that changed nothing does not
     * gate the next launch. A report that arrives while an <em>earlier</em> one is
     * still unacknowledged is merged into it rather than replacing it: the agent
     * has not read either, and dropping the first would lose the change that
     * actually invalidated its knowledge.
     */
    public static Optional<DriftGate> record(SkillStore store, DriftReport report,
                                             String operation) throws IOException {
        if (report == null || report.isEmpty()) return pending(store);
        DriftGate existing = pending(store).orElse(null);
        DriftReport merged = existing == null
                ? report
                : merge(existing.report(), report);
        // THE RESET, and it is defined against the UNION rather than against the
        // record's existence -- because `merge` means "a genuinely new change"
        // and "the same gate again" are the SAME record, differing only in
        // content. A reset keyed on "is there a record" would fire on every
        // pass and nothing would ever collapse; one keyed on nothing at all
        // would leave a real new change opening as a one-line reminder, which
        // is the safety this ticket must not break.
        int surfaced = existing != null && merged.equals(existing.report())
                ? existing.surfacedCount()
                : 0;
        DriftGate gate = new DriftGate(SCHEMA_VERSION, Instant.now().toString(), operation,
                false, null, surfaced, merged);
        Fs.ensureDir(store.root());
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(store).toFile(), gate);
        return Optional.of(gate);
    }

    /**
     * The unacknowledged drift for this home, if any.
     *
     * <p>An acknowledged record reads as absent: it stays on disk as a receipt of
     * what was acknowledged and when, and stops gating anything.
     */
    public static Optional<DriftGate> pending(SkillStore store) {
        return read(store).filter(gate -> !gate.acknowledged());
    }

    public static Optional<DriftGate> read(SkillStore store) {
        Path f = file(store);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            return Optional.ofNullable(mapper().readValue(f.toFile(), DriftGate.class));
        } catch (IOException unreadable) {
            // A record we cannot parse must not read as "nothing to acknowledge":
            // that is the permissive direction, and the whole point of the gate is
            // that the permissive direction is the silent one. Synthesize an
            // unacknowledged record naming the unreadable file.
            // surfacedCount 0: an unreadable record is a thing nobody has read
            // by definition, and collapsing the one report that says the gate
            // itself is broken would be the worst possible place to save lines.
            return Optional.of(new DriftGate(SCHEMA_VERSION, Instant.now().toString(),
                    "unreadable " + FILENAME, false, null, 0,
                    new DriftReport(null, null, List.of())));
        }
    }

    /** Mark the pending drift read. Returns empty when there was none. */
    public static Optional<DriftGate> acknowledge(SkillStore store) throws IOException {
        DriftGate gate = pending(store).orElse(null);
        if (gate == null) return Optional.empty();
        // surfacedCount is NOT carried into the acknowledged receipt, and that
        // is deliberate. An ack retires this gate; the next change writes a
        // fresh one, which must start at 0 or the first sight of something the
        // agent has never seen would be a one-line reminder.
        DriftGate acked = new DriftGate(gate.schemaVersion(), gate.detectedAt(), gate.operation(),
                true, Instant.now().toString(), 0, gate.report());
        Fs.ensureDir(store.root());
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(store).toFile(), acked);
        return Optional.of(acked);
    }

    /**
     * Whether this is the first time the gate has been shown to anybody.
     *
     * <p>The whole point of persisting the count rather than deciding it at the
     * call site: the loop being fixed is {@code exec} refuses -> operator runs
     * {@code home drift} -> {@code exec} again, which is three JVMs. Nothing
     * held in memory can answer this.
     */
    public boolean firstSurfacing() {
        return surfacedCount <= 0;
    }

    /**
     * The one line a second and later surfacing gets in place of the report.
     *
     * <p>It still names the count and still names the remedy, because a
     * reminder that does not say what to do is just noise with fewer
     * characters.
     */
    public String stillUnreadLine(String cliInvocation) {
        return stillUnreadLine(cliInvocation, "");
    }

    /**
     * As above, naming the home the gate is about.
     *
     * <p>{@code home drift} is a CLASS 2 remedy verb (#161): it takes
     * {@code --home}, so that is where the binding goes. The reminder is the
     * only thing a second and later surfacing prints, so a reminder that names
     * no home sends the reader to acknowledge drift in whichever home their
     * shell happens to carry — which unset is the operator's root.
     *
     * @param homeArg {@code --home <path>}, or empty
     */
    public String stillUnreadLine(String cliInvocation, String homeArg) {
        int units = report == null ? 0 : report.units().size();
        String ack = (cliInvocation + " home drift --ack " + homeArg).strip();
        String read = (cliInvocation + " home drift " + homeArg).strip();
        return "%d unit%s still unread — `%s` to clear, `%s` to re-read"
                .formatted(units, units == 1 ? "" : "s", ack, read);
    }

    /**
     * Record that the pending gate has been shown, so the next pass can be
     * briefer. Returns the updated gate, or empty when nothing is pending.
     *
     * <p>A write on what is otherwise a read path, and that is the cost of the
     * decision recorded on issue #213. It is bounded — one small file, once per
     * surfacing — and it fails soft: an IOException here would be an exception
     * on a path whose job was to print a warning, so callers that cannot
     * usefully handle it should let it surface rather than swallowing the gate.
     */
    public static Optional<DriftGate> markSurfaced(SkillStore store) throws IOException {
        DriftGate gate = pending(store).orElse(null);
        if (gate == null) return Optional.empty();
        DriftGate shown = new DriftGate(gate.schemaVersion(), gate.detectedAt(), gate.operation(),
                gate.acknowledged(), gate.acknowledgedAt(), gate.surfacedCount() + 1,
                gate.report());
        Fs.ensureDir(store.root());
        mapper().writerWithDefaultPrettyPrinter().writeValue(file(store).toFile(), shown);
        return Optional.of(shown);
    }

    /**
     * Compute the drift {@code operation} caused, record it, and refresh the
     * baseline. {@code before} is the digest captured ahead of the operation.
     */
    public static Optional<DriftGate> recordSince(SkillStore store, HomeDigest before,
                                                  String operation) throws IOException {
        HomeDigest after = HomeDigest.compute(store);
        DriftReport report = DriftReport.between(before, after);
        // Pending record first, baseline second. A crash between the two leaves a
        // gate with a slightly stale baseline — the next launch is blocked and the
        // next sync re-reports. The other order would leave a refreshed baseline
        // and no record: the change is invisible from then on, and the gate fails
        // open exactly when something went wrong.
        Optional<DriftGate> gate = record(store, report, operation);
        after.write(store);
        return gate;
    }

    private static DriftReport merge(DriftReport older, DriftReport newer) {
        java.util.LinkedHashMap<String, DriftReport.UnitDrift> byName =
                new java.util.LinkedHashMap<>();
        for (DriftReport.UnitDrift unit : older.units()) byName.put(unit.name(), unit);
        for (DriftReport.UnitDrift unit : newer.units()) {
            DriftReport.UnitDrift prior = byName.get(unit.name());
            byName.put(unit.name(), prior == null ? unit : union(prior, unit));
        }
        List<DriftReport.UnitDrift> units = new java.util.ArrayList<>(byName.values());
        units.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new DriftReport(older.from(), newer.to(), units);
    }

    private static DriftReport.UnitDrift union(DriftReport.UnitDrift a, DriftReport.UnitDrift b) {
        // b's digest, not a's: the union describes the unit as it stands NOW,
        // and b is the newer observation. The file lists are unioned because
        // the merge exists to keep an unread change from being dropped; the
        // digest is not a set and the older one describes a tree nobody is
        // standing on any more.
        return new DriftReport.UnitDrift(b.name(), b.kind(), b.change(), b.digest(),
                joined(a.addedFiles(), b.addedFiles()),
                joined(a.removedFiles(), b.removedFiles()),
                joined(a.modifiedFiles(), b.modifiedFiles()));
    }

    private static List<String> joined(List<String> a, List<String> b) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
