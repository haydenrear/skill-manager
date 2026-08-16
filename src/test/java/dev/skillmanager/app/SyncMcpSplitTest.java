package dev.skillmanager.app;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-21 (#123): {@code sync} stopped registering MCP servers, and the fix is
 * a SPLIT rather than a reverted default.
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@code 7fce8ed} made MCP work opt-in during {@code sync}, out of a real
 * defect: the gateway preflight was rolling back content syncs at project and
 * worktree homes. One flag then gated four things —
 * {@code EnsureGateway}, {@code SnapshotMcpDeps}, {@code RegisterMcp},
 * {@code UnregisterMcpOrphans} — and only the first of them could produce the
 * rollback. So {@code RegisterMcp} stopped firing, the
 * {@code ---MCP-INSTALL-RESULTS---} block two graph nodes grep for stopped
 * being printed, and nothing in {@code test_graph/} passed
 * {@code --include-mcp}. Confirmed twice on a hosted runner, and unnoticed for
 * three days because the graphs were not running.
 *
 * <h2>What these cases pin</h2>
 *
 * <p>The split, in both directions, plus the reconciliation the ticket asks
 * for. Reverting the default would have satisfied the graphs and reopened the
 * rollback; gating registration behind a flag the graphs pass would have left
 * every real user's {@code sync} without an MCP refresh. Neither is what is
 * asserted here.
 *
 * <p>Note what is NOT asserted: that registration SUCCEEDS without a gateway.
 * It does not — {@code LiveInterpreter.registerMcp} pings and returns
 * {@code skipped("gateway unreachable")}. That is the point. The effect is
 * PLANNED unconditionally and DECIDES for itself at run time, which is a
 * different thing from being planned only when someone passed a flag.
 */
public final class SyncMcpSplitTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SyncMcpSplitTest");

        suite.test("a default sync registers MCP servers and does NOT start a gateway", () -> {
            SkillStore store = seededHome("sync-mcp-default-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");

            // What SyncCommand builds with no flags.
            var program = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false,
                            /*withMcp=*/true, /*withAgents=*/true, /*yes=*/false,
                            /*forceScripts=*/false, /*startGateway=*/false),
                    List.of(), List.of());

            assertFalse(has(program.stage1(), SkillEffect.EnsureGateway.class),
                    "the preflight that builds a venv and starts a process — the one effect "
                            + "that can roll a content sync back — is not planned");
            assertTrue(has(program.stage1(), SkillEffect.SnapshotMcpDeps.class),
                    "the orphan snapshot is planned; it is a read of the installed units and "
                            + "never touches a gateway");

            Program<?> tail = program.stage2().apply(new EffectContext(store, gw));
            assertTrue(has(tail, SkillEffect.RegisterMcp.class),
                    "registration is planned again — this is the effect that stopped firing "
                            + "at 7fce8ed and took two graph nodes with it");
            assertTrue(has(tail, SkillEffect.UnregisterMcpOrphans.class),
                    "and so is orphan cleanup, which pings and skips exactly the same way");
        });

        suite.test("--include-mcp adds the gateway preflight and only that", () -> {
            SkillStore store = seededHome("sync-mcp-optin-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");

            var program = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false, true, true, false, false,
                            /*startGateway=*/true),
                    List.of(), List.of());

            assertTrue(has(program.stage1(), SkillEffect.EnsureGateway.class),
                    "asking for a gateway is what plans one");
            assertTrue(has(program.stage2().apply(new EffectContext(store, gw)),
                            SkillEffect.RegisterMcp.class),
                    "registration was already planned, so the flag adds nothing here");
        });

        suite.test("--skip-mcp turns off both halves, which is what it always said", () -> {
            SkillStore store = seededHome("sync-mcp-skip-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");

            // Between 7fce8ed and ARTI-21 this flag was documented as a
            // "compatibility no-op". A flag whose description says it does
            // nothing is a flag nobody can use to mean anything.
            var program = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false,
                            /*withMcp=*/false, true, false, false, /*startGateway=*/false),
                    List.of(), List.of());

            assertFalse(has(program.stage1(), SkillEffect.EnsureGateway.class),
                    "no gateway is started");
            Program<?> tail = program.stage2().apply(new EffectContext(store, gw));
            assertFalse(has(tail, SkillEffect.RegisterMcp.class),
                    "and nothing is registered");
            assertFalse(has(tail, SkillEffect.UnregisterMcpOrphans.class),
                    "nor unregistered");
        });

        suite.test("a claiming-project sync still does no gateway work unless asked", () -> {
            SkillStore store = seededHome("sync-mcp-project-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");

            // SyncClaimingProjects' gateway argument reaches a CHILD project's
            // own install program via ProjectDependencyResolver ->
            // InstallUseCase.buildProgramForStagedGraph, where ONE boolean
            // still gates both the EnsureGateway preflight and the MCP work.
            // Handing it `withMcp` would put an EnsureGateway back into a
            // project resolve — the exact rollback 7fce8ed fixed, at the exact
            // home tier it was reported at. It gets `startGateway`.
            var program = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false, /*withMcp=*/true, true, false,
                            false, /*startGateway=*/false),
                    List.of(new SyncUseCase.Target.Git("alpha")), List.of());

            SkillEffect.SyncClaimingProjects projects =
                    program.stage2().apply(new EffectContext(store, gw)).effects().stream()
                            .filter(SkillEffect.SyncClaimingProjects.class::isInstance)
                            .map(SkillEffect.SyncClaimingProjects.class::cast)
                            .findFirst().orElse(null);
            if (projects != null) {
                assertFalse(projects.withGateway(),
                        "registration at THIS home must not drag a gateway preflight into a "
                                + "child project's install");
                Tests.assertEquals(null, projects.gateway(),
                        "and it is handed no gateway to start one with");
            }
        });

        suite.test("upgrade and sync plan the same MCP work — they disagreed before", () -> {
            SkillStore store = seededHome("sync-mcp-upgrade-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");

            // UpgradeCommand's Options, verbatim, and SyncCommand's default.
            // Before this, upgrade hardcoded withMcp=true — which ALSO gated
            // the preflight, so `upgrade` at a worktree home still tried to
            // start a gateway and still rolled back when it could not. The
            // exact defect 7fce8ed fixed for sync and left standing here.
            var upgrade = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false, true, true, false, false, false),
                    List.of(), List.of());
            var sync = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false, true, true, false, false, false),
                    List.of(), List.of());

            for (Class<? extends SkillEffect> effect : List.of(
                    SkillEffect.EnsureGateway.class, SkillEffect.SnapshotMcpDeps.class)) {
                Tests.assertEquals(has(sync.stage1(), effect), has(upgrade.stage1(), effect),
                        "sync and upgrade agree about " + effect.getSimpleName());
            }
            Program<?> syncTail = sync.stage2().apply(new EffectContext(store, gw));
            Program<?> upgradeTail = upgrade.stage2().apply(new EffectContext(store, gw));
            for (Class<? extends SkillEffect> effect : List.of(
                    SkillEffect.RegisterMcp.class, SkillEffect.UnregisterMcpOrphans.class)) {
                Tests.assertEquals(has(syncTail, effect), has(upgradeTail, effect),
                        "sync and upgrade agree about " + effect.getSimpleName());
            }
            assertFalse(has(upgrade.stage1(), SkillEffect.EnsureGateway.class),
                    "and neither of them starts a gateway unasked");
        });

        return suite.runAll();
    }

    private static boolean has(Program<?> program, Class<? extends SkillEffect> kind) {
        return program.effects().stream().anyMatch(kind::isInstance);
    }

    /** A live home with one unit, which is all stage 2 needs to build a tail. */
    private static SkillStore seededHome(String prefix) throws Exception {
        Path root = Files.createTempDirectory(prefix);
        SkillStore store = new SkillStore(root);
        store.init();
        Path unit = root.resolve("skills/alpha");
        Files.createDirectories(unit);
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: alpha\ndescription: fixture\n---\nbody\n");
        Files.writeString(unit.resolve("skill-manager.toml"), """
                [skill]
                name = "alpha"
                version = "0.1.0"
                description = "alpha fixture"
                """);
        return store;
    }
}
