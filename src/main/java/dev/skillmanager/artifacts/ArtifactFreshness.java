package dev.skillmanager.artifacts;

import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Unit X moved — what is now stale?", answered.
 *
 * <p>Two steps, and keeping them apart is the design:
 *
 * <ol>
 *   <li><b>Its own verdict.</b> An artifact whose producer recorded a
 *       fingerprint over its declared inputs is decided by RE-DERIVING that
 *       fingerprint now and comparing. Nothing is trusted: the recorded digest
 *       is one side of the comparison, never the answer.</li>
 *   <li><b>What it inherits.</b> {@link ArtifactGraph} says which artifacts it
 *       was built from, and a thing built from a stale thing is stale. This is
 *       the half that makes {@code skt check}'s "new version available for
 *       deploy-helm" into "…and therefore these three artifacts must be
 *       rebuilt".</li>
 * </ol>
 *
 * <h2>Three verdicts, and why the third is not a rounding error</h2>
 *
 * <p>{@link Freshness#UNVERIFIABLE} is a first-class answer. {@code skt check}
 * learned this the expensive way: a probe that did not finish was recorded as a
 * clean verdict and believed for a whole TTL. A missing input, an uninstalled
 * declaring unit, a backend that cannot read its own declaration — every one of
 * those is "I did not look" and none of them is "current". The combination
 * rules follow from that:
 *
 * <ul>
 *   <li>{@code STALE} dominates everything. One input positively known to have
 *       moved decides the artifact, whatever else could not be read.</li>
 *   <li>{@code UNVERIFIABLE} dominates {@code CURRENT}. "I could not look" must
 *       never be reported as a clean pass, upstream or locally.</li>
 *   <li>{@code CURRENT} requires every input decided and every one of them
 *       current.</li>
 * </ul>
 *
 * <p>The same ordering {@link Artifact#materialization()} uses one field over,
 * for the same reason.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It never installs, rebuilds, prunes or repairs, and it writes nothing —
 * not even the ledger. Turning a verdict into a rebuild is the {@code build}
 * verb's job; a read command that prescribed one would be the presence-proxy
 * mistake in a new place.
 */
public final class ArtifactFreshness {

    /** Whether an artifact still describes its inputs. */
    public enum Freshness {
        /** Every input was decided, and every one of them agrees. */
        CURRENT,
        /** An input positively moved, or something upstream did. */
        STALE,
        /**
         * Something could not be read, so no verdict is available. Never
         * folded into {@link #CURRENT}: the whole epic exists because the two
         * were the same answer.
         */
        UNVERIFIABLE;

        public String token() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * One artifact's verdict.
     *
     * @param because the upstream artifact ids that decided it, empty when the
     *        artifact decided itself. What lets a report say "stale BECAUSE
     *        cli-shim:skill-script/computeq is" rather than repeating a reason
     *        the reader then has to trace by hand.
     */
    public record Verdict(String id, ArtifactKind kind, String owner, Freshness freshness,
                          String reason, List<String> because) {
        public Verdict {
            because = because == null ? List.of() : List.copyOf(because);
        }
    }

    /**
     * A local verdict and whether it rests on DIRECT evidence.
     *
     * <h2>Why a directly-read input is not downgraded by an undecided record</h2>
     *
     * <p>An upstream {@code unverifiable} normally drags a consumer down with
     * it, and it has to: an undecided input is not a clean one. But a
     * {@link Fingerprint.Kind#RESOLVED} digest is a hash of the input's BYTES,
     * re-read off this home's disk on this pass — it is the strongest evidence
     * available about that input, stronger than any record about it. When the
     * record beside those bytes cannot be checked ({@code installed/<u>.json}
     * carries no {@code gitHash}, which 8 of 28 units at root do not), the
     * bytes were still read and still matched.
     *
     * <p>Without this distinction every fingerprinted shim in a real home
     * reports {@code unverifiable}, because {@code unit:<name>} is one of its
     * inputs and most unit-store rows cannot be verified — which would take the
     * one class that reached {@code CONTENT} and report it as undecidable. The
     * rule is narrow: only an upstream {@code unverifiable} is ignored, and
     * only for an artifact whose own verdict came from re-reading input bytes.
     * An upstream {@code stale} is positive knowledge and still dominates.
     */
    private record Local(Verdict verdict, boolean direct) {}

    private final ArtifactGraph graph;
    private final Map<String, Verdict> verdicts;

    private ArtifactFreshness(ArtifactGraph graph, Map<String, Verdict> verdicts) {
        this.graph = graph;
        this.verdicts = verdicts;
    }

    /**
     * Decide every artifact in {@code index}.
     *
     * @throws ArtifactCycleException when the derived graph is not acyclic
     */
    public static ArtifactFreshness of(ArtifactIndex index, SkillStore store) {
        ArtifactGraph graph = ArtifactGraph.of(index);
        Map<String, CliDependency> deps = declaredCliDeps(store);
        Map<String, String> declaringUnit = declaringUnits(store);
        InstallerRegistry registry = new InstallerRegistry();
        Path root = store.root().toAbsolutePath().normalize();

        Map<String, Verdict> out = new LinkedHashMap<>();
        // Producers first, so an upstream verdict is always already decided.
        for (String id : graph.topological()) {
            Artifact artifact = graph.byId(id);
            if (artifact == null) continue;
            Local own = ownVerdict(artifact, store, root, registry, deps, declaringUnit);
            out.put(id, combine(artifact, own, graph, out, root));
        }
        return new ArtifactFreshness(graph, out);
    }

    // ------------------------------------------------------------ own verdict

    private static Local ownVerdict(Artifact artifact, SkillStore store, Path root,
                                    InstallerRegistry registry,
                                    Map<String, CliDependency> deps,
                                    Map<String, String> declaringUnit) {
        return switch (artifact.kind()) {
            case CLI_SHIM, PROVISIONED_TREE ->
                    byInstallFingerprint(artifact, store, registry, deps, declaringUnit);
            case UNIT_STORE, PROJECTION, DOC_IMPORT -> byAgreement(artifact);
            case MARKETPLACE_ENTRY -> byMarketplace(artifact, root);
            case MCP_REGISTRATION -> byMcpRegistration(artifact);
            case HARNESS_INSTANCE -> verdict(artifact, Freshness.UNVERIFIABLE,
                    "no fingerprint of the harness template is recorded at instantiation, so "
                            + "whether the template moved can only be learned by re-running it");
            case UNIT_DIGEST -> verdict(artifact, Freshness.UNVERIFIABLE,
                    "verifying a digest row is a full walk of the unit; this read did not do one");
        };
    }

    /**
     * The one comparison this epic is about: what a backend recorded about its
     * declared inputs, against what those inputs hash to NOW.
     *
     * <p>Applies to both the shim and the tree the same install produced,
     * because they are the same install: {@code cli-lock.toml} holds one row
     * per dep and the row's fingerprint describes that dep's inputs, whichever
     * of its two outputs is being asked about. That is why editing a unit's
     * {@code skill-scripts/} tree names the shim AND the
     * {@code cache/skill-script-*} tree — two artifacts, one moved input,
     * neither of them inferred from the other.
     */
    private static Local byInstallFingerprint(Artifact artifact, SkillStore store,
                                              InstallerRegistry registry,
                                              Map<String, CliDependency> deps,
                                              Map<String, String> declaringUnit) {
        String recorded = artifact.recorded().get("install_fingerprint");
        String backend = artifact.recorded().get("backend");
        String tool = artifact.recorded().get("tool");
        if (backend == null || tool == null) {
            return verdict(artifact, Freshness.UNVERIFIABLE,
                    "nothing in this home says which install produced it, so there are no "
                            + "declared inputs to re-read");
        }
        String lockKey = lockKey(backend, tool);
        if (recorded == null || recorded.isBlank()) {
            return verdict(artifact, Freshness.UNVERIFIABLE,
                    "its lock row records no install fingerprint, so there is nothing to "
                            + "compare this home's inputs against (one `sync` records one)");
        }
        CliDependency dep = deps.get(lockKey);
        String unitName = declaringUnit.get(lockKey);
        if (dep == null || unitName == null) {
            return verdict(artifact, Freshness.UNVERIFIABLE,
                    "no installed unit declares " + backend + ":" + tool
                            + " any more, so its declared inputs cannot be re-read — the "
                            + "fingerprint recorded for it describes inputs this home can "
                            + "no longer see");
        }
        Fingerprint now = registry.fingerprintFor(dep, store, unitName);
        if (!now.present()) {
            return verdict(artifact, Freshness.UNVERIFIABLE,
                    "its inputs could not be re-read: " + now.gap());
        }
        // RESOLVED means the digest covers bytes read off this home's disk, so
        // it is direct evidence about the input. DECLARED covers the manifest
        // only and cannot outrank an undecided record about what it describes.
        boolean direct = now.isResolved();
        if (recorded.equals(now.value())) {
            return verdict(artifact, Freshness.CURRENT,
                    "its inputs still hash to the fingerprint recorded at install ("
                            + now.basis() + ")", direct);
        }
        return verdict(artifact, Freshness.STALE,
                "its declared inputs moved: recorded " + shortDigest(recorded)
                        + ", now " + shortDigest(now.value()) + " over " + now.basis(), direct);
    }

    /**
     * For the kinds whose record already carries a comparison
     * {@link ArtifactBackfill} made against the disk. The mapping is total and
     * deliberately does not collapse {@code UNRECORDED} into current — a row
     * that records nothing about its inputs cannot say they did not move.
     */
    private static Local byAgreement(Artifact artifact) {
        return switch (artifact.agreement()) {
            case AGREES -> verdict(artifact, Freshness.CURRENT,
                    "what is recorded about it agrees with the bytes on disk");
            case DISAGREES -> verdict(artifact, Freshness.STALE,
                    "what is recorded about it does not describe the bytes on disk"
                            + describeDisagreement(artifact));
            case UNVERIFIABLE -> verdict(artifact, Freshness.UNVERIFIABLE,
                    "this home cannot cheaply check what is recorded about it");
            case UNRECORDED -> verdict(artifact, Freshness.UNVERIFIABLE,
                    "nothing about its inputs is recorded anywhere in this home");
        };
    }

    private static String describeDisagreement(Artifact artifact) {
        String recorded = artifact.recorded().get("git_hash");
        String actual = artifact.actual().get("git_hash");
        if (recorded == null || actual == null) return "";
        return " (records " + shortDigest(recorded) + ", store is at " + shortDigest(actual) + ")";
    }

    /**
     * The marketplace is regenerated wholesale precisely because "did anything
     * change" is never asked, so this asks it: a row whose plugin is no longer
     * installed, and a row whose symlink no longer resolves, are both a
     * manifest that has fallen behind the set it is generated from.
     */
    private static Local byMarketplace(Artifact artifact, Path root) {
        String state = artifact.actual().get("marketplace_state");
        if ("orphaned".equals(state)) {
            return verdict(artifact, Freshness.STALE,
                    "the generated manifest names a plugin this home no longer has installed");
        }
        if ("unlisted".equals(state)) {
            return verdict(artifact, Freshness.STALE,
                    "this plugin is installed and the generated manifest does not list it");
        }
        for (Artifact.Output output : artifact.outputs()) {
            if (output.presence() != Artifact.Presence.PRESENT) {
                return verdict(artifact, Freshness.STALE,
                        "its marketplace entry is listed and " + output.path()
                                + " is " + output.presence().name().toLowerCase(Locale.ROOT));
            }
        }
        return verdict(artifact, Freshness.CURRENT,
                "the generated manifest and its link tree match the installed plugin set");
    }

    /**
     * The registration is a content-independent marker (skill-manager#103), so
     * the only truthful local verdict is that nothing was compared — with the
     * reason, and with the one case that IS decidable called out: a declared
     * dependency that was never registered at all.
     */
    private static Local byMcpRegistration(Artifact artifact) {
        if ("declared-only".equals(artifact.actual().get("registration_state"))) {
            return verdict(artifact, Freshness.STALE,
                    "its unit declares this server and no gateway registration for it exists "
                            + "in this home");
        }
        if (artifact.owner() == null) {
            return verdict(artifact, Freshness.UNVERIFIABLE,
                    "no installed unit declares this server, so there is no declaration to "
                            + "compare the registration against");
        }
        return verdict(artifact, Freshness.UNVERIFIABLE,
                "the gateway persists its own normalized load spec rather than the payload "
                        + "skill-manager posted, so the registered spec and the declared one "
                        + "cannot be compared from this home's files alone");
    }

    // ------------------------------------------------------------ propagation

    /**
     * Fold the upstream verdicts and the unresolved inputs into the local one.
     *
     * <p>An input this home cannot account for at all is the
     * {@code unverifiable} rule at its sharpest, and is checked here rather
     * than in each kind's own verdict so that no kind can forget it.
     *
     * <p><b>Every</b> unresolved input counts, not only the {@code store:}
     * ones. A dangling {@code unit:<name>} — a lock row whose declaring unit
     * has been uninstalled — is just as much an input this home cannot read,
     * and filtering on the scheme meant the verdict was computed as if it were
     * satisfied, which is precisely what
     * {@link ArtifactGraph#unresolvedInputs}'s own contract says must not
     * happen. {@code store:} keeps one extra test in the other direction: a
     * path that EXISTS but that no artifact claims to have produced is a fact
     * this home holds rather than a gap in it.
     */
    private static Verdict combine(Artifact artifact, Local local, ArtifactGraph graph,
                                   Map<String, Verdict> decided, Path root) {
        Freshness freshness = local.verdict().freshness();
        String reason = local.verdict().reason();
        List<String> because = new ArrayList<>();

        List<String> missingInputs = new ArrayList<>();
        for (String input : graph.unresolvedInputs(artifact.id())) {
            if (input.startsWith("store:")
                    && Files.exists(root.resolve(input.substring("store:".length())))) {
                continue;
            }
            missingInputs.add(input);
        }
        if (!missingInputs.isEmpty() && freshness != Freshness.STALE) {
            freshness = Freshness.UNVERIFIABLE;
            reason = "it is built from " + missingInputs.get(0)
                    + (missingInputs.size() > 1 ? " (+" + (missingInputs.size() - 1) + " more)" : "")
                    + ", which nothing in this home produces and nothing holds";
        }

        Set<String> stale = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String upstream : graph.dependsOn(artifact.id())) {
            Verdict up = decided.get(upstream);
            if (up == null) continue;
            if (up.freshness() == Freshness.STALE) stale.add(upstream);
            else if (up.freshness() == Freshness.UNVERIFIABLE) unknown.add(upstream);
        }

        if (!stale.isEmpty()) {
            // STALE dominates: one input positively known to have moved
            // decides this artifact whatever else could not be read.
            because.addAll(stale);
            if (freshness != Freshness.STALE) {
                reason = "it is built from " + String.join(", ", stale)
                        + (stale.size() > 1 ? ", which are stale" : ", which is stale");
            }
            freshness = Freshness.STALE;
        } else if (freshness == Freshness.CURRENT && !unknown.isEmpty() && !local.direct()) {
            because.addAll(unknown);
            freshness = Freshness.UNVERIFIABLE;
            reason = "its own inputs agree, but " + unknown.iterator().next()
                    + " could not be decided, and an undecided input is not a clean one";
        }
        return new Verdict(artifact.id(), artifact.kind(), artifact.owner(), freshness, reason,
                because);
    }

    // ----------------------------------------------------------------- lookups

    /**
     * {@code <backend>\0<lock tool>} to the dependency that declares it.
     *
     * <p>Keyed exactly the way {@link CliLock} is —
     * {@code (dep.backend(), RequestedVersion.of(dep).tool())}, which is what
     * {@code CliInstallRecorder.record} writes — so a row and its declaration
     * are joined on the key that produced the row rather than on a second
     * spelling of it.
     */
    private static Map<String, CliDependency> declaredCliDeps(SkillStore store) {
        Map<String, CliDependency> out = new LinkedHashMap<>();
        for (AgentUnit unit : installedUnits(store)) {
            for (CliDependency dep : unit.cliDependencies()) {
                out.putIfAbsent(lockKey(dep.backend(), RequestedVersion.of(dep).tool()), dep);
            }
        }
        return out;
    }

    private static Map<String, String> declaringUnits(SkillStore store) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AgentUnit unit : installedUnits(store)) {
            for (CliDependency dep : unit.cliDependencies()) {
                out.putIfAbsent(lockKey(dep.backend(), RequestedVersion.of(dep).tool()),
                        unit.name());
            }
        }
        return out;
    }

    private static List<AgentUnit> installedUnits(SkillStore store) {
        try {
            return store.listInstalledUnits().units();
        } catch (IOException e) {
            Log.warn("artifacts: could not read installed units for staleness: %s", e.getMessage());
            return List.of();
        }
    }

    /** The join key between a lock row and the dependency that declared it. */
    public static String lockKey(String backend, String tool) {
        return backend + "\0" + tool;
    }

    private static Local verdict(Artifact artifact, Freshness freshness, String reason) {
        return verdict(artifact, freshness, reason, false);
    }

    /** @param direct see {@link Local} — true only when input BYTES were re-read. */
    private static Local verdict(Artifact artifact, Freshness freshness, String reason,
                                 boolean direct) {
        return new Local(new Verdict(artifact.id(), artifact.kind(), artifact.owner(), freshness,
                reason, List.of()), direct);
    }

    private static String shortDigest(String digest) {
        if (digest == null) return "(none)";
        return digest.length() <= 12 ? digest : digest.substring(0, 12) + "…";
    }

    // ------------------------------------------------------------------ reads

    public ArtifactGraph graph() { return graph; }

    public Verdict of(String id) { return verdicts.get(id); }

    /** Every verdict, producers before consumers. */
    public List<Verdict> all() { return List.copyOf(verdicts.values()); }

    public List<Verdict> withFreshness(Freshness freshness) {
        return withFreshness(freshness, null);
    }

    /** @param kind restrict to one kind, or null for every kind. */
    public List<Verdict> withFreshness(Freshness freshness, ArtifactKind kind) {
        List<Verdict> out = new ArrayList<>();
        for (Verdict verdict : verdicts.values()) {
            if (verdict.freshness() != freshness) continue;
            if (kind != null && verdict.kind() != kind) continue;
            out.add(verdict);
        }
        return out;
    }

    public int count(Freshness freshness) { return count(freshness, null); }

    public int count(Freshness freshness, ArtifactKind kind) {
        return withFreshness(freshness, kind).size();
    }

    /** How many artifacts a report over {@code kind} is describing. */
    public int total(ArtifactKind kind) {
        if (kind == null) return verdicts.size();
        int n = 0;
        for (Verdict verdict : verdicts.values()) if (verdict.kind() == kind) n++;
        return n;
    }
}
