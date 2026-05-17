package dev.skillmanager.resolve;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ResolverBundledAliasTest {

    public static int run() throws Exception {
        return Tests.suite("ResolverBundledAliasTest")
                .test("aliases bundled skill-manager refs before registry lookup", () -> {
                    Path tmp = Files.createTempDirectory("resolver-bundled-alias-");
                    SkillStore store = new SkillStore(tmp.resolve("store"));
                    store.init();

                    Path repos = tmp.resolve("repos");
                    Path manager = UnitFixtures.scaffoldSkill(
                            repos, "skill-manager", DepSpec.empty()).sourcePath();
                    Path publisher = UnitFixtures.scaffoldSkill(
                            repos, "skill-publisher",
                            DepSpec.of().ref("skill:skill-manager").build()).sourcePath();

                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(
                            List.of(new Resolver.Coord(publisher.toString(), null)),
                            Map.of(
                                    "skill-manager", new Resolver.Coord(manager.toString(), null),
                                    "skill:skill-manager", new Resolver.Coord(manager.toString(), null)
                            ));

                    assertSize(0, outcome.failures(), "no registry-backed resolve failures");
                    assertEquals(2, outcome.graph().resolved().size(), "publisher and manager resolved");
                    assertTrue(outcome.graph().contains("skill-publisher"), "publisher resolved");
                    assertTrue(outcome.graph().contains("skill-manager"), "manager alias resolved");
                })
                .runAll();
    }
}
