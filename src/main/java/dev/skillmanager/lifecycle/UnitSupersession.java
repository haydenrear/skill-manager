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
            new Retirement("skill-dev-skill", "tla-spec-dev", Kind.OBSOLETE,
                    "skill-dev-skill was deleted at OUN-4 (2026-09-06); skill development "
                            + "lives in skt now, and nothing publishes the old unit any more"),
            new Retirement("skill-manager", "tla-spec-dev", Kind.MOVED_INTO_CARRIER,
                    "the skill-manager skill now ships inside the tla-spec-dev plugin; the "
                            + "name still resolves, to the copy that plugin carries"),
            // SI-18 (2026-09-21). The carrier BECAME a passenger: skt was a
            // plugin of its own and is now a contained skill of tla-spec-dev,
            // at plugins/tla-spec-dev/skills/skt.
            //
            // Its row is not optional. Since OUN-13 a contained name that
            // collides with an installed standalone is a NOTICE, not a
            // refusal, so installing tla-spec-dev over a home that still holds
            // the skt plugin succeeds and leaves TWO copies of skt — one
            // updatable from a repository that no longer publishes it. That is
            // precisely the duplication this migration removes, arriving by
            // the migration itself.
            //
            // The two rows above changed carrier in the same commit, for the
            // same reason: `due()` only fires a row when its carrier is the
            // unit arriving, and skt is never the unit arriving any more.
            // Homes that already ran those rows against skt are unaffected —
            // a retirement of something no longer installed is a no-op — and
            // homes that never ran them get them now, from the carrier that
            // actually shows up. See FORMER_CARRIERS for the un-migrated home
            // that holds skt and nothing else.
            new Retirement("skt", "tla-spec-dev", Kind.MOVED_INTO_CARRIER,
                    "skt is a contained skill of the tla-spec-dev plugin now, not a plugin "
                            + "of its own; the name still resolves, to the copy that plugin "
                            + "carries, and the skt CLI is installed from the plugin root"));

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
    /**
     * The repositories a retired unit used to be installed from. Units written
     * before the move still reference the unit by that git coordinate, and once
     * the standalone is retired no installed record carries the origin any
     * more, so without this the reference reads as a missing dependency — and a
     * sync CLONED IT BACK in the same operation that retired it (0.27.1, every
     * project home with a stale git-epic-workflow).
     */
    private static final java.util.Map<String, String> FORMER_SOURCES = java.util.Map.of(
            "skill-manager", "https://github.com/haydenrear/skill-manager-skill",
            "skill-dev-skill", "https://github.com/haydenrear/skill-dev-skill");

    /** Every repository a carrier has been published from, under any name. */
    private static final java.util.Map<String, List<String>> CARRIER_SOURCES = java.util.Map.of(
            "tla-spec-dev", List.of("https://github.com/haydenrear/tla-spec-dev-plugin",
                    // skt's own repositories. The carrier changed NAME at
                    // SI-18, not just version: a home installed from either of
                    // these holds the unit that now answers to tla-spec-dev,
                    // and a coordinate pointing at them still has to resolve to
                    // the current carrier rather than to nothing.
                    //
                    // github.com/haydenrear/tla-spec-dev is deliberately ABSENT.
                    // That repository still publishes the spec-double-compiler
                    // SKILL so existing installs keep syncing; treating it as a
                    // carrier source would make a skill coordinate resolve to a
                    // plugin.
                    "https://github.com/haydenrear/skt",
                    "https://github.com/haydenrear/skill-publisher-skill"));

    /**
     * Carriers that used to serve a retirement's unit and may still be the
     * only one a home holds.
     *
     * <h2>The regression this exists to stop</h2>
     *
     * <p>SI-18 repointed every row's carrier from {@code skt} to
     * {@code tla-spec-dev}. That is right for PERFORMING a retirement — only
     * the carrier that actually arrives should retire anything — but the
     * "is this reference already served?" predicates ask a different question,
     * and for them the rename is a regression with a real victim: a home that
     * migrated under the old rows holds the {@code skt} plugin, holds no
     * standalone {@code skill-manager}, and has not yet seen
     * {@code tla-spec-dev}. Read through {@code carrier()} alone, that home
     * answers "not served", and the very next resolve installs the standalone
     * back — recreating the duplicate the old rows removed, in a home that was
     * already correct.
     *
     * <p>So the predicates accept any carrier in this set. Only they do:
     * {@link #due}, {@link #isDue}, {@link #isMandatory} and the removal path
     * keep asking about {@link Retirement#carrier()}, the current one, because
     * a retired carrier must never perform a retirement.
     */
    private static final java.util.Map<String, Set<String>> FORMER_CARRIERS =
            java.util.Map.of("tla-spec-dev", Set.of("skt"));

    /**
     * Every carrier name that serves {@code retirement}'s unit — current
     * first.
     *
     * <p>The unit itself is excluded, and that is not hypothetical bookkeeping:
     * {@code skt}'s own row has carrier {@code tla-spec-dev}, whose former
     * carrier is {@code skt}, so without this the row would name skt as a
     * carrier of skt. Present-skt would then read as "the standalone is served
     * by a carrier", which is the opposite of what it means — the standalone
     * being present is the condition the retirement exists to clear.
     */
    public static Set<String> servingCarriers(Retirement retirement) {
        Set<String> out = new LinkedHashSet<>();
        out.add(retirement.carrier());
        out.addAll(FORMER_CARRIERS.getOrDefault(retirement.carrier(), Set.of()));
        out.remove(retirement.unit());
        return out;
    }

    /**
     * The coordinate that installs {@code carrier} today, as an operator would
     * type it.
     *
     * <h2>Why this is not {@code github:haydenrear/<carrier>}</h2>
     *
     * <p>That is what the remedy used to interpolate, and it was right for as
     * long as the carrier's name matched its repository's — {@code skt} lived
     * in {@code haydenrear/skt}. SI-18 broke that: the carrier is
     * {@code tla-spec-dev} and its repository is
     * {@code tla-spec-dev-plugin}. The name-shaped guess would have printed
     * {@code github:haydenrear/tla-spec-dev}, which is a REAL repository that
     * resolves — to the spec-double-compiler SKILL, not to this plugin. A
     * remedy that installs the wrong unit is worse than no remedy, because the
     * operator has no reason to doubt it.
     *
     * <p>So the coordinate comes from {@link #CARRIER_SOURCES}, whose first
     * entry per carrier is its current repository, and the name-shaped guess
     * survives only as the fallback for a carrier with no row.
     */
    public static String installCoordFor(String carrier) {
        List<String> sources = CARRIER_SOURCES.get(carrier);
        if (sources != null && !sources.isEmpty()) {
            String url = sources.get(0);
            String marker = "github.com/";
            int at = url.indexOf(marker);
            if (at >= 0) {
                String path = url.substring(at + marker.length());
                if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
                return "github:" + path;
            }
            return url;
        }
        return "github:haydenrear/" + carrier;
    }

    /** The retired unit a git coordinate used to install, if it names one. */
    public static java.util.Optional<String> movedUnitForSource(String url) {
        if (url == null) return java.util.Optional.empty();
        for (var e : FORMER_SOURCES.entrySet()) {
            if (dev.skillmanager.source.UnitStore.sameOrigin(e.getValue(), url)) {
                return java.util.Optional.of(e.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    /** The carrier a git coordinate installs, under its current or a former repository name. */
    public static java.util.Optional<String> carrierForSource(String url) {
        if (url == null) return java.util.Optional.empty();
        for (var e : CARRIER_SOURCES.entrySet()) {
            for (String source : e.getValue()) {
                if (dev.skillmanager.source.UnitStore.sameOrigin(source, url)) {
                    return java.util.Optional.of(e.getKey());
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** The table's row for {@code unit}, of either kind. */
    public static java.util.Optional<Retirement> retirementFor(String unit) {
        if (unit == null) return java.util.Optional.empty();
        return TABLE.stream().filter(r -> r.unit().equals(unit)).findFirst();
    }

    /** The row a reference names — by the unit's name, or by the repository it used to live in. */
    public static java.util.Optional<Retirement> retirementNamedBy(dev.skillmanager.model.UnitReference ref) {
        if (ref == null) return java.util.Optional.empty();
        dev.skillmanager.model.Coord c = ref.coord();
        if (c instanceof dev.skillmanager.model.Coord.SubElement sub) c = sub.unitCoord();
        if (c instanceof dev.skillmanager.model.Coord.DirectGit g) {
            return movedUnitForSource(g.url()).flatMap(UnitSupersession::retirementFor);
        }
        return retirementFor(ref.name());
    }

    /** True when {@code ref} names a retired unit whose carrier is in {@code present}. */
    public static boolean referenceServedByCarrier(dev.skillmanager.model.UnitReference ref,
                                                   java.util.Collection<String> present) {
        return retirementNamedBy(ref)
                .map(r -> servingCarriers(r).stream().anyMatch(present::contains))
                .orElse(false);
    }

    /**
     * True when {@code ref} must NOT be installed into {@code store}: it names a
     * retired unit, the standalone is gone, and the carrier that replaces it is
     * installed (holding the unit, for a move). Installing it would undo the
     * migration — the reference is already served.
     */
    public static boolean servedByInstalledCarrier(SkillStore store,
                                                   dev.skillmanager.model.UnitReference ref) {
        if (store == null) return false;
        var retirement = retirementNamedBy(ref);
        if (retirement.isEmpty()) return false;
        Retirement r = retirement.get();
        if (store.containsUnit(r.unit())) return false;
        // Any serving carrier, not just the current one: a home that has not
        // yet seen tla-spec-dev but holds skt is already served.
        String installed = null;
        for (String carrier : servingCarriers(r)) {
            if (store.containsPlugin(carrier)) { installed = carrier; break; }
        }
        if (installed == null) return false;
        if (r.kind() == Kind.OBSOLETE) return true;
        return java.nio.file.Files.isDirectory(
                store.pluginsDir().resolve(installed).resolve("skills").resolve(r.unit()));
    }

    /** The table's row for {@code unit} when it has been moved into a carrier. */
    public static java.util.Optional<Retirement> movedIntoCarrier(String unit) {
        if (unit == null) return java.util.Optional.empty();
        for (Retirement r : TABLE) {
            if (r.kind() == Kind.MOVED_INTO_CARRIER && r.unit().equals(unit)) {
                return java.util.Optional.of(r);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Release every project claim on a retiring unit that the carrier already
     * satisfies, and name the claims it does not.
     *
     * <h2>Why this exists: 0.27.0 could not migrate a real root home</h2>
     *
     * <p>Retirement runs {@code RemoveUseCase}, which refuses a unit any
     * project lock still claims. The 0.27.0 migration let that refusal fail
     * the whole sync, so an operator's root home, whose projects
     * {@code commit-diff-context-parent} and {@code meta-harness} declare
     * {@code [skills.skill-manager]} exactly as the docs taught, rolled back
     * 21 effects and stayed unmigrated. A clone of that home migrated fine,
     * because a clone does not carry its source's project claims, which is how
     * the release shipped. skill-manager#175.
     *
     * <p>A claim on a {@link Kind#MOVED_INTO_CARRIER} unit is not a reason to
     * keep the standalone when the SAME project also resolves the carrier: the
     * name that project depends on is served by the carrier's contained copy,
     * which is the whole premise of the move. That claim is released — the
     * standalone's row leaves the lock — and the retirement proceeds.
     *
     * <p>A claim by a project that does NOT resolve the carrier is left alone
     * and returned, so the caller can report it and skip, never fail.
     *
     * @return the projects and child homes whose claims were NOT released
     */
    public static List<String> releaseClaimsSatisfiedByCarrier(SkillStore store,
                                                              Retirement retirement)
            throws java.io.IOException {
        var locks = new dev.skillmanager.project.SkillProjectLockStore(store);
        var childHomes = new dev.skillmanager.bindings.ChildHomeRegistry(store);
        boolean moved = retirement.kind() == Kind.MOVED_INTO_CARRIER;

        // Decide every claim before writing any: a retirement that is going to
        // be skipped must leave no claim half-released behind it.
        List<dev.skillmanager.project.SkillProjectLock> lockReleases = new ArrayList<>();
        List<dev.skillmanager.bindings.ChildHomeRegistry.ChildHomeRecord> homeReleases = new ArrayList<>();
        List<String> unsatisfied = new ArrayList<>();
        for (var lock : locks.list()) {
            var names = lock.resolvedUnits().stream().map(u -> u.name()).toList();
            if (!names.contains(retirement.unit())) continue;
            if (moved && names.contains(retirement.carrier())) lockReleases.add(lock);
            else unsatisfied.add(lock.projectName());
        }
        for (var record : childHomes.list()) {
            if (!record.units().contains(retirement.unit())) continue;
            if (moved && record.units().contains(retirement.carrier())) homeReleases.add(record);
            else unsatisfied.add(record.id());
        }
        // A child home whose record lists the unit but cannot be decoded still
        // claims it as far as RemoveUseCase is concerned; name it.
        for (String id : childHomes.childHomesClaiming(retirement.unit())) {
            boolean seen = homeReleases.stream().anyMatch(r -> r.id().equals(id))
                    || unsatisfied.contains(id);
            if (!seen) unsatisfied.add(id);
        }
        if (!unsatisfied.isEmpty()) return unsatisfied;

        for (var lock : lockReleases) {
            List<dev.skillmanager.project.SkillProjectLock.ResolvedUnit> kept = new ArrayList<>();
            for (var u : lock.resolvedUnits()) {
                if (!u.name().equals(retirement.unit())) kept.add(u);
            }
            locks.write(new dev.skillmanager.project.SkillProjectLock(
                    lock.projectName(), lock.profile(), lock.manifestFile(), lock.resolvedAt(),
                    kept, lock.bindings(), lock.envs(), lock.libs()));
        }
        for (var record : homeReleases) {
            List<String> kept = new ArrayList<>(record.units());
            kept.remove(retirement.unit());
            childHomes.write(new dev.skillmanager.bindings.ChildHomeRegistry.ChildHomeRecord(
                    record.id(), record.parentHome(), record.childHome(), record.harnessName(),
                    kept, record.createdAt()));
        }
        return List.of();
    }

    /**
     * True when a reference that resolved to {@code name} is served by a
     * carrier present in {@code present}: the unit was moved into that
     * carrier, so the carrier's contained copy satisfies the reference.
     */
    public static boolean servedByCarrier(String name, java.util.Collection<String> present) {
        return retirementFor(name)
                .map(r -> servingCarriers(r).stream().anyMatch(present::contains))
                .orElse(false);
    }

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
            // The local refs can be days stale: a home that never fetched reads
            // every commit pulled since as "on no remote", and 0.27.1 halted
            // project-home migrations on work that was already published. Ask
            // the remote once before refusing; an unreachable one still refuses.
            GitOps.fetchAllRemotes(dir);
            if (GitOps.publishedRefContaining(dir) == null) {
                return "it has commits that are on no remote";
            }
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
