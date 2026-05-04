package dev.skillmanager.app;

import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared pre-flight emitter for commands that need a configured registry
 * and/or running gateway. Each consuming use case ({@link InstallUseCase},
 * {@link SyncUseCase}, …) prepends {@link #preflight} to its effect list
 * so all three handle the same way: a {@link SkillEffect.ConfigureRegistry}
 * (when an override is supplied) and an optional
 * {@link SkillEffect.EnsureGateway}.
 */
public final class ResolveContextUseCase {

    private ResolveContextUseCase() {}

    public static List<SkillEffect> preflight(GatewayConfig gw, String registryOverride, boolean withGateway) {
        List<SkillEffect> out = new ArrayList<>();
        if (registryOverride != null && !registryOverride.isBlank()) {
            out.add(new SkillEffect.ConfigureRegistry(registryOverride));
        }
        if (withGateway && gw != null) out.add(new SkillEffect.EnsureGateway(gw));
        return out;
    }
}
