package dev.skillmanager.effects;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What actually happened when an interpreter ran an effect. Receipts are
 * the substrate for compensation planning + the operation journal — every
 * decoder reads them to build its report.
 */
public record EffectReceipt(
        SkillEffect effect,
        EffectStatus status,
        Map<String, Object> facts,
        String errorMessage,
        Instant at
) {
    public EffectReceipt {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public static EffectReceipt ok(SkillEffect effect, Map<String, Object> facts) {
        return new EffectReceipt(effect, EffectStatus.OK, facts, null, Instant.now());
    }

    public static EffectReceipt skipped(SkillEffect effect, String reason) {
        return new EffectReceipt(effect, EffectStatus.SKIPPED,
                Map.of("reason", reason), null, Instant.now());
    }

    public static EffectReceipt partial(SkillEffect effect, Map<String, Object> facts, String summary) {
        Map<String, Object> merged = new LinkedHashMap<>(facts == null ? Map.of() : facts);
        merged.put("summary", summary);
        return new EffectReceipt(effect, EffectStatus.PARTIAL, merged, null, Instant.now());
    }

    public static EffectReceipt failed(SkillEffect effect, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, Map.of(), message, Instant.now());
    }
}
