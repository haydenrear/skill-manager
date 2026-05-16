package dev.skillmanager.effects;

import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.UnitReadProblem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Converts installed-unit read diagnostics into renderer-owned facts. */
public final class UnitReadProblemReporter {

    private UnitReadProblemReporter() {}

    public static List<ContextFact> facts(List<UnitReadProblem> problems) {
        List<ContextFact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (problems == null) return facts;
        for (UnitReadProblem p : problems) {
            if (!seen.add(key(p))) continue;
            facts.add(fact(p));
        }
        return facts;
    }

    public static void render(SkillStore store, List<UnitReadProblem> problems, boolean json) {
        List<ContextFact> facts = facts(problems);
        if (facts.isEmpty()) return;
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, null, json);
        renderer.onReceipt(EffectReceipt.ok(
                new SkillEffect.ReportUnitReadProblems(problems),
                facts));
        renderer.onComplete();
    }

    private static ContextFact.CantReadUnit fact(UnitReadProblem p) {
        return new ContextFact.CantReadUnit(
                p.name(),
                p.kind() == null ? "" : p.kind().name().toLowerCase(),
                p.path() == null ? "" : p.path().toString(),
                p.message());
    }

    private static String key(UnitReadProblem p) {
        String kind = p.kind() == null ? "" : p.kind().name();
        String path = p.path() == null ? "" : p.path().toAbsolutePath().normalize().toString();
        return kind + "\u001f" + p.name() + "\u001f" + path + "\u001f" + p.message();
    }
}
