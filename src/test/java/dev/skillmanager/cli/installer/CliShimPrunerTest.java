package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The two {@code bin/cli} states a home could not previously get out of, and
 * everything the prune that clears them must NOT touch.
 *
 * <h2>The gap</h2>
 *
 * <p>A shim no installed unit declares has nothing to re-install over it, so
 * {@code sync} walked past it, {@code home verify} refused on it forever, and
 * the remedy it printed exited without changing anything — the #142 class. The
 * {@code onboarding} graph plants one ({@code bin/cli/ob-dangling}); on real
 * homes it arises whenever a unit is uninstalled or a tool is renamed.
 *
 * <p>Its sibling is a shim resolving into ANOTHER home: {@code CliPresence}
 * called that provisioned — it resolves and it is executable — so the install
 * pass skipped it and the isolation refusal stood with no way out.
 *
 * <h2>Why most of these cases are about what survives</h2>
 *
 * <p>A prune inside {@code sync} is the kind of change that quietly deletes a
 * working toolchain. Each survivor below is a real shape a home holds: npm and
 * brew link every executable in a package prefix, so undeclared-and-working is
 * normal; the CLI pin is undeclared by design; and a child home's mirror of its
 * parent's shim is sanctioned.
 */
public final class CliShimPrunerTest {

    public static int run() throws Exception {
        return Tests.suite("CliShimPrunerTest")

                .test("an undeclared BROKEN shim is pruned — the state nothing could clear", () -> {
                    TestHarness h = TestHarness.create();
                    Path orphan = danglingShim(h.store(), "ob-dangling", "../../venvs/gone/bin/x");

                    List<CliShimPruner.Pruned> pruned = CliShimPruner.prune(h.store());

                    assertFalse(Files.exists(orphan, LinkOption.NOFOLLOW_LINKS),
                            "the orphan shim is gone");
                    assertEquals(1, pruned.size(), "and it is reported: " + pruned);
                    assertTrue(pruned.get(0).reason().contains("no installed unit declares it"),
                            "with the reason verify would have refused on; got: " + pruned);
                })

                .test("an undeclared shim that WORKS is left alone", () -> {
                    TestHarness h = TestHarness.create();
                    // npm and brew link every executable in a package prefix,
                    // so a working entry no manifest names is the normal case,
                    // not garbage.
                    Path working = executable(h.store().cliBinDir().resolve("prefix-extra"));

                    CliShimPruner.prune(h.store());

                    assertTrue(Files.exists(working), "a working tool is this home's tool");
                })

                .test("a non-executable file beside the shims is not this prune's business", () -> {
                    TestHarness h = TestHarness.create();
                    // Measured: a skill-script install script keeps its run
                    // counter at bin/cli/<tool>.count, 644 and undeclared.
                    // Pruning on "not usable" deleted it and reset the counter
                    // CommandKindCoverageTest asserts on. `home verify` does
                    // not refuse it, so neither does this.
                    Fs.ensureDir(h.store().cliBinDir());
                    Path counter = h.store().cliBinDir().resolve("some-tool.count");
                    Files.writeString(counter, "1\n");

                    CliShimPruner.prune(h.store());

                    assertTrue(Files.exists(counter),
                            "a file that was never an executable shim is not shim garbage");
                })

                .test("a DECLARED broken shim is left to the install pass", () -> {
                    TestHarness h = TestHarness.create();
                    installSkill(h, "alpha", DepSpec.of().cli("pip:kept-tool==1.0").build());
                    h.seedUnit("alpha", UnitKind.SKILL);
                    Path declared = danglingShim(h.store(), "kept-tool", "../../venvs/kept/bin/x");

                    CliShimPruner.prune(h.store());

                    assertTrue(Files.exists(declared, LinkOption.NOFOLLOW_LINKS),
                            "CliPresence already treats it as absent and reinstalls it; "
                                    + "deleting it first only widens the blast radius");
                })

                .test("the home's own CLI pin is never pruned, however broken", () -> {
                    TestHarness h = TestHarness.create();
                    Path pin = danglingShim(h.store(), "skill-manager", "/nowhere/skill-manager");

                    CliShimPruner.prune(h.store());

                    assertTrue(Files.exists(pin, LinkOption.NOFOLLOW_LINKS),
                            "a stale pin failing loudly is LauncherShims' stated tradeoff, and "
                                    + "`home shims` — not sync — is what repairs it");
                })

                .test("a shim resolving into another home is pruned even though it works", () -> {
                    TestHarness h = TestHarness.create();
                    Path foreign = Files.createTempDirectory("foreign-home-");
                    new SkillStore(foreign).init();
                    executable(Files.createDirectories(foreign.resolve("bin/cli"))
                            .resolve("leaky"));
                    Fs.ensureDir(h.store().cliBinDir());
                    Path link = h.store().cliBinDir().resolve("leaky");
                    Files.createSymbolicLink(link, foreign.resolve("bin/cli/leaky"));

                    List<CliShimPruner.Pruned> pruned = CliShimPruner.prune(h.store());

                    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS),
                            "it resolves and it is executable, which is exactly why the "
                                    + "install pass used to skip it forever");
                    assertEquals(1, pruned.size(), "and it is reported: " + pruned);
                    assertTrue(pruned.get(0).reason().contains("not this home's parent store"),
                            "with the reason; got: " + pruned);
                })

                .test("a child home's sanctioned mirror of its parent's shim survives", () -> {
                    TestHarness h = TestHarness.create();
                    Path parent = Files.createTempDirectory("parent-home-");
                    new SkillStore(parent).init();
                    executable(Files.createDirectories(parent.resolve("bin/cli"))
                            .resolve("shared"));
                    Fs.ensureDir(h.store().cliBinDir());
                    Path mirror = h.store().cliBinDir().resolve("shared");
                    Files.createSymbolicLink(mirror, parent.resolve("bin/cli/shared"));
                    // The child's own record of where its units came from —
                    // the evidence that outlives `harness rm`.
                    Path records = Files.createDirectories(
                            h.store().root().resolve(".materialization/skill"));
                    Files.writeString(records.resolve("demo.json"), """
                            {
                              "schemaVersion" : 2,
                              "unitName" : "demo",
                              "unitKind" : "SKILL",
                              "mode" : "COPY",
                              "source" : "%s/skills/demo",
                              "materializedAt" : "2026-01-01T00:00:00Z",
                              "reconcileKind" : "copy"
                            }
                            """.formatted(parent));

                    List<CliShimPruner.Pruned> pruned = CliShimPruner.prune(h.store());

                    assertTrue(Files.exists(mirror, LinkOption.NOFOLLOW_LINKS),
                            "sharing the parent's provisioned tools is what a child home is "
                                    + "for; pruning it would tear down the mechanism the "
                                    + "isolation rule was narrowed for");
                    assertEquals(0, pruned.size(), "and nothing is reported: " + pruned);
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    private static Path danglingShim(SkillStore store, String name, String target) throws Exception {
        Fs.ensureDir(store.cliBinDir());
        Path link = store.cliBinDir().resolve(name);
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of(target));
        return link;
    }

    private static Path executable(Path file) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file, "#!/usr/bin/env sh\nexit 0\n");
        file.toFile().setExecutable(true, false);
        return file;
    }

    private static void installSkill(TestHarness h, String name, DepSpec deps) throws Exception {
        Path tmp = Files.createTempDirectory("cli-prune-skill-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, deps);
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().skillDir(name));
    }
}
