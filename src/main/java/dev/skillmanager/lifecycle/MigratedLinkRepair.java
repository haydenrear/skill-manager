package dev.skillmanager.lifecycle;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.project.Projector;
import dev.skillmanager.project.ProjectorRegistry;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Repoints what the content-addressed migration silently invalidates.
 *
 * <p>Before SMVENV-001 a skill's working copy was the slot itself,
 * {@code skills/<name>/}, and every agent home symlinked straight at it. The
 * migration moves that content down into {@code skills/<name>/latest/}, which
 * leaves the old links pointing at a slot with no {@code SKILL.md} under it —
 * the link still resolves, so nothing reports it as broken, but the agent sees
 * an empty directory and the skill quietly disappears.
 *
 * <p>Nothing downstream catches this on its own: the binding backfill checks
 * that the link <em>file</em> exists ({@code NOFOLLOW_LINKS}), not that it
 * still points at content, and it skips any unit that already has a ledger row.
 * So the repair has to run off the migration's own list of moved slots.
 *
 * <p>Idempotent: a link already pointing at the working copy is left alone, so
 * this is a no-op on every home that has already migrated.
 */
public final class MigratedLinkRepair {

    private MigratedLinkRepair() {}

    /**
     * @param migrated slot names returned by
     *        {@link SkillStore#migrateToContentAddressed(SkillStore)}
     * @return number of agent-home links repointed at the working copy
     */
    public static int run(SkillStore store, List<String> migrated) {
        if (migrated.isEmpty()) return 0;
        BindingStore bindings = new BindingStore(store);
        int repointed = 0;
        for (String name : migrated) {
            AgentUnit unit = transientSkill(name);
            for (Projector projector : ProjectorRegistry.defaultRegistry().projectors()) {
                for (dev.skillmanager.project.Projection planned : projector.planProjection(unit, store)) {
                    if (repoint(planned.target(), planned.source(), name, projector.agentId())) repointed++;
                }
            }
            repairLedger(bindings, store, name);
        }
        return repointed;
    }

    /**
     * Relink {@code target} at {@code source} when it points somewhere else.
     * Only symlinks are touched: a projector that materialized a real directory
     * copied the content, so that copy is stale but still readable, and
     * replacing it here would throw away whatever the agent did to it.
     */
    private static boolean repoint(Path target, Path source, String unit, String agentId) {
        try {
            if (!Files.isSymbolicLink(target)) return false;
            if (Files.readSymbolicLink(target).equals(source)) return false;
            Files.delete(target);
            Files.createSymbolicLink(target, source);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            Log.warn("reconcile: could not repoint %s on %s at the migrated store: %s",
                    unit, agentId, e.getMessage());
            return false;
        }
    }

    /**
     * Rewrite ledger rows whose {@code sourcePath} is the bare slot. Unbind
     * walks {@code destPath}, so a stale source does not break removal today —
     * but the ledger is the record of what is projected from where, and after a
     * migration it would name a directory that no longer holds the unit.
     */
    private static void repairLedger(BindingStore bindings, SkillStore store, String name) {
        Path slot = store.storeUnitDir(name);
        Path workingCopy = store.skillDir(name);
        ProjectionLedger ledger = bindings.read(name);
        boolean changed = false;
        List<Binding> next = new ArrayList<>(ledger.bindings().size());
        for (Binding binding : ledger.bindings()) {
            List<Projection> rows = new ArrayList<>(binding.projections().size());
            boolean bindingChanged = false;
            for (Projection row : binding.projections()) {
                if (row.sourcePath() != null && row.sourcePath().equals(slot)) {
                    rows.add(new Projection(row.bindingId(), workingCopy, row.destPath(),
                            row.kind(), row.backupOf(), row.boundHash()));
                    bindingChanged = true;
                } else {
                    rows.add(row);
                }
            }
            next.add(bindingChanged ? binding.withProjections(rows) : binding);
            changed |= bindingChanged;
        }
        if (!changed) return;
        try {
            bindings.write(new ProjectionLedger(name, next));
        } catch (IOException e) {
            Log.warn("reconcile: could not rewrite the binding ledger for %s: %s", name, e.getMessage());
        }
    }

    /**
     * Projectors need only name + kind to plan; the rest of the manifest stays
     * empty. Same stub the binding backfill builds.
     */
    private static AgentUnit transientSkill(String name) {
        return new Skill(name, name, null, List.of(), List.of(), List.of(), Map.of(), "", null).asUnit();
    }
}
