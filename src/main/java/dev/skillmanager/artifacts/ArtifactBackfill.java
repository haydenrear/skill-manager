package dev.skillmanager.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.HarnessInstanceLock;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.Sha256;
import dev.skillmanager.cli.installer.CliArtifact;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Derives the full artifact set from the records a home already has.
 *
 * <p><b>No rebuild, and no migration.</b> A home provisioned months before this
 * class existed lists correctly, because every fact it needs is already written
 * somewhere: {@code cli-lock.toml} for the shims, {@code installed/<u>.json} for
 * the store copies, {@code installed/<u>.projections.json} for the agent-side
 * links, the generated marketplace tree, {@code harnesses/instances/}, the
 * gateway's server list, and {@code home.digest.json}. A migration that required
 * re-provisioning would be the eager behaviour this epic is removing, arriving
 * as its own cure.
 *
 * <p>The backfill is also what keeps {@link ArtifactLedger} honest. Because the
 * whole set can be re-derived on demand, the ledger never has to be trusted as
 * a source of truth — it contributes only the artifacts the home can no longer
 * see, which is exactly the "declared but not materialized" case.
 *
 * <h2>What is probed, and what is not</h2>
 *
 * <p>Presence is probed for every output: cheap, and the point of the exercise.
 * Agreement is probed only where the answer is cheap AND meaningful — a unit
 * store's git HEAD, a managed copy's bytes. It is deliberately NOT probed for
 * {@link ArtifactKind#UNIT_DIGEST}, whose verification is a full walk of the
 * unit; that stays {@link Artifact.Agreement#UNVERIFIABLE}, which says "I did
 * not look" rather than pretending either answer.
 */
public final class ArtifactBackfill {

    /** Store roots holding machine-provisioned trees, in listing order. */
    static final List<String> PROVISIONED_ROOTS = List.of("cache", "venvs", "tools", "npm", "pm");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillStore store;
    private final Path root;

    public ArtifactBackfill(SkillStore store) {
        this.store = store;
        this.root = store.root().toAbsolutePath().normalize();
    }

    /** Every artifact this home can currently see, ordered by kind then id. */
    public List<Artifact> collect() {
        Map<String, Artifact> byId = new LinkedHashMap<>();
        add(byId, unitStores());
        add(byId, cliShims());
        add(byId, provisionedTrees());
        add(byId, projections());
        add(byId, marketplaceEntries());
        add(byId, harnessInstances());
        add(byId, mcpRegistrations());
        add(byId, docImports());
        add(byId, unitDigests());
        return List.copyOf(byId.values());
    }

    /**
     * Merge one kind's artifacts in, keeping the first of any duplicate id and
     * SAYING SO.
     *
     * <p>This was a bare {@code putIfAbsent}. Dropping an artifact because
     * something else already claimed its id is precisely the failure an id
     * scheme exists to prevent, and it is not something a listing may do
     * quietly — a home would simply report fewer artifacts than it holds, with
     * nothing anywhere saying which one lost.
     */
    private static void add(Map<String, Artifact> into, List<Artifact> artifacts) {
        for (Artifact artifact : artifacts) {
            Artifact existing = into.putIfAbsent(artifact.id(), artifact);
            if (existing != null) {
                Log.warn("artifacts: dropping a %s artifact whose id %s is already held by a %s "
                        + "artifact — one of the two is not being listed",
                        artifact.kind().id(), artifact.id(), existing.kind().id());
            }
        }
    }

    // ------------------------------------------------------------ unit store

    /**
     * One artifact per {@code installed/<name>.json}, and the one place this
     * listing compares a recorded hash against the bytes it claims to describe.
     */
    List<Artifact> unitStores() {
        List<Artifact> out = new ArrayList<>();
        for (String name : installedUnitNames()) {
            Optional<InstalledUnit> read = new UnitStore(store).read(name);
            if (read.isEmpty()) continue;
            InstalledUnit unit = read.get();
            Path dir = store.unitDir(name, unit.unitKind() == null ? UnitKind.SKILL : unit.unitKind());
            String relative = ArtifactIds.homeRelative(root, dir);

            List<String> inputs = new ArrayList<>();
            String origin = unit.origin();
            // A local origin is a claim over a checkout elsewhere on this
            // machine, so it is referenced through the record that already owns
            // it rather than copied into a second place. The test is
            // ArtifactLedger's own, not a second spelling of it: a
            // `startsWith("/")` guard passes `file:///Users/somebody/checkout`,
            // which is a first-class install coordinate, and the copy's ledger
            // would go on naming that checkout.
            String candidate = origin == null || origin.isBlank() ? null
                    : ArtifactIds.gitInput(origin, unit.gitRef());
            if (candidate != null && ArtifactLedger.unsafeReason(candidate, root.toString()) == null) {
                inputs.add(candidate);
            } else {
                inputs.add(ArtifactIds.recordInput("installed/" + name + ".json"));
            }

            String recordedHash = unit.gitHash();
            String actualHash = null;
            Artifact.Agreement agreement;
            if (recordedHash == null || recordedHash.isBlank()) {
                agreement = Artifact.Agreement.UNRECORDED;
            } else if (GitOps.isGitRepo(dir)) {
                actualHash = GitOps.headHash(dir);
                agreement = actualHash == null ? Artifact.Agreement.UNVERIFIABLE
                        : (actualHash.equals(recordedHash) ? Artifact.Agreement.AGREES
                                                           : Artifact.Agreement.DISAGREES);
            } else {
                agreement = Artifact.Agreement.UNVERIFIABLE;
            }

            out.add(new Artifact(
                    ArtifactIds.unitStore(name),
                    ArtifactKind.UNIT_STORE,
                    name,
                    inputs,
                    relative == null ? List.of()
                            : List.of(Artifact.Output.inHome(relative, presenceOf(dir))),
                    "installed/" + name + ".json",
                    Artifact.facts("git_hash", recordedHash, "git_ref", unit.gitRef(),
                            "version", unit.version()),
                    Artifact.facts("git_hash", actualHash),
                    agreement,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // -------------------------------------------------------------- bin/cli

    /**
     * One artifact per {@code cli-lock.toml} row.
     *
     * <h2>The lock cannot name the file it produced</h2>
     *
     * <p>A row is keyed by the PACKAGE — {@code ["brew"."opentofu"]},
     * {@code ["npm"."@google/gemini-cli"]}, {@code ["pip"."jinja2-cli[yaml]"]} —
     * and the shim those installs actually wrote is {@code bin/cli/tofu},
     * {@code bin/cli/gemini} and {@code bin/cli/jinja2}. The binary name lived
     * only in the declaring unit's {@code on_path}. So the mapping is rebuilt
     * here from the installed units, and where no installed unit declares the
     * row any more — an uninstalled unit leaves its lock row behind — the output
     * path is genuinely unknown and is reported as
     * {@link Artifact.Presence#UNKNOWN} rather than guessed at and reported
     * missing.
     *
     * <p>ARTI-04 closes that hole going forward rather than retroactively: the
     * recorder now writes {@code binary} into the row itself, so a row installed
     * from this version on still names its artifact after its declaring unit is
     * gone. Rows written before it cannot be repaired from anything the home
     * still holds — three in the project home are in exactly that state — so the
     * unit map is consulted first and the row's own {@code binary} is the
     * fallback, which is the order that lets a manifest that renamed its
     * {@code on_path} win over the name recorded at install time.
     *
     * <p>{@code tar:} and {@code skill-script:} are the exception, and the
     * fallback is right for them rather than merely convenient:
     * {@code TarBackend} gates on {@code bin/cli/<name>} and a skill-script
     * declares the binary it drops, so the row key IS the artifact name.
     *
     * <h2>Presence is {@link CliArtifact}, not {@code Files.exists}</h2>
     *
     * <p>Seven of the ten shims in the operator's home are generated wrappers
     * that {@code exec} into {@code cache/} or {@code venvs/}. A clone
     * re-anchors the wrapper to the new home and skips those trees, so the
     * wrapper is a perfectly good executable that cannot run — the case
     * {@code Files.isExecutable} calls healthy and {@code home verify} refuses
     * on. That disagreement is what {@link CliArtifact} was written to end, so
     * this asks it rather than growing a fourth spelling of the question.
     */
    List<Artifact> cliShims() {
        CliLock lock;
        try {
            lock = CliLock.load(store);
        } catch (IOException e) {
            Log.warn("artifacts: could not read cli-lock.toml: %s", e.getMessage());
            return List.of();
        }
        Map<String, String> declaredBinary = declaredBinaries();
        List<Artifact> out = new ArrayList<>();
        for (CliLock.Entry entry : lock.all()) {
            List<String> inputs = new ArrayList<>();
            if (entry.spec() != null) inputs.add(ArtifactIds.specInput(entry.spec()));
            for (String requester : entry.requestedBy()) inputs.add(ArtifactIds.unitInput(requester));

            String binary = declaredBinary.get(entry.backend() + " " + entry.tool());
            // Then the row's own record, for a row whose declaring unit is gone.
            if (binary == null) binary = entry.binary();
            boolean nameIsTheArtifact =
                    "tar".equals(entry.backend()) || "skill-script".equals(entry.backend());
            if (binary == null && nameIsTheArtifact) binary = entry.tool();

            List<Artifact.Output> outputs;
            Map<String, String> actual = Map.of();
            if (binary == null) {
                outputs = List.of(new Artifact.Output("bin/cli/<unknown>",
                        Artifact.Scope.HOME, Artifact.Presence.UNKNOWN));
                actual = Artifact.facts("shim_name",
                        "unknown — no installed unit declares an on_path for "
                                + entry.backend() + ":" + entry.tool()
                                + ", and the row predates the recorder writing one");
            } else {
                Path shim = store.cliBinDir().resolve(binary);
                CliArtifact.Verdict verdict = CliArtifact.inspect(shim, root);
                Artifact.Presence presence = verdict.usable() ? Artifact.Presence.PRESENT
                        : ("absent".equals(verdict.reason()) ? Artifact.Presence.MISSING
                                                             : Artifact.Presence.DANGLING);
                outputs = List.of(Artifact.Output.inHome("bin/cli/" + binary, presence));
                if (!verdict.usable()) actual = Artifact.facts("unusable_because", verdict.reason());
            }

            out.add(new Artifact(
                    ArtifactIds.cliShim(entry.backend(), entry.tool()),
                    ArtifactKind.CLI_SHIM,
                    entry.requestedBy().isEmpty() ? null : entry.requestedBy().get(0),
                    inputs,
                    outputs,
                    "cli-lock.toml",
                    Artifact.facts("version", entry.version(), "sha256", entry.sha256(),
                            "binary", entry.binary(),
                            "install_fingerprint", entry.installFingerprint(),
                            "install_fingerprint_basis",
                            entry.fingerprint() == null ? null : entry.fingerprint().basis(),
                            "install_fingerprint_gap",
                            entry.fingerprint() == null ? null : entry.fingerprint().gap()),
                    actual,
                    // The fingerprint is over the artifact's declared INPUTS,
                    // so re-deriving it is the backend's job and not a read
                    // command's; ARTI-04 owns making it comparable, and
                    // claiming AGREES here would be the presence check wearing
                    // a fingerprint's clothes a second time.
                    entry.installFingerprint() == null ? Artifact.Agreement.UNRECORDED
                                                       : Artifact.Agreement.UNVERIFIABLE,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    /** {@code backend\0package} to the binary name the declaring unit named. */
    private Map<String, String> declaredBinaries() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (AgentUnit unit : store.listInstalledUnits().units()) {
                for (CliDependency dep : unit.cliDependencies()) {
                    if (dep.name() == null) continue;
                    String onPath = dep.onPath();
                    if (onPath == null || onPath.isBlank()) continue;
                    out.putIfAbsent(dep.backend() + " " + dep.name(), onPath);
                }
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not read installed units for shim names: %s", e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------- provisioned trees

    /**
     * The classes with no declaration at all: a tree exists because some
     * installer wrote it, and nothing in the home says it should.
     *
     * <p>They are therefore emitted with no inputs and no source record, which
     * is not an omission — it is the finding, stated in the model. Once
     * {@code artifacts record} has run, the ledger supplies the declaration a
     * clone needs, and the same tree in a home that skipped it lists as
     * {@link Artifact.Materialization#DECLARED_ONLY}.
     */
    List<Artifact> provisionedTrees() {
        List<Artifact> out = new ArrayList<>();
        for (String name : PROVISIONED_ROOTS) {
            Path directory = root.resolve(name);
            if (!Files.isDirectory(directory)) continue;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                List<String> sorted = new ArrayList<>();
                for (Path child : children) {
                    if (Files.isDirectory(child)) sorted.add(child.getFileName().toString());
                }
                sorted.sort(String::compareTo);
                for (String child : sorted) {
                    out.add(new Artifact(
                            ArtifactIds.provisionedTree(name, child),
                            ArtifactKind.PROVISIONED_TREE,
                            null,
                            List.of(),
                            List.of(Artifact.Output.inHome(name + "/" + child,
                                    presenceOf(directory.resolve(child)))),
                            null,
                            Map.of(), Map.of(),
                            Artifact.Agreement.UNRECORDED,
                            Artifact.Origin.HOME));
                }
            } catch (IOException e) {
                Log.warn("artifacts: could not list %s: %s", directory, e.getMessage());
            }
        }
        return out;
    }

    // ---------------------------------------------------------- projections

    /** One artifact per persisted projection row across every unit's ledger. */
    List<Artifact> projections() {
        List<Artifact> out = new ArrayList<>();
        Map<String, Integer> seen = new TreeMap<>();
        for (Binding binding : new BindingStore(store).listAll()) {
            for (Projection projection : binding.projections()) {
                String kind = projection.kind() == null ? "UNKNOWN" : projection.kind().name();
                Path dest = projection.destPath();
                String id = ArtifactIds.projection(binding.bindingId(), kind,
                        ArtifactIds.destKey(root, binding.targetRoot(), dest));
                // Last-resort disambiguation, in ledger order. It is order
                // dependent and therefore strictly weaker than the key above,
                // which is why the key does the work and why reaching this is
                // WARNED about rather than absorbed: an order-dependent id is
                // not stable across homes, so a silent fallback would hand
                // ARTI-07 a comparison that quietly stops meaning anything.
                int repeat = seen.merge(id, 1, Integer::sum);
                if (repeat > 1) {
                    Log.warn("artifacts: id collision on %s (occurrence %d, dest %s) — the "
                            + "disambiguating suffix is order-dependent and not stable across "
                            + "homes; ArtifactIds.destKey needs a rule for this shape",
                            id, repeat, dest);
                    id = id + "~" + repeat;
                }

                List<String> inputs = new ArrayList<>();
                inputs.add(ArtifactIds.unitInput(binding.unitName()));
                inputs.add(ArtifactIds.bindingInput(binding.bindingId()));
                String sourceRelative = ArtifactIds.homeRelative(root, projection.sourcePath());
                if (sourceRelative != null) inputs.add(ArtifactIds.storeInput(sourceRelative));

                List<Artifact.Output> outputs = List.of();
                if (dest != null) {
                    String relative = ArtifactIds.homeRelative(root, dest);
                    outputs = List.of(relative == null
                            ? Artifact.Output.external(dest.toString(), presenceOf(dest))
                            : Artifact.Output.inHome(relative, presenceOf(dest)));
                }

                String recordedHash = projection.boundHash();
                String actualHash = null;
                Artifact.Agreement agreement = Artifact.Agreement.UNRECORDED;
                if (recordedHash != null && !recordedHash.isBlank()) {
                    actualHash = hashOf(dest);
                    agreement = actualHash == null ? Artifact.Agreement.UNVERIFIABLE
                            : (actualHash.equals(recordedHash) ? Artifact.Agreement.AGREES
                                                               : Artifact.Agreement.DISAGREES);
                }

                out.add(new Artifact(id, ArtifactKind.PROJECTION, binding.unitName(),
                        inputs, outputs,
                        "installed/" + binding.unitName() + ".projections.json",
                        Artifact.facts("bound_hash", recordedHash,
                                "projection_kind", kind),
                        Artifact.facts("bound_hash", actualHash),
                        agreement, Artifact.Origin.HOME));
            }
        }
        return out;
    }

    // ---------------------------------------------------------- marketplace

    /** One artifact per plugin row in the generated marketplace manifest. */
    List<Artifact> marketplaceEntries() {
        Path manifest = root.resolve("plugin-marketplace")
                .resolve(".claude-plugin").resolve("marketplace.json");
        JsonNode node = readJson(manifest);
        List<Artifact> out = new ArrayList<>();
        if (node == null || !node.path("plugins").isArray()) return out;
        for (JsonNode plugin : node.path("plugins")) {
            String name = plugin.path("name").asText(null);
            if (name == null || name.isBlank()) continue;
            Path link = root.resolve("plugin-marketplace").resolve("plugins").resolve(name);
            out.add(new Artifact(
                    ArtifactIds.marketplaceEntry(name),
                    ArtifactKind.MARKETPLACE_ENTRY,
                    name,
                    List.of(ArtifactIds.unitInput(name),
                            ArtifactIds.storeInput("plugins/" + name)),
                    List.of(Artifact.Output.inHome("plugin-marketplace/plugins/" + name,
                            presenceOf(link))),
                    "plugin-marketplace/.claude-plugin/marketplace.json",
                    Map.of(), Map.of(),
                    // RefreshHarnessPlugins regenerates the manifest and the
                    // link tree unconditionally on every pass, so there is no
                    // input record for the comparison to be made against.
                    Artifact.Agreement.UNRECORDED,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // ----------------------------------------------------- harness instances

    /** One artifact per instantiated harness sandbox. */
    List<Artifact> harnessInstances() {
        Path sandboxRoot = root.resolve("harnesses").resolve("instances");
        if (!Files.isDirectory(sandboxRoot)) return List.of();
        List<Artifact> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(sandboxRoot)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) ids.add(child.getFileName().toString());
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not list %s: %s", sandboxRoot, e.getMessage());
            return out;
        }
        ids.sort(String::compareTo);
        for (String instanceId : ids) {
            Optional<HarnessInstanceLock> lock =
                    HarnessInstanceLock.read(sandboxRoot, instanceId, root);
            String harness = lock.map(HarnessInstanceLock::harnessName).orElse(null);
            List<String> inputs = harness == null ? List.of() : List.of(
                    ArtifactIds.unitInput(harness),
                    ArtifactIds.storeInput("harnesses/" + harness));
            out.add(new Artifact(
                    ArtifactIds.harnessInstance(instanceId),
                    ArtifactKind.HARNESS_INSTANCE,
                    harness,
                    inputs,
                    List.of(Artifact.Output.inHome("harnesses/instances/" + instanceId,
                            presenceOf(sandboxRoot.resolve(instanceId)))),
                    "harnesses/instances/" + instanceId + "/" + HarnessInstanceLock.FILENAME,
                    Artifact.facts("created_at", lock.map(HarnessInstanceLock::createdAt).orElse(null)),
                    Map.of(),
                    Artifact.Agreement.UNRECORDED,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // ------------------------------------------------------ MCP registrations

    /**
     * One artifact per registered MCP server — the statically configured ones
     * in {@code gateway-config.json} and the deployed ones the gateway persists
     * in {@code gateway-data/dynamic-servers.json}, keyed by server id so a
     * server present in both is one artifact and not two.
     */
    List<Artifact> mcpRegistrations() {
        Map<String, String> sources = new LinkedHashMap<>();
        JsonNode config = readJson(root.resolve("gateway-config.json"));
        if (config != null) {
            JsonNode servers = config.has("mcp_servers") ? config.get("mcp_servers")
                                                         : config.path("servers");
            if (servers.isArray()) {
                for (JsonNode server : servers) {
                    String id = firstText(server, "server_id", "name", "id");
                    if (id != null) sources.putIfAbsent(id, "gateway-config.json");
                }
            } else if (servers.isObject()) {
                servers.fieldNames().forEachRemaining(
                        name -> sources.putIfAbsent(name, "gateway-config.json"));
            }
        }
        JsonNode dynamic = readJson(root.resolve("gateway-data").resolve("dynamic-servers.json"));
        if (dynamic != null && dynamic.isArray()) {
            for (JsonNode server : dynamic) {
                String id = firstText(server, "server_id", "name", "id");
                if (id != null) sources.putIfAbsent(id, "gateway-data/dynamic-servers.json");
            }
        }
        List<Artifact> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            out.add(new Artifact(
                    ArtifactIds.mcpRegistration(entry.getKey()),
                    ArtifactKind.MCP_REGISTRATION,
                    null,
                    List.of(),
                    List.of(),
                    entry.getValue(),
                    Map.of(), Map.of(),
                    // Provisioning short-circuits on a content-independent
                    // marker, which is why an upgraded server keeps the old
                    // binary (skill-manager#103). Nothing here to compare.
                    Artifact.Agreement.UNRECORDED,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // ---------------------------------------------------------- doc imports

    /**
     * One artifact per doc unit — the import SET, whose outputs are the managed
     * copies it maintains in other repositories.
     *
     * <p>Deliberately not the {@code docs/<n>} store directory: that is already
     * a {@link ArtifactKind#UNIT_STORE} artifact, and two artifacts naming one
     * path is a model that cannot answer which of them a rebuild would fix.
     */
    List<Artifact> docImports() {
        Path docs = root.resolve("docs");
        if (!Files.isDirectory(docs)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(docs)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) names.add(child.getFileName().toString());
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not list %s: %s", docs, e.getMessage());
            return List.of();
        }
        names.sort(String::compareTo);
        List<Artifact> out = new ArrayList<>();
        BindingStore bindings = new BindingStore(store);
        for (String name : names) {
            List<Artifact.Output> outputs = new ArrayList<>();
            int copies = 0;
            int agreeing = 0;
            for (Binding binding : bindings.read(name).bindings()) {
                for (Projection projection : binding.projections()) {
                    if (projection.kind() != ProjectionKind.MANAGED_COPY) continue;
                    copies++;
                    Path dest = projection.destPath();
                    if (dest == null) continue;
                    String relative = ArtifactIds.homeRelative(root, dest);
                    outputs.add(relative == null
                            ? Artifact.Output.external(dest.toString(), presenceOf(dest))
                            : Artifact.Output.inHome(relative, presenceOf(dest)));
                    String recorded = projection.boundHash();
                    String actual = hashOf(dest);
                    if (recorded != null && recorded.equals(actual)) agreeing++;
                }
            }
            Artifact.Agreement agreement = copies == 0 ? Artifact.Agreement.UNRECORDED
                    : (agreeing == copies ? Artifact.Agreement.AGREES
                                          : Artifact.Agreement.DISAGREES);
            out.add(new Artifact(
                    ArtifactIds.docImport(name),
                    ArtifactKind.DOC_IMPORT,
                    name,
                    List.of(ArtifactIds.unitInput(name), ArtifactIds.storeInput("docs/" + name)),
                    outputs,
                    "installed/" + name + ".projections.json",
                    Artifact.facts("managed_copies", String.valueOf(copies)),
                    Artifact.facts("agreeing_copies", String.valueOf(agreeing)),
                    agreement,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // -------------------------------------------------------- digest entries

    /**
     * One artifact per unit row in {@code home.digest.json} — <b>referenced,
     * not copied</b>.
     *
     * <p>The digest already stores a per-unit digest that nothing consumes
     * incrementally: 5.3 MB over 29,872 file entries on a real home, recomputed
     * whole on every {@code home drift --record}. Duplicating it into the
     * artifact ledger would add a second 5 MB copy that can go stale; naming it
     * gives the same rows an id and an owner, which is what the DAG needs and
     * all it needs.
     */
    List<Artifact> unitDigests() {
        Optional<HomeDigest> digest = HomeDigest.read(store);
        if (digest.isEmpty()) return List.of();
        List<Artifact> out = new ArrayList<>();
        for (HomeDigest.UnitDigest unit : digest.get().units()) {
            if (unit == null || unit.name() == null) continue;
            out.add(new Artifact(
                    ArtifactIds.unitDigest(unit.name()),
                    ArtifactKind.UNIT_DIGEST,
                    unit.name(),
                    List.of(ArtifactIds.unitInput(unit.name())),
                    List.of(),
                    HomeDigest.FILENAME,
                    Artifact.facts("digest", unit.digest(),
                            "entries", String.valueOf(unit.entries().size())),
                    Map.of(),
                    // Recomputing is a full walk of the unit. "I did not look"
                    // is the honest verdict and is not AGREES.
                    Artifact.Agreement.UNVERIFIABLE,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    // ---------------------------------------------------------------- probes

    /** Names of {@code installed/*.json} that are unit records, sorted. */
    private List<String> installedUnitNames() {
        Path dir = store.installedDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.endsWith(".projections.json")) continue;
                names.add(name.substring(0, name.length() - ".json".length()));
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not list %s: %s", dir, e.getMessage());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * What is at {@code path}.
     *
     * <p>The {@link Artifact.Presence#DANGLING} branch is the reason this is
     * not {@code Files.exists}: a cloned home ships {@code bin/cli/jinja2} and
     * {@code bin/cli/skill-dev} as symlinks into {@code venvs/} and
     * {@code cache/}, neither of which a clone carries. Both links exist. A
     * check that follows links calls them missing and a check that does not
     * calls them fine; only telling the two apart says "the shim is there and
     * it will not run".
     */
    static Artifact.Presence presenceOf(Path path) {
        if (path == null) return Artifact.Presence.UNKNOWN;
        boolean link = Files.isSymbolicLink(path);
        boolean resolves = Files.exists(path);
        if (link) return resolves ? Artifact.Presence.PRESENT : Artifact.Presence.DANGLING;
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                ? Artifact.Presence.PRESENT : Artifact.Presence.MISSING;
    }

    private static String hashOf(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            return Sha256.hashFile(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonNode readJson(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            return JSON.readTree(path.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
