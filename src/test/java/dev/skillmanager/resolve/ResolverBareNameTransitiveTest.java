package dev.skillmanager.resolve;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #434 F3: since the registry went (#427) a bare-name reference has nowhere to
 * resolve, so the only meaning it can have is "this unit, already installed".
 * The sync-side walk has always read it that way; the install walk fetched it
 * and halted the install over a dependency that was sitting in the store.
 */
public final class ResolverBareNameTransitiveTest {

    public static int run() {
        return Tests.suite("ResolverBareNameTransitiveTest")
                .test("a bare-name reference to an INSTALLED unit is satisfied", () -> {
                    Path tmp = Files.createTempDirectory("resolver-bare-installed-");
                    SkillStore store = store(tmp);
                    UnitFixtures.scaffoldSkill(store.skillsDir(), "dep", DepSpec.empty());
                    Path parent = parent(tmp, "dep");

                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(
                            List.of(new Resolver.Coord(parent.toString(), null)));
                    try {
                        assertSize(0, outcome.failures(), "the installed dependency satisfies the reference");
                        assertTrue(outcome.graph().contains("parent"), "parent resolves");
                        assertFalse(outcome.graph().contains("dep"), "the installed unit is not re-staged");
                    } finally {
                        outcome.graph().cleanup();
                    }
                })
                .test("a bare-name reference to an ABSENT unit still fails with the pointer", () -> {
                    Path tmp = Files.createTempDirectory("resolver-bare-absent-");
                    SkillStore store = store(tmp);
                    Path parent = parent(tmp, "dep");

                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(
                            List.of(new Resolver.Coord(parent.toString(), null)));
                    try {
                        assertSize(1, outcome.failures(), "nothing can supply it");
                        assertEquals("parent", outcome.failures().get(0).requestedBy(), "attributed");
                        assertContains(outcome.failures().get(0).reason(),
                                "bare unit names are not supported", "the existing pointer");
                    } finally {
                        outcome.graph().cleanup();
                    }
                })
                .test("an alias for the name still wins over the installed unit", () -> {
                    Path tmp = Files.createTempDirectory("resolver-bare-alias-");
                    SkillStore store = store(tmp);
                    UnitFixtures.scaffoldSkill(store.skillsDir(), "dep", DepSpec.empty());
                    Path aliased = UnitFixtures.scaffoldSkill(
                            tmp.resolve("bundle"), "dep", DepSpec.empty()).sourcePath();
                    Path parent = parent(tmp, "dep");

                    Resolver.ResolveOutcome outcome = new Resolver(store).resolveAll(
                            List.of(new Resolver.Coord(parent.toString(), null)),
                            Map.of("dep", new Resolver.Coord(aliased.toString(), null)));
                    try {
                        assertSize(0, outcome.failures(), "the alias resolves");
                        assertTrue(outcome.graph().contains("dep"), "the aliased source is staged");
                    } finally {
                        outcome.graph().cleanup();
                    }
                })
                .runAll();
    }

    private static SkillStore store(Path tmp) throws Exception {
        SkillStore store = new SkillStore(tmp.resolve("store"));
        store.init();
        return store;
    }

    private static Path parent(Path tmp, String reference) throws Exception {
        return UnitFixtures.scaffoldSkill(tmp.resolve("units"), "parent",
                DepSpec.of().ref(reference).build()).sourcePath();
    }
}
