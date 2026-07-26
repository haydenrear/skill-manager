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
            String materializedAt
    ) {}

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
        for (UnitKind kind : UnitKind.values()) {
            Path kindDir = unitRoot(kind);
            if (kindDir == null || !Files.isDirectory(kindDir, LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path unitDir : listSorted(kindDir)) {
                if (Files.isSymbolicLink(unitDir)
                        || !Files.isDirectory(unitDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = unitDir.getFileName().toString();
                if (!isLocallyModified(name, kind)) continue;
                out.add(new UnitOutcome(name, kind, Status.SKIPPED_LOCAL_CHANGES,
                        unitDir.toAbsolutePath().normalize(), "local changes in the child home"));
            }
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
        writeRecord(name, kind, MaterializationMode.LINK, source, null, null);
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
            String stagedDigest = treeDigest(staged);
            if (destIsDir && baseline == null) {
                // No trustworthy provenance. Adopt the directory only when it
                // is exactly what we would have written; otherwise refuse,
                // because we cannot tell an agent's edits from a stale copy.
                if (!stagedDigest.equals(currentDigest)) {
                    return heldBack(name, kind, dest);
                }
                writeRecord(name, kind, MaterializationMode.COPY, source, sourceDigest, currentDigest);
                return new UnitOutcome(name, kind, Status.UNCHANGED, dest,
                        "adopted an existing identical copy");
            }
            swapIn(staged, dest);
            writeRecord(name, kind, MaterializationMode.COPY, source, sourceDigest, stagedDigest);
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

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             String sourceDigest, String contentDigest) throws IOException {
        writeRecord(name, kind, mode, source, null, sourceDigest, contentDigest);
    }

    private void writeRecord(String name, UnitKind kind, MaterializationMode mode, Path source,
                             String sourceRevision, String sourceDigest, String contentDigest)
            throws IOException {
        Path file = recordFile(name, kind);
        Fs.ensureDir(file.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),
                new MaterializationRecord(
                        RECORD_SCHEMA_VERSION,
                        name,
                        kind.name(),
                        mode.name(),
                        source.toString(),
                        sourceRevision,
                        sourceDigest,
                        contentDigest,
                        BindingStore.nowIso()));
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
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        java.util.Set<String> skip = skipNames == null ? java.util.Set.of() : skipNames;
        List<ViewEntry> view = new ArrayList<>(plainView(root));
        view.sort(Comparator.comparing(ViewEntry::rel));
        for (ViewEntry entry : view) {
            if (isUnder(entry.rel(), skip)) continue;
            MessageDigest digest = sha256();
            switch (entry.kind()) {
                case DIR -> frame(digest, "D", entry.rel(), 0);
                case LINK -> {
                    byte[] target = entry.linkTarget().toString().getBytes(StandardCharsets.UTF_8);
                    frame(digest, "L", entry.rel(), target.length);
                    digest.update(target);
                }
                case FILE -> {
                    frame(digest, entry.executable() ? "X" : "F", entry.rel(),
                            Files.size(entry.source()));
                    try (InputStream in = Files.newInputStream(entry.source())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
                    }
                }
            }
            out.put(entry.rel(), hex(digest.digest()));
        }
        return out;
    }

    private static boolean isUnder(String rel, java.util.Set<String> skipNames) {
        if (skipNames.isEmpty()) return false;
        for (String segment : rel.split("/")) {
            if (skipNames.contains(segment)) return true;
        }
        return false;
    }

    private static String viewDigest(List<ViewEntry> view) throws IOException {
        List<ViewEntry> sorted = new ArrayList<>(view);
        sorted.sort(Comparator.comparing(ViewEntry::rel));
        MessageDigest digest = sha256();
        for (ViewEntry entry : sorted) {
            switch (entry.kind()) {
                case DIR -> frame(digest, "D", entry.rel(), 0);
                case LINK -> {
                    byte[] target = entry.linkTarget().toString().getBytes(StandardCharsets.UTF_8);
                    frame(digest, "L", entry.rel(), target.length);
                    digest.update(target);
                }
                case FILE -> {
                    frame(digest, entry.executable() ? "X" : "F", entry.rel(),
                            Files.size(entry.source()));
                    try (InputStream in = Files.newInputStream(entry.source())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
                    }
                }
            }
        }
        return hex(digest.digest());
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
        // Derived from the store's own resolver so it cannot drift from where
        // materializeUnit actually writes.
        return childStore.unitDir("probe", kind).toAbsolutePath().normalize().getParent();
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
