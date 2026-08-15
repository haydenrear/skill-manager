package dev.skillmanager.artifacts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The edges: which artifact produced the thing another artifact was built from.
 *
 * <h2>Resolution is by OUTPUT PATH, never by parsing an id</h2>
 *
 * <p>An id embeds a path for exactly one kind and not the others:
 * {@code provisioned-tree:cache/uv-tools} happens to read like its own path,
 * {@code unit-store:deploy-helm} does not (the bytes are at
 * {@code skills/deploy-helm}), and {@code cli-shim:brew/opentofu} really does
 * not (the shim is {@code bin/cli/tofu}). Matching ids would work for the first
 * kind and fail SILENTLY for the other two, producing a graph that is
 * plausible, partial, and wrong in a way no test written against the first kind
 * would catch. So an input reference is matched against other artifacts'
 * {@link Artifact.Output#path()}, which every kind records truthfully because
 * that is the field a presence probe already reads.
 *
 * <h2>The prefix-containment rule, stated rather than discovered</h2>
 *
 * <p>A {@code store:} reference names the path the depending artifact actually
 * reaches, and the producing artifact records the ROOT of what it produced.
 * Those are different strings in the motivating case and in most real ones:
 * {@code bin/cli/jinja2} reaches
 * {@code venvs/jinja2-cli/bin/jinja2} while the artifact that made it is
 * {@code provisioned-tree:venvs/jinja2-cli}, whose sole output path is
 * {@code venvs/jinja2-cli}. Exact-match resolution finds no edge for exactly
 * the case the edge exists for. The rule is therefore:
 *
 * <ol>
 *   <li><b>Exact match wins.</b> An output path equal to the reference is its
 *       producer, and nothing further is considered.</li>
 *   <li><b>Otherwise the longest ancestor wins.</b> An output path {@code Q}
 *       produces a reference {@code P} when {@code P} starts with
 *       {@code Q + "/"}. Among several such {@code Q}, the LONGEST is the
 *       producer — {@code cache/uv-tools/skill-dev} beats {@code cache} — so a
 *       tree nested inside another tree is credited to the nearer one rather
 *       than to whichever the map happened to hold first.</li>
 *   <li><b>Segment boundaries only.</b> The {@code + "/"} is load-bearing:
 *       {@code venvs/jinja2-cli-old} is not inside {@code venvs/jinja2-cli},
 *       and plain {@code startsWith} would say it is. This is the failure that
 *       makes a stale report name an unrelated tree, which costs more trust
 *       than the missing edge it was added to fix.</li>
 *   <li><b>Containment resolves in one direction only.</b> A reference is
 *       produced by an artifact that produced a path CONTAINING it. An artifact
 *       whose output is inside the referenced directory is not its producer:
 *       a shim depends on a file within a tree, and a tree does not depend on
 *       the files within it being made by somebody else. Reversing this is
 *       precisely how a cycle gets invented in a graph that has none.</li>
 *   <li><b>An artifact is never its own producer.</b> A self-edge is dropped
 *       rather than reported as a cycle, because it is not one: a doc unit
 *       whose store bytes are both its input and its output is describing one
 *       thing twice, not a loop.</li>
 * </ol>
 *
 * <h2>What is NOT an edge</h2>
 *
 * <p>{@code spec:}, {@code git:} and {@code binding:} references are terminal
 * by construction: no artifact in a home produces a package spec, an upstream
 * git url or a binding row, so a reference to one is a leaf of the graph and
 * not a missing edge. {@code record:} is terminal for the same reason unless
 * exactly one artifact names that record as its {@link Artifact#source()} —
 * {@code cli-lock.toml} is the source of every shim, so a {@code record:}
 * reference to it resolves to nothing rather than to twenty-five things.
 *
 * <h2>Nothing here is stored</h2>
 *
 * <p>This class holds no state that outlives the call that built it and writes
 * no file. Edges are recomputed from {@link Artifact#allInputs()} and
 * {@link Artifact#outputs()} on every read, which is what keeps the ledger an
 * index of identity rather than a fourth source of truth. A persisted edge is a
 * fact that can disagree with the disk.
 */
public final class ArtifactGraph {

    private final List<Artifact> artifacts;
    private final Map<String, Artifact> byId;
    /** id → the ids it is built from, in input order. */
    private final Map<String, List<String>> dependsOn;
    /** id → the ids built from it. */
    private final Map<String, List<String>> feeds;
    /** id → input references that resolved to no artifact at all. */
    private final Map<String, List<String>> unresolved;
    private final List<String> topological;

    private ArtifactGraph(List<Artifact> artifacts, Map<String, Artifact> byId,
                          Map<String, List<String>> dependsOn, Map<String, List<String>> feeds,
                          Map<String, List<String>> unresolved, List<String> topological) {
        this.artifacts = artifacts;
        this.byId = byId;
        this.dependsOn = dependsOn;
        this.feeds = feeds;
        this.unresolved = unresolved;
        this.topological = topological;
    }

    public static ArtifactGraph of(ArtifactIndex index) {
        return of(index.artifacts());
    }

    /**
     * Build the graph.
     *
     * @throws ArtifactCycleException when the edges are not acyclic. Checked
     *         before any consumer walks them, so no caller can be the one that
     *         overflows a stack on a bad graph.
     */
    public static ArtifactGraph of(List<Artifact> artifacts) {
        Map<String, Artifact> byId = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) byId.putIfAbsent(artifact.id(), artifact);

        // path → producing artifact id. First writer wins and the duplicate is
        // ignored rather than overwriting: ArtifactBackfill.add already warns
        // about a contested id, and two artifacts claiming one output path is
        // the same defect one field over.
        Map<String, String> producerByPath = new LinkedHashMap<>();
        // The unit-store lookup, so `unit:<name>` never has to be turned into
        // an id by string concatenation — see the class javadoc on ids.
        Map<String, String> unitStoreByOwner = new LinkedHashMap<>();
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            for (Artifact.Output output : artifact.outputs()) {
                if (output.scope() != Artifact.Scope.HOME) continue;
                String path = normalize(output.path());
                if (path == null) continue;
                producerByPath.putIfAbsent(path, artifact.id());
            }
            if (artifact.kind() == ArtifactKind.UNIT_STORE && artifact.owner() != null) {
                unitStoreByOwner.putIfAbsent(artifact.owner(), artifact.id());
            }
            if (artifact.source() != null) {
                bySource.computeIfAbsent(artifact.source(), k -> new ArrayList<>())
                        .add(artifact.id());
            }
        }

        Map<String, List<String>> dependsOn = new LinkedHashMap<>();
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        Map<String, List<String>> unresolved = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            Set<String> edges = new LinkedHashSet<>();
            List<String> dangling = new ArrayList<>();
            for (String input : artifact.allInputs()) {
                String producer = resolve(input, producerByPath, unitStoreByOwner, bySource);
                if (producer == null) {
                    if (!terminal(input)) dangling.add(input);
                    continue;
                }
                // Self is DROPPED, and dropped is not dangling. Rule 5: an
                // artifact naming its own output is one thing described twice.
                // Reporting it as unresolved would tell a consumer this home
                // does not hold a path it demonstrably produces, and
                // ArtifactFreshness would downgrade a current artifact on it.
                if (producer.equals(artifact.id())) continue;
                edges.add(producer);
            }
            dependsOn.put(artifact.id(), List.copyOf(edges));
            if (!dangling.isEmpty()) unresolved.put(artifact.id(), List.copyOf(dangling));
            for (String producer : edges) {
                feeds.computeIfAbsent(producer, k -> new ArrayList<>()).add(artifact.id());
            }
        }
        for (Map.Entry<String, List<String>> entry : feeds.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue()));
        }
        for (String id : byId.keySet()) feeds.putIfAbsent(id, List.of());

        return new ArtifactGraph(List.copyOf(artifacts), byId,
                dependsOn, feeds, unresolved, topoSort(byId.keySet(), dependsOn));
    }

    /**
     * The artifact that produced what {@code input} names, or null.
     *
     * <p>The four schemes are handled separately because they mean different
     * things, not because they are spelled differently:
     *
     * <ul>
     *   <li>{@code unit:<name>} — the unit's own store bytes. Looked up by
     *       {@code (kind, owner)} rather than by building the id from the name,
     *       so that the id grammar stays a private matter of
     *       {@link ArtifactIds}.</li>
     *   <li>{@code store:<path>} — the containment rule above.</li>
     *   <li>{@code record:<path>} — only when exactly one artifact declares
     *       that record as its source.</li>
     *   <li>everything else — terminal.</li>
     * </ul>
     */
    private static String resolve(String input, Map<String, String> producerByPath,
                                  Map<String, String> unitStoreByOwner,
                                  Map<String, List<String>> bySource) {
        if (input == null) return null;
        if (input.startsWith("unit:")) {
            return unitStoreByOwner.get(input.substring("unit:".length()));
        }
        if (input.startsWith("store:")) {
            return producerOf(normalize(input.substring("store:".length())), producerByPath);
        }
        if (input.startsWith("record:")) {
            List<String> owners = bySource.get(input.substring("record:".length()));
            // A record naming many artifacts (cli-lock.toml) is a shared file,
            // not a producer. Silence is the honest answer; picking the first
            // would invent an edge and order-dependently at that.
            if (owners != null && owners.size() == 1) return owners.get(0);
        }
        return null;
    }

    /** Rules 1–4 of the class javadoc, in that order. */
    private static String producerOf(String path, Map<String, String> producerByPath) {
        if (path == null) return null;
        String exact = producerByPath.get(path);
        if (exact != null) return exact;
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> candidate : producerByPath.entrySet()) {
            String producedPath = candidate.getKey();
            if (producedPath.length() >= path.length()) continue;
            // Segment boundary: venvs/jinja2-cli-old is NOT under venvs/jinja2-cli.
            if (!path.startsWith(producedPath + "/")) continue;
            if (producedPath.length() > bestLength) {
                bestLength = producedPath.length();
                best = candidate.getValue();
            }
        }
        return best;
    }

    /** Whether an unresolved reference is expected to be unresolved. */
    private static boolean terminal(String input) {
        return input != null && (input.startsWith("spec:") || input.startsWith("git:")
                || input.startsWith("binding:") || input.startsWith("record:"));
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) return null;
        String out = path.replace('\\', '/');
        while (out.startsWith("./")) out = out.substring(2);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? null : out;
    }

    /**
     * Producers first. Iterative rather than recursive: the recursion depth of
     * a real home's graph is small, but "small today" is not a property of a
     * graph derived from whatever a home happens to hold, and the failure mode
     * of getting it wrong is a stack overflow rather than a message.
     */
    private static List<String> topoSort(Set<String> ids, Map<String, List<String>> dependsOn) {
        Map<String, Integer> state = new LinkedHashMap<>();   // 0 unseen, 1 open, 2 done
        List<String> order = new ArrayList<>();
        for (String root : ids) {
            if (state.getOrDefault(root, 0) == 2) continue;
            // (id, index into its dependency list); a frame is popped when its
            // dependencies are exhausted, which is what makes the emitted order
            // producers-first.
            List<String> path = new ArrayList<>();
            List<Integer> cursor = new ArrayList<>();
            path.add(root);
            cursor.add(0);
            state.put(root, 1);
            while (!path.isEmpty()) {
                int depth = path.size() - 1;
                String id = path.get(depth);
                List<String> deps = dependsOn.getOrDefault(id, List.of());
                int at = cursor.get(depth);
                if (at >= deps.size()) {
                    state.put(id, 2);
                    order.add(id);
                    path.remove(depth);
                    cursor.remove(depth);
                    continue;
                }
                cursor.set(depth, at + 1);
                String next = deps.get(at);
                int seen = state.getOrDefault(next, 0);
                if (seen == 2) continue;
                if (seen == 1) {
                    List<String> chain = new ArrayList<>(path.subList(path.indexOf(next),
                            path.size()));
                    chain.add(next);
                    throw new ArtifactCycleException(chain);
                }
                state.put(next, 1);
                path.add(next);
                cursor.add(0);
            }
        }
        return List.copyOf(order);
    }

    // ------------------------------------------------------------------ reads

    public List<Artifact> artifacts() { return artifacts; }

    public Artifact byId(String id) { return byId.get(id); }

    /** The artifacts {@code id} is built from. */
    public List<String> dependsOn(String id) {
        return dependsOn.getOrDefault(id, List.of());
    }

    /** The artifacts built from {@code id} — what a move to it makes stale. */
    public List<String> feeds(String id) {
        return feeds.getOrDefault(id, List.of());
    }

    /**
     * Input references of {@code id} that name something no artifact in this
     * home produces, excluding the schemes that are terminal by construction.
     *
     * <p>Never folded into "no dependencies": an input this home cannot account
     * for is the reason a verdict has to be {@code unverifiable} rather than
     * {@code current}, and a graph that reported it as "no edge" would let the
     * verdict be computed as if the input were satisfied.
     */
    public List<String> unresolvedInputs(String id) {
        return unresolved.getOrDefault(id, List.of());
    }

    /** Every id, producers before consumers. */
    public List<String> topological() { return topological; }

    /** Every id reachable downstream of {@code id}, itself excluded. */
    public Set<String> downstreamOf(String id) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(feeds(id));
        while (!queue.isEmpty()) {
            String next = queue.remove(queue.size() - 1);
            if (!seen.add(next)) continue;
            queue.addAll(feeds(next));
        }
        return seen;
    }
}
