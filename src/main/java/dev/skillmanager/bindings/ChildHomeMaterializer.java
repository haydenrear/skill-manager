package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single implementation of "put parent-store unit {@code X} into child home
 * {@code Y}", shared by {@link ChildHomeHarnessInstaller} and the project
 * child home scaffolder. Both write into the same {@code <dir>/.skill-manager}
 * layout, so they must agree on how units get there.
 *
 * <h2>What {@link MaterializationMode#COPY} guarantees</h2>
 *
 * <p>The child home gets an independent tree: no entry in it is a symlink at
 * the parent store, and symlinks <em>inside</em> the copied unit whose target
 * resolves back into the parent store are dereferenced into real content. So
 * ordinary edits an agent makes below the child unit directory cannot reach
 * the parent store.
 *
 * <p>Three deliberate exceptions, none of which links back into the store:
 * symlinks pointing outside the parent store entirely (a checkout elsewhere on
 * disk, {@code /usr/local/...}) are preserved verbatim, because rewriting them
 * would break the tools they point at — writes through those still land
 * wherever they always pointed; relative symlinks that stay inside the unit are
 * preserved because inside the copy they resolve inside the copy; and a symlink
 * cycle inside the parent store (or one nested past
 * {@link #MAX_DEREFERENCE_DEPTH}) is expanded until the repeat is detected —
 * so a self-referential link yields one level of duplicated content and the
 * repeating link is then dropped with a warning, never recreated as a link
 * into the store. Cyclic content is bounded, not reproduced faithfully.
 *
 * <h2>Local modifications are never overwritten</h2>
 *
 * <p>Each COPY writes a materialization record under
 * {@code <child home>/.materialization/<kind>/<name>.json} holding the mode,
 * the digest of the materialized view of the parent tree, and the digest of the
 * tree that was written. A later materialization recomputes the digest of what
 * is on disk: if it no longer matches the recorded one, the unit has been
 * edited locally and is left completely alone and reported as
 * {@link Status#SKIPPED_LOCAL_CHANGES}. A directory with no usable record is
 * treated the same way unless it is byte-identical to what would be written,
 * since there is no evidence about who put it there.
 *
 * <p>The recorded source digest is taken over the <em>materialized view</em>
 * (links into the store replaced by their content), not over the raw source
 * tree. A raw digest would hash a store link as its target string, so an
 * upgrade of the unit it points at would never be noticed and the child copy
 * would keep stale dereferenced content forever.
 *
 * <h2>Reconciling two homes is the same operation</h2>
 *
 * <p>{@link #planSync} and {@link #applySync} reconcile one unit from the
 * "parent" store into the "child" one in whichever direction the caller names
 * — which is what {@code home sync} needs to push a ticket worktree's edits
 * back up into the project home it was cloned from. That deliberately reuses
 * everything above rather than adding a second copier: the hold-back rule, the
 * record, the staging area and the atomic swap are the same, because "do not
 * destroy an edit somebody made in this tree" is the same requirement whether
 * the tree is being refreshed from a store or reconciled against a sibling
 * home. The only thing reconciliation adds is what to do when <em>both</em>
 * sides moved, which a downward materialization never had to answer: with
 * {@code merge} the recorded per-file baseline decides each path, and any path
 * both sides changed is reported as a conflict rather than resolved.
 */
public final class ChildHomeMaterializer {

    /** Directory (under the child home root) holding per-unit records. */
    public static final String RECORDS_DIR = ".materialization";

    /** Staging + displaced-tree area; swept before and after every run. */
    private static final String STAGING_DIR = "tmp";

    private static final int RECORD_SCHEMA_VERSION = 1;

    /** Guard against pathological symlink graphs while dereferencing. */
    private static final int MAX_DEREFERENCE_DEPTH = 32;

    public enum Status {
        /** The child unit was (re)written from the parent store. */
        MATERIALIZED,
        /** The child unit already matched the parent; nothing was written. */
        UNCHANGED,
        /** The child unit has local edits and was deliberately left alone. */
        SKIPPED_LOCAL_CHANGES
    }

    public record UnitOutcome(
            String unitName,
            UnitKind unitKind,
            Status status,
            Path childPath,
            String detail
    ) {
        public boolean heldBack() { return status == Status.SKIPPED_LOCAL_CHANGES; }

        public String label() {
            return unitKind.name().toLowerCase() + ":" + unitName;
        }
    }

    /**
     * On-disk provenance for one materialized unit.
     *
     * <p>{@code mode} is stored as a plain string rather than an enum so a
     * record written by a newer skill-manager is readable here: an unrecognized
     * mode simply means "no usable baseline", which routes to the conservative
     * skip-and-report path instead of a parse failure.
     *
     * <p>{@code sourceRevision} carries the git revision for
     * {@link MaterializationMode#CHECKOUT} units — the commit the checkout was
     * materialized at, which is how a later pass tells "still exactly what we
     * cloned" from "the agent has committed something here".
     *
     * <p>{@code entryDigests} is the same baseline as {@code contentDigest},
     * broken out per file. The whole-tree digest can only answer "did this unit
     * change"; a three-way reconciliation between two homes has to answer
     * "<em>which files</em> did each side change", and that is the difference
     * between merging two disjoint edits and declaring the whole unit
     * conflicted. It is written for {@link MaterializationMode#COPY} only —
     * a checkout's baseline is its git history, which already answers this
     * better than any digest map could. Absent (an older record, an
     * interrupted write) it reads as null, which routes the merge to the same
     * conservative refusal a missing record already gets.
     *
     * <p>{@code reconcileKind} distinguishes a tree that was copied wholesale
     * from one that is the <em>result of a merge</em>. They are not
     * interchangeable: a pristine copy may be overwritten the moment its source
     * moves, because nothing in it is unique. A merge result carries local work
     * that exists nowhere else, so it must be merged again rather than
     * overwritten — and it looks exactly like a pristine copy to a digest
     * comparison against its own record.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterializationRecord(
            int schemaVersion,
            String unitName,
            String unitKind,
            String mode,
            String source,
            String sourceRevision,
            String sourceDigest,
            String contentDigest,
            String materializedAt,
            java.util.Map<String, String> entryDigests,
            String reconcileKind
    ) {
        /** A tree written by copying a source wholesale. */
        public static final String COPIED = "copy";

        /** A tree produced by three-way merging a source into local work. */
        public static final String MERGED = "merge";

        /** Pre-{@code entryDigests} shape, kept so older call sites still read. */
        public MaterializationRecord(int schemaVersion, String unitName, String unitKind,
                                     String mode, String source, String sourceRevision,
                                     String sourceDigest, String contentDigest,
                                     String materializedAt) {
            this(schemaVersion, unitName, unitKind, mode, source, sourceRevision,
                    sourceDigest, contentDigest, materializedAt, null, null);
        }

        public boolean isMergeResult() { return MERGED.equals(reconcileKind); }
    }

    private final SkillStore parentStore;
    private final SkillStore childStore;
    private final Path parentRootReal;

    public ChildHomeMaterializer(SkillStore parentStore, SkillStore childStore) {
        this.parentStore = parentStore;
        this.childStore = childStore;
        this.parentRootReal = realOrNormalized(parentStore.root());
    }

    // ------------------------------------------------------------- units

    /**
     * Materializes one parent-store unit into the child home.
     *
     * @return what happened, including whether the unit was held back because
     *         it carries local edits.
     */
    public UnitOutcome materializeUnit(String name, UnitKind kind, MaterializationMode mode)
            throws IOException {
        Path source = parentStore.unitDir(name, kind).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("parent unit directory missing for " + kind
                    + ":" + name + " at " + source);
        }
        Path dest = childStore.unitDir(name, kind).toAbsolutePath().normalize();
        return switch (effectiveMode(name, kind, mode)) {
            case COPY -> copyUnit(name, kind, source, dest);
            case CHECKOUT -> checkoutUnit(name, kind, source, dest);
            case LINK -> linkUnit(name, kind, source, dest);
        };
    }

    /**
     * The mode this pass will actually use for {@code name}, which is not always
     * the one it was asked for.
     *
     * <p>A recorded {@link MaterializationMode#CHECKOUT} wins over any requested
     * mode. That single rule is what keeps a checkout from being destroyed by an
     * ordinary resolve: the caller's default is a property of the <em>project</em>
     * ("copy units into the child home"), while the checkout is a property of
     * <em>one unit</em> that some agent asked for so it could commit and push.
     * Letting the project-wide default win would delete a tree that may hold
     * unpushed commits, and the deletion would look like routine reconciliation.
     *
     * <p>The other direction needs no rule: nothing recorded as COPY or LINK is
     * silently promoted, because promotion is what the caller asked for.
     */
    private MaterializationMode effectiveMode(String name, UnitKind kind,
                                              MaterializationMode requested) {
        MaterializationMode fallback = requested == null ? MaterializationMode.LINK : requested;
        MaterializationRecord record = readRecord(name, kind).orElse(null);
        if (record == null) return fallback;
        if (!MaterializationMode.CHECKOUT.name().equals(record.mode())) return fallback;
        // A recorded checkout whose directory is gone (deleted on purpose, per
        // the documented way to leave the mode) is no longer a checkout.
        Path dest = childStore.unitDir(name, kind).toAbsolutePath().normalize();
        if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return fallback;
        return MaterializationMode.CHECKOUT;
    }

    /**
     * Reports whether one child-home unit currently differs from the tree that
     * was materialized into it. Units with no usable record are reported as
     * modified: without provenance there is no evidence they are disposable.
     */
    public boolean isLocallyModified(String name, UnitKind kind) throws IOException {
        Path dest = childStore.unitDir(name, kind).toAbsolutePath().normalize();
        if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return false;
        MaterializationRecord record = readRecord(name, kind).orElse(null);
        if (record != null && MaterializationMode.CHECKOUT.name().equals(record.mode())) {
            return checkoutIsModified(dest, record);
        }
        String baseline = copyBaseline(record);
        if (baseline == null) return true;
        return !baseline.equals(treeDigest(dest));
    }

    /**
     * Whether a {@code CHECKOUT} unit carries work the parent store does not
     * have.
     *
     * <p>A content digest is the wrong question for a checkout: {@code .git}
     * churns on every command, so a digest comparison would report a checkout as
     * modified permanently and for no reason. Git already tracks this exactly —
     * an uncommitted worktree change, or a HEAD that has moved off the revision
     * the materialization recorded, means there is something here that would be
     * lost.
     *
     * <p>A checkout git cannot answer for (no {@code git} on PATH, a
     * {@code .git} that was removed) is reported as modified. That is the same
     * asymmetry the no-record case takes: without evidence that a tree is
     * disposable, it is not disposable.
     */
    private static boolean checkoutIsModified(Path dest, MaterializationRecord record) {
        if (!GitOps.isAvailable() || !GitOps.isGitRepo(dest)) return true;
        if (GitOps.hasWorktreeChanges(dest)) return true;
        String recorded = record.sourceRevision();
        if (recorded == null || recorded.isBlank()) return true;
        String head = GitOps.headHash(dest);
        return head == null || !head.equals(recorded);
    }

    /**
     * Every child-home unit that {@link #isLocallyModified(String, UnitKind)}
     * would refuse to overwrite — the units a teardown must leave alone.
     *
     * <p>Enumerated by walking the child home's unit directories, not the
     * records directory, and answered by the same predicate a refresh uses.
     * Driving this off the records would silently disagree with the refresh
     * for exactly the units that have no usable record — a child home from
     * before records existed, or one whose record write was interrupted —
     * and those are the ones a teardown must be most careful with.
     */
    public List<UnitOutcome> locallyModifiedUnits() throws IOException {
        List<UnitOutcome> out = new ArrayList<>();
        for (UnitRef ref : unitDirectories(childStore)) {
            if (!isLocallyModified(ref.name(), ref.kind())) continue;
            out.add(new UnitOutcome(ref.name(), ref.kind(), Status.SKIPPED_LOCAL_CHANGES,
                    childStore.unitDir(ref.name(), ref.kind()).toAbsolutePath().normalize(),
                    "local changes in the child home"));
        }
        return out;
    }

    /** One unit directory in a home, named the way the store names it. */
    public record UnitRef(String name, UnitKind kind) implements Comparable<UnitRef> {
        public String label() { return kind.name().toLowerCase() + ":" + name; }

        @Override
        public int compareTo(UnitRef other) {
            int byKind = kind.compareTo(other.kind);
            return byKind != 0 ? byKind : name.compareTo(other.name);
        }
    }

    /**
     * Every unit <em>directory</em> in a home, of every kind.
     *
     * <p>Deliberately not {@code listInstalledUnits()}: that parses each unit
     * and drops the ones that do not load. A unit an agent has half-edited is
     * exactly the one whose {@code SKILL.md} may not parse yet, and it is
     * exactly the one a close-out must not silently omit — "it did not appear
     * in the list" and "there was nothing to lose" are the same report to
     * whoever reads it, and only one of them is true.
     */
    public static List<UnitRef> unitDirectories(SkillStore store) throws IOException {
        List<UnitRef> out = new ArrayList<>();
        for (UnitKind kind : UnitKind.values()) {
            Path kindDir = unitRootOf(store, kind);
            if (kindDir == null || !Files.isDirectory(kindDir, LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path unitDir : listSorted(kindDir)) {
                if (Files.isSymbolicLink(unitDir)
                        || !Files.isDirectory(unitDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                out.add(new UnitRef(unitDir.getFileName().toString(), kind));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Record each unrecorded unit in {@code home} as its own baseline.
     *
     * <p>What this is for: a home produced by copying another one — {@code home
     * clone} — has the bytes but no provenance, and provenance is the only
     * thing that makes an edit inside it recoverable later. Without it, the
     * first reconciliation back into the home it came from sees two trees that
     * differ, no common ancestor, and has to report a conflict on the whole
     * unit — even when only one side ever moved. The clone <em>is</em> the
     * common ancestor; this writes that down while it is still true.
     *
     * <p>Records written here carry no {@code source} path on purpose. The
     * path would name the home this one was copied from, and a copied home that
     * names its origin is exactly the leak class {@code home verify} exists to
     * catch. The digests say everything the merge needs; the path said nothing
     * it uses.
     *
     * <p>Never overwrites an existing record — a copied home may have inherited
     * real ones, including a {@code CHECKOUT} whose baseline is its git history
     * and would be destroyed by being restated as a tree digest.
     */
    public static List<UnitRef> adoptUnrecordedUnits(SkillStore home) throws IOException {
        ChildHomeMaterializer materializer = new ChildHomeMaterializer(home, home);
        List<UnitRef> adopted = new ArrayList<>();
        for (UnitRef ref : unitDirectories(home)) {
            if (materializer.readRecord(ref.name(), ref.kind()).isPresent()) continue;
            Path dir = home.unitDir(ref.name(), ref.kind()).toAbsolutePath().normalize();
            Fingerprint print = fingerprint(dir);
            materializer.writeRecord(ref.name(), ref.kind(), MaterializationMode.COPY, null, null,
                    print.digest(), print.digest(), print.entries(),
                    MaterializationRecord.COPIED);
            adopted.add(ref);
        }
        return adopted;
    }

    // ------------------------------------------------- home-to-home reconcile

    /** What reconciling one unit from the source home into this one did, or would do. */
    public enum SyncStatus {
        /** The destination already holds what the source holds. */
        UNCHANGED,
        /** The destination was a pristine copy and was refreshed from the source. */
        UPDATED,
        /** The destination carries local work and was left exactly as it was. */
        HELD_BACK,
        /** Both sides moved on disjoint files; the two edits were folded together. */
        MERGED,
        /** Both sides changed the same file. Nothing was written; a human decides. */
        CONFLICTED,
        /** The source has this unit and the destination does not. */
        NEW,
        /** The destination has this unit and the source does not. Never deleted here. */
        REMOVED_UPSTREAM
    }

    public record UnitSync(
            String unitName,
            UnitKind unitKind,
            SyncStatus status,
            Path destPath,
            List<String> files,
            List<String> conflicts,
            String detail
    ) {
        public UnitSync {
            files = files == null ? List.of() : List.copyOf(files);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public String label() { return unitKind.name().toLowerCase() + ":" + unitName; }

        /** Did (or would) this outcome write to the destination unit? */
        public boolean writes() {
            return status == SyncStatus.UPDATED || status == SyncStatus.MERGED
                    || status == SyncStatus.NEW;
        }

        /** Is this an outcome a close-out must refuse on? */
        public boolean unresolved() {
            return status == SyncStatus.HELD_BACK || status == SyncStatus.CONFLICTED;
        }
    }

    /**
     * What reconciling {@code name} from the source home into this one would do.
     * Computes every digest the real run computes and writes nothing at all —
     * not even into the staging area, which lives inside the destination home.
     */
    public UnitSync planSync(String name, UnitKind kind, boolean merge) throws IOException {
        return reconcile(name, kind, merge, false);
    }

    /** {@link #planSync} plus the write it describes, staged and swapped in atomically. */
    public UnitSync applySync(String name, UnitKind kind, boolean merge) throws IOException {
        return reconcile(name, kind, merge, true);
    }

    /**
     * The whole decision, in one place, for both the dry run and the real one.
     *
     * <p>Splitting "what would happen" from "make it happen" into two functions
     * is how a {@code --dry-run} comes to report something the real run does not
     * do. There is one decision here and {@code apply} only chooses whether to
     * act on it.
     */
    private UnitSync reconcile(String name, UnitKind kind, boolean merge, boolean apply)
            throws IOException {
        Path source = parentStore.unitDir(name, kind).toAbsolutePath().normalize();
        Path dest = childStore.unitDir(name, kind).toAbsolutePath().normalize();
        boolean destIsDir = Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS);

        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return new UnitSync(name, kind, SyncStatus.REMOVED_UPSTREAM, dest, List.of(), List.of(),
                    "not in the source home; left in place — deleting a unit is not what a sync is for");
        }
        if (destIsDir && sameRealPath(source, dest)) {
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "source and destination are the same directory");
        }

        MaterializationRecord record = readRecord(name, kind).orElse(null);
        if (destIsDir && record != null
                && MaterializationMode.CHECKOUT.name().equals(record.mode())) {
            // A checkout's history is the thing of value in it, and a tree copy
            // cannot carry history. Overwriting one would destroy commits that
            // may exist nowhere else; merging into one is `project sync`'s job.
            return new UnitSync(name, kind, SyncStatus.HELD_BACK, dest, List.of(), List.of(),
                    "materialized as a git checkout — send its commits home with "
                            + "`skill-manager unit publish " + name + "`, not with a file copy");
        }

        List<ViewEntry> view = materializedView(source);
        Fingerprint src = fingerprintOf(view, java.util.Set.of());

        if (!destIsDir) {
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                return new UnitSync(name, kind, SyncStatus.HELD_BACK, dest, List.of(), List.of(),
                        "a file or symlink already occupies " + dest + "; not replaced");
            }
            if (apply) writeCopy(name, kind, view, source, dest, src);
            return new UnitSync(name, kind, SyncStatus.NEW, dest,
                    List.copyOf(src.entries().keySet()), List.of(),
                    "not in the destination home; copied from the source");
        }

        Fingerprint dst = fingerprint(dest);
        if (src.digest().equals(dst.digest())) {
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "already byte-identical to the source");
        }

        String baseline = copyBaseline(record);
        boolean destUntouched = baseline != null && baseline.equals(dst.digest());
        if (destUntouched && src.digest().equals(record.sourceDigest())) {
            // The destination differs from the source only by work a previous
            // --merge folded in, and the source has not moved since. Copying the
            // source over it now would delete that work for no gain.
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "the source has not moved since the last reconcile");
        }
        if (destUntouched && !record.isMergeResult()) {
            if (apply) writeCopy(name, kind, view, source, dest, src);
            return new UnitSync(name, kind, SyncStatus.UPDATED, dest,
                    changedFiles(dst.entries(), src.entries()), List.of(),
                    "refreshed from the source; the destination held no local work");
        }

        // Everything below: the destination carries something the source does not.
        if (!merge) {
            return new UnitSync(name, kind, SyncStatus.HELD_BACK, dest, List.of(), List.of(),
                    baseline == null
                            ? "no usable materialization record, so its contents cannot be shown to be "
                                    + "disposable; re-run with --merge to reconcile it"
                            : "locally modified; re-run with --merge to three-way merge it");
        }
        java.util.Map<String, String> base = mergeBase(name, kind, record);
        if (base == null || base.isEmpty()) {
            return new UnitSync(name, kind, SyncStatus.CONFLICTED, dest, List.of(),
                    changedFiles(dst.entries(), src.entries()),
                    "both sides differ and no per-file baseline was recorded for this unit, so "
                            + "there is no merge base; resolve it by hand or publish the edit with "
                            + "`skill-manager unit publish " + name + "`");
        }

        MergePlan plan = mergePlan(base, src.entries(), dst.entries());
        if (!plan.conflicts().isEmpty()) {
            return new UnitSync(name, kind, SyncStatus.CONFLICTED, dest, List.of(),
                    plan.conflicts(),
                    plan.conflicts().size() + " file(s) changed on both sides; nothing was written");
        }
        if (plan.take().isEmpty()) {
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "the destination is ahead of the source; the source has nothing to contribute");
        }
        if (apply) {
            List<ViewEntry> merged = mergedView(view, dest, plan.take());
            Path staged = stage(merged, name, kind);
            try {
                Fingerprint stagedPrint = fingerprint(staged);
                swapIn(staged, dest);
                writeCopyRecord(name, kind, source, stagedPrint, src.digest(),
                        MaterializationRecord.MERGED);
            } finally {
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
            }
        }
        return new UnitSync(name, kind, SyncStatus.MERGED, dest,
                List.copyOf(plan.take()), List.of(),
                plan.take().size() + " file(s) taken from the source; local work kept");
    }

    /** Stage the source view and swap it into place — the copy path, atomically. */
    private void writeCopy(String name, UnitKind kind, List<ViewEntry> view, Path source, Path dest,
                           Fingerprint src) throws IOException {
        Path staged = stage(view, name, kind);
        try {
            Fingerprint stagedPrint = fingerprint(staged);
            swapIn(staged, dest);
            writeCopyRecord(name, kind, source, stagedPrint, src.digest(),
                    MaterializationRecord.COPIED);
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
        }
    }

    /**
     * The per-file common ancestor of the two homes' copies of one unit.
     *
     * <p>The destination's own record first: it says what the destination was
     * handed, which is the ancestor of anything the destination has since done
     * to it. When the destination has none — a home whose units were installed
     * rather than materialized — the source's record is used instead, because
     * a home produced by copying this one records, at adoption time, exactly
     * the content the two homes last shared. That is the case the whole return
     * path depends on: a ticket worktree is a clone, and its clone-time record
     * is the only surviving witness to what the project home held when it was
     * made.
     *
     * <p>When both exist the destination's is preferred even though the
     * source's may be the tighter ancestor. An older ancestor produces more
     * conflicts and never fewer, so the cost of choosing wrong here is a
     * conflict a human resolves, not an edit nobody sees again.
     */
    private java.util.Map<String, String> mergeBase(String name, UnitKind kind,
                                                    MaterializationRecord destRecord) {
        if (destRecord != null && destRecord.entryDigests() != null
                && !destRecord.entryDigests().isEmpty()) {
            return destRecord.entryDigests();
        }
        return new ChildHomeMaterializer(parentStore, parentStore).readRecord(name, kind)
                .map(MaterializationRecord::entryDigests)
                .orElse(null);
    }

    /** Which paths the source would take over, and which cannot be decided. */
    private record MergePlan(java.util.LinkedHashSet<String> take, List<String> conflicts) {}

    /**
     * The three-way decision, per path, against the destination's recorded
     * per-file baseline.
     *
     * <p>Four cases and no fifth: the two sides agree; only the destination
     * moved (keep it); only the source moved (take it); both moved (a conflict,
     * which nothing here is entitled to resolve). "Absent" is a value like any
     * other, so a file added upstream, deleted locally, or deleted upstream all
     * fall out of the same comparison rather than needing their own rule.
     */
    private static MergePlan mergePlan(java.util.Map<String, String> base,
                                       java.util.Map<String, String> source,
                                       java.util.Map<String, String> dest) {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(source.keySet());
        paths.addAll(dest.keySet());
        java.util.LinkedHashSet<String> take = new java.util.LinkedHashSet<>();
        List<String> conflicts = new ArrayList<>();
        for (String path : paths) {
            String s = source.get(path);
            String d = dest.get(path);
            if (java.util.Objects.equals(s, d)) continue;
            String b = base.get(path);
            if (java.util.Objects.equals(s, b)) continue;
            if (java.util.Objects.equals(d, b)) {
                take.add(path);
                continue;
            }
            conflicts.add(path);
        }
        return new MergePlan(take, conflicts);
    }

    /** The destination tree with exactly {@code take} replaced by the source's version. */
    private static List<ViewEntry> mergedView(List<ViewEntry> sourceView, Path dest,
                                              java.util.Set<String> take) throws IOException {
        java.util.Map<String, ViewEntry> bySourceRel = new java.util.LinkedHashMap<>();
        for (ViewEntry entry : sourceView) bySourceRel.put(entry.rel(), entry);
        List<ViewEntry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (ViewEntry entry : plainView(dest)) {
            seen.add(entry.rel());
            if (!take.contains(entry.rel())) {
                out.add(entry);
                continue;
            }
            // Taken from the source; absent there means the source deleted it.
            ViewEntry replacement = bySourceRel.get(entry.rel());
            if (replacement != null) out.add(replacement);
        }
        for (String rel : take) {
            if (seen.contains(rel)) continue;
            ViewEntry added = bySourceRel.get(rel);
            if (added != null) out.add(added);
        }
        out.sort(Comparator.comparing(ViewEntry::rel));
        return out;
    }

    private static List<String> changedFiles(java.util.Map<String, String> from,
                                             java.util.Map<String, String> to) {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(from.keySet());
        paths.addAll(to.keySet());
        List<String> out = new ArrayList<>();
        for (String path : paths) {
            if (!java.util.Objects.equals(from.get(path), to.get(path))) out.add(path);
        }
        return out;
    }

    /** Drops the record for a unit that is no longer part of the child home. */
    public void forgetUnit(String name, UnitKind kind) throws IOException {
        Path file = recordFile(name, kind);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
    }

    /**
     * Removes the staging area, including a displaced tree left by an
     * interrupted run — and including a non-directory squatting on the path,
     * which would otherwise wedge every future materialization.
     */
    public void cleanStaging() {
        Path staging = stagingRoot();
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Fs.deleteRecursive(staging);
        } catch (IOException cleanup) {
            // Housekeeping only: leftovers are invisible to the store, so a
            // stubborn one must not fail the run. If something is squatting on
            // the staging path itself, the next materialization fails loudly
            // when it tries to create it.
            Log.warn("child home: could not clear the staging area at %s (%s)",
                    staging, cleanup.getMessage());
        }
    }

    public Path recordFile(String name, UnitKind kind) {
        return recordsRoot()
                .resolve(kind.name().toLowerCase())
                .resolve(safeSegment(name) + ".json");
    }

    public Optional<MaterializationRecord> readRecord(String name, UnitKind kind) {
        return readRecordFile(recordFile(name, kind));
    }

    // -------------------------------------------------------------- shims

    /**
     * Mirrors an existing parent {@code bin/} entry into the child home.
     *
     * <p>Always a symlink, independent of the unit materialization mode.
     * These entries are launchers into toolchains the parent store installed
     * (brew/npm prefixes, {@code uv tool} bin dirs) and are frequently
     * symlinks themselves; copying them would dereference whole binaries and
     * pin the child home to a toolchain the parent may later upgrade. Nothing
     * edits a shim through the child home, so they do not carry the
     * write-through hazard that motivates copying unit directories.
     */
    public void mirrorExistingShim(Path source, Path dest) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        Path from = source.toAbsolutePath().normalize();
        Path to = dest.toAbsolutePath().normalize();
        // Degenerate layout (child home == parent store): source and dest are
        // the same entry. Replacing it would delete the parent's shim and leave
        // a self-referential link behind.
        if (from.equals(to) || sameRealPath(from, to)) return;
        linkPath(from, to);
    }

    // ------------------------------------------------------------ interns

    private UnitOutcome linkUnit(String name, UnitKind kind, Path source, Path dest)
            throws IOException {
        linkPath(source, dest);
        writeRecord(name, kind, MaterializationMode.LINK, source, null, null, null);
        return new UnitOutcome(name, kind, Status.MATERIALIZED, dest, "linked at parent store");
    }

    /** Pre-existing LINK behavior, unchanged. */
    private static void linkPath(Path source, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(dest)) {
                if (linksTo(dest, source)) return;
                Files.delete(dest);
            } else if (Files.isDirectory(dest)) {
                if (sameRealPath(source, dest)) return;
                Fs.deleteRecursive(dest);
            } else {
                throw new IOException("child home path already exists: " + dest);
            }
        }
        try {
            Files.createSymbolicLink(dest, source);
        } catch (UnsupportedOperationException | IOException sym) {
            if (Files.isDirectory(source)) {
                Fs.copyRecursive(source, dest);
            } else {
                Files.copy(source, dest,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * Materializes the unit as its own git clone, so edits inside the child home
     * are commits that can be pushed back to the unit's trunk.
     *
     * <p>The clone source is the parent store's own checkout, not the unit's
     * remote URL: the parent is already at the revision the project resolved, and
     * cloning it needs no network. {@code origin} is then re-pointed at the
     * unit's real remote when the parent has one, because the point of a checkout
     * is to push somewhere that is not this machine.
     *
     * <p><b>An existing checkout is never replaced.</b> Not when it is dirty, not
     * when it has moved ahead of the parent, not when the parent has moved ahead
     * of it. It may hold commits that exist nowhere else; the correct response to
     * "the parent has newer content" is a pull into the checkout
     * ({@code project sync}), which merges, and never a re-materialization, which
     * would delete.
     */
    private UnitOutcome checkoutUnit(String name, UnitKind kind, Path source, Path dest)
            throws IOException {
        Files.createDirectories(dest.getParent());
        boolean exists = Files.exists(dest, LinkOption.NOFOLLOW_LINKS);
        if (exists && Files.isSymbolicLink(dest)) {
            // A leftover LINK materialization: it points at the parent store, so
            // there is nothing of the agent's in it to lose.
            Files.delete(dest);
            exists = false;
        }
        if (exists) {
            if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("child home path already exists: " + dest);
            }
            if (GitOps.isGitRepo(dest)) {
                writeRecord(name, kind, MaterializationMode.CHECKOUT, source,
                        GitOps.headHash(dest), null, null);
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "existing checkout left in place at " + shortHash(GitOps.headHash(dest)));
            }
            // A directory that is not a checkout and whose provenance we cannot
            // establish. Same rule as COPY: report, do not overwrite.
            return heldBack(name, kind, dest);
        }
        if (!GitOps.isAvailable()) {
            throw new IOException("CHECKOUT materialization of " + kind.name().toLowerCase()
                    + ":" + name + " needs git on PATH");
        }
        if (!GitOps.isGitRepo(source)) {
            throw new IOException("cannot check out " + kind.name().toLowerCase() + ":" + name
                    + " — the parent store copy at " + source + " is not a git repository. "
                    + "Reinstall it from a git source, or materialize it as a copy.");
        }
        if (!GitOps.clone(dest, source.toString(), null)) {
            throw new IOException("git clone of " + source + " into " + dest + " failed");
        }
        String upstream = GitOps.originUrl(source);
        if (upstream != null && !upstream.isBlank()) GitOps.setOrigin(dest, upstream);
        String head = GitOps.headHash(dest);
        writeRecord(name, kind, MaterializationMode.CHECKOUT, source, head, null, null);
        return new UnitOutcome(name, kind, Status.MATERIALIZED, dest,
                "checked out from the parent store at " + shortHash(head));
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) return "(unknown)";
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    private UnitOutcome copyUnit(String name, UnitKind kind, Path source, Path dest)
            throws IOException {
        Files.createDirectories(dest.getParent());
        boolean exists = Files.exists(dest, LinkOption.NOFOLLOW_LINKS);
        boolean destIsLink = exists && Files.isSymbolicLink(dest);
        boolean destIsDir = exists && !destIsLink && Files.isDirectory(dest);
        if (exists && !destIsLink && !destIsDir) {
            throw new IOException("child home path already exists: " + dest);
        }
        if (destIsDir && sameRealPath(source, dest)) {
            // Degenerate layout: the child home IS the parent store. There is
            // nothing to copy, and replacing dest would destroy the source.
            return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                    "child home resolves to the parent store");
        }

        List<ViewEntry> view = materializedView(source);
        String sourceDigest = viewDigest(view);
        MaterializationRecord record = readRecord(name, kind).orElse(null);
        String baseline = copyBaseline(record);
        String currentDigest = destIsDir ? treeDigest(dest) : null;

        if (destIsDir && baseline != null) {
            if (!baseline.equals(currentDigest)) {
                return heldBack(name, kind, dest);
            }
            if (sourceDigest.equals(record.sourceDigest())) {
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "already matches the parent store");
            }
        }

        Path staged = stage(view, name, kind);
        try {
            Fingerprint stagedPrint = fingerprint(staged);
            if (destIsDir && baseline == null) {
                // No trustworthy provenance. Adopt the directory only when it
                // is exactly what we would have written; otherwise refuse,
                // because we cannot tell an agent's edits from a stale copy.
                if (!stagedPrint.digest().equals(currentDigest)) {
                    return heldBack(name, kind, dest);
                }
                writeCopyRecord(name, kind, source, stagedPrint, sourceDigest,
                        MaterializationRecord.COPIED);
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "adopted an existing identical copy");
            }
            swapIn(staged, dest);
            writeCopyRecord(name, kind, source, stagedPrint, sourceDigest,
                    MaterializationRecord.COPIED);
            return new UnitOutcome(name, kind, Status.MATERIALIZED, dest,
                    destIsLink ? "replaced a symlink into the parent store" : "copied from the parent store");
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
        }
    }

    private static UnitOutcome heldBack(String name, UnitKind kind, Path dest) {
        Log.warn("child home %s:%s has local changes — left as-is, not refreshed from the parent store (%s)",
                kind.name().toLowerCase(), name, dest);
        return new UnitOutcome(name, kind, Status.SKIPPED_LOCAL_CHANGES, dest,
                "local changes in the child home");
    }

    /** The recorded content digest, or null when the record cannot be trusted. */
    private static String copyBaseline(MaterializationRecord record) {
        if (record == null) return null;
        if (!MaterializationMode.COPY.name().equals(record.mode())) return null;
        if (record.contentDigest() == null || record.sourceDigest() == null) return null;
        return record.contentDigest();
    }

    /** Record for a {@link MaterializationMode#COPY}, with its per-file baseline. */
    private void writeCopyRecord(String name, UnitKind kind, Path source, Fingerprint written,
                                 String sourceDigest, String reconcileKind) throws IOException {
        writeRecord(name, kind, MaterializationMode.COPY, source, null, sourceDigest,
                written.digest(), written.entries(), reconcileKind);
    }

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             String sourceRevision, String sourceDigest, String contentDigest)
            throws IOException {
        writeRecord(name, kind, mode, source, sourceRevision, sourceDigest, contentDigest,
                null, null);
    }

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             String sourceRevision, String sourceDigest, String contentDigest,
                             java.util.Map<String, String> entryDigests, String reconcileKind)
            throws IOException {
        Path file = recordFile(name, kind);
        Fs.ensureDir(file.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),
                new MaterializationRecord(
                        RECORD_SCHEMA_VERSION,
                        name,
                        kind.name(),
                        mode.name(),
                        source == null ? null : source.toString(),
                        sourceRevision,
                        sourceDigest,
                        contentDigest,
                        BindingStore.nowIso(),
                        entryDigests,
                        reconcileKind));
    }

    private Optional<MaterializationRecord> readRecordFile(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.ofNullable(
                    BindingJson.MAPPER.readValue(file.toFile(), MaterializationRecord.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // --------------------------------------------------------- filesystem

    /**
     * Builds the desired tree in a staging directory under the child home, so
     * a failure part way through never leaves the live child unit truncated.
     * Staging lives beside the records (same filesystem as the destination),
     * which keeps the final move atomic.
     */
    private Path stage(List<ViewEntry> view, String name, UnitKind kind) throws IOException {
        Path staging = stagingRoot();
        Fs.ensureDir(staging);
        Path staged = staging.resolve(kind.name().toLowerCase() + "-" + safeSegment(name)
                + "-" + UUID.randomUUID());
        try {
            copyView(view, staged);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Fs.deleteRecursive(staged);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        return staged;
    }

    /**
     * Atomically replaces {@code dest} with {@code staged}.
     *
     * <p>The displaced tree is parked under the staging directory, never as a
     * sibling of {@code dest}: a sibling left behind by a crash between the two
     * moves would sit inside {@code <child>/skills/} and load as a second copy
     * of the same unit, because unit identity comes from {@code SKILL.md}
     * rather than the directory name.
     */
    private void swapIn(Path staged, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        Path replaced = null;
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = stagingRoot();
            Fs.ensureDir(staging);
            replaced = staging.resolve("replaced-" + UUID.randomUUID());
            Files.move(dest, replaced, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(staged, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException move) {
            if (replaced != null) {
                try {
                    Files.move(replaced, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restore) {
                    move.addSuppressed(restore);
                }
            }
            throw move;
        }
        if (replaced == null) return;
        try {
            Fs.deleteRecursive(replaced);
        } catch (IOException cleanup) {
            // The swap already succeeded; failing to delete the tree it
            // displaced must not fail the materialization. It sits in staging,
            // invisible to the store, and the next run sweeps it.
            Log.warn("child home: could not remove the displaced tree at %s (%s)",
                    replaced, cleanup.getMessage());
        }
    }

    // ------------------------------------------------------ view + digest

    private enum EntryKind { DIR, FILE, LINK }

    /**
     * One entry of a tree as it should exist in the child home. The same list
     * drives the copy and the freshness digest, so what gets hashed is exactly
     * what would be written.
     */
    private record ViewEntry(String rel, EntryKind kind, Path source, Path linkTarget,
                             boolean executable) {}

    /** The parent tree as it would look once materialized (store links dereferenced). */
    private List<ViewEntry> materializedView(Path source) throws IOException {
        List<ViewEntry> out = new ArrayList<>();
        walk(source, "", realOrNormalized(source), new ArrayDeque<>(), out);
        return out;
    }

    /** A tree exactly as it is on disk (every symlink stays a symlink). */
    private static List<ViewEntry> plainView(Path root) throws IOException {
        List<ViewEntry> out = new ArrayList<>();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) walkPlain(root, "", out);
        return out;
    }

    private void walk(Path src, String rel, Path unitRootReal, Deque<Path> expanding,
                      List<ViewEntry> out) throws IOException {
        if (Files.isSymbolicLink(src)) {
            Path raw = Files.readSymbolicLink(src);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : src.getParent().resolve(raw).normalize();
            Path real = realOrNull(resolved);
            boolean insideParentStore = real != null && real.startsWith(parentRootReal);
            boolean internalRelative = !raw.isAbsolute() && real != null
                    && real.startsWith(unitRootReal);
            if (!insideParentStore || internalRelative) {
                // Outside the store, broken, or resolves inside the copy anyway.
                out.add(new ViewEntry(rel, EntryKind.LINK, src, raw, false));
                return;
            }
            if (expanding.contains(real) || expanding.size() >= MAX_DEREFERENCE_DEPTH) {
                // Cannot be materialized as content, and recreating the link
                // would point the child home back into the parent store.
                Log.warn("child home: skipping %s — symlink into the parent store cannot be "
                        + "dereferenced (cycle or nesting depth)", src);
                return;
            }
            expanding.push(real);
            try {
                walk(real, rel, unitRootReal, expanding, out);
            } finally {
                expanding.pop();
            }
            return;
        }
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            if (!rel.isEmpty()) out.add(new ViewEntry(rel, EntryKind.DIR, src, null, false));
            for (Path child : listSorted(src)) {
                walk(child, join(rel, child.getFileName().toString()), unitRootReal, expanding, out);
            }
            return;
        }
        out.add(new ViewEntry(rel, EntryKind.FILE, src, null, Files.isExecutable(src)));
    }

    private static void walkPlain(Path src, String rel, List<ViewEntry> out) throws IOException {
        if (Files.isSymbolicLink(src)) {
            out.add(new ViewEntry(rel, EntryKind.LINK, src, Files.readSymbolicLink(src), false));
            return;
        }
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            if (!rel.isEmpty()) out.add(new ViewEntry(rel, EntryKind.DIR, src, null, false));
            for (Path child : listSorted(src)) {
                walkPlain(child, join(rel, child.getFileName().toString()), out);
            }
            return;
        }
        out.add(new ViewEntry(rel, EntryKind.FILE, src, null, Files.isExecutable(src)));
    }

    private static void copyView(List<ViewEntry> view, Path dest) throws IOException {
        Files.createDirectories(dest);
        for (ViewEntry entry : view) {
            Path target = dest.resolve(entry.rel());
            switch (entry.kind()) {
                case DIR -> Files.createDirectories(target);
                case FILE -> {
                    Files.createDirectories(target.getParent());
                    Files.copy(entry.source(), target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                case LINK -> recreateLink(target, entry.linkTarget());
            }
        }
    }

    private static void recreateLink(Path dst, Path target) throws IOException {
        Files.createDirectories(dst.getParent());
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) Files.delete(dst);
        try {
            Files.createSymbolicLink(dst, target);
        } catch (UnsupportedOperationException | IOException e) {
            Path resolved = target.isAbsolute()
                    ? target
                    : dst.getParent().resolve(target).normalize();
            if (Files.isDirectory(resolved)) {
                Fs.copyRecursive(resolved, dst);
            } else if (Files.exists(resolved)) {
                Files.copy(resolved, dst,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * A tree's whole-tree digest and its per-file digests, taken in one pass.
     *
     * <p>Both numbers describe the same bytes, so computing them from two
     * separate walks would read every file twice and — worse — leave two
     * definitions of "the content of this unit" free to drift apart. The
     * whole-tree digest decides whether a unit was edited at all; the per-file
     * map decides which side of a three-way merge owns each path. They have to
     * agree, so they are produced together.
     *
     * <p>{@code digest} is null when {@code skipNames} is non-empty: a
     * whole-tree digest that silently omitted entries would not be comparable
     * with one that did not, and every caller that wants the whole-tree number
     * wants it over the whole tree.
     */
    public record Fingerprint(String digest, java.util.LinkedHashMap<String, String> entries) {
        public Fingerprint {
            entries = entries == null ? new java.util.LinkedHashMap<>() : entries;
        }
    }

    /** {@link Fingerprint} of a tree exactly as it sits on disk. */
    public static Fingerprint fingerprint(Path root) throws IOException {
        return fingerprintOf(plainView(root), java.util.Set.of());
    }

    /**
     * Stable digest over a tree as it is on disk. Framing is shared with the
     * materialized-view digest, so the digest of a view equals the digest of
     * the tree that view produces.
     */
    public static String treeDigest(Path root) throws IOException {
        return viewDigest(plainView(root));
    }

    /**
     * Per-entry digests for a tree: relative path → digest of that one entry.
     *
     * <p>Built on the same walk as {@link #treeDigest(Path)} deliberately. The
     * drift digest ({@code dev.skillmanager.store.HomeDigest}) needs to say
     * <em>which files</em> changed, not just that something did, and a second
     * independent walker would be a second definition of "the content of a unit"
     * — free to disagree with the one the hold-back rule uses about symlinks,
     * executable bits, or which entries count at all.
     *
     * <p>{@code skipNames} drops directories by name at any depth. Callers pass
     * {@code .git} for that: a git directory rewrites itself on every read-only
     * command, so including it would report drift constantly and train whoever
     * reads the report to ignore it.
     */
    public static java.util.LinkedHashMap<String, String> entryDigests(
            Path root, java.util.Set<String> skipNames) throws IOException {
        return fingerprintOf(plainView(root),
                skipNames == null ? java.util.Set.of() : skipNames).entries();
    }

    private static boolean isUnder(String rel, java.util.Set<String> skipNames) {
        if (skipNames.isEmpty()) return false;
        for (String segment : rel.split("/")) {
            if (skipNames.contains(segment)) return true;
        }
        return false;
    }

    private static String viewDigest(List<ViewEntry> view) throws IOException {
        return fingerprintOf(view, java.util.Set.of()).digest();
    }

    /**
     * The single walk behind {@link #treeDigest}, {@link #entryDigests} and
     * {@link #fingerprint}: each entry is framed into its own digest and, when
     * the whole tree is in scope, into the shared one — from the same bytes,
     * read once.
     */
    private static Fingerprint fingerprintOf(List<ViewEntry> view, java.util.Set<String> skipNames)
            throws IOException {
        boolean whole = skipNames.isEmpty();
        List<ViewEntry> sorted = new ArrayList<>(view);
        sorted.sort(Comparator.comparing(ViewEntry::rel));
        MessageDigest all = whole ? sha256() : null;
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        for (ViewEntry entry : sorted) {
            if (isUnder(entry.rel(), skipNames)) continue;
            MessageDigest one = sha256();
            switch (entry.kind()) {
                case DIR -> {
                    frame(one, "D", entry.rel(), 0);
                    if (whole) frame(all, "D", entry.rel(), 0);
                }
                case LINK -> {
                    byte[] target = entry.linkTarget().toString().getBytes(StandardCharsets.UTF_8);
                    frame(one, "L", entry.rel(), target.length);
                    one.update(target);
                    if (whole) {
                        frame(all, "L", entry.rel(), target.length);
                        all.update(target);
                    }
                }
                case FILE -> {
                    String kind = entry.executable() ? "X" : "F";
                    long size = Files.size(entry.source());
                    frame(one, kind, entry.rel(), size);
                    if (whole) frame(all, kind, entry.rel(), size);
                    try (InputStream in = Files.newInputStream(entry.source())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            one.update(buffer, 0, read);
                            if (whole) all.update(buffer, 0, read);
                        }
                    }
                }
            }
            entries.put(entry.rel(), hex(one.digest()));
        }
        return new Fingerprint(whole ? hex(all.digest()) : null, entries);
    }

    /**
     * Length-prefixed framing: both the path and the payload are preceded by
     * their length, so file bytes can never be read as the start of the next
     * entry and two different trees cannot be framed identically.
     */
    private static void frame(MessageDigest digest, String kind, String rel, long payloadLength) {
        byte[] path = rel.getBytes(StandardCharsets.UTF_8);
        digest.update((kind + "\0" + path.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(path);
        digest.update(("\0" + payloadLength + "\0").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    // -------------------------------------------------------------- paths

    private Path recordsRoot() {
        return childStore.root().resolve(RECORDS_DIR);
    }

    /**
     * Where displaced trees are parked during a swap, and staging is built.
     *
     * <p>Deliberately outside every directory the store scans for units: a
     * displaced tree left by a crash must never be loadable as a second copy
     * of the unit it came from. Exposed so that invariant can be asserted.
     */
    public Path stagingRoot() {
        return recordsRoot().resolve(STAGING_DIR);
    }

    /** The child-home directory holding units of {@code kind}. */
    private Path unitRoot(UnitKind kind) {
        return unitRootOf(childStore, kind);
    }

    /** The directory in {@code store} holding units of {@code kind}. */
    private static Path unitRootOf(SkillStore store, UnitKind kind) {
        // Derived from the store's own resolver so it cannot drift from where
        // materializeUnit actually writes.
        return store.unitDir("probe", kind).toAbsolutePath().normalize().getParent();
    }

    private static List<Path> listSorted(Path dir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(children::add);
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return children;
    }

    private static String join(String rel, String name) {
        return rel.isEmpty() ? name : rel + "/" + name;
    }

    private static UnitKind parseKind(String value) {
        if (value == null) return null;
        try {
            return UnitKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean linksTo(Path link, Path source) throws IOException {
        Path existing = Files.readSymbolicLink(link);
        Path normalized = existing.isAbsolute()
                ? existing.normalize()
                : link.getParent().resolve(existing).normalize();
        return normalized.equals(source);
    }

    private static boolean sameRealPath(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static Path realOrNormalized(Path path) {
        Path real = realOrNull(path);
        return real != null ? real : path.toAbsolutePath().normalize();
    }

    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) return "unit";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
