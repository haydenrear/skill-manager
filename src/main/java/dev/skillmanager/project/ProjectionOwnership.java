package dev.skillmanager.project;

import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * <b>The one place a projection may destroy bytes.</b>
 *
 * <p>{@link ChildHomeMaterializer} owns this question for the unit trees inside
 * a home, where it is answered from a materialization record. This class owns
 * it for the <em>other</em> half — the agent trees and bound project roots that
 * projections write into: {@code <home>/.claude/skills/<name>},
 * {@code <home>/.codex/skills/<name>}, {@code <home>/.gemini/skills/<name>}, a
 * harness sandbox, a project's {@code docs/agents/}.
 *
 * <h2>Why these two need different mechanisms and the same rule</h2>
 *
 * <p>The rule is the one already stated in {@code ChildHomeMaterializer}: a
 * write may destroy bytes only where it can show it is entitled to. A child
 * home needs a record to show that, because the bytes in it are authored there
 * and there is no other witness. A projection needs none and should have none:
 * it is a <em>pure function of the store</em>, so the entitlement is decidable
 * from the two paths alone, and inventing a provenance file for it would be
 * machinery nobody needs. Two mechanisms, because the evidence available
 * differs; one rule, because the requirement does not.
 *
 * <h2>What went wrong without it</h2>
 *
 * <p>{@code <home>/.claude/skills/} is not a skill-manager-only namespace — it
 * is where a human writes skills by hand. Three writers destroyed content there
 * with no report and no way back, and no two of them were in the same file:
 *
 * <ul>
 *   <li>the three {@link Projector}s' {@code apply}, each carrying its own copy
 *       of "delete whatever is at the target, then link";</li>
 *   <li>their {@code remove}, same six lines inverted;</li>
 *   <li>{@code BindingBackfill}, which adopted <em>any existing path</em> at a
 *       projection target into the ledger as though skill-manager had put it
 *       there — after which the ledger-driven
 *       {@code LiveInterpreter.reverseProjection} deleted it on the next
 *       uninstall, believing it was removing its own symlink.</li>
 * </ul>
 *
 * <p>That last one is why this is a shared class rather than a guard inside
 * {@link Projector}: guarding the projectors alone would have left the removal
 * path deleting the same directory through a different door, and the ledger
 * entry that authorized it was written by a third file again. One predicate,
 * every door.
 */
public final class ProjectionOwnership {

    private ProjectionOwnership() {}

    /**
     * Whether {@code target} is something a projection of {@code source} is
     * entitled to destroy. Two showings, and no third:
     *
     * <ul>
     *   <li>a <b>symlink</b> — any symlink. Its bytes live wherever it points,
     *       so removing the link destroys nothing. This is what {@code apply}
     *       leaves behind on every filesystem that supports it.</li>
     *   <li>a <b>directory byte-identical to the source</b> — the output of the
     *       copy fallback taken on filesystems that do not. Rewriting it with
     *       the same bytes, or removing it while the store still holds them,
     *       loses nothing.</li>
     * </ul>
     *
     * <p>Anything else — a real directory or file whose content the source does
     * not have — is somebody's work. A missing target is trivially ours: there
     * is nothing there to lose.
     */
    public static boolean isOurs(Path target, Path source) {
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                return true;
            }
            if (Files.isSymbolicLink(target)) return true;
            if (source == null) return false;
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                return ChildHomeMaterializer.treeDigest(target)
                        .equals(ChildHomeMaterializer.treeDigest(source));
            }
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return java.util.Arrays.equals(
                        Files.readAllBytes(target), Files.readAllBytes(source));
            }
            return false;
        } catch (IOException unreadable) {
            // Cannot establish entitlement, so there is none. Same asymmetry the
            // materializer takes: without evidence a tree is disposable, it is
            // not disposable.
            return false;
        }
    }

    /**
     * Free {@code target} for a projection of {@code source}, or report why it
     * was left alone.
     *
     * <p>Holding back costs a skill the agent cannot see until a human renames
     * one of the two. Overwriting costs the content. Only one of those is
     * recoverable, which is the whole of the choice.
     *
     * @return true when {@code target} is now free
     */
    public static boolean clear(String who, Path target, Path source) throws IOException {
        if (!isOurs(target, source)) {
            Log.warn("%s: %s holds content that is not a projection of %s — left in place. "
                            + "Rename one of the two, or remove %s by hand.",
                    who, target, source, target);
            return false;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            Fs.deleteRecursive(target);
        }
        return true;
    }
}
