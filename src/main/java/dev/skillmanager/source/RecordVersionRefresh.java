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
}
