package dev.skillmanager.commands;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.DocRepoBinder;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Coord;
import dev.skillmanager.model.DocRepoParser;
import dev.skillmanager.model.DocUnit;
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
 *
 * <p>Three flows live behind one verb:
 * <ul>
 *   <li><b>Whole-unit skill/plugin</b> → one Binding with a single
 *       SYMLINK projection at {@code <root>/<name>}.</li>
 *   <li><b>Whole doc-repo</b> ({@code doc:<repo>}) → fan out to N
 *       Bindings, one per declared {@code [[sources]]} row. Each
 *       Binding has a MANAGED_COPY + IMPORT_DIRECTIVE×agents pair.</li>
 *   <li><b>Doc-repo sub-element</b> ({@code doc:<repo>/<source>}) →
 *       one Binding for just that source.</li>
 * </ul>
 *
 * <p>The Binding(s) survive across {@code sync} and are torn down by
 * {@code unbind} or by removal of the underlying unit via
 * {@code uninstall} (ledger walk).
 */
@Command(name = "bind",
        description = """
                Bind an installed unit (or one of its sub-elements) to a
                target root. Creates a persisted Binding record in
                installed/<name>.projections.json, then materializes the
                filesystem actions:
                  - skills/plugins: a symlink at <root>/<name>
                  - doc-repos: tracked file copies under <root>/docs/agents/
                               plus @-import lines in CLAUDE.md / AGENTS.md

                Examples:
                  bind plugin:repo-intel --to /Users/me/work
                  bind doc:my-prompts/review-stance --to ./project --policy rename
                  bind doc:my-prompts --to ./project
                  bind hello-skill --to ~/scratch --id team-scratch
                """)
public final class BindCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Unit coord (whole-unit or sub-element)")
    String coord;

    @Option(names = "--to", required = true,
            description = "Target root directory the unit projects into.")
    String targetRoot;

    @Option(names = "--id",
            description = "Optional explicit binding id (ignored when binding a whole doc-repo "
                    + "with multiple sources — the binder generates one id per source).")
    String bindingId;

    @Option(names = "--policy",
            description = "Conflict policy when destPath exists: error|rename|skip|overwrite. "
                    + "Default: error for whole-unit bindings, rename for doc-repo bindings.")
    String policy;

    @Option(names = "--dry-run",
            description = "Print the effects without touching the filesystem or ledger.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        Coord c = Coord.parse(coord);
        Optional<NameKind> nk = resolveLocal(c, store);
        if (nk.isEmpty()) {
            Log.error("not installed: %s — `skill-manager install %s` first", coord, coord);
            return 1;
        }
        String unitName = nk.get().name;
        UnitKind unitKind = nk.get().kind;
        String subElement = c instanceof Coord.SubElement s ? s.elementName() : null;

        if (subElement != null && unitKind != UnitKind.DOC) {
            Log.error("sub-element bindings only supported for doc-repos (got kind=%s)", unitKind);
            return 2;
        }

        Path tr = Path.of(expandHome(targetRoot)).toAbsolutePath().normalize();
        ConflictPolicy cp = resolvePolicy(policy, unitKind == UnitKind.DOC);

        List<SkillEffect> effects = new ArrayList<>();
        if (unitKind == UnitKind.DOC) {
            // Doc-repo: defer to DocRepoBinder for the fan-out.
            DocUnit du = DocRepoParser.load(store.unitDir(unitName, UnitKind.DOC));
            DocRepoBinder.Plan plan = DocRepoBinder.plan(
                    du, tr, subElement, cp, BindingSource.EXPLICIT);
            for (Binding b : plan.bindings()) {
                for (Projection p : b.projections()) {
                    effects.add(new SkillEffect.MaterializeProjection(p, cp));
                }
                effects.add(new SkillEffect.CreateBinding(b));
            }
        } else {
            // Whole-unit SKILL / PLUGIN binding.
            String id = bindingId != null && !bindingId.isBlank()
                    ? bindingId
                    : BindingStore.newBindingId();
            Path source = store.unitDir(unitName, unitKind);
            Path dest = tr.resolve(unitName);
            Projection sym = new Projection(id, source, dest, ProjectionKind.SYMLINK, null);

            Projection backup = null;
            if (cp == ConflictPolicy.RENAME_EXISTING
                    && java.nio.file.Files.exists(dest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
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
                    id, unitName, unitKind, null, tr, cp,
                    BindingStore.nowIso(), BindingSource.EXPLICIT, ledgerRows);
            effects.add(new SkillEffect.CreateBinding(b));
        }

        Program<Void> program = new Program<>("bind-" + unitName, effects, receipts -> null);
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

    private static ConflictPolicy resolvePolicy(String policy, boolean isDocRepo) {
        if (policy == null || policy.isBlank()) {
            // Doc-repos default to RENAME_EXISTING because the target
            // is typically a user's project root with a pre-existing
            // CLAUDE.md / AGENTS.md that should not be silently lost.
            // Whole-unit binds default to ERROR — a stray dir at
            // <root>/<name> is almost always a mistake.
            return isDocRepo ? ConflictPolicy.RENAME_EXISTING : ConflictPolicy.ERROR;
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
