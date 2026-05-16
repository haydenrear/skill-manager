package dev.skillmanager.bindings;

import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.Projector;
import dev.skillmanager.project.ProjectorRegistry;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time migration: walk every installed unit and write
 * {@link BindingSource#DEFAULT_AGENT} {@link Binding}s into the
 * projection ledger for symlinks that the install flow created
 * before ticket 49 introduced the ledger.
 *
 * <p>Idempotent: a unit whose ledger already contains a default
 * binding for the (agent, unit) pair is skipped — the existing
 * record wins. Re-running the reconciler finds nothing to do.
 * Units whose expected symlink is missing get a warning logged and
 * are not backfilled (the user will need to re-bind or sync).
 */
public final class BindingBackfill {

    private BindingBackfill() {}

    /**
     * @return number of {@link Binding}s written this pass.
     */
    public static int run(SkillStore store) {
        BindingStore bs = new BindingStore(store);
        UnitStore us = new UnitStore(store);
        ProjectorRegistry projectors = ProjectorRegistry.defaultRegistry();
        int written = 0;
        try {
            var listed = store.listInstalledUnits();
            for (var rec : listed.units()) {
                // Doc-repos and harness templates don't project into
                // agent dirs — they bind explicitly to project roots
                // (doc-repos) or sandboxes (harnesses). Backfill skips.
                if (rec.kind() == UnitKind.DOC || rec.kind() == UnitKind.HARNESS) continue;
                ProjectionLedger ledger = bs.read(rec.name());
                for (Projector proj : projectors.projectors()) {
                    String bindingId = LiveInterpreter.defaultBindingId(proj.agentId(), rec.name());
                    if (ledger.findById(bindingId).isPresent()) continue;
                    // Recover a transient AgentUnit so the projector can plan.
                    // Just name + kind needed — projectors don't reach into
                    // the unit's parsed manifest for skills/plugins paths.
                    AgentUnit transient_ = transientUnit(rec.name(), rec.kind());
                    List<dev.skillmanager.project.Projection> entries =
                            proj.planProjection(transient_, store);
                    if (entries.isEmpty()) continue;
                    List<Projection> ledgerProjections = new ArrayList<>(entries.size());
                    boolean allPresent = true;
                    for (var e : entries) {
                        if (!Files.exists(e.target(), LinkOption.NOFOLLOW_LINKS)) {
                            allPresent = false;
                            Log.warn("reconcile: expected symlink missing for %s on %s — re-bind to recover",
                                    rec.name(), proj.agentId());
                            break;
                        }
                        ledgerProjections.add(new Projection(
                                bindingId, e.source(), e.target(), ProjectionKind.SYMLINK, null));
                    }
                    if (!allPresent) continue;
                    Path targetRoot = switch (rec.kind()) {
                        case SKILL -> proj.skillsDir();
                        case PLUGIN -> proj.pluginsDir();
                        case DOC, HARNESS -> throw new IllegalStateException(
                                "doc-repos and harness templates are filtered earlier and "
                                        + "never reach the projector loop");
                    };
                    Binding b = new Binding(
                            bindingId, rec.name(), rec.kind(), null,
                            targetRoot, ConflictPolicy.ERROR,
                            BindingStore.nowIso(), BindingSource.DEFAULT_AGENT,
                            ledgerProjections);
                    ledger = ledger.withBinding(b);
                    try {
                        bs.write(ledger);
                        written++;
                    } catch (IOException io) {
                        Log.warn("reconcile: failed to backfill binding for %s on %s: %s",
                                rec.name(), proj.agentId(), io.getMessage());
                    }
                }
            }
        } catch (IOException io) {
            Log.warn("reconcile: binding backfill aborted — could not list installed units: %s",
                    io.getMessage());
        }
        return written;
    }

    /**
     * Build a stub {@link AgentUnit} for the projector. Projectors only
     * need name + kind for their planning math; the rest of the
     * manifest stays empty. Matches the same trick {@code Executor}
     * uses for {@code UnprojectIfOrphan}.
     */
    private static AgentUnit transientUnit(String name, UnitKind kind) {
        return switch (kind) {
            case SKILL -> new Skill(name, name, null,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(), "", null).asUnit();
            case PLUGIN -> new PluginUnit(
                    name, null, name,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(), java.util.List.of(), null);
            case DOC, HARNESS -> throw new IllegalStateException(
                    "doc-repos and harness templates are filtered before this call site");
        };
    }
}
