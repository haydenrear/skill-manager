package dev.skillmanager.plan;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.InstallPolicy;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.policy.PolicyGate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-12 contract #5: {@code --yes} can't bypass {@code !}-marked
 * categorization lines under default policy. Sweeps {@code (flag combo
 * × dep mix that triggers each flag × unit kind)}.
 *
 * <p>The gate is a pure function on (categorization lines, InstallPolicy);
 * the test exercises it directly via
 * {@link PolicyGate#violations}. The wiring into {@code InstallCommand}
 * is integration coverage (the {@code -y} flag → exit 5 path), out of
 * scope for unit tests.
 */
public final class PolicyGatingTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("PolicyGatingTest");
        Path tmp = Files.createTempDirectory("policy-gating-");

        // ---------------------------------------- per-flag, per-dep, per-kind sweep

        for (UnitKind kind : UnitKind.values()) {
            String label = kind.name().toLowerCase();

            // CLI dep + cli flag on → CLI_DEPS violation.
            suite.test(label + " — CLI dep with require_confirmation_for_cli_deps=true blocks", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-cli-on"),
                        DepSpec.of().cli("pip:cowsay==6.0").build());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        new InstallPolicy(false, false, true, false));
                assertEquals(1, v.size(), "one violation");
                assertEquals(PolicyGate.Category.CLI_DEPS, v.get(0), "CLI_DEPS surfaces");
            });

            suite.test(label + " — CLI dep with require_confirmation_for_cli_deps=false allows", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-cli-off"),
                        DepSpec.of().cli("pip:cowsay==6.0").build());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        new InstallPolicy(true, true, false, true));
                assertEquals(0, v.size(), "no violation when flag off");
            });

            // MCP dep (docker) — triggers ! MCP only (no hooks since docker isn't a hook).
            suite.test(label + " — MCP dep with require_confirmation_for_mcp=true blocks", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-mcp-on"),
                        DepSpec.of().mcp("srv-a").build());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        new InstallPolicy(false, true, false, false));
                assertEquals(1, v.size(), "one violation");
                assertEquals(PolicyGate.Category.MCP, v.get(0), "MCP surfaces");
            });

            suite.test(label + " — MCP dep with require_confirmation_for_mcp=false allows", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-mcp-off"),
                        DepSpec.of().mcp("srv-a").build());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        new InstallPolicy(true, false, true, true));
                assertEquals(0, v.size(), "no violation when flag off");
            });

            // No deps → never any violations regardless of flags.
            suite.test(label + " — no deps + all flags on → zero violations", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-empty"), DepSpec.empty());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        InstallPolicy.defaults());
                assertEquals(0, v.size(), "nothing to gate");
            });

            // Mixed: CLI + MCP, all flags on → both violations.
            suite.test(label + " — CLI + MCP with both flags on → two violations", () -> {
                List<String> cat = categorize(kind, tmp.resolve(label + "-mixed"),
                        DepSpec.of().cli("pip:foo==1.0").mcp("srv-a").build());
                List<PolicyGate.Category> v = PolicyGate.violations(cat,
                        InstallPolicy.defaults());
                assertEquals(2, v.size(), "both surface");
                assertTrue(v.contains(PolicyGate.Category.CLI_DEPS), "CLI present");
                assertTrue(v.contains(PolicyGate.Category.MCP), "MCP present");
            });
        }

        // ----------------------------------------------------- HOOKS triggers

        suite.test("MCP shell-load triggers HOOKS gate when flag on", () -> {
            // Categorization detects shell/binary-with-init MCP as hooks via PlanBuilder.
            // We synthesize the categorization line directly here since shell-load
            // construction has more parameters than the fixture exposes.
            List<String> cat = List.of("+ SKILLS  alpha", "! MCP", "! HOOKS");
            List<PolicyGate.Category> v = PolicyGate.violations(cat,
                    new InstallPolicy(true, false, false, false));
            assertEquals(1, v.size(), "HOOKS only");
            assertEquals(PolicyGate.Category.HOOKS, v.get(0), "HOOKS shape");
        });

        suite.test("HOOKS flag off allows even when MCP flag on but no hooks line", () -> {
            // Edge case: MCP line present but no HOOKS — only MCP gate triggers.
            List<String> cat = List.of("! MCP");
            List<PolicyGate.Category> v = PolicyGate.violations(cat,
                    new InstallPolicy(true, true, false, false));
            assertEquals(1, v.size(), "MCP only — HOOKS line not present");
            assertEquals(PolicyGate.Category.MCP, v.get(0), "MCP");
        });

        // ----------------------------------------------------- formatting

        suite.test("formatViolationMessage names each policy.install.* flag", () -> {
            String msg = PolicyGate.formatViolationMessage(List.of(
                    PolicyGate.Category.CLI_DEPS, PolicyGate.Category.MCP));
            assertContains(msg, "policy.install.require_confirmation_for_cli_deps", "CLI flag named");
            assertContains(msg, "policy.install.require_confirmation_for_mcp", "MCP flag named");
        });

        suite.test("empty violations → empty message", () -> {
            String msg = PolicyGate.formatViolationMessage(List.of());
            assertEquals("", msg, "no message for empty");
        });

        // ----------------------------------------------------- defaults round-trip

        suite.test("Policy.defaults() install matches InstallPolicy.defaults()", () -> {
            Policy p = Policy.defaults();
            InstallPolicy d = InstallPolicy.defaults();
            assertEquals(d.requireConfirmationForHooks(), p.install().requireConfirmationForHooks(), "hooks");
            assertEquals(d.requireConfirmationForMcp(), p.install().requireConfirmationForMcp(), "mcp");
            assertEquals(d.requireConfirmationForCliDeps(), p.install().requireConfirmationForCliDeps(), "cli");
            assertEquals(d.requireConfirmationForExecutableCommands(),
                    p.install().requireConfirmationForExecutableCommands(), "exec");
            // All defaults are conservative (every category requires confirmation).
            assertTrue(d.requireConfirmationForHooks() && d.requireConfirmationForMcp()
                    && d.requireConfirmationForCliDeps() && d.requireConfirmationForExecutableCommands(),
                    "every default flag is true");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    private static List<String> categorize(UnitKind kind, Path root, DepSpec deps) throws Exception {
        Files.createDirectories(root);
        AgentUnit u = UnitFixtures.buildEquivalent(kind, root, "alpha", deps);
        InstallPlan plan = new PlanBuilder(Policy.defaults())
                .plan(List.of(u), true, true, root.resolve("bin"));
        return PlanBuilder.categorize(List.of(u), plan);
    }
}
