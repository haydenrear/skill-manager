package dev.skillmanager.resolve;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.SkillUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitKindFilter;
import dev.skillmanager.model.UnitReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pure coord-to-descriptor resolver. Stateless given its ports — the
 * same {@link Coord} + same {@link Registry} state + same on-disk
 * shape produces the same {@link UnitDescriptor}.
 *
 * <p>Resolution rules (matching the spec's grammar table):
 * <ul>
 *   <li>{@link Coord.Bare} — registry lookup; multi-kind collision
 *       returns {@link ResolutionError.MultiKindCollision}.</li>
 *   <li>{@link Coord.Kinded} — kind-filtered registry lookup; if the
 *       resolved unit is the wrong kind, returns
 *       {@link ResolutionError.KindMismatch}.</li>
 *   <li>{@link Coord.DirectGit} — clone via {@link Git#cloneAt}, parse
 *       the on-disk shape to detect kind.</li>
 *   <li>{@link Coord.Local} — read the on-disk shape directly, no clone.</li>
 * </ul>
 *
 * <p>For ticket 04 the resolver only emits descriptors. The planner
 * (ticket 05+) consumes them; until then the legacy
 * {@link Resolver} keeps the install path running unchanged.
 */
public final class CoordResolver {

    private final Git git;
    private final Registry registry;
    private final Path scratchRoot;

    public CoordResolver(Git git, Registry registry, Path scratchRoot) {
        this.git = git;
        this.registry = registry;
        this.scratchRoot = scratchRoot;
    }

    /**
     * Resolution result. {@code Resolved} carries a populated
     * {@link UnitDescriptor}; {@code Failed} carries a typed error.
     */
    public sealed interface Result permits Result.Resolved, Result.Failed {
        record Resolved(UnitDescriptor descriptor) implements Result {}
        record Failed(ResolutionError error) implements Result {}
    }

    public Result resolve(Coord coord) {
        return switch (coord) {
            case Coord.Bare b -> resolveRegistry(coord, b.name(), b.version(), UnitKindFilter.ANY);
            case Coord.Kinded k -> resolveRegistry(
                    coord, k.name(), k.version(), UnitKindFilter.forKind(k.kind()));
            case Coord.DirectGit g -> resolveDirectGit(coord, g);
            case Coord.Local l -> resolveLocal(coord, l);
        };
    }

    // -------------------------------------------------------------- registry

    private Result resolveRegistry(Coord coord, String name, String version, UnitKindFilter filter) {
        List<Registry.Hit> hits = registry.lookup(name, version, filter);
        if (hits.isEmpty()) {
            return new Result.Failed(new ResolutionError.NotFound(
                    coord.raw(), "no registry record for '" + name + "'"));
        }
        if (filter == UnitKindFilter.ANY && distinctKinds(hits).size() > 1) {
            return new Result.Failed(new ResolutionError.MultiKindCollision(
                    coord.raw(), distinctKinds(hits)));
        }
        Registry.Hit hit = hits.get(0);
        if (filter != UnitKindFilter.ANY && !filter.accepts(hit.kind())) {
            return new Result.Failed(new ResolutionError.KindMismatch(
                    coord.raw(), filterToKind(filter), hit.kind()));
        }
        return materializeFromGit(coord, hit, DiscoveryKind.REGISTRY);
    }

    // ------------------------------------------------------------ direct git

    private Result resolveDirectGit(Coord coord, Coord.DirectGit g) {
        try {
            Path dest = newScratch();
            Path tree = git.cloneAt(g.url(), g.ref(), dest);
            return materializeFromOnDisk(coord, tree, g.url(), DiscoveryKind.DIRECT, Transport.GIT,
                    git.headHash(tree));
        } catch (IOException e) {
            return new Result.Failed(new ResolutionError.FetchFailed(coord.raw(), e.getMessage()));
        }
    }

    // ----------------------------------------------------------------- local

    private Result resolveLocal(Coord coord, Coord.Local l) {
        Path tree = Path.of(l.path()).toAbsolutePath();
        if (!Files.isDirectory(tree)) {
            return new Result.Failed(new ResolutionError.NotFound(
                    coord.raw(), "no directory at '" + tree + "'"));
        }
        return materializeFromOnDisk(coord, tree, tree.toString(), DiscoveryKind.DIRECT, Transport.LOCAL, null);
    }

    // ---------------------------------------------------- registry → on-disk

    private Result materializeFromGit(Coord coord, Registry.Hit hit, DiscoveryKind discoveryKind) {
        try {
            Path dest = newScratch();
            Path tree = git.cloneAt(hit.gitUrl(), hit.gitRef(), dest);
            String sha = git.headHash(tree);
            Result onDisk = materializeFromOnDisk(coord, tree, hit.gitUrl(), discoveryKind, Transport.GIT, sha);
            if (!(onDisk instanceof Result.Resolved r)) return onDisk;
            UnitDescriptor d = r.descriptor();
            // Prefer the registry's view of (name, version) over what the
            // on-disk parser inferred — the registry is the authority for
            // identity when the unit came from there.
            UnitDescriptor canonical = new UnitDescriptor(
                    hit.name() != null ? hit.name() : d.name(),
                    hit.kind() != null ? hit.kind() : d.unitKind(),
                    hit.version() != null ? hit.version() : d.version(),
                    d.sourceId(),
                    d.discoveryKind(),
                    d.transport(),
                    d.origin(),
                    d.resolvedSha(),
                    d.references(),
                    d.cliDependencies(),
                    d.mcpDependencies()
            );
            return new Result.Resolved(canonical);
        } catch (IOException e) {
            return new Result.Failed(new ResolutionError.FetchFailed(coord.raw(), e.getMessage()));
        }
    }

    /**
     * Read the on-disk layout to detect kind, then parse via the
     * appropriate parser. Plugin's effective dep set is already
     * unioned at parse time (ticket 01).
     */
    private Result materializeFromOnDisk(Coord coord, Path tree, String origin,
                                         DiscoveryKind discoveryKind, Transport transport,
                                         String sha) {
        try {
            if (PluginParser.looksLikePlugin(tree)) {
                PluginUnit p = PluginParser.load(tree);
                return new Result.Resolved(toDescriptor(
                        p, origin, discoveryKind, transport, sha));
            }
            if (Files.isRegularFile(tree.resolve(SkillParser.SKILL_FILENAME))) {
                Skill s = SkillParser.load(tree);
                return new Result.Resolved(toDescriptor(
                        s.asUnit(), origin, discoveryKind, transport, sha));
            }
            return new Result.Failed(new ResolutionError.UnknownLayout(coord.raw(), tree.toString()));
        } catch (IOException e) {
            return new Result.Failed(new ResolutionError.FetchFailed(coord.raw(), e.getMessage()));
        }
    }

    private static UnitDescriptor toDescriptor(AgentUnit unit, String origin,
                                               DiscoveryKind discoveryKind, Transport transport,
                                               String sha) {
        return new UnitDescriptor(
                unit.name(),
                unit.kind(),
                unit.version(),
                "default",
                discoveryKind,
                transport,
                origin,
                sha,
                unit.references(),
                unit.cliDependencies(),
                unit.mcpDependencies()
        );
    }

    // ------------------------------------------------------------------ misc

    private Path newScratch() throws IOException {
        Files.createDirectories(scratchRoot);
        return Files.createTempDirectory(scratchRoot, "resolve-");
    }

    private static List<UnitKind> distinctKinds(List<Registry.Hit> hits) {
        return hits.stream().map(Registry.Hit::kind).distinct().toList();
    }

    private static UnitKind filterToKind(UnitKindFilter filter) {
        return switch (filter) {
            case SKILL_ONLY -> UnitKind.SKILL;
            case PLUGIN_ONLY -> UnitKind.PLUGIN;
            case ANY -> null;
        };
    }
}
