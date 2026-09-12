package dev.skillmanager.lifecycle;

import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The units this version of skill-manager retires from a home, and what
 * retires them.
 *
 * <h2>Why a table, and why in the product</h2>
 *
 * <p>A home is a copy of a copy: nothing in it updates itself, and a unit that
 * stopped existing upstream stays installed forever. Two of those are now
 * actively harmful rather than merely stale:
 *
 * <ul>
 *   <li><b>{@code skill-dev-skill}</b> — deleted from this repository at
 *       OUN-4. Nothing publishes it, nothing imports it, and it will never
 *       resolve again. It is just occupying a name.</li>
 *   <li><b>the standalone {@code skill-manager} skill</b> — superseded by the
 *       copy carried INSIDE the {@code skt} plugin. This one is the sharp
 *       case: from OUN-1 a plugin's contained skills are addressable by name,
 *       so both copies answer to {@code skill-manager}, and from OUN-2
 *       installing the plugin while the standalone is present is
 *       <b>refused</b>. Without a migration the upgrade that fixes the home is
 *       the upgrade the home rejects.</li>
 * </ul>
 *
 * <h2>The gate is satisfied, not weakened</h2>
 *
 * <p>This runs BEFORE {@code RejectContainedNameCollision} in the same
 * operation, and it retires <b>only names in this table, only when the unit
 * named as their successor is the one being installed</b>. Every other
 * collision reaches the gate exactly as before and is refused exactly as
 * before. A migration that worked by disabling the guard would have produced
 * the state the guard exists to prevent — which is why OUN-5's own constraint
 * says so in as many words.
 *
 * <h2>Why this is not read from a manifest</h2>
 *
 * <p>A {@code supersedes = [...]} key in the incoming unit's manifest is the
 * general mechanism and it is the right one for a unit that knows what it
 * replaces. It cannot serve HERE: the retirement has to be believed on the
 * word of the SKILL-MANAGER doing the upgrade, not on the word of a unit
 * fetched from a repository — a unit that could otherwise name any installed
 * unit as superseded and have it removed. These two are historical facts about
 * this product's own units, they are dated, and they stop applying once every
 * home has been through them. When a third case appears that is genuinely the
 * unit's own business, the manifest key is the change to make; it is recorded
 * in the deferred backlog rather than built speculatively for one caller.
 */
public final class UnitSupersession {

    /** Why a unit is being retired — which changes what the operator is told. */
    public enum Kind {
        /**
         * The unit no longer exists upstream. Nothing replaces the name; it
         * is simply gone.
         */
        OBSOLETE,

        /**
         * The same name now lives inside the carrier as a contained skill.
         * The name keeps working after the retirement — that is what makes
         * this safe, and it is exactly what OUN-1 delivered.
         */
        MOVED_INTO_CARRIER
    }

    /**
     * One retirement.
     *
     * @param unit    the installed unit name that goes
     * @param carrier the unit whose installation performs the retirement
     * @param kind    what happens to the name afterwards
     * @param reason  what the operator is told, in one sentence
     */
    public record Retirement(String unit, String carrier, Kind kind, String reason) {}

    /**
     * The table. Append-only in spirit: a row that has been shipped stays
     * until every home plausibly has it, because deleting a row does not undo
     * a migration, it only strands the homes that never ran it.
     */
    public static final List<Retirement> TABLE = List.of(
            new Retirement("skill-dev-skill", "skt", Kind.OBSOLETE,
                    "skill-dev-skill was deleted at OUN-4 (2026-09-06); skill development "
                            + "lives in skt now, and nothing publishes the old unit any more"),
            new Retirement("skill-manager", "skt", Kind.MOVED_INTO_CARRIER,
                    "the skill-manager skill now ships inside the skt plugin; the name still "
                            + "resolves, to the copy skt carries"));

    private UnitSupersession() {}

    /**
     * The retirements due in {@code store} when {@code graph} is installed.
     *
     * <p>Empty when the home has already migrated — a retirement of something
     * that is not installed is not an operation, which is what makes running
     * this twice a no-op rather than an error.
     */
    public static List<Retirement> due(SkillStore store, ResolvedGraph graph) {
        if (graph == null) return List.of();
        Set<String> incoming = new LinkedHashSet<>();
        List<Retirement> out = new ArrayList<>();
        for (var resolved : graph.resolved()) {
            if (resolved.name() != null) incoming.add(resolved.name());
        }
        for (Retirement retirement : TABLE) {
            if (!incoming.contains(retirement.carrier())) continue;
            if (!isDue(store, graph, retirement)) continue;
            out.add(retirement);
        }
        return out;
    }

    /**
     * Every retirement this home is due, read from the home itself.
     *
     * <h2>Why this does not ask what is being synced</h2>
     *
     * <p>The install path asks "is the carrier arriving?", because there the
     * collision is about to be CREATED and the retirement is what clears the
     * way for it. Sync is the other shape: a home that holds both copies is
     * <b>already broken</b>, and it got that way without anyone installing
     * anything — the carrier was updated in place by an earlier sync and the
     * standalone simply stayed.
     *
     * <p>Keying the sync-side retirement on "is skt in the target list" made
     * the fix arrive only for the whole-home sweep. {@code sync skill-manager}
     * — the exact command someone runs to bring the skill-manager unit up to
     * date, and the one most likely to be typed by a person who has just been
     * told about this migration — named the retired unit and not its carrier,
     * so nothing fired. Same for {@code upgrade skill-manager}. The home stayed
     * in the two-copies state and said nothing.
     *
     * <p>Broadening it is safe because the conditions have not moved:
     * {@link Kind#MOVED_INTO_CARRIER} still requires the carrier to be present
     * and to ACTUALLY CONTAIN a skill of that name, which is only true of a
     * home that is already holding two copies of one name.
     */
    public static List<Retirement> dueInThisHome(SkillStore store) {
        List<Retirement> out = new ArrayList<>();
        for (Retirement retirement : TABLE) {
            if (!isDue(store, null, retirement)) continue;
            out.add(retirement);
        }
        return out;
    }

    /**
     * Whether a due retirement must stop the operation when it cannot be
     * performed, as opposed to being reported and skipped.
     *
     * <p>The distinction is the difference between an operation this
     * retirement is FOR and an operation it is merely riding along with.
     *
     * <ul>
     *   <li><b>Mandatory</b> — the carrier is what is being installed or
     *       synced. Proceeding without the retirement produces exactly the
     *       state the collision gate exists to refuse, so the operation
     *       stops.</li>
     *   <li><b>Opportunistic</b> — some unrelated unit is being synced and
     *       this home happens to be due. Halting there would make
     *       {@code sync deploy-helm} fail because {@code skill-manager} has an
     *       uncommitted edit, which is a migration holding an unrelated
     *       command hostage. It reports and moves on; the home is no worse
     *       than it was a second ago.</li>
     * </ul>
     */
    public static boolean isMandatory(Retirement retirement, List<String> namesInScope) {
        return namesInScope != null && namesInScope.contains(retirement.carrier());
    }

    /**
     * Whether one row applies to this home right now.
     *
     * <p>{@link Kind#MOVED_INTO_CARRIER} carries an extra condition that is
     * the whole safety of the thing: the carrier must ACTUALLY contain a skill
     * of that name. Retiring the standalone {@code skill-manager} against an
     * {@code skt} that does not carry it yet would delete the only copy in the
     * home — which is the state this epic's own tickets pass through, since
     * OUN-6 is what moves it in and lands after this.
     */
    private static boolean isDue(SkillStore store, ResolvedGraph graph, Retirement retirement) {
        if (!installedStandalone(store, retirement.unit())) return false;
        if (retirement.kind() == Kind.OBSOLETE) return true;
        return carrierContains(store, graph, retirement.carrier(), retirement.unit());
    }

    /** Whether {@code name} is installed as a unit of its own — not as a contained skill. */
    private static boolean installedStandalone(SkillStore store, String name) {
        return store.contains(name) || store.containsPlugin(name)
                || store.containsDocRepo(name) || store.containsHarness(name);
    }

    /**
     * Whether the carrier contains a skill called {@code name} — read from the
     * resolved graph when there is one (install), else from the copy already
     * on disk (sync, where the carrier was updated a few effects ago).
     */
    private static boolean carrierContains(SkillStore store, ResolvedGraph graph,
                                           String carrier, String name) {
        if (graph != null) {
            for (var resolved : graph.resolved()) {
                if (!carrier.equals(resolved.name())) continue;
                if (!(resolved.unit() instanceof PluginUnit plugin)) continue;
                for (var contained : plugin.containedSkills()) {
                    if (name.equals(contained.name())) return true;
                }
            }
            return false;
        }
        for (var root : store.containedSkillDirs(name)) {
            java.nio.file.Path carrierDir = root.getParent() == null
                    ? null : root.getParent().getParent();
            if (carrierDir != null && carrierDir.getFileName() != null
                    && carrier.equals(carrierDir.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why {@code unit} must not be retired right now, or null when it may be.
     *
     * <h2>Every installed unit is a git checkout</h2>
     *
     * <p>Measured on the operator root home: {@code skills/skill-manager} and
     * {@code skills/skill-dev-skill} each hold a {@code .git/} of their own,
     * because that is how a change-managed unit is materialized. A retirement
     * is an uninstall, an uninstall deletes the tree, and deleting the tree of
     * a git checkout with unpushed commits destroys work <b>that is in no
     * other copy</b> — a home is the only place a unit's history lives until
     * {@code unit publish} moves it, which is the same fact
     * {@code home close-out} exists to enforce at the other seam.
     *
     * <p>So a migration that would do that stops and says so. The alternative
     * is an upgrade that silently eats a half-finished skill edit, which is
     * the single worst thing a migration can do and the reason this check was
     * written before the first one ran anywhere.
     *
     * <p>Absence of evidence is treated as unpublished: {@link
     * dev.skillmanager.source.GitOps#publishedRefContaining} answers null both
     * for "nothing contains HEAD" and for "not a git checkout at all", and a
     * gate about destroying work must read the ambiguous case as the unsafe
     * one. A unit that is not a checkout has nothing to publish and reaches
     * the {@code isGitRepo} test first.
     */
    public static String blockedFrom(SkillStore store, String unit) {
        Path dir = unitDir(store, unit);
        if (dir == null || !GitOps.isGitRepo(dir)) return null;
        if (GitOps.hasWorktreeChanges(dir)) {
            return "it has uncommitted changes";
        }
        if (GitOps.publishedRefContaining(dir) == null) {
            return "it has commits that are on no remote";
        }
        return null;
    }

    /** Where {@code unit} lives in this home, or null when it is not installed. */
    private static Path unitDir(SkillStore store, String unit) {
        if (store.contains(unit)) return store.skillDir(unit);
        if (store.containsPlugin(unit)) return store.pluginsDir().resolve(unit);
        if (store.containsDocRepo(unit)) return store.docsDir().resolve(unit);
        if (store.containsHarness(unit)) return store.harnessesDir().resolve(unit);
        return null;
    }

    /**
     * How the operator gets a retired unit back if the operation that retired
     * it does not finish — the origin the home recorded, or null when it
     * recorded none.
     *
     * <p>A retirement runs before the install it is clearing the way for, so
     * there is a window in which the old unit is gone and the new one has not
     * landed. It is small and it is real, and the honest answer to it is to
     * print the one command that undoes it rather than to pretend it does not
     * exist.
     */
    public static String reinstallHint(SkillStore store, String unit) {
        return new UnitStore(store).read(unit)
                .map(InstalledUnit::origin)
                .filter(origin -> origin != null && !origin.isBlank())
                .orElse(null);
    }
}
