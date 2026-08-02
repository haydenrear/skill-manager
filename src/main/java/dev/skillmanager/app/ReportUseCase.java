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
        System.err.println();
        System.err.println("⚠ skills with outstanding errors (" + bySkill.size() + ") — "
                + causes.size() + " distinct cause(s) — re-run after fixing:");
        for (var block : blocks.entrySet()) {
            List<String> units = block.getValue();
            System.err.println();
            System.err.println("  " + String.join(", ", units) + ":");
            for (var err : block.getKey().entrySet()) {
                System.err.println("    - " + err.getKey() + ": " + err.getValue());
                System.err.println("      → " + hint(err.getKey(), units, store));
            }
        }
        System.err.println();
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
            case MERGE_CONFLICT -> one
                    ? "resolve in " + storeDir + ", then `git add` + `git commit`"
                    : "resolve in each unit's store directory, then `git add` + `git commit`";
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
}
