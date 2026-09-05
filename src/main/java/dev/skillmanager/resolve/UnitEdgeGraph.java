package dev.skillmanager.resolve;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Who depends on what, in one home, computed on demand.
 *
 * <h2>Why this exists</h2>
 *
 * <p>"What in this home imports X" had no answer. There are two edge
 * mechanisms and they address units by different keys:
 *
 * <ul>
 *   <li>{@code skill-imports} — markdown frontmatter, addresses a unit
 *       <b>by name</b>, resolved against this home's store.</li>
 *   <li>{@code skill_references} — the unit's own manifest, addresses a unit
 *       <b>by coordinate</b> ({@code github:owner/repo}, a path, a registry
 *       ref), resolved at install time by fetching.</li>
 * </ul>
 *
 * <p>Neither is recorded in {@code installed/*.json}, which holds name,
 * version, gitHash, kind, origin, installSource, errors and installedAt. So
 * the reverse edge exists nowhere on disk, and answering the question by hand
 * meant recursive grep — which returned a different answer per home and could
 * not follow a coordinate to the name it installs.
 *
 * <h2>Recomputed, never stored</h2>
 *
 * <p>This builds from the unit trees on every invocation and writes nothing.
 * A persisted index would be a second record of the edges, and a second record
 * can disagree with the units it describes — a home is a <em>copy</em>, so an
 * index copied along with it describes the home it was built in rather than
 * the one you are standing in. The homes in this repository already
 * demonstrate the divergence the index would freeze: the same question
 * answers 8 in the operator root home and 6 in the project home.
 *
 * <h2>The coordinate/name join</h2>
 *
 * <p>{@code github:haydenrear/skill-manager-skill} installs a unit named
 * {@code skill-manager}; {@code github:haydenrear/skill-publisher-skill}
 * installs {@code skt}. The repository name is not the unit name, so a
 * coordinate cannot be turned into a name by taking its last path segment —
 * that yields units no home contains. The join is each unit's own
 * {@code installed/<name>.json} {@code origin} field, via
 * {@link UnitStore#findInstalledNameByOrigin}. A coordinate that resolves to
 * nothing installed is reported as unresolved rather than guessed at.
 */
public final class UnitEdgeGraph {

    /** How one unit reaches another. */
    public enum Mechanism {
        /** {@code skill-imports} in markdown frontmatter — addressed by name. */
        SKILL_IMPORTS,
        /** {@code references} in the unit manifest — addressed by coordinate. */
        SKILL_REFERENCES
    }

    /**
     * One directed edge, with the file that carries it.
     *
     * <p>The source file is part of the answer, not decoration: "what imports
     * skill-manager" is asked by someone about to change it, and the useful
     * reply names the line they will break.
     */
    public record Edge(String from, String to, Mechanism mechanism, Path source,
                       String spelling) {
    }

    /** A unit root in this home. {@code containedIn} is null for standalone units. */
    public record Node(String name, Path root, String containedIn) {
        public boolean contained() {
            return containedIn != null;
        }
    }

    private static final String FRONTMATTER_KEY = "skill-imports";
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", ".venv", "venvs", "build",
            "target", ".gradle", "dist");

    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final List<String> unresolvedCoordinates;

    private UnitEdgeGraph(Map<String, Node> nodes, List<Edge> edges,
                          List<String> unresolvedCoordinates) {
        this.nodes = nodes;
        this.edges = edges;
        this.unresolvedCoordinates = unresolvedCoordinates;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** Coordinates naming nothing installed here — reported, never guessed. */
    public List<String> unresolvedCoordinates() {
        return unresolvedCoordinates;
    }

    /** Edges that point AT {@code target}, in importer-name order. */
    public List<Edge> directImporters(String target) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) {
            if (e.to().equals(target)) out.add(e);
        }
        out.sort(Comparator.comparing(Edge::from)
                .thenComparing(e -> e.mechanism().name())
                .thenComparing(e -> e.source().toString()));
        return out;
    }

    /**
     * Every unit that reaches {@code target}, directly or through another.
     *
     * <p>CYCLE-SAFE BY CONSTRUCTION: a name enters {@code seen} before it is
     * expanded, so a loop is walked once and the walk returns. The loops are
     * real — in this repository's root home {@code git-epic-workflow} imports
     * {@code git-issue-workflow} and it imports back — which is why the whole
     * chain was asked for rather than the direct importers alone.
     */
    public List<String> transitiveImporters(String target) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.push(target);
        while (!frontier.isEmpty()) {
            String current = frontier.pop();
            for (Edge e : directImporters(current)) {
                String importer = e.from();
                if (importer.equals(target) || !seen.add(importer)) continue;
                frontier.push(importer);
            }
        }
        List<String> out = new ArrayList<>(seen);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Build the graph for one home. Reads only. */
    public static UnitEdgeGraph of(SkillStore store) {
        Map<String, Node> nodes = new TreeMap<>();
        for (Node n : enumerate(store)) nodes.putIfAbsent(n.name(), n);

        UnitStore units = new UnitStore(store);
        List<Edge> edges = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<Path> containedRoots = nodes.values().stream()
                .filter(Node::contained).map(Node::root).toList();

        for (Node node : nodes.values()) {
            collectImports(node, containedRoots, edges);
            collectReferences(store, units, node, nodes.keySet(), edges, unresolved);
        }
        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to));
        return new UnitEdgeGraph(Map.copyOf(nodes), List.copyOf(edges),
                List.copyOf(new LinkedHashSet<>(unresolved)));
    }

    private static List<Node> enumerate(SkillStore store) {
        List<Node> out = new ArrayList<>();
        for (Path dir : List.of(store.pluginsDir(), store.harnessesDir(),
                store.docsDir(), store.skillsDir())) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(child -> {
                            String name = child.getFileName().toString();
                            if (name.startsWith(".")) return;
                            out.add(new Node(name, child, null));
                            if (!dir.equals(store.pluginsDir())) return;
                            Path skills = child.resolve("skills");
                            if (!Files.isDirectory(skills)) return;
                            try (Stream<Path> inner = Files.list(skills)) {
                                inner.filter(Files::isDirectory)
                                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                                        .forEach(skill -> {
                                            String sn = skill.getFileName().toString();
                                            // A contained skill of the plugin's own name is that
                                            // plugin's ENTRY SKILL — one unit under one name, not a
                                            // second node. Adding it would make every skt-carrying
                                            // home report a self-import.
                                            if (sn.startsWith(".") || sn.equals(name)) return;
                                            out.add(new Node(sn, skill, name));
                                        });
                            } catch (IOException ignored) {
                                // an unreadable plugin contributes no contained skills
                            }
                        });
            } catch (IOException ignored) {
                // an unreadable store directory contributes no nodes
            }
        }
        return out;
    }

    private static void collectImports(Node node, List<Path> containedRoots, List<Edge> edges) {
        for (Path md : markdownUnder(node.root())) {
            // A contained skill's files belong to the contained unit, not to
            // the plugin carrying it — otherwise every plugin inherits its
            // skills' imports and the reverse edge names the wrong importer.
            if (!node.contained() && containedRoots.stream().anyMatch(md::startsWith)) continue;
            String content;
            try {
                content = Files.readString(md);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            for (String target : importedUnits(content)) {
                edges.add(new Edge(node.name(), target, Mechanism.SKILL_IMPORTS, md, target));
            }
        }
    }

    private static void collectReferences(SkillStore store, UnitStore units, Node node,
                                          Set<String> known, List<Edge> edges,
                                          List<String> unresolved) {
        Optional<? extends AgentUnit> loaded = loadUnit(store, node);
        if (loaded.isEmpty()) return;
        Path manifest = node.root().resolve(SkillParser.TOML_FILENAME);
        for (UnitReference ref : loaded.get().references()) {
            String resolved = resolveReference(units, known, ref);
            if (resolved == null) {
                unresolved.add(spelling(ref));
                continue;
            }
            edges.add(new Edge(node.name(), resolved, Mechanism.SKILL_REFERENCES,
                    manifest, spelling(ref)));
        }
    }

    private static Optional<? extends AgentUnit> loadUnit(SkillStore store, Node node) {
        try {
            if (node.contained()) {
                return Optional.of(new dev.skillmanager.model.SkillUnit(
                        SkillParser.load(node.root())));
            }
            return store.loadUnit(node.name());
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * A reference's installed unit name, or null when nothing here matches.
     *
     * <p>The name on the coordinate is tried first only when a unit of that
     * name is actually installed; otherwise the origin join decides. Guessing
     * from the coordinate's last segment is exactly the mistake this exists to
     * avoid.
     */
    private static String resolveReference(UnitStore units, Set<String> known, UnitReference ref) {
        String named = ref.name();
        if (named != null && known.contains(named)) return named;
        String url = ref.gitUrl();
        if (url != null) {
            try {
                Optional<String> byOrigin = units.findInstalledNameByOrigin(url);
                if (byOrigin.isPresent()) return byOrigin.get();
            } catch (IOException ignored) {
                // fall through to unresolved
            }
        }
        if (ref.isLocal() && ref.path() != null) {
            Path p = Path.of(ref.path());
            String tail = p.getFileName() != null ? p.getFileName().toString() : null;
            if (tail != null && known.contains(tail)) return tail;
        }
        return null;
    }

    private static String spelling(UnitReference ref) {
        if (ref.gitUrl() != null) return ref.gitUrl();
        if (ref.path() != null) return ref.path();
        return ref.name() != null ? ref.name() : ref.toString();
    }

    private static List<Path> markdownUnder(Path root) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) return false;
                        }
                        return true;
                    })
                    .sorted()
                    .forEach(out::add);
        } catch (IOException | RuntimeException ignored) {
            // an unreadable unit contributes no edges
        }
        return out;
    }

    /** Unit names named by a {@code skill-imports} frontmatter block. */
    static List<String> importedUnits(String content) {
        List<String> out = new ArrayList<>();
        Map<String, Object> front = frontmatter(content);
        if (front == null) return out;
        Object raw = front.get(FRONTMATTER_KEY);
        if (!(raw instanceof List<?> imports)) return out;
        for (Object item : imports) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object unit = map.get("unit");
            if (unit == null) unit = map.get("skill");
            if (unit instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static Map<String, Object> frontmatter(String content) {
        if (!content.startsWith("---")) return null;
        int firstNl = content.indexOf('\n');
        if (firstNl < 0) return null;
        int end = content.indexOf("\n---", firstNl);
        if (end < 0) return null;
        try {
            Object loaded = new Yaml().load(content.substring(firstNl + 1, end));
            if (!(loaded instanceof Map<?, ?> map)) return null;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            return copy;
        } catch (YAMLException ex) {
            return null;
        }
    }
}
