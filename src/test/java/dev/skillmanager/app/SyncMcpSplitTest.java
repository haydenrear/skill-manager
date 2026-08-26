package dev.skillmanager.app;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.LiveInterpreter;
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

        // ---------------------------------------------------- review, F1
        //
        // Measured A/B on one machine, one fixture, a LIVE gateway holding one
        // server that fails `tools/list`:
        //
        //     base epic/artifact-dag : exit 0
        //     MCP on by default      : exit 1     <-- regression
        //
        // The first version of this ticket claimed no exit-code change because
        // a SKIPPED receipt is neither FAILED nor PARTIAL. True only for the
        // gateway-ABSENT case: with a gateway PRESENT and a server failing to
        // deploy the receipt is PARTIAL, and PARTIAL was counted. It was also
        // what turned `smoke`'s sync_noop_exit_zero and sync_force_exit_zero
        // red — two assertions that had been passing.
        suite.test("a server that fails to deploy does not change what sync EXITS", () -> {
            SkillStore store = seededHome("sync-mcp-exit-");
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:59999");
            var decoder = SyncUseCase.buildProgram(store, gw,
                    new SyncUseCase.Options(null, false, false, true, true, false, false, false),
                    List.of(), List.of()).decoder();

            SkillEffect register = new SkillEffect.RegisterMcp(List.of(), gw);
            SyncUseCase.Report partial = decoder.decode(List.of(
                    EffectReceipt.partial(register, "1 unit(s) had MCP errors")));
            Tests.assertEquals(0, partial.errorCount(),
                    "a partly-failed registration is recorded per unit and reported; it is "
                            + "not a verdict on the content sync");
            Tests.assertEquals(0, partial.worstRc(), "and it does not raise the exit code");

            // The neighbours still count, so this is a targeted exemption and
            // not a hole: a content target that partly failed is still an error.
            SyncUseCase.Report agents = decoder.decode(List.of(
                    EffectReceipt.partial(new SkillEffect.SyncAgents(List.of(), gw),
                            "agent sync failures")));
            Tests.assertEquals(1, agents.errorCount(),
                    "SyncAgents PARTIAL still counts — only the gateway half was exempted");
        });

        // ---------------------------------------------------- review, F3
        //
        // registerMcp runs in STAGE 2, AFTER CommitUnitsToStore, and was
        // declared `throws IOException` with ctx.addError and registerAll
        // unguarded. Executor sets failed=true on FAILED status ALONE and then
        // walks the journal, where CommitUnitsToStore left one DeleteUnitDir
        // per committed unit. Rollback is decoupled from halt — reasoning about
        // continuations was the wrong frame. So a gateway hiccup on the DEFAULT
        // sync path could delete the units that same sync had just committed.
        suite.test("an unreachable gateway leaves a SKIPPED receipt, never a FAILED one", () -> {
            SkillStore store = seededHome("sync-mcp-total-");
            // Port 1 is privileged and unbound, so the ping cannot succeed —
            // which drives the exact branch whose ctx.addError was unguarded.
            GatewayConfig gw = GatewayConfig.resolve(store, "http://127.0.0.1:1");

            EffectReceipt receipt = new LiveInterpreter(store, gw).runOne(
                    new SkillEffect.RegisterMcp(List.of(), gw), new EffectContext(store, gw));

            Tests.assertEquals(EffectStatus.SKIPPED, receipt.status(),
                    "the handler decided for itself and reported a state, not a failure");
            assertFalse(receipt.status() == EffectStatus.FAILED,
                    "and a FAILED receipt here would walk CommitUnitsToStore's "
                            + "DeleteUnitDir compensations over a healthy store");
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
