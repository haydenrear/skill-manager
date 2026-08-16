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
 * <h2>Presence may DEMOTE a verdict, and may never promote one (ARTI-06)</h2>
 *
 * <p>Through ARTI-05 this class read {@link Artifact#outputs()} in exactly one
 * place — {@link #byMarketplace} — so for {@code CLI_SHIM} and
 * {@code PROVISIONED_TREE} the verdict was a pure input comparison. Measured on
 * a real project home before this change:
 *
 * <pre>
 * $ mv bin/cli/skt bin/cli/.hidden
 * cli-shim:skill-script/skt
 *   materialization = declared-only   outputs = [{path: bin/cli/skt, presence: missing}]
 *   freshness       = CURRENT  "its inputs still hash to the fingerprint recorded at install"
 * </pre>
 *
 * <p>That is the case the whole epic exists for — a cloned home that skipped
 * {@code cache/} and shipped dangling shims reads as entirely current, so a
 * {@code build --stale} over it would rebuild <b>nothing</b>. Keeping the two
 * axes apart was defensible while nothing acted on the verdict; it stopped
 * being defensible the moment {@code build} did. And {@link StaleReport} has no
 * presence field, so a consumer of {@code stale --json} alone could not recover
 * the missing half and re-join it either.
 *
 * <p>So {@link #combine} folds {@link Artifact#materialization()} in, under one
 * rule stated in both directions:
 *
 * <ul>
 *   <li><b>Demote only.</b> A present output earns an artifact nothing. Whether
 *       it is CURRENT is still decided by re-deriving its inputs, exactly as
 *       before — which is what keeps this from being the presence proxy this
 *       epic exists to remove.</li>
 *   <li>{@code declared-only} / {@code partial} — this pass positively probed an
 *       output and it is not usable, so the artifact must be rebuilt: STALE. Not
 *       a new rule, a generalised one. {@link #byMarketplace} already returned
 *       STALE for a listed entry whose link was missing; that one kind's special
 *       case is now every kind's rule and has been deleted from there.</li>
 *   <li>{@code unknown} materialization — an output that could not be probed at
 *       all ({@code cli-lock.toml} keys a row by PACKAGE while the shim is named
 *       for the BINARY, and the row does not record the second) is "I did not
 *       look", so it may not report CURRENT. UNVERIFIABLE, the same ordering
 *       {@link Artifact#materialization()} uses one field over.</li>
 * </ul>
 *
 * <p>"Its inputs hash to what was recorded" and "there is nothing at the path"
 * are both true of a hidden shim. CURRENT claims the artifact still describes
 * its inputs; an artifact with no usable output describes nothing.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It never installs, rebuilds, prunes or repairs, and it writes nothing —
 * not even the ledger. Turning a verdict into a rebuild is
 * {@link dev.skillmanager.commands.BuildCommand}'s job; a read command that
 * prescribed one would be the presence-proxy mistake in a new place.
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
     * @param materialization the OTHER axis, carried on the verdict rather than
     *        left for a consumer to re-derive. ARTI-05's review found that a
     *        reader of {@code stale --json} could not tell a stale-because-its-
     *        inputs-moved artifact from a stale-because-it-is-not-there one,
     *        and could not recover the second fact from that document at all.
     */
    public record Verdict(String id, ArtifactKind kind, String owner, Freshness freshness,
                          String reason, List<String> because,
                          Artifact.Materialization materialization) {
        public Verdict {
            because = because == null ? List.of() : List.copyOf(because);
            if (materialization == null) materialization = Artifact.Materialization.UNKNOWN;
        }

        public Verdict(String id, ArtifactKind kind, String owner, Freshness freshness,
                       String reason, List<String> because) {
            this(id, kind, owner, freshness, reason, because, Artifact.Materialization.UNKNOWN);
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
        Map<String, CliProducer> producers = cliProducers(store);
        InstallerRegistry registry = new InstallerRegistry();
        Path root = store.root().toAbsolutePath().normalize();

        Map<String, Verdict> out = new LinkedHashMap<>();
        // Producers first, so an upstream verdict is always already decided.
        for (String id : graph.topological()) {
            Artifact artifact = graph.byId(id);
            if (artifact == null) continue;
            Local own = ownVerdict(artifact, store, root, registry, producers);
            out.put(id, combine(artifact, own, graph, out, root));
        }
        return new ArtifactFreshness(graph, out);
    }

    // ------------------------------------------------------------ own verdict

    private static Local ownVerdict(Artifact artifact, SkillStore store, Path root,
                                    InstallerRegistry registry,
                                    Map<String, CliProducer> producers) {
        return switch (artifact.kind()) {
            case CLI_SHIM, PROVISIONED_TREE ->
                    byInstallFingerprint(artifact, store, registry, producers);
            case UNIT_STORE, PROJECTION, DOC_IMPORT -> byAgreement(artifact);
            case MARKETPLACE_ENTRY -> byMarketplace(artifact, root);
            case MCP_REGISTRATION -> byMcpRegistration(artifact);
            case HARNESS_INSTANCE -> byHarnessTemplate(artifact);
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
                                              Map<String, CliProducer> producers) {
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
        CliProducer producer = producers.get(lockKey);
        CliDependency dep = producer == null ? null : producer.dep();
        String unitName = producer == null ? null : producer.unitName();
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
     * change" is never asked, so this asks it — on the two axes that are this
     * method's: a row whose plugin is no longer installed, and a row whose
     * recorded input fingerprint no longer matches the plugin's bytes, are both
     * a manifest that has fallen behind the set it is generated from.
     *
     * <p>Whether the link is THERE is deliberately not asked here.
     * {@link #combine} owns presence for every kind since ARTI-06, and the loop
     * that used to test it in this one method was that change's motivating
     * example.
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
        // The output loop that used to live here — "listed, and its link is
        // missing" → STALE — is GONE, not moved by accident. It was the one
        // place in this class that read outputs(), and ARTI-06 made it every
        // kind's rule in combine(). Two spellings of one fact is how the two
        // axes came to disagree in the first place. ARTI-17 keeps that deletion
        // and asks the OTHER question in its place: set membership is settled
        // above, presence is combine()'s, and what is left is the one the
        // wholesale regeneration existed to avoid asking.
        return byInputFingerprint(artifact, "input_fingerprint",
                "the generated manifest and its link tree match the installed plugin set, and "
                        + "nothing records what that set hashed to when it was generated "
                        + "(one regeneration records it)",
                "the plugin this entry exposes is unchanged since the marketplace was generated",
                "the plugin this entry exposes moved since the marketplace was generated");
    }

    /**
     * A harness instance against the template it was instantiated from — the
     * comparison {@code SyncHarness} used to perform by DOING the work: it
     * re-instantiated with {@code OVERWRITE} on every pass because there was no
     * recorded template digest to ask instead.
     */
    private static Local byHarnessTemplate(Artifact artifact) {
        return byInputFingerprint(artifact, "input_fingerprint",
                "its record carries no fingerprint of the harness template, so whether the "
                        + "template moved can only be learned by re-running the instantiation "
                        + "(one `sync harness:<n>` records one)",
                "the installed template still hashes to the fingerprint recorded when this "
                        + "instance was instantiated",
                "the harness template moved since this instance was instantiated");
    }

    /**
     * The shared shape for the kinds whose producer now records a graded digest
     * of its inputs and whose reader can re-read those inputs on this pass.
     *
     * <p>{@link ArtifactBackfill} does the comparing — it holds both halves —
     * and stamps the answer as an {@link Artifact.Agreement}. This maps that to
     * a verdict with the kind's own words, and marks it DIRECT only when the
     * producer graded its digest {@link Fingerprint.Kind#RESOLVED}: that grade
     * means bytes re-read off this home's disk, which is the strongest evidence
     * available about an input and must not be dragged down by an undecided
     * record ABOUT those same bytes. A {@code declared} digest gets no such
     * privilege, on the same rule {@link #byInstallFingerprint} applies.
     */
    private static Local byInputFingerprint(Artifact artifact, String key,
                                            String unrecorded, String current, String stale) {
        // DIRECT is read off the GRADE the producer asserted, exactly as
        // byInstallFingerprint reads it off `now.isResolved()`. Setting it
        // unconditionally — which this method did at first — would let a
        // `declared` digest outrank an undecided record about the very thing it
        // describes, which is the privilege ARTI-05 granted only to a hash of
        // bytes re-read off this home's disk. It matters here and not in theory:
        // the MCP digest is graded `declared`, its owning unit's store row is
        // frequently UNVERIFIABLE (8 of 28 units at root carry no gitHash), and
        // the difference is whether that pair reports CURRENT or UNVERIFIABLE.
        // The CLI rows would report UNVERIFIABLE; one bar, not two.
        boolean direct = Fingerprint.Kind.RESOLVED
                == Fingerprint.Kind.fromToken(artifact.recorded().get(key + "_kind"));
        return switch (artifact.agreement()) {
            case UNRECORDED -> verdict(artifact, Freshness.UNVERIFIABLE, unrecorded);
            case AGREES -> verdict(artifact, Freshness.CURRENT, current, direct);
            case DISAGREES -> verdict(artifact, Freshness.STALE,
                    stale + " (recorded " + shortDigest(artifact.recorded().get(key))
                            + ", now " + shortDigest(artifact.actual().get(key)) + ")", direct);
            case UNVERIFIABLE -> verdict(artifact, Freshness.UNVERIFIABLE,
                    "its inputs could not be re-read: "
                            + artifact.actual().getOrDefault(key + "_gap", "no reason recorded"));
        };
    }

    /**
     * A registration against the declaration it was built from.
     *
     * <p>This used to be hardcoded {@code unverifiable} on the grounds that the
     * recorded digest covered init values only the installing process could
     * read. That was wrong about its own record: {@code GatewayClient.specDigest}
     * digests {@code load_spec} and {@code init_schema} and nothing else, both
     * pure functions of the installed {@code McpDependency}. Nothing about the
     * installing process is in it, so any pass that can read the unit's manifest
     * can recompute it — and {@link ArtifactBackfill} does, which is what makes
     * this decidable rather than merely recorded.
     *
     * <p>What stays undecidable is the GATEWAY's copy (skill-manager#121) and
     * whether the server it actually ran has moved. Neither is what this
     * comparison claims.
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
        return byInputFingerprint(artifact, "spec_digest",
                "this home records no spec digest for the registration it made, so there is "
                        + "nothing to compare its declaration against (one `sync --include-mcp` "
                        + "records one)",
                "its declared mcp spec still hashes to the digest recorded when this home "
                        + "registered it",
                "the mcp dependency this server was registered from moved");
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

        // The OTHER axis, folded in last and here rather than in each kind's
        // own verdict so that no kind can forget it — the same argument the
        // unresolved-input check above makes. See the class javadoc: this may
        // only demote. A present output earns nothing.
        Artifact.Materialization materialization = artifact.materialization();
        switch (materialization) {
            case MATERIALIZED -> { }
            case DECLARED_ONLY, PARTIAL -> {
                String absence = describeAbsence(artifact, materialization);
                reason = freshness == Freshness.STALE ? reason + "; and " + absence : absence;
                freshness = Freshness.STALE;
            }
            case UNKNOWN -> {
                if (freshness == Freshness.CURRENT) {
                    freshness = Freshness.UNVERIFIABLE;
                    reason = "its inputs agree, but this home cannot say where it landed, so "
                            + "whether the artifact is there was never asked"
                            + (artifact.actual().get("shim_name") == null ? ""
                                    : " (" + artifact.actual().get("shim_name") + ")");
                }
            }
        }
        return new Verdict(artifact.id(), artifact.kind(), artifact.owner(), freshness, reason,
                because, materialization);
    }

    /**
     * Why an artifact with no usable output is not current, naming the first
     * output that is not there and how it is not there.
     *
     * <p>{@link Artifact.Presence#DANGLING} is spelled out rather than folded
     * into "missing": a shim whose target was never provisioned IS on disk and
     * IS executable, which is exactly how it passed every presence check in the
     * system while failing at exec time.
     */
    private static String describeAbsence(Artifact artifact,
                                          Artifact.Materialization materialization) {
        for (Artifact.Output output : artifact.outputs()) {
            if (output.presence() == Artifact.Presence.PRESENT) continue;
            String how = switch (output.presence()) {
                case DANGLING -> "is a link whose target this home does not hold";
                case MISSING -> "is not there";
                default -> "could not be probed";
            };
            return "its output " + output.path() + " " + how
                    + (materialization == Artifact.Materialization.PARTIAL
                            ? " (some of its outputs are)" : "")
                    + " — a declared artifact with nothing usable at its path does not describe "
                    + "its inputs, whatever they hash to";
        }
        // No outputs at all and origin LEDGER: the row says this home is
        // supposed to hold it and the home cannot produce it on this pass.
        return "this home records that it holds this artifact and cannot produce it now";
    }

    // ----------------------------------------------------------------- lookups

    /**
     * The install a lock row came from: the unit that declares it and the
     * dependency it declared.
     *
     * <p>One record rather than the two parallel maps this class carried
     * through ARTI-05 ({@code declaredCliDeps} and {@code declaringUnits}, both
     * walking the same units and both keyed the same way). They could not
     * disagree, but nothing said so, and ARTI-06 needs the pair as a unit
     * anyway — {@code build} has to hand a backend BOTH halves.
     */
    public record CliProducer(String unitName, CliDependency dep) {}

    /**
     * {@code <backend>\0<lock tool>} to the install that declares it.
     *
     * <p>Keyed exactly the way {@link CliLock} is —
     * {@code (dep.backend(), RequestedVersion.of(dep).tool())}, which is what
     * {@code CliInstallRecorder.record} writes — so a row and its declaration
     * are joined on the key that produced the row rather than on a second
     * spelling of it. Public because {@code build} resolves an artifact to its
     * producer through the SAME join that decided the artifact was stale; a
     * second spelling of it is a second thing that can disagree.
     */
    public static Map<String, CliProducer> cliProducers(SkillStore store) {
        Map<String, CliProducer> out = new LinkedHashMap<>();
        for (AgentUnit unit : installedUnits(store)) {
            for (CliDependency dep : unit.cliDependencies()) {
                out.putIfAbsent(lockKey(dep.backend(), RequestedVersion.of(dep).tool()),
                        new CliProducer(unit.name(), dep));
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
