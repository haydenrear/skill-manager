package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager bind <coord> --to <root>} creates a persisted
 * {@link Binding} from an installed unit (or one of its sub-elements)
 * to {@code <root>} and materializes the resulting filesystem actions.
 * The binding survives across {@code sync} and is torn down by
 * {@code unbind} or by removal of the underlying unit via
 * {@code uninstall} (ledger walk).
 */
@Command(name = "bind",
        description = """
                Bind an installed unit (or one of its sub-elements) to a
                target root. Creates a persisted Binding record in
                installed/<name>.projections.json, then materializes the
                filesystem actions (symlink + optional rename-original-
                backup for RENAME_EXISTING).

                Examples:
                  bind plugin:repo-intel --to /Users/me/work
                  bind doc:my-prompts/review-stance --to ./project --policy rename
                  bind hello-skill --to ~/scratch --id team-scratch
                """)
public final class BindCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Unit coord (whole-unit or sub-element)")
    String coord;

    @Option(names = "--to", required = true,
            description = "Target root directory the unit projects into.")
    String targetRoot;

    @Option(names = "--id",
            description = "Optional explicit binding id (default: ULID).")
    String bindingId;

    @Option(names = "--policy",
            description = "Conflict policy when destPath exists: error|rename|skip|overwrite. "
                    + "Default: error for whole-unit bindings, rename for sub-element bindings.")
    String policy;

    @Option(names = "--dry-run",
            description = "Print the effects without touching the filesystem or ledger.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        Coord c = Coord.parse(coord);
        // The resolver path is overkill for an already-installed unit —
        // bind only operates on units that are in the store. We just
        // need the unit name + kind to construct the projection.
        Optional<NameKind> nk = resolveLocal(c, store);
        if (nk.isEmpty()) {
            Log.error("not installed: %s — `skill-manager install %s` first", coord, coord);
            return 1;
        }
        String unitName = nk.get().name;
        UnitKind unitKind = nk.get().kind;
        String subElement = c instanceof Coord.SubElement s ? s.elementName() : null;

        if (subElement != null) {
            // Sub-element bindings need a unit-kind-specific parser to
            // map the sub-element to a destination — that lands with
            // ticket #48 (doc-repos) and #47 (harness templates). Until
            // then, only whole-unit bindings are supported.
            Log.error("sub-element bindings not yet supported (pending #48 / #47)");
            return 2;
        }

        Path tr = Path.of(expandHome(targetRoot)).toAbsolutePath().normalize();
        ConflictPolicy cp = resolvePolicy(policy, subElement != null);
        String id = bindingId != null && !bindingId.isBlank()
                ? bindingId
                : BindingStore.newBindingId();

        // Whole-unit binding: one SYMLINK projection from <store>/<kind>/<name>
        // to <targetRoot>/<name>.
        Path source = store.unitDir(unitName, unitKind);
        Path dest = tr.resolve(unitName);
        Projection sym = new Projection(id, source, dest, ProjectionKind.SYMLINK, null);

        List<SkillEffect> effects = new ArrayList<>();
        // If RENAME_EXISTING and the dest exists, emit a backup move
        // BEFORE the symlink projection (so the symlink lands on a
        // clean dest). The backup is its own ledger row so unbind can
        // restore it independently.
        Projection backup = null;
        if (cp == ConflictPolicy.RENAME_EXISTING && java.nio.file.Files.exists(dest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            String ts = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            Path bak = tr.resolve(unitName + ".skill-manager-backup-" + ts);
            backup = new Projection(id, null, bak, ProjectionKind.RENAMED_ORIGINAL_BACKUP, dest.toString());
            effects.add(new SkillEffect.MaterializeProjection(backup, cp));
        }
        effects.add(new SkillEffect.MaterializeProjection(sym, cp));

        List<Projection> ledgerRows = new ArrayList<>();
        if (backup != null) ledgerRows.add(backup);
        ledgerRows.add(sym);

        Binding b = new Binding(
                id, unitName, unitKind, subElement, tr, cp,
                BindingStore.nowIso(), BindingSource.EXPLICIT, ledgerRows);
        effects.add(new SkillEffect.CreateBinding(b));

        Program<Void> program = new Program<>("bind-" + id, effects, receipts -> null);
        GatewayConfig gw = GatewayConfig.resolve(store, null);
        if (dryRun) {
            new DryRunInterpreter(store).run(program);
            return 0;
        }
        Executor.Outcome<Void> outcome = new Executor(store, gw).run(program);
        if (outcome.rolledBack()) {
            Log.error("bind rolled back %d effect(s)", outcome.applied().size());
            return 4;
        }
        return 0;
    }

    private static Optional<NameKind> resolveLocal(Coord c, SkillStore store) {
        UnitStore us = new UnitStore(store);
        return switch (c) {
            case Coord.Kinded k -> us.read(k.name())
                    .map(u -> new NameKind(u.name(), u.unitKind()));
            case Coord.Bare b -> us.read(b.name())
                    .map(u -> new NameKind(u.name(), u.unitKind()));
            case Coord.SubElement s -> resolveLocal(s.unitCoord(), store);
            case Coord.DirectGit g -> Optional.empty();
            case Coord.Local l -> Optional.empty();
        };
    }

    private static ConflictPolicy resolvePolicy(String policy, boolean isSubElement) {
        if (policy == null || policy.isBlank()) {
            return isSubElement ? ConflictPolicy.RENAME_EXISTING : ConflictPolicy.ERROR;
        }
        return switch (policy.toLowerCase(Locale.ROOT)) {
            case "error" -> ConflictPolicy.ERROR;
            case "rename", "rename-existing", "rename_existing" -> ConflictPolicy.RENAME_EXISTING;
            case "skip" -> ConflictPolicy.SKIP;
            case "overwrite" -> ConflictPolicy.OVERWRITE;
            default -> throw new IllegalArgumentException("unknown --policy: " + policy);
        };
    }

    private static String expandHome(String path) {
        if (path == null) return null;
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private record NameKind(String name, UnitKind kind) {}
}
