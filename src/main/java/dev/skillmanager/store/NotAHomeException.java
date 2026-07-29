package dev.skillmanager.store;

import dev.skillmanager.launch.LaunchEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * A path presented as a Skill Manager home is not one.
 *
 * <h2>Why this exists as a refusal and not as an empty report</h2>
 *
 * <p>Nothing used to validate home-ness, and the consequence was a gate that
 * FAILED OPEN. {@code home close-out --home <the worktree directory>} — rather
 * than {@code <worktree>/.skill-manager} — exited 0 with {@code "safe": true}
 * and {@code "blockers": []}, and printed
 * {@code ✓ <path> holds nothing that removing it would destroy} while naming
 * the directory that held the only copy of the agent's edit. So did a
 * {@code --home} that did not exist at all. The mechanism was arithmetic rather
 * than intent: {@link HomeSync#unitsToVisit} returns the union of both sides, a
 * non-home contributes zero units, every remaining unit becomes
 * {@code REMOVED_UPSTREAM}, and {@code REMOVED_UPSTREAM} maps to no blocker.
 * Zero blockers, exit 0, teardown approved.
 *
 * <p>That mistake is maximally likely, which is the whole reason to refuse
 * rather than to report gently: {@code <worktree>} is exactly the argument
 * {@code git worktree remove} takes, so the wrong path is the one already in
 * the operator's hand. The same hole was on the sync side —
 * {@code home sync --from <a path that does not exist>} printed
 * {@code ✓ reconciled <dest>} with all-zero counts, exit 0, and created the
 * destination's layout on the way.
 *
 * <h2>What "is a home" means, and where it is decided</h2>
 *
 * <p>{@link LaunchEnv#looksLikeStoreRoot} — a {@code home.runtime.json}
 * descriptor, or the {@code installed/} + {@code skills/} pair every home has.
 * That predicate already existed for the PATH sanitizer; this reuses it rather
 * than adding a fourth spelling of the same question, because three spellings
 * would eventually disagree about exactly the homes where it matters.
 *
 * <h2>What is deliberately NOT validated here</h2>
 *
 * <p>A sync <em>destination</em> that does not exist yet. Creating a home by
 * reconciling into it is the documented way a project or worktree tier comes
 * into being, and refusing it would break the operation this epic exists for.
 * The asymmetry is exact: the source is where bytes are read FROM, so it has to
 * already be a home for the read to mean anything, while the destination is
 * where they are written TO and may legitimately be new.
 *
 * <p>{@code home describe}, {@code home drift}, {@code home policy} and
 * {@code home shims} were once excluded here, on the reasoning that laying out
 * an empty home at a mistyped path is a mess rather than a data loss. They now
 * require a home too. The reasoning was half right and the missing half was
 * that a descriptor is <em>machine</em>-read: the launch shims and
 * {@code bootstrap-home.sh} take {@code home describe --json} as the answer to
 * "where does this agent's config live", so a descriptor computed for a
 * directory the command had just invented is acted on rather than merely read.
 * The legitimate gesture the old behaviour covered —
 * {@code home policy frozen --home <a home being created>} — survives as an
 * explicit {@code --init}. Issue #33.
 */
public final class NotAHomeException extends IOException {

    /**
     * Exit code for "the path you named is not a Skill Manager home".
     *
     * <p>Deliberately distinct from every other code these two commands return,
     * because the whole point is that a caller can tell them apart: 0 is
     * "reconciled / safe to remove", 1 is "this home still holds work",
     * {@code FrozenHomeException.EXIT_CODE} (9) is "refused by policy, nothing
     * attempted". 2 is the conventional argument-error code and this is an
     * argument error — the operator named the wrong directory.
     */
    public static final int EXIT_CODE = 2;

    private final transient Path path;

    private NotAHomeException(String message, Path path) {
        super(message);
        this.path = path;
    }

    public Path path() { return path; }

    /**
     * Refuse unless {@code candidate} is an existing Skill Manager home.
     *
     * @param role how the caller named it, e.g. {@code "home sync --from"},
     *             so the message points at the argument to fix rather than at
     *             an internal variable
     */
    public static void require(Path candidate, String role) throws NotAHomeException {
        Path path = candidate == null ? null : candidate.toAbsolutePath().normalize();
        if (path != null && LaunchEnv.looksLikeStoreRoot(path)) return;
        throw new NotAHomeException(role + ": " + path + " is not a Skill Manager home ("
                + describe(path) + "). A home carries a " + dev.skillmanager.store.HomeDescriptor.FILENAME
                + " descriptor, or an installed/ and a skills/ directory. If you meant the home "
                + "inside a worktree, name it: " + (path == null ? "<dir>" : path.resolve(".skill-manager"))
                + ". Nothing was read and nothing was written.", path);
    }

    /** Why it failed the test, in the terms whoever typed the path will recognise. */
    private static String describe(Path path) {
        if (path == null) return "no path given";
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "it does not exist";
        if (!Files.isDirectory(path)) return "it is not a directory";
        if (Files.isDirectory(path.resolve(".skill-manager"))) {
            return "it CONTAINS a .skill-manager home but is not one itself";
        }
        return "it exists but carries neither a descriptor nor the installed/ + skills/ pair";
    }
}
