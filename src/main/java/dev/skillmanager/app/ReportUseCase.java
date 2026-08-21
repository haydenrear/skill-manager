package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closing program every command runs after its main work — replaces the
 * file-walking {@code SkillReconciler.printOutstandingErrors} with a
 * single-effect program so an unreadable {@code sources/<name>.json}
 * surfaces as a receipt instead of being silently skipped.
 *
 * <p>The {@link Report} groups outstanding errors by skill so the CLI hook
 * can render one banner per skill with hint text.
 */
public final class ReportUseCase {

    private ReportUseCase() {}

    public record Report(Map<String, List<InstalledUnit.UnitError>> errorsBySkill) {
        public boolean isEmpty() { return errorsBySkill.isEmpty(); }
    }

    public static Program<Report> buildProgram() {
        return new Program<>(
                "report-" + UUID.randomUUID(),
                List.of(new SkillEffect.LoadOutstandingErrors()),
                ReportUseCase::decode);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        Map<String, List<InstalledUnit.UnitError>> bySkill = new LinkedHashMap<>();
        for (EffectReceipt r : receipts) {
            for (ContextFact f : r.facts()) {
                if (f instanceof ContextFact.OutstandingError oe) {
                    bySkill.computeIfAbsent(oe.skillName(), k -> new java.util.ArrayList<>())
                            .add(new InstalledUnit.UnitError(oe.kind(), oe.message(), null));
                }
            }
        }
        return new Report(bySkill);
    }

    public static void print(Report report, SkillStore store) {
        Map<String, Map<InstalledUnit.ErrorKind, String>> bySkill = new LinkedHashMap<>();
        for (var entry : report.errorsBySkill.entrySet()) {
            LinkedHashMap<InstalledUnit.ErrorKind, String> byKind = new LinkedHashMap<>();
            // Handlers may emit the same kind twice across receipts.
            for (InstalledUnit.UnitError err : entry.getValue()) {
                byKind.putIfAbsent(err.kind(), err.message());
            }
            bySkill.put(entry.getKey(), byKind);
        }
        printOutstanding(bySkill, store);
    }

    /**
     * The outstanding-error banner, printed once per DISTINCT cause.
     *
     * <h2>Why it groups</h2>
     *
     * <p>One failure can be a property of something none of these units is: a
     * {@code project sync} that cannot complete is stamped on every unit the
     * project claims, so ten records carry one message. Printed per unit, and
     * re-printed by every later command — {@code ls}, {@code show},
     * {@code exec}, {@code --print-env} — that is a wall of identical text
     * which reads as "everything is broken" rather than "one thing is".
     * Measured on the reporting operator's home: 10 units, 1 distinct message.
     * Issue #144.
     *
     * <p>So units carrying an IDENTICAL error set share one block. The unit
     * count in the header is unchanged — ten degraded units are still ten
     * degraded units — and the distinct-cause count is stated beside it, which
     * is the number an operator actually has to act on. Units whose errors
     * differ still get their own block, so nothing is merged that is not the
     * same fact.
     *
     * @param bySkill unit name → its outstanding errors, one message per kind
     */
    public static void printOutstanding(
            Map<String, ? extends Map<InstalledUnit.ErrorKind, String>> bySkill,
            SkillStore store) {
        if (bySkill == null || bySkill.isEmpty()) return;
        // Keyed by the whole error set, so only units that are degraded in
        // exactly the same way share a line. Map equality is order-insensitive,
        // which is what we want: the same two errors reported in either order
        // are the same cause.
        Map<Map<InstalledUnit.ErrorKind, String>, List<String>> blocks = new LinkedHashMap<>();
        for (var entry : bySkill.entrySet()) {
            blocks.computeIfAbsent(new LinkedHashMap<>(entry.getValue()),
                    k -> new java.util.ArrayList<>()).add(entry.getKey());
        }
        LinkedHashSet<String> causes = new LinkedHashSet<>();
        for (var byKind : bySkill.values()) {
            for (var err : byKind.entrySet()) causes.add(err.getKey() + ": " + err.getValue());
        }
        dev.skillmanager.util.Log.warn("skills with outstanding errors (%d) — %d distinct "
                + "cause(s) — re-run after fixing:", bySkill.size(), causes.size());
        // One block per distinct cause, and the whole thing bounded: this
        // banner is appended by EVERY command (`ls`, `show`, `exec`,
        // `--print-env`), so a home in a bad state paid for it on every
        // invocation. The bound is on the console only — the run log carries
        // every block.
        List<String> rows = new java.util.ArrayList<>();
        for (var block : blocks.entrySet()) {
            List<String> units = block.getValue();
            rows.add(String.join(", ", units) + ":");
            for (var err : block.getKey().entrySet()) {
                rows.add("  - " + err.getKey() + ": " + err.getValue());
                rows.add("    → " + hint(err.getKey(), units, store));
            }
        }
        dev.skillmanager.util.Log.errorList("  ", rows);
    }

    /**
     * The remedy for one error, addressed to however many units share it.
     *
     * <p>Single definition on purpose: this table was duplicated verbatim in
     * {@code ConsoleProgramRenderer}, and two spellings of one remedy is how
     * they come to disagree.
     *
     * <p>A per-unit remedy ({@code sync <name>}, a store directory) is only
     * printed when the block really is one unit. For a shared cause the whole
     * point is that naming one of the ten is misleading.
     */
    public static String hint(InstalledUnit.ErrorKind kind, List<String> units, SkillStore store) {
        boolean one = units != null && units.size() == 1;
        String name = one ? units.get(0) : null;
        String target = one ? " " + name : "";
        Path storeDir = one && store != null ? store.skillDir(name) : null;
        return switch (kind) {
            case GATEWAY_UNAVAILABLE -> "start the gateway: skill-manager gateway up";
            case MCP_REGISTRATION_FAILED -> "retry: skill-manager sync" + target;
            case MERGE_CONFLICT -> mergeConflictRemedy(storeDir, name,
                    store != null ? store.root() : null, 0);
            case NO_GIT_REMOTE -> one
                    ? "set origin: cd " + storeDir + " && git remote add origin <url>"
                    : "set origin in each unit's store directory: git remote add origin <url>";
            case NEEDS_GIT_MIGRATION -> "file/local installs do not sync; reinstall from a git source: "
                    + (one ? "skill-manager uninstall " + name
                            + " && skill-manager install github:<owner>/<repo>"
                            : "skill-manager uninstall <name> && "
                                    + "skill-manager install github:<owner>/<repo>");
            case REGISTRY_UNAVAILABLE -> "ensure the registry is reachable, then re-run sync/upgrade "
                    + "(or use --git-latest to bypass the registry for git-tracked skills)";
            case AGENT_SYNC_FAILED -> "retry: skill-manager sync" + target
                    + " (will re-attempt the agent symlink)";
            case HARNESS_CLI_UNAVAILABLE -> "install the missing harness CLI, then re-run "
                    + "skill-manager sync" + target;
            case AUTHENTICATION_NEEDED -> "run `skill-manager login`, then re-run "
                    + "`skill-manager sync" + target + "`";
            case TRANSITIVE_RESOLVE_FAILED -> "fix the failing transitive (see message) "
                    + "and re-run: skill-manager sync" + target;
            // Deliberately NOT a per-unit remedy even for a block of one: the
            // failure belongs to the project, and the sync that clears it is
            // the one that realizes the project again.
            case PROJECT_SYNC_FAILED -> "fix the named project sync failure, then re-run "
                    + "`skill-manager sync` — a project sync that succeeds clears this from every "
                    + "unit it claims";
        };
    }

    /**
     * The remedy for a {@code MERGE_CONFLICT}, chosen by looking at the store
     * rather than by assuming which conflict it was.
     *
     * <h2>Why this is not one sentence any more</h2>
     *
     * <p>It was, and the sentence was wrong for the state it was printed for.
     * MEASURED on the operator's project home, on two units, for nine days:
     * unmerged index stages at mode {@code 120000} and <b>no {@code MERGE_HEAD}</b>
     * — residue of a failed {@code git stash pop}, not a merge in progress. The
     * printed remedy was
     *
     * <pre>resolve in &lt;store&gt;, then `git add` + `git commit`</pre>
     *
     * <p>which is the remedy for a merge in progress. There was no merge to
     * commit; running it verbatim could not clear the state, and the state
     * re-armed on the next sync. HIS-4's acceptance is that the printed remedy
     * is one that actually clears the state it is printed for, so this asks
     * which state it is:
     *
     * <ul>
     *   <li><b>Mid-merge</b> ({@code MERGE_HEAD} present) — resolve, {@code git
     *       add}, {@code git commit}, or back out with {@code git merge --abort}.
     *       The original sentence, now printed only where it is true.</li>
     *   <li><b>Unmerged paths with no {@code MERGE_HEAD}</b> — stash-pop
     *       residue. {@code git reset} drops the phantom stages; the stash, if
     *       one is left, still holds the local work. This is the hand recovery
     *       the epic performed, made printable.</li>
     *   <li><b>Neither</b> — the condition has already cleared and the record
     *       has not caught up. It is retired by the reconcile pass on the very
     *       next command ({@code ReconcileUseCase} → {@code ValidateAndClearError}),
     *       so the remedy is to say so rather than to send anyone into a store
     *       directory to fix nothing.</li>
     * </ul>
     *
     * <p><b>The CLI is spelled with {@code HomeDescriptor.cliInvocation(homeRoot)},
     * not as a bare {@code skill-manager}</b>, and that is not cosmetic. DEF-002
     * recorded a remedy naming the ROOT home's CLI while the operator was
     * working in the project home, so following it verbatim silently operates on
     * a different home; #142 is the same family with a bare name. HIS-12 (#161)
     * owns that surface generally. This remedy is new text landing before that
     * ticket, so it uses the existing spelling helper rather than adding two more
     * bare invocations for HIS-12 to find.
     *
     * <p>Store-less (a shared block of several units, or no store handle) falls
     * back to the shape-agnostic sentence: naming one unit's git state for ten
     * units is the misleading-remedy problem in the other direction.
     */
    public static String mergeConflictRemedy(Path storeDir, String name, Path homeRoot) {
        return mergeConflictRemedy(storeDir, name, homeRoot, 0);
    }

    /**
     * @param conflictedCount how many files the operation reported as
     *        conflicting, read BEFORE any rollback. Zero means "not known here"
     *        — the outstanding-errors banner reads a persisted record and has no
     *        such set, so it passes zero and gets the store-derived answer.
     */
    public static String mergeConflictRemedy(Path storeDir, String name, Path homeRoot,
                                             int conflictedCount) {
        String cli = homeRoot != null
                ? dev.skillmanager.store.HomeDescriptor.cliInvocation(homeRoot)
                : "skill-manager";
        if (storeDir == null) {
            return "in each unit's store directory: `git status` says which — resolve + `git add` + "
                    + "`git commit` mid-merge, or `git reset` to drop a failed stash pop";
        }
        if (dev.skillmanager.source.GitOps.isMidMerge(storeDir)) {
            return "resolve in " + storeDir + ", then `git add` + `git commit` "
                    + "(or back out with `git merge --abort`)";
        }
        if (!dev.skillmanager.source.GitOps.unmergedFiles(storeDir).isEmpty()) {
            return "a failed stash pop, not a merge — no MERGE_HEAD, so `git commit` has nothing "
                    + "to do. Clear the stages: git -C " + storeDir + " reset"
                    + (dev.skillmanager.source.GitOps.hasStash(storeDir)
                            ? "  (local work is still at stash@{0})" : "")
                    + ", then `" + cli + " sync " + name + "`";
        }
        if (conflictedCount > 0) {
            // THE ROLLED-BACK CONFLICT, and it needs its own sentence because
            // the store looks pristine and is not. The merge was undone, so
            // there are no unmerged paths and no MERGE_HEAD to find -- and
            // saying "already clear" here told an operator whose sync had just
            // refused that nothing was wrong. Review finding HIGH-2.
            return "nothing was changed — the merge was rolled back, so the store is exactly "
                    + "where it was, and " + conflictedCount + " local file(s) conflict with "
                    + "upstream. Commit or drop the local work in " + storeDir
                    + " (`git status`), then `" + cli + " sync " + name + "`; or merge by hand "
                    + "there if both sides are wanted";
        }
        return "already clear in " + storeDir + " — the record has not caught up and the next "
                + "command retires it; `" + cli + " sync " + name + "` does so now";
    }
}
