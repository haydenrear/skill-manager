package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.shared.util.Rederivable;
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
 * <h2 id="unit-content">What counts as unit content</h2>
 *
 * <p>Everything below decides who may destroy which bytes. That question only
 * makes sense about bytes somebody wrote, so the walkers behind every digest
 * and every copy skip {@linkplain Rederivable re-derivable build output} —
 * {@code .gradle/}, {@code __pycache__/}, {@code build/}, {@code node_modules/},
 * {@code .venv/} and the rest — on BOTH sides at once. A path that is skipped
 * is invisible to this class in every direction: not hashed, so it cannot make
 * a unit look edited; not copied, so it cannot travel between homes; and not
 * deleted, because {@link #carryOverUnownedTrees} carries it across the swap.
 *
 * <p>Owning it on one side only is issue #41. {@code HomeCloner} had skipped
 * these names inside a unit since the first clone and this class had not, so
 * running {@code discover.py} once in a ticket worktree — the thing a worktree
 * exists to do — moved the unit's digest, reported it {@code conflicted}, and
 * printed a remedy naming {@code executionHistory.bin} as a file to resolve.
 * Running that remedy verbatim exited 1 without clearing the gate, so the
 * worktree could never be closed out.
 *
 * <p>{@code .git} is deliberately NOT skipped, and {@link Rederivable} says at
 * length why: a unit whose agent committed work would otherwise read as
 * unmodified and the next teardown would take the commits with it (issue #29).
 * It is not <em>digested</em> either, which is the other half of the same issue
 * and the part that took a second decision: a copy of {@code .git} rewrites
 * itself on every git command, so a whole-tree digest holds a git-sourced unit
 * back permanently and for no reason. The tree is therefore split at
 * {@code .git} and each half asked of the authority that can answer it —
 * {@link #gitCopyIsUntouched}, the digest for the worktree and git for the
 * history. Skipping and splitting look alike and are opposites: one loses the
 * commits, the other keeps them and stops lying about the rest.
 *
 * <p>"Git for the history" means EVERY REF, not HEAD. Asking HEAD alone is a
 * third, separate data-loss defect — it destroyed a side-branch commit and a
 * {@code git stash} on real homes — and {@link #gitHistoryMovedOn} carries the
 * measurement under "Every ref, not HEAD".
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
 *
 * <h2 id="baseline-rule">The baseline rule — one rule, three places</h2>
 *
 * <p><b>A reconciliation may destroy bytes in the destination only where it can
 * show the source home passed through those same bytes.</b> Everything below is
 * that one sentence applied to the three decisions a reconcile makes, and the
 * three defects it replaces (CHM-9, CHM-10, and the stale clone baseline) were
 * three different answers to it.
 *
 * <p>What a {@link MaterializationRecord} is evidence <em>of</em> follows from
 * it, and the two halves must never be confused:
 *
 * <ul>
 *   <li>{@code contentDigest} is evidence about the DESTINATION alone — "these
 *       are the bytes this reconcile wrote here". Anything may read it to tell
 *       an untouched tree from an edited one, whoever wrote it.</li>
 *   <li>{@code source}, {@code sourceDigest} and {@code entryDigests} are
 *       evidence about a PAIR of homes — "this destination and that source last
 *       shared exactly this". {@code entryDigests} therefore holds the SOURCE's
 *       tree at the moment of the reconcile, not the tree that was written: for
 *       a wholesale copy those are the same thing, and for a merge they are
 *       emphatically not, because the merged result is a state the source never
 *       held. A record says nothing at all about a third home.</li>
 * </ul>
 *
 * <p>The four decisions:
 *
 * <ol>
 *   <li><b>Wholesale copy</b> (a fast-forward). Allowed only when the
 *       destination still holds exactly what its record says was written there
 *       <em>and</em> that record is evidence about this source. A pristine copy
 *       is disposable only relative to the home it is a copy OF: once a project
 *       home holds a fast-forwarded copy of a worktree that has since been torn
 *       down, those bytes exist nowhere else, and nothing about the
 *       destination's own digest can say so. That was CHM-9.</li>
 *   <li><b>The merge base.</b> The destination's own record is the tighter
 *       ancestor and is preferred — but only when it is evidence about this
 *       source. Otherwise the SOURCE's record is used, which is sound for a
 *       reason worth stating: it records a state the source itself was handed,
 *       and the destination's half is enforced per path by the algebra, which
 *       takes a path only where {@code d == b}. Neither available, no base, and
 *       a conflict is reported rather than a guess written. That was
 *       CHM-10.</li>
 *   <li><b>What a clone writes down.</b> A copied home's inherited records
 *       describe a pair it is not part of. Its own baseline is its content at
 *       clone time — which the home it was copied from also held, by
 *       construction — so {@link #recordCloneBaselines} states that, instead of
 *       leaving a baseline describing content neither home has any more.</li>
 *   <li id="record-write"><b>What a reconciliation writes down.</b> The first
 *       three decisions all concern which baseline is <em>read</em>. This one
 *       concerns which baseline is <em>written</em>, and it is the same rule
 *       pointed the other way: a record may claim a path as shared only where
 *       the reconciliation can show BOTH homes stood on that byte. Three ways
 *       to show it and no fourth — the path was just written here from the
 *       source; the two sides already agreed on it; or the source is still
 *       standing on a baseline this destination is itself on record as having
 *       shared with this source. A path the reconciliation DECLINED to write,
 *       measured against a base that was only ever the source's own idea of
 *       what it was handed, is none of those, and claiming it is CHM-12: the
 *       record then says the destination holds bytes it has never held, and the
 *       <em>next</em> reconcile in the opposite direction reads {@code d == b}
 *       as "the destination is standing on the base" and overwrites the work.
 *       {@link #sharedAfterMerge} is that decision; everything it cannot
 *       justify is simply omitted, which costs a later conflict and never an
 *       edit.</li>
 * </ol>
 *
 * <p><b>One rule needs one reader.</b> The four decisions above are read by
 * three different writers into a home — {@link #copyUnit} (the downward
 * materialization {@code project resolve} and {@code project sync} run),
 * {@link #linkUnit}, and {@link #reconcile} ({@code home sync}) — plus the
 * predicate {@link #isLocallyModified} that a prune, a teardown and a close-out
 * consult before deleting. Each of them used to inline its own conjunction, and
 * they did not agree: the reconcile asked whether the record was evidence about
 * this source, and the other three did not. A unit a worktree merged up into a
 * project home is <em>pristine by its own record</em> while being a copy of no
 * store on earth, so the omitted clause was the whole difference between
 * refreshing a stale tree and deleting work that exists nowhere else — CHM-15,
 * the {@code project sync} × {@code home sync} seam. {@link Disposal} is now the
 * single reading; nothing else answers "may these bytes go".
 *
 * <p><b>The asymmetry is deliberate and is the whole design.</b> A baseline
 * that is too OLD costs a conflict a human resolves; a baseline that is too
 * NEW, or that belongs to a different pair of homes, costs an edit nobody ever
 * sees again. Where the rule cannot show a shared baseline it holds back or
 * conflicts — never writes. {@code specs/desired_program_model/External.tla}
 * states the same thing as {@code MergeBasePolicy = "SHARED_ANCESTOR"} and
 * {@code ReconcileProvenance = "SOURCE_AWARE"}, checked by
 * {@code External_sync.cfg}.
 */
public final class ChildHomeMaterializer {

    /** Directory (under the child home root) holding per-unit records. */
    public static final String RECORDS_DIR = ".materialization";

    /** Staging + displaced-tree area; swept before and after every run. */
    private static final String STAGING_DIR = "tmp";

    /**
     * The schema every record this build writes carries, and the only one it
     * reads as evidence.
     *
     * <h2>Why it is 2 (issue #46)</h2>
     *
     * <p>A record's digests answer a question, and issue #41 changed the
     * question without changing the number. Before it, every digest here was
     * computed over the whole unit tree including {@linkplain Rederivable
     * re-derivable build output}; after it, those paths are invisible to this
     * class in every direction. A version-1 record and a version-2 record
     * therefore describe two different trees and are not comparable, so the
     * first pass over a home holding version-1 records saw a digest mismatch it
     * could not explain and held the unit back — correctly, and <b>silently</b>.
     * All seven onboarded constituent homes carry such records. The silence was
     * the defect, not the hold-back.
     *
     * <h2>What a stale-schema record means here, and why that is the safe half
     * of the choice</h2>
     *
     * <p>{@link #usableAsEvidence} makes a record of any other version <b>no
     * evidence at all</b>: no baseline, no {@code entryDigests}, no
     * "the source held these bytes". Under the
     * <a href="#baseline-rule">baseline rule</a> that costs exactly one thing —
     * the ability to fast-forward — and cannot cost bytes, because every
     * decision that destroys a destination tree needs evidence and now has
     * none. The <em>other</em> reading of "re-baseline", recomputing
     * {@code contentDigest} from whatever the destination currently holds so the
     * tree reads as untouched again, is the unsafe one: it would be true by
     * construction, prove nothing about whether an agent edited the unit since
     * the copy, and hand the next reconcile a licence to overwrite. That is
     * CHM-9's shape reached through the version field.
     *
     * <p>Deliberate re-baselining happens in the one place it is sound:
     * {@link #recordCloneBaselines}, where the home's content IS what the home
     * it was copied from held, by construction. Everywhere else a pristine unit
     * self-heals on the next pass (the adopt-an-identical-copy branch of
     * {@link #copyUnit}, and the {@code sourceHeldTheseBytes} fast-forward, both
     * write a fresh record), and a unit that is NOT pristine holds back with
     * {@link #staleSchemaNote} naming the schema change as the cause — which is
     * the half of issue #46 an operator actually experiences.
     *
     * <h2>What "no evidence at all" does NOT cover, stated so the framing is not
     * read as wider than it is</h2>
     *
     * <p>{@link #usableAsEvidence} gates the three DIGEST readers and nothing
     * else. Two other fields are read without it, both deliberately:
     *
     * <ul>
     *   <li>{@code mode}, by {@link #effectiveMode}. It is the one field whose
     *       whole job is to stop a deletion: a stale record that says
     *       {@code CHECKOUT} still keeps a project-wide COPY default from deleting
     *       a checkout holding unpushed commits. Distrusting the digests must not
     *       distrust that.</li>
     *   <li>{@code historyDigest}, by {@link #gitHistoryMovedOn} on the
     *       {@link #checkoutIsModified} path. A record whose version this build
     *       cannot read still fails that comparison unless the refs match
     *       exactly, so the ungated read can only report a checkout as MORE
     *       modified, never less — it cannot make a destination writable. Gating
     *       it would change nothing except to make a stale checkout unconditionally
     *       "modified", which is where it already lands.</li>
     * </ul>
     *
     * <p>So the rule is "no evidence that anything may be DESTROYED", not "the
     * record is ignored". Every ungated read was traced: none of them makes a
     * destination more writable than the gated reading would.
     */
    private static final int RECORD_SCHEMA_VERSION = 2;

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
     * <p>{@code sourceRevision} carries the git revision of the tree that was
     * written here — the commit it was materialized at, which is how a later
     * pass tells "still exactly what we wrote" from "the agent has committed
     * something here". Written for {@link MaterializationMode#CHECKOUT} units,
     * which have never had any other baseline, and — since issue #29 — for a
     * {@link MaterializationMode#COPY} whose tree carries its own {@code .git}:
     * the same question, asked of the same authority, rather than a second
     * spelling of it. Null when the tree is not a git repository, or when git
     * cannot answer, both of which read as "no evidence" and hold back.
     *
     * <p>{@code historyDigest} is a digest over the tree's full ref listing plus
     * HEAD, and it is the field that decides whether a git-backed tree holds work
     * — for a COPY and for a {@link MaterializationMode#CHECKOUT} alike, through
     * the one predicate {@link #gitHistoryMovedOn}. It exists because
     * {@code sourceRevision} on its own is not a summary of a repository: a side
     * branch, a stash, a tag and a note all leave HEAD where it was, and a
     * reconciliation that trusted HEAD alone destroyed every one of them. See
     * {@link #gitHistoryMovedOn}, section "Every ref, not HEAD". Evidence about
     * this home alone, like {@code contentDigest}.
     *
     * <p>{@code worktreeDigest} is {@code contentDigest} with {@code .git}
     * excluded, and it exists only for a git-backed COPY. It is the other half
     * of the issue #29 answer: {@code contentDigest} moves every time git
     * rewrites its index or appends a reflog, so on its own it makes a
     * git-sourced unit differ from its record permanently and holds it back
     * forever. Splitting the tree at {@code .git} lets each half be judged by
     * the authority that can actually answer it — git for the history, the
     * digest for every byte outside it — instead of skipping {@code .git}, which
     * would make a unit whose agent committed work read as unmodified and is the
     * data-loss defect the same issue warns about. It is evidence about the
     * DESTINATION alone, like {@code contentDigest}, and is comparable only with
     * another digest of the same scope.
     *
     * <p>{@code source} names the tree the content came from — a parent-store
     * unit directory for a materialization, the other home's unit directory for
     * a reconcile, and {@code null} when there is no such tree (a clone's own
     * baseline, restated by {@link #recordCloneBaselines}). It is <b>read</b>,
     * not merely written: see the <a href="#baseline-rule">baseline rule</a>.
     * A record whose {@code source} names some other home is evidence about
     * that pair of homes and about no other, which is exactly what CHM-9 turned
     * on — the field was there, correct, and nothing consulted it.
     *
     * <p>{@code entryDigests} is the per-file form of the baseline the two
     * homes shared, and it is <b>partial by design</b>: it names a path only
     * where the reconcile that wrote it could show both homes stood on that
     * byte. For a wholesale copy that is the whole of the source's tree, which
     * is also the tree that was written. For a merge it is deliberately neither
     * the merged tree (CHM-10) nor the source's whole tree (CHM-12) but the
     * subset {@link #sharedAfterMerge} can justify; a path missing from the map
     * reads as "no baseline here", which routes that path to a conflict rather
     * than to a silent overwrite. The whole-tree digest can only answer "did
     * this unit change"; a three-way reconciliation between two homes has to
     * answer "<em>which files</em> did each side change", and that is the
     * difference between merging two disjoint edits and declaring the whole
     * unit conflicted. Recording the MERGED tree here instead is CHM-10: the
     * destination's own local work becomes part of its recorded ancestor, the
     * next merge measures a source that never held those bytes against them,
     * reads {@code d == b} as "only the source moved", and reverts the work
     * with no conflict and no report. Written for
     * {@link MaterializationMode#COPY} only — a checkout's baseline is its git
     * history, which already answers this better than any digest map could.
     * Absent (an older record, an interrupted write) it reads as null, which
     * routes the merge to the same conservative refusal a missing record
     * already gets.
     *
     * <p>{@code contentDigest} is the other half and stays what it always was:
     * the tree that was actually written here, so a later pass can tell an
     * untouched destination from an edited one.
     *
     * <p>{@code reconcileKind} distinguishes a tree that was copied wholesale
     * from one that is the <em>result of a merge</em>. They are not
     * interchangeable: a pristine copy may be overwritten by the home it is a
     * copy of, because nothing in it is unique to it. A merge result is a copy
     * of no home at all — it carries work folded in from two — so it must be
     * merged again rather than overwritten, and it looks exactly like a
     * pristine copy to a digest comparison against its own record.
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
            String worktreeDigest,
            String historyDigest,
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
                    sourceDigest, contentDigest, null, null, materializedAt, null, null);
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
     * Reports whether one child-home unit holds anything the parent store cannot
     * be shown to have — the predicate a prune, a teardown and a close-out
     * consult before destroying it. Units with no usable record are reported as
     * modified: without provenance there is no evidence they are disposable.
     *
     * <p>"Differs from the tree that was materialized into it" is the weaker
     * question and was the wrong one. A unit a worktree merged up into this home
     * matches its own record exactly and is still a copy of no store: it is the
     * whole of {@link Disposal}, not just its first clause, that says whether the
     * bytes may go.
     */
    public boolean isLocallyModified(String name, UnitKind kind) throws IOException {
        Path dest = childStore.unitDir(name, kind).toAbsolutePath().normalize();
        if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return false;
        MaterializationRecord record = readRecord(name, kind).orElse(null);
        if (record != null && MaterializationMode.CHECKOUT.name().equals(record.mode())) {
            return checkoutIsModified(dest, record);
        }
        return !disposalOf(name, kind, record, dest, treeDigest(dest)).disposable();
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
        return gitHistoryMovedOn(dest, record);
    }

    /**
     * Whether a git-backed tree's <em>history</em> has moved off the revision a
     * record says was materialized into it — the {@code .git} half of the
     * question, asked of git.
     *
     * <p>Shared by {@link #checkoutIsModified} and {@link #gitCopyIsUntouched}
     * so a CHECKOUT and a COPY-with-a-{@code .git} cannot come to disagree about
     * what "the agent has committed something" means. Two paths that should
     * agree and don't, with nothing detecting it, is the failure shape this
     * class has already paid for three times.
     *
     * <p>No recorded history — an older record, a tree that was not a git
     * repository when it was written, a git that could not answer — is "moved
     * on". Without evidence that a tree is disposable, it is not disposable, and
     * that is the same asymmetry the no-record case takes.
     *
     * <h2 id="not-head">Every ref, not HEAD — and this cost a data-loss
     * regression to learn</h2>
     *
     * <p>The first fix for issue #29 asked git exactly one question,
     * {@code HEAD == the recorded revision}, and that <b>destroyed bytes</b>.
     * Measured on real homes with a real {@code git+file://} unit: an agent
     * commits on a side branch and switches back, so HEAD is unmoved and the
     * worktree is clean; upstream then moves and a plain downward
     * {@code home sync} reports {@code updated — the destination held no local
     * work} and replaces {@code .git} wholesale. {@code cat-file -e} on the side
     * commit afterwards exits 128. {@code git stash push} does the same: the
     * stash list comes back empty and {@code reflog show refs/stash} reports an
     * unknown revision.
     *
     * <p>It was a REGRESSION rather than a gap, and that is the part worth
     * remembering. Before the fix the whole-tree digest never matched after any
     * git command, so the permanent false positive #29 exists to remove was
     * <em>accidentally protecting every object in the repository</em>. Replacing
     * "the bytes are identical" with "(HEAD, worktree bytes) are identical" made
     * everything else under {@code .git} disposable at a stroke: side branches,
     * stashes, tags, notes, fetched refs.
     *
     * <p>The correction is the asymmetry rule applied literally — <b>when in
     * doubt, conflict</b>. A digest over the whole ref listing plus HEAD says
     * "some ref moved" for every one of those cases and says nothing at all for
     * git's own bookkeeping, because an index rewrite, a reflog append and a
     * {@code gc} that repacks objects change no ref. So issue #41 stays fixed and
     * the protection the digest used to give for free is back.
     *
     * <p>Two consequences, both named rather than discovered later: a
     * {@code git fetch} that advances a remote-tracking ref, and a
     * {@code git branch -d} of a merged branch, both read as "moved on" even
     * though neither loses anything. Each costs a conflict a human resolves. That
     * is the direction this whole mechanism errs in, and it is the opposite of
     * what the HEAD-only reading cost.
     */
    private static boolean gitHistoryMovedOn(Path dest, MaterializationRecord record) {
        String recorded = record == null ? null : record.historyDigest();
        if (recorded == null || recorded.isBlank()) return true;
        String now = gitHistoryDigest(dest);
        return now == null || !now.equals(recorded);
    }

    /**
     * A digest over everything in {@code .git} that <em>holds</em> something: the
     * full ref listing, plus HEAD, which is not a ref under {@code refs/}.
     *
     * <p>Null when git cannot answer, which every reader takes as no evidence.
     * See {@link #gitHistoryMovedOn} for why this is a listing digest rather than
     * a single revision or a reachability query.
     */
    private static String gitHistoryDigest(Path tree) {
        String refs = GitOps.refListing(tree);
        if (refs == null) return null;
        String head = GitOps.headHash(tree);
        MessageDigest digest = sha256();
        digest.update(refs.getBytes(StandardCharsets.UTF_8));
        digest.update("\nHEAD ".getBytes(StandardCharsets.UTF_8));
        // A repository with no commit yet has no HEAD hash; the absence is itself
        // part of the state, so it is framed rather than skipped.
        digest.update((head == null ? "" : head).getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    /**
     * Whether a {@link MaterializationMode#COPY} unit that carries its own
     * {@code .git} still holds exactly what was materialized into it: <b>issue
     * #29, and the ticket's whole difficulty is that the obvious answer loses
     * data.</b>
     *
     * <h2>The permanent false positive</h2>
     *
     * <p>A unit installed from a git source keeps {@code .git} inside the home,
     * so a COPY carries it — 61 entries, measured. {@code .git} rewrites itself
     * on every command, {@code git status} included, so the unit's whole-tree
     * digest moves off {@code contentDigest} the first time anybody so much as
     * looks at it, and every later pass reports it {@code locally modified}.
     * Nothing clears that, ever, on exactly the units an agent is most likely to
     * be working in.
     *
     * <h2>Why "skip {@code .git} in the digest" is a data-loss defect</h2>
     *
     * <p>Because a commit moves bytes ONLY inside {@code .git}. An agent whose
     * edit has already been synced up and who then commits it locally has a
     * working tree byte-identical to the record and history that exists in one
     * home on earth. Excluded from the digest, that unit reads UNMODIFIED: the
     * teardown gate clears, the worktree is removed, and the commits stop
     * existing. {@link Rederivable} says the same thing at length under its
     * {@code not-git} heading, and epic #1 verified end-to-end that {@code .git}
     * is not skipped.
     *
     * <h2>What this asks instead</h2>
     *
     * <p>The tree is split at {@code .git} and each half is judged by the
     * authority that can answer it — <b>both halves, ANDed</b>, because either
     * one alone is a defect:
     *
     * <ul>
     *   <li>{@code worktreeDigest}: every byte OUTSIDE {@code .git} is still
     *       what was written here. This is the ordinary content question, in the
     *       ordinary ownership scope, so re-derivable build output stays
     *       invisible (issue #41) — which is also why {@code git status} is NOT
     *       consulted here: a unit that does not gitignore its {@code build/}
     *       would come back dirty and put #41 straight back.</li>
     *   <li>{@link #gitHistoryMovedOn}: EVERY ref, plus HEAD, is still what the
     *       record names. A commit — the case the naive fix destroys — moves one.
     *       Asking about HEAD alone is a data-loss defect in its own right and
     *       <a href="#not-head">that section</a> is the measurement.</li>
     * </ul>
     *
     * <p>So git's own bookkeeping (an index rewrite, a reflog append, a
     * {@code gc} that repacks objects) changes neither half and the unit reads
     * as untouched; committed work changes the second half and it does not. A
     * missing {@code worktreeDigest} or {@code historyDigest} is no evidence
     * and holds back, which is what a record written before this existed gets.
     */
    private static boolean gitCopyIsUntouched(Path dest, MaterializationRecord record)
            throws IOException {
        if (!usableAsEvidence(record)) return false;
        String recordedWorktree = record.worktreeDigest();
        if (recordedWorktree == null) return false;
        if (!GitOps.isAvailable() || !GitOps.isGitRepo(dest)) return false;
        if (!recordedWorktree.equals(worktreeDigest(dest))) return false;
        return !gitHistoryMovedOn(dest, record);
    }

    /**
     * Whether two homes' copies of a git-backed unit differ ONLY in git's own
     * bookkeeping — <b>the symmetric half of issue #29, which the record-based
     * route cannot reach.</b>
     *
     * <p>The issue's words are "after ANY git command <em>on either side</em>",
     * and {@link #gitCopyIsUntouched} only answers for a destination that has a
     * usable record. Two cases it cannot help with, both measured:
     *
     * <ul>
     *   <li>the destination is an <em>installed</em> home and has no record at all
     *       — the operator's root home, which is issue #43's shape;</li>
     *   <li>the churn happened in the SOURCE, so the destination is pristine by
     *       its own record and the trees still differ.</li>
     * </ul>
     *
     * <p>In both, {@code home close-out} kept reporting a blocker whose remedy was
     * a sync that would copy 61 {@code .git} entries and change nothing anybody
     * wrote. Answered here instead of through the record, because it needs no
     * record: if the two trees are identical outside {@code .git} <em>and</em>
     * stand on exactly the same refs, then neither home holds a file or a commit
     * the other lacks, and the honest outcome is that there is nothing to
     * reconcile.
     *
     * <p>Writing NOTHING is what makes this safe unconditionally — it cannot
     * destroy anything, in either direction, whatever either record says. What it
     * can do is fail to propagate, and only for objects that are unreachable from
     * every ref in both homes, which is the definition of what git itself is
     * entitled to garbage-collect.
     *
     * <p>{@code specs/desired_program_model/External.tla} says the same thing as
     * {@code GitAgree} in {@code SyncStatusOf}'s first branch; the model had it
     * before the code did.
     */
    private static boolean gitTwinsDifferOnlyInBookkeeping(Path source, Path dest)
            throws IOException {
        if (!carriesGitDirectory(source) || !carriesGitDirectory(dest)) return false;
        if (!GitOps.isAvailable()) return false;
        if (!GitOps.isGitRepo(source) || !GitOps.isGitRepo(dest)) return false;
        String sourceHistory = gitHistoryDigest(source);
        if (sourceHistory == null || !sourceHistory.equals(gitHistoryDigest(dest))) return false;
        return worktreeDigest(source).equals(worktreeDigest(dest));
    }

    /**
     * Whether {@code unit} carries its own git directory — the gate on the
     * git-aware route above.
     *
     * <p>Presence of a {@code .git} DIRECTORY in the unit itself, deliberately
     * not {@code GitOps.isGitRepo}: that shells out to
     * {@code rev-parse --is-inside-work-tree}, which walks UP the tree, so any
     * home that happens to live inside somebody's checkout would answer yes for
     * every unit in it. A {@code .git} FILE (the shape a git worktree or a
     * submodule leaves) is deliberately not this either: the history it points
     * at lives outside the unit, so nothing in the unit's own bytes can be
     * judged against it, and the unchanged digest path is the conservative
     * answer.
     */
    private static boolean carriesGitDirectory(Path unit) {
        return Files.isDirectory(unit.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
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
            // Why, when the why is a record this build cannot read rather than
            // anything the operator did (issue #46). A teardown that names
            // "local changes" for a unit nobody changed sends whoever reads it
            // looking for an edit that is not there.
            String stale = staleSchemaNote(readRecord(ref.name(), ref.kind()).orElse(null));
            out.add(new UnitOutcome(ref.name(), ref.kind(), Status.SKIPPED_LOCAL_CHANGES,
                    childStore.unitDir(ref.name(), ref.kind()).toAbsolutePath().normalize(),
                    stale != null ? stale : "local changes in the child home"));
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
     *
     * <p>The same reasoning is why <b>symlinks are enumerated, not skipped</b>.
     * A unit directory that is a link, and an entire kind directory that is a
     * link, both used to be dropped here with no report at all — so a home
     * whose {@code skills/} was a symlink reconciled as
     * {@code {"clean":true,"units":[]}} and a linked unit beside a normal one
     * was never named. Worse, the enumerator and {@link #reconcile} disagreed:
     * reconcile applies {@code NOFOLLOW_LINKS} only to the final path
     * component, so it reads THROUGH a linked kind directory, and the same unit
     * was invisible to {@code home sync} while {@code home close-out} reported
     * it conflicted — purely because the destination contributed the name to
     * the union. Naming them here and letting reconcile answer
     * {@link SyncStatus#LINKED} makes the two agree by construction.
     */
    public static List<UnitRef> unitDirectories(SkillStore store) throws IOException {
        List<UnitRef> out = new ArrayList<>();
        for (UnitKind kind : UnitKind.values()) {
            Path kindDir = unitRootOf(store, kind);
            // isDirectory() follows the link deliberately: a linked kind
            // directory still HAS units, and they have to be named before
            // anything may call the home clean.
            if (kindDir == null || !Files.isDirectory(kindDir)) continue;
            for (Path unitDir : listSorted(kindDir)) {
                boolean link = Files.isSymbolicLink(unitDir);
                if (!link && !Files.isDirectory(unitDir, LinkOption.NOFOLLOW_LINKS)) continue;
                // A link is enumerated whatever it points at, a dangling one
                // included: "this name is a link" is the fact to report, and
                // resolving it to decide whether to mention it would put the
                // silence back for the worst-shaped case.
                out.add(new UnitRef(unitDir.getFileName().toString(), kind));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * The symlink on the path to {@code name}'s unit directory in {@code store}
     * — the kind directory or the unit directory itself — or null when neither
     * is one.
     *
     * <p>Both levels, because {@link MaterializationMode#LINK} produces the
     * second and a hand-built or hand-repaired home produces the first, and the
     * consequence is identical: bytes written "into this home" land somewhere
     * else, and bytes read "out of this home" belong to somewhere else.
     */
    private static Path symlinkOnUnitPath(SkillStore store, String name, UnitKind kind) {
        Path kindDir = unitRootOf(store, kind);
        if (kindDir != null && Files.isSymbolicLink(kindDir)) return kindDir;
        Path unitDir = store.unitDir(name, kind).toAbsolutePath().normalize();
        return Files.isSymbolicLink(unitDir) ? unitDir : null;
    }

    /**
     * State each unit in a freshly copied {@code home} against its own content:
     * the third of the three decisions in the <a href="#baseline-rule">baseline
     * rule</a>.
     *
     * <p>What this is for: a home produced by copying another one — {@code home
     * clone} — has the bytes but no provenance of its own, and provenance is
     * the only thing that makes an edit inside it recoverable later. Without
     * it, the first reconciliation back into the home it came from sees two
     * trees that differ, no common ancestor, and has to report a conflict on
     * the whole unit — even when only one side ever moved. The clone
     * <em>is</em> the common ancestor; this writes that down while it is still
     * true.
     *
     * <p>Two cases, one rule:
     *
     * <ul>
     *   <li><b>No record at all.</b> The unit's content is its baseline.</li>
     *   <li><b>A {@link MaterializationMode#COPY} record inherited from the
     *       home this one was copied from, describing content this copy does
     *       not hold.</b> That happens for the ordinary reason: a record says
     *       what a home was <em>handed</em>, and editing a unit in place does
     *       not update it — correctly, because a record is provenance and not a
     *       snapshot. But the inherited record describes a pair of homes this
     *       copy is not part of, and a baseline OLDER than the two homes' real
     *       common ancestor turns a clean fast-forward back into a conflict a
     *       human has to settle. Measured: a worktree whose content strictly
     *       CONTAINS the project's reported CONFLICTED and wrote nothing.
     *       Restating it costs nothing that was true — the copy really did
     *       start from these bytes, and so did the home it came from.</li>
     * </ul>
     *
     * <p>An inherited record that still describes this copy's content is left
     * exactly as it is: it is accurate, and it additionally names the home the
     * bytes came from, which is evidence a later reconcile can use and this
     * function cannot reconstruct.
     *
     * <p>{@link MaterializationMode#CHECKOUT} is never touched, whatever its
     * content says. A checkout's baseline is its git history, and restating it
     * as a tree digest would destroy the only thing that can answer whether its
     * commits have been pushed. {@link MaterializationMode#LINK} likewise: it
     * has no tree of its own to be the baseline of.
     *
     * <p>Records written here carry no {@code source} path on purpose. The
     * path would name the home this one was copied from, and a copied home that
     * names its origin is exactly the leak class {@code home verify} exists to
     * catch. The digests say everything the merge needs; the path said nothing
     * it uses.
     *
     * @return every unit whose baseline this call wrote or restated
     */
    public static List<UnitRef> recordCloneBaselines(SkillStore home) throws IOException {
        ChildHomeMaterializer materializer = new ChildHomeMaterializer(home, home);
        List<UnitRef> recorded = new ArrayList<>();
        for (UnitRef ref : unitDirectories(home)) {
            MaterializationRecord inherited =
                    materializer.readRecord(ref.name(), ref.kind()).orElse(null);
            if (inherited != null && !MaterializationMode.COPY.name().equals(inherited.mode())) {
                continue;
            }
            // A record this build cannot read is not a baseline (issue #46), and
            // this is the ONE place re-baselining one is sound rather than a
            // licence invented by the pass that wanted it: a clone's content is
            // what the home it was copied from held, by construction. The mode
            // check above still runs on the record as written, so a stale
            // CHECKOUT record is never restated as a copy baseline.
            if (!usableAsEvidence(inherited)) inherited = null;
            Path dir = home.unitDir(ref.name(), ref.kind()).toAbsolutePath().normalize();
            Fingerprint print = fingerprint(dir);
            if (inherited != null && print.digest().equals(inherited.contentDigest())) continue;
            materializer.writeRecord(ref.name(), ref.kind(), MaterializationMode.COPY, null,
                    gitStateOf(dir), print.digest(), print.digest(), print.entries(),
                    MaterializationRecord.COPIED);
            recorded.add(ref);
        }
        return recorded;
    }

    // ------------------------------------------------- home-to-home reconcile

    /** What reconciling one unit from the source home into this one did, or would do. */
    public enum SyncStatus {
        /** The destination already holds what the source holds. */
        UNCHANGED,
        /** The destination is a pristine copy and the source has moved on. */
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
        REMOVED_UPSTREAM,
        /**
         * One side reaches this unit through a symlink — the unit directory
         * itself, or the whole kind directory above it. Nothing is written and
         * nothing is claimed about the bytes.
         *
         * <p>This is a reported outcome rather than a skipped one on purpose.
         * A reconcile owns bytes on behalf of a home; a linked unit's bytes
         * belong to whatever the link points at, so a copy into it would write
         * through into a tree no report mentions, and a copy out of it would
         * claim a shared baseline with a home that does not own what it is
         * offering. {@link MaterializationMode#LINK} produces exactly this
         * shape, and so does a hand-made {@code ln -s}. The one thing that must
         * never happen is what happened before: the enumerator skipped it, the
         * report said {@code clean: true}, and the unit was never named.
         */
        LINKED
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
            return status == SyncStatus.HELD_BACK || status == SyncStatus.CONFLICTED
                    || status == SyncStatus.LINKED;
        }

        /**
         * The status word to print, given whether the run that produced this
         * outcome actually wrote.
         *
         * <h2>Why a status needs a tense at all</h2>
         *
         * <p>{@link SyncStatus} names a verdict about a pair of homes, and the
         * same verdict is reached by the run that performs the write and by
         * the dry run that only plans it. Printing the bare enum name gave
         * both the past tense: {@code home close-out} — documented "Writes
         * nothing; safe to run repeatedly", and measurably writing nothing —
         * announced {@code updated skill:&lt;unit&gt;} for a unit it had not
         * touched. An operator reading that concludes the refusal printed
         * beside it has already been remediated, and tears the worktree down.
         * The behaviour was right and the sentence was false, which is the
         * more dangerous of the two failures. Issue #133.
         *
         * <p>Only the writing statuses take the prefix. {@code held-back} and
         * {@code conflicted} describe what is true of the two homes right now,
         * and are equally true of a run that wrote and one that did not.
         *
         * <p>The JSON {@code status} field deliberately keeps the untensed
         * token: consumers branch on it, and {@code close-change.sh} is one of
         * them. What JSON carries instead is {@link #detail}, which was
         * rewritten to state a condition rather than an act.
         */
        public String statusLabel(boolean applied) {
            String word = status.name().toLowerCase().replace('_', '-');
            if (applied || !writes()) return word;
            return switch (status) {
                case UPDATED -> "would-update";
                case NEW -> "would-create";
                case MERGED -> "would-merge";
                default -> "would-" + word;
            };
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

        // Before anything reads bytes: a home that reaches this unit through a
        // link does not own the bytes on either end of it. Reported, never
        // skipped — see SyncStatus.LINKED and unitDirectories.
        Path sourceLink = symlinkOnUnitPath(parentStore, name, kind);
        Path destLink = symlinkOnUnitPath(childStore, name, kind);
        if (sourceLink != null || destLink != null) {
            return new UnitSync(name, kind, SyncStatus.LINKED, dest, List.of(), List.of(),
                    linkedDetail(sourceLink, destLink));
        }

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
                    "not in the destination home; the source holds it and this home does not");
        }

        Fingerprint dst = fingerprint(dest);
        if (src.digest().equals(dst.digest())) {
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "already byte-identical to the source");
        }
        if (gitTwinsDifferOnlyInBookkeeping(source, dest)) {
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "identical outside .git and standing on the same refs; the two copies differ "
                            + "only in git's own bookkeeping (an index, a reflog, a repack), which "
                            + "belongs to neither home");
        }

        // The destination's record is evidence about the pair (this
        // destination, the home named in it). Read it as evidence about THIS
        // source only when it can be shown to be — see the baseline rule. One
        // reading, shared with copyUnit and isLocallyModified.
        Disposal disposal = disposal(name, kind, record, source, dest, src, dst.digest());
        MaterializationRecord sourceRecord = disposal.sourceRecord();
        boolean recordIsAboutThisSource = disposal.recordIsAboutThisSource();
        String baseline = disposal.baseline();
        boolean destUntouched = disposal.destUntouched();
        if (destUntouched && recordIsAboutThisSource
                && src.digest().equals(record.sourceDigest())) {
            // The destination differs from the source only by work a previous
            // --merge folded in, and the source has not moved since. Copying the
            // source over it now would delete that work for no gain.
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    "the source has not moved since the last reconcile");
        }
        if (disposal.disposable()) {
            if (apply) writeCopy(name, kind, view, source, dest, src);
            return new UnitSync(name, kind, SyncStatus.UPDATED, dest,
                    changedFiles(dst.entries(), src.entries()), List.of(),
                    "the destination holds no local work, so a sync replaces it with the source copy");
        }

        // Everything below: the destination carries something this source
        // cannot be shown to have — either work made here, or a copy of a
        // THIRD home's work, which is indistinguishable from the first by any
        // measurement of the destination alone.
        if (!merge) {
            return new UnitSync(name, kind, SyncStatus.HELD_BACK, dest, List.of(), List.of(),
                    holdBackReason(record, baseline, destUntouched, recordIsAboutThisSource));
        }
        MergeBase base = mergeBase(record, recordIsAboutThisSource, sourceRecord);
        if (base == null || base.entries().isEmpty()) {
            return new UnitSync(name, kind, SyncStatus.CONFLICTED, dest, List.of(),
                    changedFiles(dst.entries(), src.entries()),
                    "both sides differ and neither home recorded a per-file baseline they can be "
                            + "shown to share, so there is no merge base; resolve it by hand or "
                            + "publish the edit with `skill-manager unit publish " + name + "`");
        }

        MergePlan plan = mergePlan(base.entries(), src.entries(), dst.entries());
        if (!plan.conflicts().isEmpty()) {
            return new UnitSync(name, kind, SyncStatus.CONFLICTED, dest, List.of(),
                    plan.conflicts(),
                    plan.conflicts().size() + " file(s) changed on both sides; nothing was written");
        }
        if (plan.take().isEmpty()) {
            // Every differing path had s == b: the source has not moved off the
            // baseline. What that PROVES depends on whose baseline it is, and
            // saying so is the whole of this branch.
            //
            // With a shared base the conclusion is sound: the destination is
            // demonstrably ahead and the source really has nothing to offer.
            //
            // With a base taken from the source's own record it is not. That
            // record says what the SOURCE was handed; nothing in it says the
            // destination ever stood there. "The destination moved past it" and
            // "the destination never received it" are the same arithmetic, and
            // announcing the first is how a byte stops travelling upward while
            // every re-run agrees it never had to.
            //
            // The status stays UNCHANGED anyway, and that is a measured choice
            // rather than a shrug. Reporting this as unresolved would block
            // `home close-out` for the ordinary, correct case it is far more
            // often: a ticket worktree that is merely BEHIND on a file some
            // other worktree contributed to the project home holds nothing that
            // removing it would destroy, and refusing its teardown would refuse
            // the flow this epic exists to make routine. So the outcome is
            // unchanged and the SENTENCE is honest — it no longer asserts a
            // direction the evidence cannot support, and it names what to run
            // when the direction turns out to be the other one.
            List<String> differing = changedFiles(dst.entries(), src.entries());
            return new UnitSync(name, kind, SyncStatus.UNCHANGED, dest, List.of(), List.of(),
                    base.shared()
                            ? "the destination is ahead of the source; the source has nothing to "
                                    + "contribute"
                            : "the source has not moved off the baseline it was handed, so this "
                                    + "pass has nothing to take — but that baseline is the "
                                    + "source's own record and says nothing about what this "
                                    + "destination ever held, so whether the destination is ahead "
                                    + "on " + String.join(", ", differing) + " or has simply never "
                                    + "received those bytes cannot be decided here. If it is the "
                                    + "latter, reconcile through a home that shares a baseline "
                                    + "with both, or `skill-manager unit publish " + name + "`.");
        }
        if (apply) {
            List<ViewEntry> merged = mergedView(view, dest, plan.take());
            Path staged = stage(merged, name, kind);
            try {
                Fingerprint stagedPrint = fingerprint(staged);
                carryOverUnownedTrees(dest, staged);
                swapIn(staged, dest);
                // The written tree is the merged one; the BASELINE recorded
                // against this source is the subset of paths these two homes
                // can be SHOWN to share. Recording the merged tree is CHM-10;
                // recording the source's whole tree is CHM-12. See the
                // <a href="#record-write">fourth decision</a>.
                writeMergeRecord(name, kind, source, dest, stagedPrint.digest(), src,
                        sharedAfterMerge(src.entries(), dst.entries(), plan.take(), base));
            } finally {
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
            }
        }
        return new UnitSync(name, kind, SyncStatus.MERGED, dest,
                List.copyOf(plan.take()), List.of(),
                plan.take().size() + " file(s) come from the source; local work is kept");
    }

    /** Which side is linked, where it points, and what to do about it. */
    private static String linkedDetail(Path sourceLink, Path destLink) {
        StringBuilder sb = new StringBuilder();
        if (sourceLink != null) sb.append("the source reaches it through the symlink ")
                .append(sourceLink).append(" -> ").append(linkTargetOf(sourceLink));
        if (sourceLink != null && destLink != null) sb.append("; ");
        if (destLink != null) sb.append("the destination reaches it through the symlink ")
                .append(destLink).append(" -> ").append(linkTargetOf(destLink));
        return sb + ". Nothing was written and no baseline was recorded: a reconcile moves bytes "
                + "between two homes that own them, and a link's bytes belong to what it points "
                + "at. Replace the link with a real directory (`skill-manager home clone`, or a "
                + "COPY materialization) to reconcile this unit.";
    }

    private static String linkTargetOf(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "(unreadable)";
        }
    }

    /** Stage the source view and swap it into place — the copy path, atomically. */
    private void writeCopy(String name, UnitKind kind, List<ViewEntry> view, Path source, Path dest,
                           Fingerprint src) throws IOException {
        Path staged = stage(view, name, kind);
        try {
            Fingerprint stagedPrint = fingerprint(staged);
            carryOverUnownedTrees(dest, staged);
            swapIn(staged, dest);
            writeCopyRecord(name, kind, source, dest, stagedPrint.digest(), src,
                    MaterializationRecord.COPIED);
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
        }
    }

    /** The source home's own record for this unit, or null. */
    private MaterializationRecord sourceRecordFor(String name, UnitKind kind) {
        return new ChildHomeMaterializer(parentStore, parentStore)
                .readRecord(name, kind).orElse(null);
    }

    private static boolean hasEntries(MaterializationRecord record) {
        return usableAsEvidence(record)
                && record.entryDigests() != null && !record.entryDigests().isEmpty();
    }

    /**
     * Whether {@code record} — written by the destination — is evidence about
     * <em>this</em> source, i.e. whether the baseline in it is a state the
     * source itself passed through. The pivot of the
     * <a href="#baseline-rule">baseline rule</a>, and the question CHM-9 and
     * CHM-10 both answered by not asking it.
     *
     * <p>Four ways to show it, and no fifth. Every one of them is local
     * evidence on disk; none of them is an assumption about who ran what:
     *
     * <ol>
     *   <li><b>The record names no source at all.</b> It is a home's own
     *       clone-time baseline ({@link #recordCloneBaselines}), which is what
     *       it held when it was copied — and therefore what the home it was
     *       copied from held too. This is the one case with no better witness
     *       available, and it is safe for the same reason a clone is: the bytes
     *       are still in the home this one came from. A teardown of that home
     *       is what {@code home close-out} gates.</li>
     *   <li><b>The record names this source.</b> These bytes came from here, or
     *       a merge with this source recorded the state it then held.</li>
     *   <li><b>The source is standing on the baseline right now.</b> Nothing to
     *       argue about: it is in the source's tree as we read it.</li>
     *   <li><b>Both homes recorded the same baseline.</b> Two homes handed the
     *       same content — the ordinary shape of a clone that inherited its
     *       records, and the reason the epic's core flow does not turn into a
     *       conflict when a project home's record names the root it came
     *       from.</li>
     * </ol>
     */
    private static boolean describesSource(MaterializationRecord record, Path sourceUnit,
                                           Fingerprint src, MaterializationRecord sourceRecord) {
        if (!hasEntries(record)) return false;
        String named = record.source();
        if (named == null || named.isBlank()) return true;
        if (samePathString(named, sourceUnit)) return true;
        if (record.entryDigests().equals(src.entries())) return true;
        return hasEntries(sourceRecord)
                && record.entryDigests().equals(sourceRecord.entryDigests());
    }

    /**
     * The per-file common ancestor of the two homes' copies of one unit — a
     * state BOTH of them passed through, or nothing.
     *
     * <p>The destination's own record first, when it is evidence about this
     * source ({@link #describesSource}): it is the tightest ancestor available,
     * because it says what these two homes last shared. A record about some
     * other pair of homes is not an ancestor of this one at all — measured
     * against it the source looks like the only side that moved, the other
     * home's work is taken away, and no conflict is ever reported. That is
     * CHM-10, and it is why this returns the source's record instead rather
     * than the destination's whenever the destination's cannot be shown to be
     * shared.
     *
     * <p>The source's record is sound as a base for a reason worth stating: it
     * records what the SOURCE was handed, so the source passed through it by
     * construction, and the destination's half of "only one side moved" is
     * enforced per path by {@link #mergePlan}, which takes a path only where
     * the destination is standing on the base. It may be older than the true
     * common ancestor; an older ancestor produces more conflicts and never
     * fewer, and that is the direction this whole mechanism errs in.
     */
    private static MergeBase mergeBase(MaterializationRecord destRecord,
                                       boolean destRecordIsAboutThisSource,
                                       MaterializationRecord sourceRecord) {
        if (hasEntries(destRecord) && destRecordIsAboutThisSource) {
            return new MergeBase(destRecord.entryDigests(), true);
        }
        return hasEntries(sourceRecord)
                ? new MergeBase(sourceRecord.entryDigests(), false)
                : null;
    }

    /**
     * The per-file base a merge measured against, and whether it is a state the
     * DESTINATION is on record as having shared with this source.
     *
     * <p>Carrying that flag rather than only the map is what keeps two very
     * different situations from being reported as one. A shared base makes
     * {@code s == b} mean "the source has not moved and the destination is
     * ahead". A base taken from the source's own record makes the identical
     * comparison mean only "the source has not moved off what it was handed" —
     * the destination may be ahead, or it may never have received the byte at
     * all, and nothing on disk distinguishes those. Both the report and
     * {@link #sharedAfterMerge} need to know which one they are looking at.
     *
     * @param entries relative path → digest of the state the base names
     * @param shared  true when the base came from the destination's own record
     *                and that record was shown to be evidence about this source
     */
    private record MergeBase(java.util.Map<String, String> entries, boolean shared) {}

    /**
     * The per-path baseline a completed merge may write down: the
     * <a href="#record-write">fourth decision</a> of the baseline rule.
     *
     * <p>A record's {@code entryDigests} is a claim that the destination and
     * the named source both stood on these bytes. Writing the source's whole
     * tree there — which is what a merge did until CHM-12 — claims it for
     * paths the merge explicitly DECLINED to write, and a claim about bytes the
     * destination has never held is a licence for the next reconcile in the
     * opposite direction to overwrite them. It is CHM-10's failure mode
     * reintroduced through the record-write side rather than the base-selection
     * side, and it costs the same thing: an edit nobody ever sees again.
     *
     * <p>So a path is claimed only where this pass can show both homes stood on
     * it, and there are exactly three such showings:
     *
     * <ol>
     *   <li><b>The merge took it.</b> The destination is standing on the
     *       source's byte because this pass just put it there.</li>
     *   <li><b>The two sides already agreed.</b> Nothing to argue about: both
     *       trees were read this pass and hold the same digest.</li>
     *   <li><b>The source has not moved off a SHARED base.</b> {@code s == b}
     *       where {@code b} came from the destination's own record about this
     *       source — so the destination is on record as having stood on it, and
     *       the source is standing on it now. This is the case that keeps a
     *       repeated merge from the same source working: the file the agent
     *       owns keeps its ancestor, so the next pass still reads "only the
     *       source moved" for the file upstream owns. It is <em>not</em>
     *       available when the base is the source's own record, which says
     *       what the SOURCE was handed and nothing about this destination.</li>
     * </ol>
     *
     * <p>Everything else is omitted. A missing path reads as "no baseline
     * here", which routes the next merge on that path to a conflict — the
     * direction this whole mechanism errs in, and the only one that cannot
     * silently destroy work.
     */
    private static java.util.LinkedHashMap<String, String> sharedAfterMerge(
            java.util.Map<String, String> source, java.util.Map<String, String> dest,
            java.util.Set<String> taken, MergeBase base) {
        java.util.LinkedHashMap<String, String> shared = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : source.entrySet()) {
            String path = entry.getKey();
            String digest = entry.getValue();
            boolean justWritten = taken.contains(path);
            boolean alreadyAgreed = digest.equals(dest.get(path));
            boolean sourceStillOnASharedBase =
                    base.shared() && digest.equals(base.entries().get(path));
            if (justWritten || alreadyAgreed || sourceStillOnASharedBase) {
                shared.put(path, digest);
            }
        }
        return shared;
    }

    /** Why a plain (non-merge) pass left this unit alone, in the caller's terms. */
    private static String holdBackReason(MaterializationRecord record, String baseline,
                                         boolean destUntouched, boolean recordIsAboutThisSource) {
        // Before the generic sentences: a cause the operator can neither see on
        // the unit nor act on by looking for their own edit (issue #46). It has
        // to come first, because every message below is TRUE of this case and
        // all of them send the reader hunting for a change nobody made.
        String stale = staleSchemaNote(record);
        if (stale != null) {
            return stale + " — re-run with --merge to reconcile it, or refresh the unit from its "
                    + "store (a unit that is still a pristine copy re-baselines itself with no "
                    + "further hold-back)";
        }
        if (baseline == null) {
            // Two different situations wore this one sentence until issue #43.
            // A destination with no record of its own is the ORDINARY shape of
            // an installed root home, not a suspicious one -- and it is now
            // fast-forwarded whenever the SOURCE's record shows the source held
            // exactly these bytes (Disposal.sourceHeldTheseBytes). Reaching
            // here means that showing failed too, so the destination really is
            // holding something neither home can account for, and saying which
            // is the difference between a message an operator can act on and
            // the one that made the upward sync look broken.
            return "this home has no materialization record of its own and its current bytes are "
                    + "not a state the source is on record as having held, so replacing them "
                    + "could delete work that exists nowhere else; re-run with --merge to "
                    + "reconcile it";
        }
        if (destUntouched && !recordIsAboutThisSource) {
            return "its content came from " + record.source() + ", which this source has never "
                    + "held, so replacing it would delete work that may exist nowhere else; "
                    + "re-run with --merge to reconcile it";
        }
        if (destUntouched && record.isMergeResult()) {
            return "the result of an earlier merge, so it is a copy of no home; "
                    + "re-run with --merge to reconcile it";
        }
        return "locally modified; re-run with --merge to three-way merge it";
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
     * What {@link #mirrorExistingShim} did, so a caller can say so.
     *
     * <p>It used to be {@code void} and to signal the one interesting case by
     * throwing. That is what made {@code project sync} non-idempotent: see
     * {@link #KEPT_LOCAL}.
     */
    public enum ShimOutcome {
        /** The parent store has no such entry; nothing to mirror. */
        NO_SOURCE,
        /** Degenerate layout — source and destination are the same entry. */
        SAME_ENTRY,
        /** The child now links at the parent's entry. */
        MIRRORED,
        /** The child already holds this exact shim; nothing to do. */
        UNCHANGED,
        /**
         * The child home holds its own real file where the shim would go, and
         * it is kept.
         *
         * <p>This is the state {@code sync --force-scripts} leaves in every
         * home it runs in: a {@code skill-script} CLI dep is a generated shell
         * script, a regular file, not a symlink. A child home is a home — an
         * agent launches with it, {@code bootstrap-home.sh} tells the operator
         * to re-provision its tools that way — so the shim it generated for
         * itself is the normal content of {@code <child>/bin/cli/<dep>}, not
         * an obstruction.
         *
         * <p>Replacing it would be wrong twice over: it deletes a working tool
         * this home provisioned, and it puts a live symlink into the parent
         * home in a directory that exists to reach no other home — the very
         * thing {@code home verify} refuses. So it is kept, and reported.
         * {@code project sync --rebuild} is the escape hatch for a shim that
         * really is stale; it tears the child home down first.
         */
        KEPT_LOCAL;

        /** True when the child home's own entry was kept over the parent's. */
        public boolean keptLocal() { return this == KEPT_LOCAL; }
    }

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
     *
     * <p><b>Never fails on an occupied destination.</b> {@link #linkPath}
     * reconciles a symlink and a directory but throws on a regular file, and a
     * regular file is exactly what a {@code skill-script} CLI dep is. One
     * {@code project sync} into a home that had ever provisioned its own
     * {@code computeq} therefore aborted the whole realization — and, because
     * the failure is then stamped on every unit the project claims, it aborted
     * it loudly and permanently. A projection whose destination may legitimately
     * be occupied has to reconcile; issue #144.
     */
    public ShimOutcome mirrorExistingShim(Path source, Path dest) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return ShimOutcome.NO_SOURCE;
        Path from = source.toAbsolutePath().normalize();
        Path to = dest.toAbsolutePath().normalize();
        // Degenerate layout (child home == parent store): source and dest are
        // the same entry. Replacing it would delete the parent's shim and leave
        // a self-referential link behind.
        if (from.equals(to) || sameRealPath(from, to)) return ShimOutcome.SAME_ENTRY;
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(to)
                && !Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
            // Byte-identical means an earlier pass already put this shim here
            // (linkPath falls back to a copy where symlinks are unavailable),
            // so there is nothing to reconcile and nothing to report.
            return sameContent(from, to) ? ShimOutcome.UNCHANGED : ShimOutcome.KEPT_LOCAL;
        }
        linkPath(from, to);
        return ShimOutcome.MIRRORED;
    }

    /** Byte equality of two regular files; false when either cannot be read. */
    private static boolean sameContent(Path a, Path b) {
        try {
            if (!Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.size(a) != Files.size(b)) return false;
            return Files.mismatch(a, b) < 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    // ------------------------------------------------------------ interns

    /**
     * Replaces the child unit with a symlink at the parent store.
     *
     * <p>Subject to the same {@link Disposal} as a copy, and for the same
     * reason: {@link #linkPath} deletes a real directory to put a link where it
     * stood, so a home that was materialized as {@code COPY} and then edited
     * would have its edits deleted by a later pass that merely asked for
     * {@code LINK}. Only a tree this pass can show the parent store passed
     * through is disposable; everything else is reported and left alone.
     */
    private UnitOutcome linkUnit(String name, UnitKind kind, Path source, Path dest)
            throws IOException {
        if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS) && !sameRealPath(source, dest)) {
            MaterializationRecord record = readRecord(name, kind).orElse(null);
            if (!disposalOf(name, kind, record, dest, treeDigest(dest)).disposable()) {
                return heldBack(name, kind, dest,
                        "a real directory the parent store cannot be shown to have passed through; "
                                + "not replaced with a symlink into it");
            }
        }
        linkPath(source, dest);
        writeRecord(name, kind, MaterializationMode.LINK, source, GitState.NONE, null, null);
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
                // COPY_ATTRIBUTES is the APFS clone path and is load-bearing
                // for cost, not tidiness — see Fs#copyRecursive's javadoc and
                // the home.clone.costs.far.less.than.a.copy graph node.
                // Measured: 0.01 MB with the flag, 67.11 MB without, for one
                // 64 MB file. MaterializationMode.DEFAULT_MODE is COPY on
                // purpose, and a copy-per-worktree model is only affordable
                // because these copies share blocks.
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
                        gitHistoryOf(dest), null, null);
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
        writeRecord(name, kind, MaterializationMode.CHECKOUT, source,
                gitHistoryOf(dest), null, null);
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
        Fingerprint sourcePrint = fingerprintOf(view, java.util.Set.of());
        String sourceDigest = sourcePrint.digest();
        MaterializationRecord record = readRecord(name, kind).orElse(null);
        String currentDigest = destIsDir ? treeDigest(dest) : null;
        Disposal disposal = disposal(name, kind, record, source, destIsDir ? dest : null,
                sourcePrint, currentDigest);
        String baseline = disposal.baseline();

        if (destIsDir && baseline != null) {
            if (!disposal.disposable()) {
                // ONE test, three sentences. The conjunction is Disposal's, not
                // this method's: `home sync` and `project resolve` read the same
                // value or they eventually disagree about the units that matter,
                // which is what CHM-15 was. What varies here is only which of
                // the three ways it can fail the reader is looking at.
                //
                // The middle case is the CHM-15 shape itself: pristine by its
                // own record, and a copy of no store this pass can name.
                // `home sync` leaves exactly that behind when a worktree merges
                // its work up into a project home — the record then names the
                // WORKTREE, and reading it as a licence to refresh from the
                // parent store deletes that work while calling itself routine
                // reconciliation.
                if (!disposal.destUntouched()) return heldBack(name, kind, dest);
                return heldBack(name, kind, dest, disposal.mergeResult()
                        ? "the result of an earlier merge, so it is a wholesale copy of no home; "
                                + "send it on with `skill-manager unit publish " + name + "` (or "
                                + "`home sync`) before this home is refreshed from the store"
                        : "its content came from " + record.source() + ", which this store has "
                                + "never held, so replacing it would delete work that may exist "
                                + "nowhere else; send it on with `skill-manager unit publish "
                                + name + "` (or `home sync`) first");
            }
            // "The source has not moved since it wrote this record" is only a
            // reason to do nothing when the destination is still standing on
            // what that record describes. Reached with the destination moved —
            // possible now that the SOURCE's record can license a refresh —
            // this would report UNCHANGED over a stale tree.
            if (disposal.destUntouched() && sourceDigest.equals(record.sourceDigest())) {
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "already matches the parent store");
            }
        }

        Path staged = stage(view, name, kind);
        try {
            Fingerprint stagedPrint = fingerprint(staged);
            if (destIsDir && baseline == null && !disposal.disposable()) {
                // No trustworthy provenance. Adopt the directory only when it
                // is exactly what we would have written; otherwise refuse,
                // because we cannot tell an agent's edits from a stale copy.
                //
                // `!disposal.disposable()` is what routes past this: a child
                // home with no record of its own, whose bytes the PARENT STORE
                // is on record as having held, is disposable by the same
                // baseline rule the reconcile applies -- and Disposal is
                // deliberately the single reading of that rule, so this writer
                // may not answer it differently from `home sync` (CHM-15 was
                // exactly that divergence). Issue #43.
                if (!stagedPrint.digest().equals(currentDigest)) {
                    // Same message as `home sync` gives, from the same one
                    // function, when the reason is a record this build cannot
                    // read rather than an edit (issue #46). The two surfaces
                    // saying different things about one cause is how "spurious
                    // locally-modified" became a thing nobody could act on.
                    String stale = staleSchemaNote(record);
                    return stale != null
                            ? heldBack(name, kind, dest, stale + " — send anything of yours on with "
                                    + "`skill-manager unit publish " + name + "` (or `home sync "
                                    + "--merge`) first; an untouched copy re-baselines itself here "
                                    + "with no further hold-back")
                            : heldBack(name, kind, dest);
                }
                writeCopyRecord(name, kind, source, dest, stagedPrint.digest(), sourcePrint,
                        MaterializationRecord.COPIED);
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "adopted an existing identical copy");
            }
            if (destIsDir) carryOverUnownedTrees(dest, staged);
            swapIn(staged, dest);
            writeCopyRecord(name, kind, source, dest, stagedPrint.digest(), sourcePrint,
                    MaterializationRecord.COPIED);
            return new UnitOutcome(name, kind, Status.MATERIALIZED, dest,
                    destIsLink ? "replaced a symlink into the parent store" : "copied from the parent store");
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) Fs.deleteRecursive(staged);
        }
    }

    private static UnitOutcome heldBack(String name, UnitKind kind, Path dest) {
        return heldBack(name, kind, dest, "local changes in the child home");
    }

    private static UnitOutcome heldBack(String name, UnitKind kind, Path dest, String detail) {
        Log.warn("child home %s:%s — left as-is, not refreshed from the parent store: %s (%s)",
                kind.name().toLowerCase(), name, detail, dest);
        return new UnitOutcome(name, kind, Status.SKIPPED_LOCAL_CHANGES, dest, detail);
    }

    /**
     * Whether a record's digests may be read at all — the one place the
     * {@link #RECORD_SCHEMA_VERSION} gate lives.
     *
     * <p>Consulted by every reader of a digest and by nothing else:
     * {@link #copyBaseline}, {@link #hasEntries} (which is what
     * {@link #describesSource} and {@link #mergeBase} go through) and
     * {@link #sourceHeldTheseBytes}. Those are exactly the three showings the
     * <a href="#baseline-rule">baseline rule</a> accepts, so gating them here
     * gates every licence to destroy a destination tree at once, rather than at
     * three call sites where one would eventually be missed.
     */
    private static boolean usableAsEvidence(MaterializationRecord record) {
        return record != null && record.schemaVersion() == RECORD_SCHEMA_VERSION;
    }

    /**
     * The sentence an operator needs when a hold-back's cause is a record this
     * build cannot read, or null when that is not the cause.
     *
     * <p>Issue #46 is really two defects and only one of them is the hold-back.
     * A pre-#41 record makes a unit differ from its own record for a reason
     * nothing on the unit's surface explains: no file was edited, no command was
     * run, and the report said {@code locally modified}. An operator reading
     * that looks for their own edit, does not find one, and cannot act. Naming
     * the schema change turns it into a one-line, one-time, understood event.
     */
    private static String staleSchemaNote(MaterializationRecord record) {
        if (record == null || usableAsEvidence(record)) return null;
        return "its materialization record is schema " + record.schemaVersion()
                + " and this build reads " + RECORD_SCHEMA_VERSION
                + " — the digests in it were computed over a different set of files "
                + "(re-derivable build output used to count as unit content, issue #41), so they "
                + "are not evidence about these bytes and nothing may be replaced on their "
                + "authority. Nothing was written";
    }

    /** The recorded content digest, or null when the record cannot be trusted. */
    private static String copyBaseline(MaterializationRecord record) {
        if (!usableAsEvidence(record)) return null;
        if (!MaterializationMode.COPY.name().equals(record.mode())) return null;
        if (record.contentDigest() == null || record.sourceDigest() == null) return null;
        return record.contentDigest();
    }

    /**
     * The one question every writer that destroys a destination tree has to ask,
     * answered in one place: <b>may this pass destroy what is in the
     * destination?</b>
     *
     * <p>This is the <a href="#baseline-rule">baseline rule</a>'s first decision
     * as a value rather than as an inlined conjunction. It exists because the
     * conjunction was inlined twice and the two copies did not agree:
     * {@link #reconcile} asked all three parts of it, while {@link #copyUnit} —
     * the downward materialization {@code project resolve} and {@code project
     * sync} run — asked only the first, and {@link #isLocallyModified}, the
     * predicate a prune and a teardown consult, asked only the first too. A unit
     * a worktree had merged up into a project home is <em>pristine</em> by its
     * own record while being a copy of no store on earth, so the missing two
     * parts were exactly the difference between "refresh this" and "delete work
     * that exists nowhere else" (CHM-15). Three readings of one rule is how a
     * rule gets two answers; there is now one reading.
     *
     * @param baseline               the trusted content digest, or null when the
     *                               record cannot be read as one at all
     * @param destUntouched          the destination still holds exactly the bytes
     *                               its own record says were written there
     * @param recordIsAboutThisSource that record is evidence about THIS source —
     *                               see {@link #describesSource}
     * @param mergeResult            the destination's tree was produced by a
     *                               merge, so it is a wholesale copy of no home
     * @param sourceHeldTheseBytes   the SOURCE's own record names the tree the
     *                               destination is standing on now — the second
     *                               showing, and the only one available against
     *                               an installed home that has no record of its
     *                               own. See {@link #sourceHeldTheseBytes}.
     * @param sourceRecord           the source home's own record, carried so the
     *                               merge base is chosen from the same reading
     */
    record Disposal(String baseline, boolean destUntouched, boolean recordIsAboutThisSource,
                    boolean mergeResult, boolean sourceHeldTheseBytes,
                    MaterializationRecord sourceRecord) {

        /**
         * True only where the source can be shown to have passed through the
         * bytes now in the destination. Everything else holds back.
         *
         * <p>Two independent showings, and the rule is satisfied by either
         * because the rule is about the SOURCE, not about which home wrote the
         * evidence down:
         *
         * <ol>
         *   <li>The destination's own record — it still holds what that record
         *       says was written there, that record is evidence about this
         *       source, and the tree is not a merge result.</li>
         *   <li>{@link #sourceHeldTheseBytes} — the SOURCE's record says the
         *       source once held exactly the tree the destination is standing
         *       on now.</li>
         * </ol>
         */
        boolean disposable() {
            return (destUntouched && recordIsAboutThisSource && !mergeResult)
                    || sourceHeldTheseBytes;
        }
    }

    /**
     * The second showing of the <a href="#baseline-rule">baseline rule</a>'s
     * first decision: <b>the source's own record names the bytes the
     * destination is standing on right now.</b>
     *
     * <h2>The no-op this exists to remove</h2>
     *
     * <p>A root home is <em>installed</em> into, never materialized into, so it
     * carries no per-unit record at all. With only the destination-record
     * showing available, {@code home sync --from <project> --to ~/.skill-manager}
     * reported <em>every</em> shared unit {@code held-back} — "no usable
     * materialization record, so its contents cannot be shown to be disposable"
     * — and exited 0 having reconciled nothing. 6, 7 and 5 units on three real
     * repositories. The documented upward sync was a silent no-op against the
     * only destination an operator actually has, and the same sync against a
     * throwaway root produced by {@code home clone} worked, because a clone
     * records baselines and an installed home has none. Issue #43.
     *
     * <h2>Why this is not "adopting a baseline", which would be unsafe</h2>
     *
     * <p>Writing a baseline into the record-less destination — asserting that
     * the two homes shared bytes they may never have shared — is the move this
     * deliberately does NOT make. It would be a claim invented by the pass that
     * needed it, it would persist, and the next reconcile in either direction
     * would read it as licence. Nothing is written here. This is a question
     * asked and answered from evidence already on disk, once, per pass.
     *
     * <p>{@code contentDigest} and not {@code sourceDigest}, and the difference
     * is the whole safety argument. {@code contentDigest} is defined as "the
     * bytes this reconcile wrote HERE" — into the source home — so the source
     * demonstrably held them. {@code sourceDigest} is the tree the source was
     * HANDED, which for a merge is a tree the source never held; reading that
     * one would assert exactly the thing this class exists to stop asserting.
     * It is also a whole-tree digest, which {@code entryDigests} is not:
     * {@code entryDigests} is partial by design after a merge, so matching a
     * destination tree against it would compare a full tree with a subset.
     *
     * <p>What it licenses: the reconcile may destroy the destination's bytes
     * B. B is exactly what the source's record says the source was standing on
     * when that record was written. So the destroyed bytes are bytes the source
     * passed through — which is the rule, word for word, and the same standard
     * the destination-record showing meets. Every hazardous shape stays
     * blocked, because all of them make the destination hold something else:
     * a THIRD home's work merged into the destination since, an edit made in
     * the destination, or an upstream change — each moves the destination's
     * digest off the source's recorded content and routes the unit back to
     * held-back or to a conflict a human resolves.
     */
    private static boolean sourceHeldTheseBytes(MaterializationRecord sourceRecord,
                                                String destDigest) {
        if (!usableAsEvidence(sourceRecord) || destDigest == null) return false;
        if (!MaterializationMode.COPY.name().equals(sourceRecord.mode())) return false;
        String held = sourceRecord.contentDigest();
        return held != null && held.equals(destDigest);
    }

    /**
     * Compute {@link Disposal} for one unit. {@code destDigest} is the
     * destination tree's digest, or null when there is no destination tree.
     */
    private Disposal disposal(String name, UnitKind kind, MaterializationRecord record,
                              Path source, Path dest, Fingerprint src, String destDigest)
            throws IOException {
        MaterializationRecord sourceRecord = sourceRecordFor(name, kind);
        String baseline = copyBaseline(record);
        return new Disposal(
                baseline,
                destUntouched(record, baseline, dest, destDigest),
                describesSource(record, source, src, sourceRecord),
                record != null && record.isMergeResult(),
                sourceHeldTheseBytes(sourceRecord, destDigest),
                sourceRecord);
    }

    /**
     * Whether the destination still holds exactly the bytes its own record says
     * were written there — the first of {@link Disposal}'s two showings, and the
     * one place the digest question and the git question meet.
     *
     * <p>The digest answers it for an ordinary tree. For a tree carrying its own
     * {@code .git} the digest CANNOT answer it — git rewrites itself on every
     * command, so the digest says "edited" forever (issue #29) — and
     * {@link #gitCopyIsUntouched} is asked instead. Asked here rather than at
     * each of the four readers, for the same reason {@link Disposal} exists at
     * all: three inlined readings of one rule is how a rule gets two answers.
     *
     * <p>The digest is tried FIRST and the git route is a fallback, never an
     * override. A tree whose whole digest still matches is untouched by the
     * stricter measure already, and a git route that could turn a digest MATCH
     * into "modified" would be a new way to hold a unit back rather than a fix
     * for one.
     */
    private static boolean destUntouched(MaterializationRecord record, String baseline,
                                         Path dest, String destDigest) throws IOException {
        if (baseline != null && baseline.equals(destDigest)) return true;
        if (dest == null || !carriesGitDirectory(dest)) return false;
        return gitCopyIsUntouched(dest, record);
    }

    /**
     * The same computation for a caller that has no source fingerprint in hand —
     * {@link #isLocallyModified}, which is asked about units whose source may not
     * even exist any more (a dependency the project dropped, a unit uninstalled
     * upstream). A source that is not there cannot be shown to have passed
     * through anything, so it contributes no evidence: the empty fingerprint
     * leaves {@link #describesSource}'s "the source is standing on it right now"
     * arm false and the name-based arms intact, which is exactly the reading
     * that lets an ordinary pristine copy still be pruned while a tree some
     * other home contributed is not.
     */
    private Disposal disposalOf(String name, UnitKind kind, MaterializationRecord record,
                                Path dest, String destDigest) throws IOException {
        Path source = parentStore.unitDir(name, kind).toAbsolutePath().normalize();
        Fingerprint src = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                ? fingerprintOf(materializedView(source), java.util.Set.of())
                : new Fingerprint(null, new java.util.LinkedHashMap<>());
        return disposal(name, kind, record, source, dest, src, destDigest);
    }

    /**
     * Record for a {@link MaterializationMode#COPY}.
     *
     * <p>Two digests with two different jobs, and the split is the
     * <a href="#baseline-rule">baseline rule</a> in one method signature:
     * {@code contentDigest} is what was written HERE (evidence about this home
     * alone, read to tell an untouched tree from an edited one), while
     * {@code shared} is the SOURCE's tree — the state the two homes now share,
     * which is what a later three-way merge against this same source must
     * measure from. For a wholesale copy they describe the same tree; for a
     * merge result they must not.
     */
    private void writeCopyRecord(String name, UnitKind kind, Path source, Path dest,
                                 String contentDigest,
                                 Fingerprint shared, String reconcileKind) throws IOException {
        writeRecord(name, kind, MaterializationMode.COPY, source, gitStateOf(dest),
                shared.digest(), contentDigest, shared.entries(), reconcileKind);
    }

    /**
     * Record for a completed three-way merge.
     *
     * <p>Separate from {@link #writeCopyRecord} because the two differ in
     * exactly the place the <a href="#record-write">fourth decision</a> is
     * about, and a single method taking "the entries" would have let a caller
     * pass the source's whole fingerprint by accident — which is the defect.
     * A wholesale copy wrote the source's tree, so the source's tree IS what
     * the two homes share; a merge wrote a tree neither home held, so what
     * they share has to be computed ({@link #sharedAfterMerge}) and is a
     * SUBSET of the source's paths.
     *
     * <p>{@code sourceDigest} stays the source's whole-tree digest: it answers
     * a different question ("has the source moved at all since this record was
     * written"), and narrowing it to the shared subset would make a source that
     * changed a held-back path look unmoved.
     */
    private void writeMergeRecord(String name, UnitKind kind, Path source, Path dest,
                                  String contentDigest,
                                  Fingerprint src, java.util.Map<String, String> shared)
            throws IOException {
        writeRecord(name, kind, MaterializationMode.COPY, source, gitStateOf(dest), src.digest(),
                contentDigest, shared, MaterializationRecord.MERGED);
    }

    /**
     * The git-shaped evidence a record carries about the tree that was written:
     * the revision it stands on and the digest of everything outside
     * {@code .git}. {@link #NONE} means "this tree is not a git repository, or
     * git could not answer", which every reader takes as no evidence.
     */
    private record GitState(String revision, String historyDigest, String worktreeDigest) {
        static final GitState NONE = new GitState(null, null, null);
    }

    /**
     * Read {@link GitState} off a tree that is on disk right now.
     *
     * <p>Taken from the DESTINATION after the swap rather than from the staged
     * copy, because that is what the record is evidence about: "the bytes we
     * wrote HERE". A staged tree and the live one are the same content by
     * construction, but only one of them is the thing a later pass will measure.
     */
    private static GitState gitStateOf(Path tree) throws IOException {
        GitState history = gitHistoryOf(tree);
        if (history == GitState.NONE) return GitState.NONE;
        return new GitState(history.revision(), history.historyDigest(), worktreeDigest(tree));
    }

    /**
     * The history half alone — what a {@link MaterializationMode#CHECKOUT} record
     * carries, since a checkout's baseline IS its history and it has no
     * worktree-digest baseline to be measured against.
     *
     * <p>Written by {@code checkoutUnit} on every pass, which is what makes a
     * checkout whose record predates {@code historyDigest} heal on the first
     * resolve rather than stay held back.
     */
    private static GitState gitHistoryOf(Path tree) {
        if (tree == null || !carriesGitDirectory(tree)) return GitState.NONE;
        if (!GitOps.isAvailable() || !GitOps.isGitRepo(tree)) return GitState.NONE;
        String history = gitHistoryDigest(tree);
        if (history == null) return GitState.NONE;
        return new GitState(GitOps.headHash(tree), history, null);
    }

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             GitState git, String sourceDigest, String contentDigest)
            throws IOException {
        writeRecord(name, kind, mode, source, git, sourceDigest, contentDigest, null, null);
    }

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             GitState git, String sourceDigest, String contentDigest,
                             java.util.Map<String, String> entryDigests, String reconcileKind)
            throws IOException {
        GitState state = git == null ? GitState.NONE : git;
        Path file = recordFile(name, kind);
        Fs.ensureDir(file.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),
                new MaterializationRecord(
                        RECORD_SCHEMA_VERSION,
                        name,
                        kind.name(),
                        mode.name(),
                        source == null ? null : source.toString(),
                        state.revision(),
                        sourceDigest,
                        contentDigest,
                        state.worktreeDigest(),
                        state.historyDigest(),
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

    /**
     * Whether a unit-relative path is something a reconcile does not own.
     *
     * <p>Asked at the top of BOTH walkers, so the answer is the same on the
     * source side and the destination side by construction. A path that is
     * unowned is invisible to this class in every direction at once: it is not
     * fingerprinted (so it cannot make a unit look edited), not copied (so it
     * cannot travel between homes), and not destroyed (see
     * {@link #carryOverUnownedTrees}). Owning it on one side only is what
     * produced issue #41 — the cloner skipped {@code .gradle} inside a unit
     * while the reconcile hashed it, so one run of {@code discover.py} in a
     * ticket worktree made {@code home close-out} unsatisfiable: the unit came
     * back {@code conflicted}, the printed remedy named
     * {@code executionHistory.bin} as a file to "resolve", running that remedy
     * verbatim exited 1, and {@code home sync --merge} then reported nothing
     * was written.
     *
     * <p>The list is {@link Rederivable}, which is also what {@code HomeCloner}
     * reads, and its javadoc is where the reasoning lives — including the
     * loudest part: <b>{@code .git} is not in it, and adding it would be a
     * data-loss defect</b> (issue #29), because a unit whose agent committed
     * work would then read as unmodified and the next teardown would take the
     * commits with it.
     */
    private static boolean isUnowned(String rel) {
        return Rederivable.isDerived(rel);
    }

    /**
     * Move every unowned tree out of {@code dest} and into {@code staged}, at
     * the same relative path, immediately before the two are swapped.
     *
     * <p>This is the other half of {@link #isUnowned}, and without it the
     * exclusion would be half a rule. {@link #swapIn} replaces the destination
     * WHOLESALE: the staged tree was built from a view that omits these paths,
     * so a refresh would delete the destination's {@code .gradle/},
     * {@code build/} and {@code .venv/} as a side effect of not owning them.
     * "Not mine to compare" and "mine to destroy" cannot both be true of the
     * same bytes. Re-derivable is not the same as disposable-without-notice:
     * rebuilding a venv is minutes, and a refresh that silently did it would
     * train whoever hit it to stop running refreshes.
     *
     * <p>Only the top-most unowned entry on each branch is moved, because
     * moving {@code .venv} carries everything under it. Best-effort by design —
     * a move that fails leaves the tree where it was and costs a rebuild, which
     * must not fail a reconciliation that has already succeeded.
     */
    private static void carryOverUnownedTrees(Path dest, Path staged) throws IOException {
        if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return;
        for (String rel : unownedRoots(dest)) {
            Path from = dest.resolve(rel);
            Path to = staged.resolve(rel);
            try {
                Files.createDirectories(to.getParent());
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.move(from, to);
            } catch (IOException move) {
                Log.warn("child home: could not carry %s across the swap in %s (%s) — it is "
                        + "re-derivable, so rebuild it rather than looking for it", rel, dest,
                        move.getMessage());
            }
        }
    }

    /** Top-most unowned entries under {@code root}, as unit-relative paths. */
    private static List<String> unownedRoots(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        collectUnownedRoots(root, "", out);
        return out;
    }

    private static void collectUnownedRoots(Path dir, String rel, List<String> out)
            throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        for (Path child : listSorted(dir)) {
            String childRel = join(rel, child.getFileName().toString());
            if (isUnowned(childRel)) {
                out.add(childRel);
                continue;
            }
            collectUnownedRoots(child, childRel, out);
        }
    }

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
        if (isUnowned(rel)) return;
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
            List<Path> children = listSorted(src);
            List<ViewEntry> below = new ArrayList<>();
            for (Path child : children) {
                walk(child, join(rel, child.getFileName().toString()), unitRootReal, expanding,
                        below);
            }
            if (!rel.isEmpty() && emitDirectory(children, below)) {
                out.add(new ViewEntry(rel, EntryKind.DIR, src, null, false));
            }
            out.addAll(below);
            return;
        }
        out.add(new ViewEntry(rel, EntryKind.FILE, src, null, Files.isExecutable(src)));
    }

    private static void walkPlain(Path src, String rel, List<ViewEntry> out) throws IOException {
        if (isUnowned(rel)) return;
        if (Files.isSymbolicLink(src)) {
            out.add(new ViewEntry(rel, EntryKind.LINK, src, Files.readSymbolicLink(src), false));
            return;
        }
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> children = listSorted(src);
            List<ViewEntry> below = new ArrayList<>();
            for (Path child : children) {
                walkPlain(child, join(rel, child.getFileName().toString()), below);
            }
            if (!rel.isEmpty() && emitDirectory(children, below)) {
                out.add(new ViewEntry(rel, EntryKind.DIR, src, null, false));
            }
            out.addAll(below);
            return;
        }
        out.add(new ViewEntry(rel, EntryKind.FILE, src, null, Files.isExecutable(src)));
    }

    /**
     * Whether a directory is part of the view once its unowned children have
     * been dropped: <b>a directory whose only content is unowned is itself
     * unowned.</b>
     *
     * <p>Without this the exclusion is only half applied, and it fails exactly
     * where it is needed. A build tool that writes {@code build-logic/.gradle/}
     * into a directory the unit did not already have leaves {@code build-logic/}
     * behind as a real, empty entry — so the unit still differs from the source,
     * still reports {@code conflicted}, and issue #41 survives its own fix by
     * one directory level. Worse, {@link #carryOverUnownedTrees} recreates those
     * parents in the staged tree so that the {@code .gradle} it carries has
     * somewhere to land, which would make the destination differ from its own
     * freshly written record forever.
     *
     * <p>An <em>originally</em> empty directory is kept, because that one is an
     * authoring decision rather than a leftover: the difference is whether the
     * directory had children at all, not whether it has entries in the view.
     */
    private static boolean emitDirectory(List<Path> children, List<ViewEntry> below) {
        return children.isEmpty() || !below.isEmpty();
    }

    private static void copyView(List<ViewEntry> view, Path dest) throws IOException {
        Files.createDirectories(dest);
        for (ViewEntry entry : view) {
            Path target = dest.resolve(entry.rel());
            switch (entry.kind()) {
                case DIR -> Files.createDirectories(target);
                case FILE -> {
                    Files.createDirectories(target.getParent());
                    // COPY_ATTRIBUTES: the APFS clone path. This is the bulk
                    // site — it writes the whole materialized view of a unit —
                    // so it is where deleting the flag costs the most. See
                    // Fs#copyRecursive and home.clone.costs.far.less.than.a.copy.
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
                // COPY_ATTRIBUTES: the APFS clone path, same claim as the two
                // sites above. See Fs#copyRecursive and
                // home.clone.costs.far.less.than.a.copy.
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

    /** The one segment git's own state lives under. */
    private static final java.util.Set<String> GIT_DIR = java.util.Set.of(".git");

    /**
     * {@link #treeDigest} of everything OUTSIDE {@code .git} — the worktree half
     * of a git-backed unit, and half of the issue #29 answer.
     *
     * <p>Deliberately its own function producing its own field rather than a
     * {@code skipNames} argument to {@link #fingerprintOf}: that one returns a
     * null whole-tree digest whenever anything is skipped, precisely so a
     * partial digest can never be compared with a whole one by accident. This
     * one is a different scope with a different name in the record, comparable
     * only with another of itself, and the filtering is visible at the point the
     * scope is chosen.
     *
     * <p>Segment-wise, so a nested repository's {@code .git} is excluded too:
     * the same bytes are the same kind of thing four levels down.
     */
    private static String worktreeDigest(Path root) throws IOException {
        List<ViewEntry> outsideGit = new ArrayList<>();
        for (ViewEntry entry : plainView(root)) {
            if (!isUnder(entry.rel(), GIT_DIR)) outsideGit.add(entry);
        }
        return viewDigest(outsideGit);
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

    /**
     * Whether a recorded path string names {@code path}.
     *
     * <p>The recorded form and the live one are both produced by
     * {@code toAbsolutePath().normalize()}, so the string comparison decides it
     * in every ordinary case; the real-path comparison exists for the one that
     * is not ordinary — a home reached through a symlinked parent (on macOS,
     * {@code /tmp} is one), where the same directory has two spellings and
     * getting this wrong would silently downgrade a shared baseline to "no
     * evidence".
     */
    private static boolean samePathString(String recorded, Path path) {
        if (recorded == null || recorded.isBlank()) return false;
        Path named = Path.of(recorded).toAbsolutePath().normalize();
        return named.equals(path) || sameRealPath(named, path);
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
