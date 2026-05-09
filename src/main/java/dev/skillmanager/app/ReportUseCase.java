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
        if (report.isEmpty()) return;
        System.err.println();
        System.err.println("⚠ skills with outstanding errors (" + report.errorsBySkill.size()
                + ") — re-run after fixing:");
        for (var entry : report.errorsBySkill.entrySet()) {
            String skillName = entry.getKey();
            Path dir = store.skillDir(skillName);
            System.err.println();
            System.err.println("  " + skillName + ":");
            // dedupe error kinds (handlers may emit the same kind twice across receipts)
            LinkedHashSet<InstalledUnit.ErrorKind> seen = new LinkedHashSet<>();
            for (InstalledUnit.UnitError err : entry.getValue()) {
                if (!seen.add(err.kind())) continue;
                System.err.println("    - " + err.kind() + ": " + err.message());
                System.err.println("      → " + hint(err.kind(), skillName, dir));
            }
        }
        System.err.println();
    }

    private static String hint(InstalledUnit.ErrorKind kind, String skillName, Path storeDir) {
        return switch (kind) {
            case GATEWAY_UNAVAILABLE -> "start the gateway: skill-manager gateway up";
            case MCP_REGISTRATION_FAILED -> "retry: skill-manager sync " + skillName;
            case MERGE_CONFLICT -> "resolve in " + storeDir + ", then `git add` + `git commit`";
            case NO_GIT_REMOTE -> "set origin: cd " + storeDir + " && git remote add origin <url>";
            case NEEDS_GIT_MIGRATION -> "reinstall from a git source: skill-manager uninstall "
                    + skillName + " && skill-manager install github:<owner>/<repo>";
            case REGISTRY_UNAVAILABLE -> "ensure the registry is reachable, then re-run sync/upgrade "
                    + "(or use --git-latest to bypass the registry for git-tracked skills)";
            case AGENT_SYNC_FAILED -> "retry: skill-manager sync " + skillName
                    + " (will re-attempt the agent symlink)";
            case HARNESS_CLI_UNAVAILABLE -> "install the missing harness CLI, then re-run "
                    + "skill-manager sync " + skillName;
            case AUTHENTICATION_NEEDED -> "run `skill-manager login`, then re-run "
                    + "`skill-manager sync " + skillName + "`";
        };
    }
}
