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
     * Whether {@code target} lies in the agent tree of a home that is not the
     * one this process is operating on — in which case no entitlement this
     * class can establish is enough, because the bytes are not in our home.
     *
     * <h2>Why "ours to destroy" needed a second question</h2>
     *
     * <p>{@link #isOurs} asks only about the target's <em>content</em>: a
     * symlink may be removed because its bytes live elsewhere. True, and
     * insufficient. Issue #145's damage was done entirely to symlinks, every
     * one of which {@code isOurs} correctly called disposable — they simply
     * were not in this home. A cloned home's ledger named the SOURCE home's
     * {@code .claude}, {@code .codex} and {@code .gemini}, and an
     * {@code uninstall} in the copy deleted three of the operator's global
     * skill links, printed {@code ✓ unbound} three times, and exited 0.
     *
     * <p>So the rule gains a scope: <b>a projection may only be undone inside
     * its own home.</b> The refusal names a remedy rather than only refusing,
     * because the state is repairable and this is not the operator's mistake:
     * {@code home clone} now re-anchors these records, so a home still holding
     * them was copied by something else — an {@code rsync}, a restored backup,
     * a container build — and re-cloning or re-syncing repairs it.
     *
     * <h2>Why the refusal is here rather than in {@code sync}</h2>
     *
     * <p>Issue #145 proposes refusing a {@code sync} whose ledger names paths
     * outside the home. Measured, that is the wrong lever in both directions.
     *
     * <p>{@code sync} does not read the ledger for destinations — it re-derives
     * them from the projectors and <em>overwrites</em> the default-agent rows,
     * which was confirmed by deleting a cloned home's ledger outright and
     * watching the hijack happen anyway. A sync is therefore precisely the
     * operation that REPAIRS an armed home; refusing it would freeze the damage
     * and remove the only self-healing path. That also settles what to do about
     * homes already carrying bad records: they are repaired by their next sync,
     * and until then this guard stops the one operation that could do harm.
     *
     * <p>And "outside the home" is far too broad a predicate to refuse on.
     * {@code bind <unit> --target <repo>} exists to put a projection in a
     * project checkout, so an explicit binding outside the home is the
     * product's primary feature, not a defect.
     *
     * <p>Hence nothing is refused for being outside the home. What is refused
     * is destroying something inside <em>another home's</em> agent tree, which
     * has no legitimate reading at all: a managed row is derived from this
     * home's own agent roots, so one naming a different home's can only have
     * arrived by copying.
     */
    private static Path foreignAgentTree(Path target) {
        Path agentDir = dev.skillmanager.launch.LaunchEnv.agentDirOwnedByAHome(target);
        if (agentDir == null) return null;
        Path owner = agentDir.getParent();
        Path active = dev.skillmanager.agent.AgentHomes.agentHomeRoot()
                .toAbsolutePath().normalize();
        return owner != null && owner.equals(active) ? null : agentDir;
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
    /**
     * {@link #clear} for a target named by a <em>recorded</em> default-agent
     * projection, rather than by the live environment.
     *
     * <p>Everything {@link #clear} checks, plus the home scope
     * {@link #foreignAgentTree} describes. Separate entry point because the two
     * callers hold different evidence, and the difference decides the answer: a
     * projector applying or removing a projection derived it from the
     * environment a moment ago, so the destination is a present-tense
     * instruction; the undo path is replaying a row that may have been written
     * by a different home on a different machine, and #145 is what happens when
     * that row is trusted.
     *
     * @return true when {@code target} is now free
     */
    public static boolean clearRecorded(String who, String bindingId, Path target, Path source)
            throws IOException {
        if (dev.skillmanager.effects.LiveInterpreter.isDefaultAgentBindingId(bindingId)) {
            Path foreign = foreignAgentTree(target);
            if (foreign != null) {
                Log.warn("%s: refusing to remove %s — it is inside %s, which belongs to another "
                                + "Skill Manager home, not the one this command is running "
                                + "against. A default-agent row is derived from the active home's "
                                + "own agent directories, so one naming another home's was copied "
                                + "from it. Run `skill-manager sync` here to re-derive this home's "
                                + "rows, then retry.",
                        who, target, foreign);
                return false;
            }
        }
        return clear(who, target, source);
    }

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
