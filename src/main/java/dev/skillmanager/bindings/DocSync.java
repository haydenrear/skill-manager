package dev.skillmanager.bindings;

import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-binding sync for {@link ProjectionKind#MANAGED_COPY} doc-repo
 * projections — the four-state drift matrix applied per row, with
 * per-binding side effects on disk (rewrite tracked copy when
 * upgrading; warn or {@code --force} clobber on local edits or
 * conflict). The companion {@link ManagedImports} editor is rerun
 * over each {@link ProjectionKind#IMPORT_DIRECTIVE} row to ensure
 * the managed section is in lockstep with the ledger.
 *
 * <p>Decoupled from {@code DocSyncCommand} so tests can drive the
 * loop directly with constructed fixtures.
 */
public final class DocSync {

    private DocSync() {}

    public record Outcome(
            List<Action> actions,
            int errors,
            int warnings
    ) {
        public static Outcome empty() { return new Outcome(List.of(), 0, 0); }
    }

    /** One per binding visited; reports what sync decided for that binding. */
    public record Action(
            String bindingId,
            String unitName,
            String subElement,
            Path targetRoot,
            String description,
            Severity severity
    ) {
        public enum Severity { INFO, WARN, ERROR }
    }

    /**
     * Run sync over every binding for {@code unitName}. Returns the
     * per-binding actions plus warning + error counts the CLI uses
     * to pick its exit code.
     *
     * @param force when {@code true}, locally-edited and conflict
     *              bindings get clobbered (and the user is told what
     *              was lost via the action description).
     */
    public static Outcome run(SkillStore store, String unitName, boolean force) {
        BindingStore bs = new BindingStore(store);
        ProjectionLedger ledger = bs.read(unitName);
        if (ledger.bindings().isEmpty()) {
            Log.info("sync: no bindings recorded for %s", unitName);
            return Outcome.empty();
        }
        List<Action> actions = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        for (Binding b : ledger.bindings()) {
            // Verify the target root still exists. Don't auto-prune
            // bindings to deleted dirs — surface as an error so the
            // user can decide.
            if (b.targetRoot() != null && !Files.isDirectory(b.targetRoot())) {
                actions.add(new Action(
                        b.bindingId(), b.unitName(), b.subElement(), b.targetRoot(),
                        "target root missing — review and re-bind, or run `skill-manager unbind "
                                + b.bindingId() + "`",
                        Action.Severity.ERROR));
                errors++;
                continue;
            }
            // Walk each projection: MANAGED_COPY rows route through the
            // four-state matrix; IMPORT_DIRECTIVE rows re-run the
            // managed-section editor (idempotent). Other kinds aren't
            // expected on doc-repos but we tolerate them.
            try {
                List<Projection> updated = new ArrayList<>(b.projections().size());
                boolean bindingChanged = false;
                for (Projection p : b.projections()) {
                    switch (p.kind()) {
                        case MANAGED_COPY -> {
                            ManagedSync r = syncManagedCopy(p, force);
                            actions.add(new Action(
                                    b.bindingId(), b.unitName(), b.subElement(),
                                    b.targetRoot(), r.description, r.severity));
                            if (r.severity == Action.Severity.WARN) warnings++;
                            if (r.severity == Action.Severity.ERROR) errors++;
                            if (r.newProjection != null) {
                                updated.add(r.newProjection);
                                bindingChanged = true;
                            } else {
                                updated.add(p);
                            }
                        }
                        case IMPORT_DIRECTIVE -> {
                            // Idempotent re-apply — keeps the @-line in
                            // place even if the user manually pruned it.
                            String line = "@" + p.sourcePath().toString();
                            Path md = p.destPath();
                            String current = Files.exists(md) ? Files.readString(md) : "";
                            String next = ManagedImports.upsertLine(current, line);
                            if (!current.equals(next)) {
                                Files.createDirectories(md.getParent());
                                Files.writeString(md, next);
                            }
                            // Report unknown lines so the user can reconcile.
                            for (String unknown : ManagedImports.unknownLines(current)) {
                                actions.add(new Action(
                                        b.bindingId(), b.unitName(), b.subElement(),
                                        b.targetRoot(),
                                        "unknown line in managed section of " + md + ": " + unknown,
                                        Action.Severity.WARN));
                                warnings++;
                            }
                            updated.add(p);
                        }
                        default -> updated.add(p);
                    }
                }
                if (bindingChanged) {
                    bs.write(ledger.withBinding(b.withProjections(updated)));
                    // Re-read so subsequent iterations see the update.
                    ledger = bs.read(unitName);
                }
            } catch (IOException io) {
                actions.add(new Action(
                        b.bindingId(), b.unitName(), b.subElement(), b.targetRoot(),
                        "sync failed: " + io.getMessage(),
                        Action.Severity.ERROR));
                errors++;
            }
        }
        return new Outcome(actions, errors, warnings);
    }

    /** Per-projection result returned by {@link #syncManagedCopy}. */
    private record ManagedSync(
            String description,
            Action.Severity severity,
            /** Replacement projection (with new boundHash) when sync rewrote dest; else null. */
            Projection newProjection
    ) {}

    private static ManagedSync syncManagedCopy(Projection p, boolean force) throws IOException {
        String currentSource = Sha256.hashFile(p.sourcePath());
        String currentDest = Sha256.hashFile(p.destPath());
        SyncDecision.State state = SyncDecision.decide(p.boundHash(), currentSource, currentDest);
        switch (state) {
            case UP_TO_DATE -> {
                return new ManagedSync(
                        "up-to-date " + p.destPath(),
                        Action.Severity.INFO,
                        null);
            }
            case UPGRADE_AVAILABLE -> {
                Files.createDirectories(p.destPath().getParent());
                Files.copy(p.sourcePath(), p.destPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new ManagedSync(
                        "upgraded " + p.destPath() + " (boundHash → " + abbrev(currentSource) + ")",
                        Action.Severity.INFO,
                        new Projection(p.bindingId(), p.sourcePath(), p.destPath(),
                                p.kind(), p.backupOf(), currentSource));
            }
            case LOCALLY_EDITED -> {
                if (!force) {
                    return new ManagedSync(
                            "locally edited (preserved): " + p.destPath()
                                    + " — re-run with --force to clobber",
                            Action.Severity.WARN,
                            null);
                }
                Files.copy(p.sourcePath(), p.destPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new ManagedSync(
                        "force-rewrote locally-edited " + p.destPath() + " (local edits lost)",
                        Action.Severity.WARN,
                        new Projection(p.bindingId(), p.sourcePath(), p.destPath(),
                                p.kind(), p.backupOf(), currentSource));
            }
            case CONFLICT -> {
                if (!force) {
                    return new ManagedSync(
                            "conflict (both changed): " + p.destPath()
                                    + " — re-run with --force to take source",
                            Action.Severity.WARN,
                            null);
                }
                Files.copy(p.sourcePath(), p.destPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new ManagedSync(
                        "force-resolved conflict at " + p.destPath() + " — took source",
                        Action.Severity.WARN,
                        new Projection(p.bindingId(), p.sourcePath(), p.destPath(),
                                p.kind(), p.backupOf(), currentSource));
            }
            case ORPHAN_SOURCE -> {
                return new ManagedSync(
                        "orphan source: " + p.sourcePath()
                                + " missing — binding stale until rebound",
                        Action.Severity.ERROR,
                        null);
            }
            case ORPHAN_DEST -> {
                Files.createDirectories(p.destPath().getParent());
                Files.copy(p.sourcePath(), p.destPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new ManagedSync(
                        "recreated dest at " + p.destPath() + " (was missing)",
                        Action.Severity.INFO,
                        new Projection(p.bindingId(), p.sourcePath(), p.destPath(),
                                p.kind(), p.backupOf(), currentSource));
            }
            default -> throw new IllegalStateException("unreachable state " + state);
        }
    }

    private static String abbrev(String hash) {
        return hash == null ? "?" : hash.substring(0, Math.min(12, hash.length()));
    }
}
