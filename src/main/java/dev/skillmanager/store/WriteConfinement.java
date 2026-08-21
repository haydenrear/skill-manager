package dev.skillmanager.store;

import dev.skillmanager.shared.util.Fs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses a filesystem mutation that lands outside the roots the operation
 * declared it may write under, naming the offending path and the home it
 * escaped.
 *
 * <h2>The class of defect this exists for</h2>
 *
 * <p>Across the {@code home-integrity-sync} epic the operator's <b>root</b> home
 * was written by things that had no business writing it, and every instance had
 * the same shape: a path <em>spelled</em> inside the active home
 * <em>resolved</em> into a different one, and no layer between the caller and
 * the filesystem asked. Four instances, all measured, none hypothetical:
 *
 * <ol start="0">
 *   <li><b>A delete that follows a link out of the home (DEF-007).</b>
 *       {@code CliShimPruner.prune} opened {@code store.cliBinDir()} with
 *       {@code Files.isDirectory} + {@code Files.list}, both of which FOLLOW a
 *       symlink. With {@code homeB/bin/cli} a link at {@code homeA/bin/cli}, a
 *       {@code sync} in homeB judged every entry correctly and deleted it in
 *       homeA: 2 entries before, 0 after. {@code home verify} SEES that shape
 *       ({@code walkFileTree} without {@code FOLLOW_LINKS}) and refuses it; the
 *       repair followed it. Two readers, disagreeing in the one direction that
 *       destroys bytes. {@link dev.skillmanager.cli.installer.InstallerRegistry#takeOwnershipOfShim}
 *       performs the same follow-the-link delete in a different method, and
 *       HIS-10 made that second call site <em>reachable</em> by keeping a
 *       sanctioned inherited link alive across syncs.</li>
 *   <li><b>A producer following an inherited symlink out of the home.</b>
 *       A producer is an arbitrary script handed {@code $SKILL_MANAGER_BIN_DIR};
 *       its {@code cat > "$SKILL_MANAGER_BIN_DIR/<n>"} follows a link exactly
 *       the way {@code Files.writeString} does. HIS-7 closed this for
 *       {@code cli-shim} rebuilds only, by taking ownership of the one slot it
 *       was about to write.</li>
 *   <li><b>A home's pinned {@code bin/cli/skill-manager} rebinding
 *       {@code SKILL_MANAGER_HOME} to its own home</b> — handled in
 *       {@link dev.skillmanager.launch.LauncherShims}, not here, because it is
 *       bash and runs before this JVM exists.</li>
 *   <li><b>{@code bootstrap-home.sh} and {@code skt} resolving their CLI from
 *       the root home.</b> Out of scope: those live in other repositories.</li>
 * </ol>
 *
 * <h2>What "outside" means, and why writes and deletes get different answers</h2>
 *
 * <p>A write and a delete do not act on the same thing when the last path
 * component is a symlink, so asking one question would get one of them wrong:
 *
 * <ul>
 *   <li>A <b>write</b> follows the final component. {@code cat > link} and
 *       {@code Files.writeString(link, …)} both replace the bytes of the
 *       link's <em>target</em>. So {@link #checkWrite} resolves the whole path,
 *       leaf included — that is instance (1), and refusing it is the point.</li>
 *   <li>A <b>delete</b> does not. {@code Files.delete(link)} removes the link
 *       itself. So {@link #checkDelete} resolves the <em>parent</em> and
 *       re-appends the name. This distinction is not pedantry: a child home's
 *       sanctioned mirror of its parent's shim is a link that <em>points</em>
 *       into the parent store while <em>living</em> inside the child, and a
 *       delete rule that followed the leaf would refuse every legitimate
 *       {@code CliShimPruner} and {@code takeOwnershipOfShim} call in every
 *       child home. It is the resolved <b>parent</b> that catches DEF-007,
 *       where the escaping component is the {@code bin/cli} directory.</li>
 * </ul>
 *
 * <p>Both sides resolve through {@link Fs#realOrNormalized}, which is this
 * codebase's one spelling-proof path comparison — a comparison that can be
 * defeated by a spelling has been, five times, and that method's javadoc lists
 * them.
 *
 * <h2>Declared, not ambient — and the default is permissive on purpose</h2>
 *
 * <p>Modelled on {@link HomeScaffold}: an operation {@link #declare}s a scope,
 * puts the previous one back in a {@code finally}, and the scope outside the
 * outermost declaration is {@link Scope#unconfined()}. Nesting therefore
 * composes, and a declaration cannot outlive its invocation and pin the rest of
 * the JVM.
 *
 * <p><b>The default has to be permissive, and the reason is measured, not
 * timid.</b> Roughly a dozen of the 58 {@code SkillEffect}s write outside the
 * home <em>as their entire purpose</em> — {@code MaterializeProjection} and
 * {@code CreateBinding} write {@code ~/.claude}, {@code SyncClaimingProjects}
 * writes a project checkout, {@code ScaffoldSkill} writes an operator-named
 * directory, and every package backend writes a shared cache
 * ({@code PackageCaches}'s own javadoc says "confinement is not this codebase's
 * mechanism"). A guard that defaulted to "inside the home or refuse" would not
 * be a guard, it would be an outage. So confinement is stated by the effects
 * that claim to be writing <em>into this home</em>, and
 * {@code SkillEffect.writeConfinement} is the enumeration of which ones do.
 *
 * <h2>Two enforcement modes, and the difference matters</h2>
 *
 * <ul>
 *   <li>{@link #checkWrite} / {@link #checkDelete} are <b>scoped</b>: they
 *       consult whatever the current invocation declared, and do nothing when
 *       nothing declared anything.</li>
 *   <li>{@link #requireInside} is <b>unconditional</b>. It is for the handful of
 *       facts that are true of a home whatever is running — {@code bin/cli}
 *       belongs to the home whose {@code bin/cli} it is spelled as, always —
 *       so the two DEF-007 call sites do not depend on a caller having
 *       remembered to declare a scope.</li>
 * </ul>
 *
 * <h2>What this does NOT cover, stated so nobody reads more into it</h2>
 *
 * <p>There are <b>173 direct {@code java.nio.file.Files} mutation call sites</b>
 * in {@code src/main/java}, and {@code Fs} is not a choke point — it carries
 * recursive delete, recursive copy, mkdir and chmod, and no write, move or
 * symlink at all. Enforcement here is at {@link Fs#deleteRecursive}, at the two
 * DEF-007 delete sites, at the producer boundary in
 * {@code InstallerRegistry.installOne}, and at the effect boundary in
 * {@code LiveInterpreter.execute}. Those are the sites where the measured
 * damage happened. An arbitrary {@code Files.writeString} elsewhere in the
 * product is still unguarded, and making {@code Fs} the universal choke point is
 * separate work with its own blast radius.
 *
 * <p>Nothing here can see what a <em>forked producer</em> writes; no in-JVM hook
 * can. What the producer boundary does instead is refuse to hand a producer a
 * destination that escapes, and refuse the install when the artifact it
 * produced resolved outside the home anyway.
 */
public final class WriteConfinement {

    /**
     * The roots one operation may write under, and the home it is about.
     *
     * @param home  the home the operation was given — named in every refusal,
     *              because a refusal that cannot say which home it is
     *              protecting is one an operator cannot act on
     * @param roots every root a write may land under, already resolved. An
     *              EMPTY list is a scope that permits nothing; {@code null} is
     *              {@link #unconfined()}, which permits everything. The two are
     *              deliberately different values rather than one nullable list,
     *              because "declared nothing" and "declared no roots" are
     *              opposite intentions.
     * @param what  what the operation is, for the refusal text
     */
    public record Scope(Path home, List<Path> roots, String what) {

        /** True when this scope gates nothing. */
        public boolean unconfined() { return roots == null; }

        /** True when {@code resolved} — already real — lies under some root. */
        boolean permits(Path resolved) {
            if (roots == null) return true;
            for (Path root : roots) {
                if (resolved.equals(root) || resolved.startsWith(root)) return true;
            }
            return false;
        }
    }

    /** The scope that gates nothing. What is in force when nothing declared. */
    public static Scope unconfined() { return UNCONFINED; }

    private static final Scope UNCONFINED = new Scope(null, null, "unconfined");

    /**
     * A scope permitting writes under {@code home} and under each of
     * {@code alsoUnder}.
     *
     * <p>{@code alsoUnder} is how a legitimate exception becomes
     * <em>reviewable</em>: a sanctioned parent store, a shared package cache and
     * an operator-named output directory are all real, and an exception that is
     * listed can be argued with while one that is implicit is the bug. Nulls
     * are dropped so a caller can pass an optional root without branching.
     */
    public static Scope forHome(Path home, String what, Path... alsoUnder) {
        if (home == null) return UNCONFINED;
        List<Path> roots = new ArrayList<>();
        roots.add(Fs.realOrNormalized(home));
        if (alsoUnder != null) {
            for (Path extra : alsoUnder) {
                if (extra != null) roots.add(Fs.realOrNormalized(extra));
            }
        }
        return new Scope(home.toAbsolutePath().normalize(), List.copyOf(roots), what);
    }

    private WriteConfinement() {}

    private static final ThreadLocal<Scope> CURRENT = ThreadLocal.withInitial(() -> UNCONFINED);

    /**
     * State the scope of the operation about to run, and hand back the scope it
     * displaced.
     *
     * <p>The return value is not optional bookkeeping: whoever declares owns
     * putting the previous scope back with {@link #restore}, in a
     * {@code finally}. Thread-scoped rather than process-global because a
     * program's effects run on the caller's thread while the gateway and the
     * package-manager runtime do not, and a confinement leaking onto one of
     * those would refuse work that was never in scope.
     */
    public static Scope declare(Scope scope) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope == null ? UNCONFINED : scope);
        return previous;
    }

    /** Put back a scope captured from {@link #declare}. */
    public static void restore(Scope previous) {
        if (previous == null || previous.unconfined()) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** The scope in force; {@link #unconfined()} when nothing declared one. */
    public static Scope declared() { return CURRENT.get(); }

    /** Back to the permissive default. For tests; production pairs declare/restore. */
    public static void reset() { CURRENT.remove(); }

    // ------------------------------------------------------------- the gate

    /**
     * Refuse {@code target} when writing its bytes would land outside the
     * declared roots. Follows the final component, because a write does.
     */
    public static void checkWrite(Path target, String what) {
        check(CURRENT.get(), target, writeTargetOf(target), what, "write");
    }

    /**
     * Refuse {@code target} when removing that entry would remove it from
     * outside the declared roots. Does NOT follow the final component, because
     * a delete does not — see the class javadoc.
     */
    public static void checkDelete(Path target, String what) {
        check(CURRENT.get(), target, deleteTargetOf(target), what, "delete");
    }

    /**
     * Refuse unconditionally when {@code target} does not resolve inside
     * {@code home}, whatever scope is or is not declared.
     *
     * <p>For the facts that hold whoever is running: a home's own
     * {@code bin/cli} is inside that home, and an entry the pruner is about to
     * delete belongs to the home it was listed from. Both DEF-007 call sites
     * use this rather than the scoped form, so that closing the defect does not
     * depend on a caller upstream having remembered to declare anything.
     *
     * <p>Resolves the parent and re-appends the name — the delete rule — so a
     * sanctioned mirror shim, which lives inside the home and points out of it,
     * is not caught.
     */
    public static void requireInside(Path target, Path home, String what) {
        if (target == null || home == null) return;
        Scope scope = forHome(home, what);
        check(scope, target, deleteTargetOf(target), what, "reach");
    }

    /**
     * As {@link #requireInside}, for a DIRECTORY about to be listed and written
     * or deleted through — so the directory itself is resolved.
     *
     * <p>The distinction is DEF-007 exactly, and getting it wrong is silent.
     * {@link #requireInside} does not follow the final component, which is right
     * for an entry (a sanctioned mirror shim must not be refused) and useless
     * for a container: when {@code bin/cli} IS the escaping link,
     * {@code <home>/bin} resolves perfectly well inside the home and the check
     * passes while every entry the caller then lists lives somewhere else.
     * Measured: the first version of this guard used the entry rule here and
     * refused nothing at all on the exact fixture it was written for.
     */
    public static void requireContainerInside(Path dir, Path home, String what) {
        if (dir == null || home == null) return;
        Scope scope = forHome(home, what);
        check(scope, dir, writeTargetOf(dir), what, "list and delete through");
    }

    private static void check(Scope scope, Path spelled, Path resolved,
                              String what, String verb) {
        if (scope == null || scope.unconfined() || spelled == null) return;
        if (scope.permits(resolved)) return;
        throw new WriteOutsideHomeException(what, verb, spelled, resolved, scope);
    }

    /**
     * The bytes a write to {@code path} would land in: every component
     * resolved, the leaf included.
     */
    static Path writeTargetOf(Path path) {
        return Fs.realOrNormalized(path);
    }

    /**
     * The entry a delete of {@code path} would remove: the parent resolved, the
     * name re-appended unresolved.
     */
    static Path deleteTargetOf(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path name = absolute.getFileName();
        if (parent == null || name == null) return Fs.realOrNormalized(absolute);
        return Fs.realOrNormalized(parent).resolve(name);
    }
}
