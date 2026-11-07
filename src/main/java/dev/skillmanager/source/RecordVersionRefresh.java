package dev.skillmanager.source;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Path;
import java.util.Optional;

/**
 * OHV-3 (c), DEF-OHV-004: an installed record's {@code version} restated from
 * the checkout it describes.
 *
 * <p>A sync moves {@code gitHash} ({@link InstalledUnit#withGitMoved}) and
 * never touched {@code version}, so a record read {@code skt 0.8.1} while the
 * plugin.json at the very same commit said {@code 0.8.2}. {@code skt check}
 * compares hashes and called it current; {@code skt status} printed the stale
 * number. Measured on the kickoff baseline: 5 records on root, 1 on the
 * project home.
 *
 * <p>The version is taken from the manifest ONLY when the record's
 * {@code gitHash} is the checkout's HEAD — then the manifest on disk is, by
 * construction, the one the record describes. Any other state (no hash, not a
 * repository, HEAD elsewhere, a manifest that does not parse or names no
 * version) changes nothing: this restates a fact, it never guesses one.
 */
public final class RecordVersionRefresh {

    private RecordVersionRefresh() {}

    /** The record with its version restated, or empty when nothing should change. */
    public static Optional<InstalledUnit> refreshed(SkillStore store, InstalledUnit record) {
        if (record == null || record.name() == null) return Optional.empty();
        String hash = record.gitHash();
        if (hash == null || hash.isBlank()) return Optional.empty();
        try {
            Path dir = store.unitDir(record.name(), record.unitKind());
            if (dir == null || !GitOps.isGitRepo(dir)) return Optional.empty();
            if (!hash.equals(GitOps.headHash(dir))) return Optional.empty();
            Optional<AgentUnit> unit = store.loadUnit(record.name());
            if (unit.isEmpty()) return Optional.empty();
            String manifest = unit.get().version();
            if (manifest == null || manifest.isBlank() || manifest.equals(record.version())) {
                return Optional.empty();
            }
            return Optional.of(record.withVersion(manifest));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /** {@link #refreshed}, or the record unchanged. */
    public static InstalledUnit orSame(SkillStore store, InstalledUnit record) {
        return refreshed(store, record).orElse(record);
    }

    /**
     * HP-4 (#298): the record's version restated from the manifest now in the
     * store, with NO git precondition.
     *
     * <p>For the paths that have just replaced a unit's bytes wholesale and so
     * know, without asking git, that the manifest on disk is the one the
     * record now describes: {@code sync <unit> --from <dir>} (a copy of a
     * directory that is usually not a repository at all) and {@code home sync}
     * (a tree reconcile whose record carries the destination's clone-time
     * hash). {@link #refreshed} declines both — no hash, or a hash that is not
     * HEAD — which is exactly how the record stayed at 0.1.0 while the bytes
     * said 0.3.0. Everything else is as strict: an unparseable manifest, one
     * naming no version, or one naming a unit of another kind changes nothing.
     */
    public static Optional<InstalledUnit> restatedAfterWrite(SkillStore store, InstalledUnit record) {
        if (record == null || record.name() == null) return Optional.empty();
        try {
            Optional<AgentUnit> unit = store.loadUnit(record.name());
            if (unit.isEmpty()) return Optional.empty();
            if (record.unitKind() != null && unit.get().kind() != record.unitKind()) {
                return Optional.empty();
            }
            String manifest = unit.get().version();
            if (manifest == null || manifest.isBlank() || manifest.equals(record.version())) {
                return Optional.empty();
            }
            return Optional.of(record.withVersion(manifest));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /**
     * {@link #restatedAfterWrite} applied to {@code installed/<name>.json} in
     * {@code store}, when that record exists. A home with no record for the
     * unit (a materialized child home that never installed it) gets none:
     * restating a fact is not inventing a record.
     *
     * @return true when the record was rewritten
     */
    public static boolean restateAfterWrite(SkillStore store, String name) throws java.io.IOException {
        UnitStore records = new UnitStore(store);
        Optional<InstalledUnit> current = records.read(name);
        if (current.isEmpty()) return false;
        Optional<InstalledUnit> next = restatedAfterWrite(store, current.get());
        if (next.isEmpty()) return false;
        records.write(next.get());
        return true;
    }
}
