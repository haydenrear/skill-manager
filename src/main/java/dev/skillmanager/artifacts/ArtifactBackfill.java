package dev.skillmanager.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.HarnessInstanceLock;
import dev.skillmanager.bindings.HarnessInstantiator;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.Sha256;
import dev.skillmanager.cli.installer.CliArtifact;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.Fingerprint;
import dev.skillmanager.mcp.McpRegistrationLock;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.HarnessParser;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.MarketplaceInputs;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.HomeCloner;
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
        List<Artifact> shims = cliShims();
        add(byId, unitStores());
        add(byId, shims);
        // After the shims, and given them: a provisioned tree's only
        // declaration in a home is the lock row whose shim runs out of it.
        add(byId, provisionedTrees(shims));
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
            List<String> observed = List.of();
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
                observed = referencedTreePaths(shim);
            }

            out.add(new Artifact(
                    ArtifactIds.cliShim(entry.backend(), entry.tool()),
                    ArtifactKind.CLI_SHIM,
                    entry.requestedBy().isEmpty() ? null : entry.requestedBy().get(0),
                    inputs,
                    outputs,
                    "cli-lock.toml",
                    Artifact.facts("backend", entry.backend(), "tool", entry.tool(),
                            "spec", entry.spec(),
                            "version", entry.version(), "sha256", entry.sha256(),
                            "binary", entry.binary(),
                            "install_fingerprint", entry.installFingerprint(),
                            "install_fingerprint_kind",
                            entry.fingerprint() == null || entry.fingerprint().kind() == null
                                    ? null : entry.fingerprint().kind().token(),
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
                    Artifact.Origin.HOME,
                    observed));
        }
        return out;
    }

    /**
     * The provisioned-tree paths {@code shim} actually runs out of, home-relative.
     *
     * <p>This is the edge the ticket exists to draw, and it is
     * {@link HomeCloner#referencesIn} rather than a name convention: a shim's
     * TREE is not recoverable from its name ({@code bin/cli/computeq} does not
     * say {@code cache/skill-script-deploy-helm-computeq}) and the two shim
     * shapes a home holds recover it two different ways — a generated wrapper
     * names the path in its body, a {@code uv}/{@code npm} shim names it as a
     * symlink target. One call covers both because that scanner already had to.
     *
     * <p>Returned as {@link Artifact#observedInputs()} and never as
     * {@link Artifact#inputs()}: this was read off the disk on this pass, and a
     * disk observation written into {@code artifacts.lock.toml} would be a
     * remembered edge that can disagree with the disk it was read from.
     */
    private List<String> referencedTreePaths(Path shim) {
        List<String> out = new ArrayList<>();
        for (String absolute : HomeCloner.referencesIn(shim, root)) {
            String relative = ArtifactIds.homeRelative(root, Path.of(absolute));
            if (relative == null) continue;
            String reference = ArtifactIds.storeInput(relative);
            if (!out.contains(reference)) out.add(reference);
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
     * The trees {@code cache/}, {@code venvs/}, {@code tools/}, {@code npm/}
     * and {@code pm/} hold — ten of them in the project home, and before
     * ARTI-05 none had an owner, an input or a source record.
     *
     * <h2>They are declared after all, one record over</h2>
     *
     * <p>Nothing writes "this tree exists because of X". What a home DOES hold
     * is the shim the same install produced, and that shim names its tree —
     * {@code bin/cli/computeq} execs
     * {@code cache/skill-script-deploy-helm-computeq/venv/bin/computeq}, and
     * {@code bin/cli/jinja2} is a symlink into {@code venvs/jinja2-cli}. So a
     * tree is credited to the {@code cli-lock.toml} row whose shim runs out of
     * it, and inherits that row's declaration: the unit that asked for it, the
     * spec that describes it, and the fingerprint the backend recorded over the
     * inputs it was built from.
     *
     * <p>The association is made with the same prefix-containment rule
     * {@link ArtifactGraph} resolves edges by — a shim references a path INSIDE
     * a tree and the tree artifact's output is its ROOT — rather than by
     * reading a naming convention out of the directory name. The convention is
     * real ({@code cache/skill-script-<unit>-<dep>}) and it is the SCRIPT's,
     * not skill-manager's: parsing it would make this listing wrong for every
     * installer that names its own directory, which is all of the ones under
     * {@code venvs/}, {@code tools/} and {@code npm/}.
     *
     * <p>A tree no shim references — {@code pm/uv}, {@code pm/node}, the shared
     * package-manager roots — keeps the old shape: no owner, no inputs, and a
     * verdict of {@code unverifiable} rather than a guess. That is still the
     * finding, now stated about two trees instead of about all of them.
     */
    List<Artifact> provisionedTrees(List<Artifact> shims) {
        List<Artifact> out = new ArrayList<>();
        List<String> treePaths = new ArrayList<>();
        Map<String, Path> directories = new LinkedHashMap<>();
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
                    treePaths.add(name + "/" + child);
                    directories.put(name + "/" + child, directory.resolve(child));
                }
            } catch (IOException e) {
                Log.warn("artifacts: could not list %s: %s", directory, e.getMessage());
            }
        }

        Map<String, Artifact> producerByTree = producersOf(treePaths, shims);
        for (String treePath : treePaths) {
            Artifact shim = producerByTree.get(treePath);
            int slash = treePath.indexOf('/');
            String id = ArtifactIds.provisionedTree(treePath.substring(0, slash),
                    treePath.substring(slash + 1));
            List<String> inputs = new ArrayList<>();
            Map<String, String> recorded = Map.of();
            String owner = null;
            String source = null;
            if (shim != null) {
                owner = shim.owner();
                source = shim.source();
                if (owner != null) inputs.add(ArtifactIds.unitInput(owner));
                String spec = shim.recorded().get("spec");
                if (spec != null) inputs.add(ArtifactIds.specInput(spec));
                recorded = shim.recorded();
            }
            out.add(new Artifact(id, ArtifactKind.PROVISIONED_TREE, owner, inputs,
                    List.of(Artifact.Output.inHome(treePath, presenceOf(directories.get(treePath)))),
                    source, recorded, Map.of(),
                    recorded.get("install_fingerprint") == null ? Artifact.Agreement.UNRECORDED
                                                                : Artifact.Agreement.UNVERIFIABLE,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    /**
     * Tree path to the shim artifact that runs out of it.
     *
     * <p>The containment test is {@link ArtifactGraph}'s, stated once here for
     * the association and once there for the edge because they are asked at
     * different times — the association has to exist before the tree artifact
     * does, and the edge is resolved after every artifact exists. Same rule,
     * and the tests pin both against the same fixture.
     *
     * <h2>What this can still get wrong, stated rather than implied</h2>
     *
     * <p>Two guards below narrow it — the reference has to RESOLVE, and the
     * tree has to have exactly one claimant — and neither makes the inference
     * sound. A shared installer root with a single consumer in this home
     * ({@code cache/uv-tools} in a home holding one uv tool) is still credited
     * to that consumer, and the tree then inherits that dep's fingerprint. No
     * record in a home says which installer created a directory, so the shim
     * that demonstrably runs out of it is the strongest available evidence and
     * not proof. It is at least ACTIONABLE evidence: if that dep's inputs
     * moved, rebuilding it is what rewrites that tree.
     */
    private Map<String, Artifact> producersOf(List<String> treePaths, List<Artifact> shims) {
        Map<String, List<Artifact>> claimants = new LinkedHashMap<>();
        for (Artifact shim : shims) {
            for (String observed : shim.observedInputs()) {
                if (!observed.startsWith("store:")) continue;
                String path = observed.substring("store:".length());
                // A reference that resolves to NOTHING is no evidence about who
                // wrote the tree it points into. A dangling
                // `bin/cli/dangler -> cache/uv-tools/…/dangler` would otherwise
                // make its lock row the sole claimant of a shared uv root and
                // hand that root one dep's fingerprint on a broken link.
                if (!Files.exists(root.resolve(path))) continue;
                String best = null;
                for (String treePath : treePaths) {
                    if (!path.equals(treePath) && !path.startsWith(treePath + "/")) continue;
                    if (best == null || treePath.length() > best.length()) best = treePath;
                }
                if (best == null) continue;
                List<Artifact> holders = claimants.computeIfAbsent(best, k -> new ArrayList<>());
                if (!holders.contains(shim)) holders.add(shim);
            }
        }
        Map<String, Artifact> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Artifact>> entry : claimants.entrySet()) {
            // EXACTLY ONE, and this is the honest half of the rule. A tree that
            // several shims run out of — `cache/uv-tools` holds every
            // uv-installed tool in a real home — was not produced by one lock
            // row, so no row's fingerprint describes it. Crediting it to
            // whichever shim the lock listed first would attribute one dep's
            // recorded inputs to another dep's artifact, and the resulting
            // "stale" would name the wrong unit. Ambiguous stays unowned, and
            // ArtifactFreshness reports it as unverifiable rather than guessing.
            if (entry.getValue().size() == 1) out.put(entry.getKey(), entry.getValue().get(0));
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

    /**
     * One artifact per plugin the marketplace should hold — asking, for the
     * first time, whether it does.
     *
     * <h2>The generated set and the set it is generated FROM</h2>
     *
     * <p>{@code RefreshHarnessPlugins} rewrites the manifest and the symlink
     * tree unconditionally on every pass, which is what the census's
     * {@code PRESENCE} verdict records: nothing ever compares the generated
     * output with the installed plugin set, so "did anything change" is a
     * question the home does not ask. It is a cheap question with an exact
     * answer, and both sides of it are already on disk — so it is asked here.
     *
     * <p>Three states, each recorded as {@code marketplace_state} so the
     * verdict is decided by a fact rather than by re-deriving the comparison
     * downstream:
     *
     * <ul>
     *   <li>{@code listed} — the manifest names it and it is installed;</li>
     *   <li>{@code orphaned} — the manifest names it and it is NOT installed,
     *       so the generated tree describes a home that no longer exists;</li>
     *   <li>{@code unlisted} — it is installed and the manifest does not name
     *       it, which is the manifest having fallen behind. The artifact is
     *       still emitted, because "the artifact that should exist and does
     *       not" is the row a demand-driven build needs and the row a listing
     *       keyed on the manifest can never produce.</li>
     * </ul>
     */
    List<Artifact> marketplaceEntries() {
        Path manifest = root.resolve("plugin-marketplace")
                .resolve(".claude-plugin").resolve("marketplace.json");
        JsonNode node = readJson(manifest);
        List<String> installedPlugins = installedPluginNames();
        List<String> listed = new ArrayList<>();
        if (node != null && node.path("plugins").isArray()) {
            for (JsonNode plugin : node.path("plugins")) {
                String name = plugin.path("name").asText(null);
                if (name != null && !name.isBlank() && !listed.contains(name)) listed.add(name);
            }
        }
        // Nothing generated and nothing to generate: no artifacts, rather than
        // an "unlisted" row per plugin in a home with no marketplace at all.
        if (node == null && installedPlugins.isEmpty()) return List.of();

        List<String> names = new ArrayList<>(listed);
        for (String installed : installedPlugins) {
            if (!names.contains(installed)) names.add(installed);
        }
        names.sort(String::compareTo);

        Path marketplaceRoot = root.resolve("plugin-marketplace");
        Optional<MarketplaceInputs> inputs = MarketplaceInputs.read(marketplaceRoot);

        List<Artifact> out = new ArrayList<>();
        for (String name : names) {
            boolean isListed = listed.contains(name);
            boolean isInstalled = installedPlugins.contains(name);
            String state = isListed && isInstalled ? "listed"
                    : (isListed ? "orphaned" : "unlisted");
            Path link = marketplaceRoot.resolve("plugins").resolve(name);

            // What the generator recorded it built this entry from, and what
            // the same inputs hash to now. Two fields rather than one, because
            // "the record claims X" and "the disk says X" are different claims
            // and the whole point of this class is to stop conflating them.
            Optional<MarketplaceInputs.Entry> row = inputs.flatMap(i -> i.plugin(name));
            Optional<Fingerprint> recorded = row.flatMap(MarketplaceInputs.Entry::fingerprint)
                    .filter(Fingerprint::present);
            Fingerprint now = MarketplaceInputs.fingerprintOf(name,
                    row.map(MarketplaceInputs.Entry::source).orElse("./plugins/" + name),
                    row.map(MarketplaceInputs.Entry::target).orElse(storedLinkTarget(link)),
                    store.unitDir(name, UnitKind.PLUGIN));
            Artifact.Agreement agreement;
            if (recorded.isEmpty()) {
                agreement = Artifact.Agreement.UNRECORDED;
            } else if (!now.present()) {
                agreement = Artifact.Agreement.UNVERIFIABLE;
            } else {
                agreement = recorded.get().value().equals(now.value())
                        ? Artifact.Agreement.AGREES : Artifact.Agreement.DISAGREES;
            }

            out.add(new Artifact(
                    ArtifactIds.marketplaceEntry(name),
                    ArtifactKind.MARKETPLACE_ENTRY,
                    name,
                    List.of(ArtifactIds.unitInput(name),
                            ArtifactIds.storeInput("plugins/" + name)),
                    List.of(Artifact.Output.inHome("plugin-marketplace/plugins/" + name,
                            presenceOf(link))),
                    "plugin-marketplace/.claude-plugin/marketplace.json",
                    Artifact.facts("marketplace_state", state,
                            "input_fingerprint", recorded.map(Fingerprint::value).orElse(null),
                            "input_fingerprint_kind",
                            recorded.map(f -> f.kind().token()).orElse(null)),
                    Artifact.facts("marketplace_state", state,
                            "input_fingerprint", now.value(),
                            "input_fingerprint_gap", now.gap()),
                    agreement,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    /**
     * The target stored in a marketplace symlink, or null when the entry is a
     * copy or is not there. Read off the link rather than re-derived, so it is
     * the same string the generator wrote.
     */
    private static String storedLinkTarget(Path link) {
        try {
            return Files.isSymbolicLink(link) ? Files.readSymbolicLink(link).toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Names of installed units of kind {@code PLUGIN}, sorted. */
    private List<String> installedPluginNames() {
        List<String> out = new ArrayList<>();
        try {
            for (AgentUnit unit : store.listInstalledUnits().units()) {
                if (unit.kind() == UnitKind.PLUGIN && !out.contains(unit.name())) {
                    out.add(unit.name());
                }
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not read installed units for the marketplace: %s",
                    e.getMessage());
        }
        out.sort(String::compareTo);
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

            // The template digest the instantiation recorded, against what this
            // home's installed template hashes to now. An instance written
            // before the field existed has no recorded digest and stays
            // UNRECORDED — it is not promoted, because a grade nobody wrote
            // down is not a grade.
            Optional<Fingerprint> recorded = lock.flatMap(HarnessInstanceLock::fingerprint)
                    .filter(Fingerprint::present);
            Fingerprint now = harness == null
                    ? Fingerprint.gap("this instance's record does not name a harness, so the "
                            + "template it came from cannot be identified")
                    : currentTemplateFingerprint(harness);
            Artifact.Agreement agreement;
            if (recorded.isEmpty()) {
                agreement = Artifact.Agreement.UNRECORDED;
            } else if (!now.present()) {
                agreement = Artifact.Agreement.UNVERIFIABLE;
            } else {
                agreement = recorded.get().value().equals(now.value())
                        ? Artifact.Agreement.AGREES : Artifact.Agreement.DISAGREES;
            }

            out.add(new Artifact(
                    ArtifactIds.harnessInstance(instanceId),
                    ArtifactKind.HARNESS_INSTANCE,
                    harness,
                    inputs,
                    List.of(Artifact.Output.inHome("harnesses/instances/" + instanceId,
                            presenceOf(sandboxRoot.resolve(instanceId)))),
                    "harnesses/instances/" + instanceId + "/" + HarnessInstanceLock.FILENAME,
                    Artifact.facts("created_at", lock.map(HarnessInstanceLock::createdAt).orElse(null),
                            "input_fingerprint", recorded.map(Fingerprint::value).orElse(null),
                            "input_fingerprint_kind",
                            recorded.map(f -> f.kind().token()).orElse(null)),
                    Artifact.facts("input_fingerprint", now.value(),
                            "input_fingerprint_gap", now.gap()),
                    agreement,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    /**
     * What the installed template for {@code harness} hashes to right now, or a
     * gap naming why it could not be read.
     *
     * <p>A gap here is the ordinary case on a home that carries instances of
     * harnesses it no longer installs: the sandbox dirs survive, the template
     * does not, and "the template is gone" is a different answer from "the
     * template matches". Reporting the second would be the presence-proxy
     * defect wearing a fingerprint's clothes.
     */
    private Fingerprint currentTemplateFingerprint(String harness) {
        Path dir = store.unitDir(harness, UnitKind.HARNESS);
        if (!Files.isDirectory(dir)) {
            return Fingerprint.gap("harnesses/" + harness + " is not installed in this home, so "
                    + "the template this instance was made from cannot be re-read");
        }
        try {
            return HarnessInstantiator.fingerprintOf(HarnessParser.load(dir), store);
        } catch (IOException e) {
            return Fingerprint.gap("the installed template for " + harness
                    + " could not be parsed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------ MCP registrations

    /**
     * One artifact per MCP server this home is supposed to have registered —
     * the statically configured ones in {@code gateway-config.json}, the
     * deployed ones the gateway persists in
     * {@code gateway-data/dynamic-servers.json}, and <b>the ones a unit
     * declares and nothing registered</b>. Keyed by server id, so a server in
     * more than one of those is one artifact and not three.
     *
     * <h2>The declaring unit is the input, and it was not being read</h2>
     *
     * <p>Before ARTI-05 a registration had no owner and no inputs: it was a
     * name in a JSON file with nothing on the other end of it. Every one of
     * them is derived from a unit's {@code mcp_dependencies}, which the home
     * already parses — so that unit is the input, and the edge to its store
     * bytes is what makes "deploy-helm moved" reach the servers it declares.
     *
     * <p>The two states are recorded as {@code registration_state}:
     * {@code registered}, and {@code declared-only} for a dependency a unit
     * declares that this home has no registration for. The second is the
     * shape of an existing defect rather than a hypothetical — {@code sync}
     * performs no MCP registration since gateway work became opt-in, so a home
     * synced without {@code --include-mcp} is in exactly this state and nothing
     * in it said so.
     *
     * <p>What is NOT claimed: whether a REGISTERED server still matches its
     * declaration. The gateway persists its own normalized {@code load_spec}
     * rather than the payload skill-manager posted, so the two cannot be
     * digested against each other from this home's files.
     * {@link ArtifactFreshness} reports that as {@code unverifiable} with the
     * reason, which is the honest answer and not a missing feature dressed up
     * as a passing one.
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

        Map<String, String> declaringUnit = new LinkedHashMap<>();
        try {
            for (AgentUnit unit : store.listInstalledUnits().units()) {
                for (McpDependency dep : unit.mcpDependencies()) {
                    if (dep.name() != null) declaringUnit.putIfAbsent(dep.name(), unit.name());
                }
            }
        } catch (IOException e) {
            Log.warn("artifacts: could not read installed units for MCP declarations: %s",
                    e.getMessage());
        }

        List<String> ids = new ArrayList<>(sources.keySet());
        for (String declared : declaringUnit.keySet()) {
            if (!ids.contains(declared)) ids.add(declared);
        }
        ids.sort(String::compareTo);

        // This home's own record of what it asked the gateway to hold. Keyed by
        // server id and read here rather than re-derived: the digest was
        // computed by the writer that posted the payload, and nothing else in
        // the home can reconstruct the environment it was built in.
        McpRegistrationLock registrations = McpRegistrationLock.read(root);

        List<Artifact> out = new ArrayList<>();
        for (String id : ids) {
            String owner = declaringUnit.get(id);
            String source = sources.get(id);
            String state = source != null ? "registered" : "declared-only";
            List<String> inputs = new ArrayList<>();
            if (owner != null) {
                inputs.add(ArtifactIds.unitInput(owner));
                inputs.add(ArtifactIds.storeInput(unitStorePath(owner)));
            }
            Optional<Fingerprint> recorded = registrations.server(id)
                    .flatMap(McpRegistrationLock.Entry::fingerprint)
                    .filter(Fingerprint::present);
            out.add(new Artifact(
                    ArtifactIds.mcpRegistration(id),
                    ArtifactKind.MCP_REGISTRATION,
                    owner,
                    inputs,
                    List.of(),
                    // A declared-only server has no record of its own; naming
                    // the unit record it comes FROM keeps `source` meaning "the
                    // home-relative record that declares this artifact".
                    source != null ? source
                            : (owner == null ? null : "installed/" + owner + ".json"),
                    Artifact.facts("registration_state", state,
                            "spec_digest", recorded.map(Fingerprint::value).orElse(null),
                            "spec_digest_kind", recorded.map(f -> f.kind().token()).orElse(null)),
                    Artifact.facts("registration_state", state),
                    // Recorded, and still not re-checkable HERE. The digest
                    // covers the register payload including the init values the
                    // installing process read out of ITS environment; this pass
                    // is a different process and cannot reconstruct them, so
                    // recomputing would produce a different digest for reasons
                    // that are not staleness. "I did not look" is the honest
                    // verdict and it is not AGREES.
                    recorded.isPresent() ? Artifact.Agreement.UNVERIFIABLE
                            : Artifact.Agreement.UNRECORDED,
                    Artifact.Origin.HOME));
        }
        return out;
    }

    /** Where {@code unit}'s bytes live in this home, or its skill path by default. */
    private String unitStorePath(String unit) {
        for (UnitKind kind : UnitKind.values()) {
            Path candidate = store.unitDir(unit, kind);
            String relative = ArtifactIds.homeRelative(root, candidate);
            if (relative != null && Files.isDirectory(candidate)) return relative;
        }
        return "skills/" + unit;
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
