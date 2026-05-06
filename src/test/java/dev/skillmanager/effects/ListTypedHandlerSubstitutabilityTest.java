package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;

/**
 * Ticket-07 substitutability: list-typed effects (now {@code List<AgentUnit>})
 * carry the same dep view across {@link UnitKind}. The handlers don't need
 * to dispatch on kind for the per-unit dep iteration — the union'd dep set
 * on a {@link dev.skillmanager.model.PluginUnit} matches the unioned skill
 * carrier point-for-point.
 *
 * <p>This is a typing-level claim: assert the {@link AgentUnit} accessors
 * return the same shape on both kinds when fed the same {@link DepSpec}.
 * The full handler-execution path stays in {@link HandlerSubstitutabilityTest}
 * for ticket 06's leaf coverage; running the bulk handlers end-to-end
 * requires real installer / gateway / agent setup that's out of scope
 * until the InMemoryFs / FakeGateway fakes flesh out (tickets 08–11).
 */
public final class ListTypedHandlerSubstitutabilityTest {

    public static int run() throws Exception {
        Path tmp = Files.createTempDirectory("list-typed-handler-test-");
        Tests.Suite suite = Tests.suite("ListTypedHandlerSubstitutabilityTest");

        // Sweep: dep mix × batch size 1..3
        DepSpec[] mixes = {
                DepSpec.empty(),
                DepSpec.of().cli("pip:cowsay==6.0").build(),
                DepSpec.of().mcp("srv-a").build(),
                DepSpec.of().cli("pip:cowsay==6.0").mcp("srv-a").mcp("srv-b").build(),
        };

        int counter = 0;
        for (DepSpec deps : mixes) {
            for (int batchSize = 1; batchSize <= 3; batchSize++) {
                final int n = batchSize;
                final DepSpec d = deps;
                final int idx = counter++;
                String label = "batch=" + n + " deps={cli=" + d.cliSpecs.size()
                        + ",mcp=" + d.mcpServers.size() + "}";

                suite.test(label + " — cli/mcp accessors match across kinds", () -> {
                    List<AgentUnit> skills = batch(UnitKind.SKILL, tmp, "s" + idx, n, d);
                    List<AgentUnit> plugins = batch(UnitKind.PLUGIN, tmp, "p" + idx, n, d);

                    assertEquals(skills.size(), plugins.size(), "batch size");
                    int skillCli = 0, pluginCli = 0, skillMcp = 0, pluginMcp = 0;
                    for (AgentUnit u : skills) {
                        skillCli += u.cliDependencies().size();
                        skillMcp += u.mcpDependencies().size();
                    }
                    for (AgentUnit u : plugins) {
                        pluginCli += u.cliDependencies().size();
                        pluginMcp += u.mcpDependencies().size();
                    }
                    assertEquals(skillCli, pluginCli, "total cli dep count across kinds");
                    assertEquals(skillMcp, pluginMcp, "total mcp dep count across kinds");
                });
            }
        }

        return suite.runAll();
    }

    private static List<AgentUnit> batch(UnitKind kind, Path tmp, String prefix,
                                          int size, DepSpec deps) {
        java.util.ArrayList<AgentUnit> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Path root = tmp.resolve(prefix + "-" + i);
            out.add(UnitFixtures.buildEquivalent(kind, root, prefix + i, deps));
        }
        return out;
    }
}
