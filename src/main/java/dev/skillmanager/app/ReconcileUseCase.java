package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the program that runs at the start of every command. Two jobs:
 *
 * <ol>
 *   <li>Onboard skills with no {@code sources/<name>.json} record yet via
 *       {@link SkillEffect.OnboardSource} effects.</li>
 *   <li>Re-validate every existing error via
 *       {@link SkillEffect.ValidateAndClearError} effects so transient errors
 *       (gateway flap, conflict resolved by hand) don't stick.</li>
 * </ol>
 *
 * <p>Failure-tolerant by construction: every effect produces a receipt even
 * if its handler throws — the report just records {@code errorCount}.
 */
public final class ReconcileUseCase {

    private ReconcileUseCase() {}

    public record Report(int onboarded, int cleared, int errorCount) {
        public static Report empty() { return new Report(0, 0, 0); }
    }

    public static Program<Report> buildProgram(SkillStore store) throws IOException {
        SkillSourceStore sources = new SkillSourceStore(store);
        List<Skill> installed = store.listInstalled();
        List<SkillEffect> effects = new ArrayList<>();
        for (Skill s : installed) {
            if (sources.read(s.name()).isEmpty()) {
                effects.add(new SkillEffect.OnboardSource(s));
            }
        }
        for (Skill s : installed) {
            sources.read(s.name()).ifPresent(src -> {
                if (!src.hasErrors()) return;
                for (SkillSource.SkillError err : List.copyOf(src.errors())) {
                    effects.add(new SkillEffect.ValidateAndClearError(s.name(), err.kind()));
                }
            });
        }
        return new Program<>("reconcile-" + UUID.randomUUID(), effects, ReconcileUseCase::decode);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int onboarded = 0, cleared = 0, errorCount = 0;
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED) {
                errorCount++;
                continue;
            }
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SkillOnboarded ignored -> onboarded++;
                    case ContextFact.ErrorValidated ev -> { if (ev.cleared()) cleared++; }
                    default -> {}
                }
            }
        }
        return new Report(onboarded, cleared, errorCount);
    }
}
