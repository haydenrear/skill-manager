package dev.skillmanager.app;

import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared pre-flight emitter for commands that need a running gateway. Each
 * consuming use case ({@link InstallUseCase}, {@link SyncUseCase}, …)
 * prepends {@link #preflight} to its effect list so they all handle it the
 * same way: an optional {@link SkillEffect.EnsureGateway}.
 */
public final class ResolveContextUseCase {

    private ResolveContextUseCase() {}

    public static List<SkillEffect> preflight(GatewayConfig gw, boolean withGateway) {
        List<SkillEffect> out = new ArrayList<>();
        if (withGateway && gw != null) out.add(new SkillEffect.EnsureGateway(gw));
        return out;
    }
}
