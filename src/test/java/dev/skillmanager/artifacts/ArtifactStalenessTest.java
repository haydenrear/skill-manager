package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.installer.InstallerRegistry;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-05: "unit X moved — what is now stale?", and the rule that a probe which
 * did not finish is not a clean verdict.
 *
 * <p>The headline case is the ticket's declared expected effect, run as a test
 * rather than only as a demonstration: <b>editing a unit's
 * {@code skill-scripts/} tree makes {@code artifacts stale} name that unit's
 * shim AND the {@code cache/skill-script-*} tree it writes, with no install in
 * between.</b> Both halves matter — the shim is decided by its own recorded
 * fingerprint moving, the tree by being the same install's other output — and a
 * version of this that named only the shim would be the graph missing exactly
 * the class the ticket exists to bring into it.
 */
public final class ArtifactStalenessTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ArtifactStalenessTest");

        suite.test("an untouched home reports its fingerprinted shim as current", () -> {
            SkillStore store = fingerprinted();
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);

            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, shim.freshness(),
                    "nothing moved: " + shim.reason());
            var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, tree.freshness(),
                    "and neither did the tree that install wrote: " + tree.reason());
        });

        suite.test("editing skill-scripts/ makes the shim AND its tree stale, with no install",
                () -> {
                    SkillStore store = fingerprinted();
                    // One byte, in the tree the fingerprint is over. No install,
                    // no sync, no touching of bin/cli or cache/.
                    Path script = store.root().resolve("skills/alpha/skill-scripts/install.sh");
                    Files.writeString(script, Files.readString(script) + "\n# edited\n");

                    ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store),
                            store);

                    var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
                    assertEquals(ArtifactFreshness.Freshness.STALE, shim.freshness(),
                            "the shim's declared inputs moved: " + shim.reason());
                    assertContains(shim.reason(), "skill-scripts/",
                            "and the reason names what moved");

                    var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
                    assertEquals(ArtifactFreshness.Freshness.STALE, tree.freshness(),
                            "so did the tree that same install wrote: " + tree.reason());

                    // And every output is still exactly where it was — which is
                    // the point: no presence check in the system moved.
                    assertTrue(Files.exists(store.root().resolve("bin/cli/alpha-script")),
                            "the shim file is untouched");
                    assertTrue(Files.isDirectory(store.root().resolve("cache/skill-script-alpha-alpha-script")),
                            "and so is the tree");
                });

        suite.test("a shim whose declaring unit is gone is unverifiable, never current", () -> {
            SkillStore store = fingerprinted();
            // The unit's manifest is what declares the inputs. Without it there
            // is nothing to re-read, and "I did not look" is the only true
            // answer — the rule `skt check` learned the hard way.
            Files.delete(store.root().resolve("skills/alpha/skill-manager.toml"));

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, shim.freshness(),
                    "an input that cannot be read is not a passing one: " + shim.reason());
            assertContains(shim.reason(), "no longer see",
                    "and it says which side of the comparison is missing");
        });

        suite.test("a row with no recorded fingerprint is unverifiable, not current", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The fixture's pip shim IS the dangler — a link into a tree this
            // home does not hold — and since ARTI-06 that alone decides it
            // (stale). Materialise its target first, so this case measures the
            // axis it is named for: the row carries no install_fingerprint,
            // which is the 0-of-16 state the epic found on the real home.
            Path target = store.root().resolve("cache/uv-tools/alpha/bin/dangler");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "#!/bin/sh\necho ok\n");
            target.toFile().setExecutable(true);

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("pip", "alpha-pkg"));
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE, shim.freshness(),
                    "nothing to compare against: " + shim.reason());
        });

        suite.test("ARTI-06: a shim on disk whose output is hidden stops being current", () -> {
            // THE case the epic exists for, and the one `stale` reported as
            // CURRENT through ARTI-05. Measured then, on a real home:
            //   $ mv bin/cli/skt bin/cli/.hidden
            //   freshness = CURRENT "its inputs still hash to the fingerprint
            //                        recorded at install"
            SkillStore store = fingerprinted();
            var before = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.CURRENT, before.freshness(),
                    "precondition: it is current while its output is there");

            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            var after = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, after.freshness(),
                    "a declared artifact with nothing at its path is not current: "
                            + after.reason());
            assertContains(after.reason(), "bin/cli/alpha-script",
                    "and the reason names the output that is not there");
            assertEquals(Artifact.Materialization.DECLARED_ONLY, after.materialization(),
                    "the verdict carries the axis that decided it");
        });

        suite.test("ARTI-06: presence may DEMOTE a verdict and may never promote one", () -> {
            // The companion that keeps the rule above from BEING the presence
            // proxy this epic removes. A present output earns nothing: an
            // artifact whose inputs moved is still stale with its file in place.
            SkillStore store = fingerprinted();
            Files.writeString(store.root().resolve("skills/alpha/skill-scripts/install.sh"),
                    "# replaced\n");

            var moved = ArtifactFreshness.of(ArtifactIndex.of(store), store)
                    .of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, moved.freshness(),
                    "a present file does not rescue moved inputs: " + moved.reason());
            assertEquals(Artifact.Materialization.MATERIALIZED, moved.materialization(),
                    "and the artifact IS on disk, so presence contributed nothing here");
            assertContains(moved.reason(), "skill-scripts/",
                    "the reason is still the input comparison, not the file");
        });

        suite.test("ARTI-06: stale --json carries the axis that decided each row", () -> {
            SkillStore store = fingerprinted();
            Files.move(store.root().resolve("bin/cli/alpha-script"),
                    store.root().resolve("bin/cli/.hidden"));

            StaleReport report = StaleReport.of(store.root().toString(),
                    ArtifactFreshness.of(ArtifactIndex.of(store), store));

            StaleReport.VerdictView row = report.stale().stream()
                    .filter(v -> v.id().equals(ArtifactIds.cliShim("skill-script", "alpha-script")))
                    .findFirst().orElseThrow();
            assertEquals("declared-only", row.materialization(),
                    "a consumer of stale --json alone can now tell the two apart");
            assertTrue(StaleReport.SCHEMA >= 2, "and the schema says the field is there");
        });

        suite.test("staleness propagates downstream and names what caused it", () -> {
            SkillStore store = fingerprinted();
            Path script = store.root().resolve("skills/alpha/skill-scripts/install.sh");
            Files.writeString(script, "# replaced\n");

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var tree = freshness.of(ArtifactIds.provisionedTree("cache", "skill-script-alpha-alpha-script"));
            assertEquals(ArtifactFreshness.Freshness.STALE, tree.freshness(), "stale");

            StaleReport report = StaleReport.of(store.root().toString(), freshness);
            assertTrue(report.summary().stale() >= 2,
                    "the shim and the tree are both named: " + report.summary().stale());
            assertTrue(report.stale().stream()
                            .anyMatch(v -> v.kind().equals(ArtifactKind.PROVISIONED_TREE.id())),
                    "a provisioned tree is in the stale list, which is new in ARTI-05");
        });

        suite.test("a tree named only through a BROKEN link is credited to nobody", () -> {
            SkillStore store = fingerprinted();
            ArtifactIndex index = ArtifactIndex.of(store);

            // bin/cli/dangler -> cache/uv-tools/alpha/bin/dangler, which is not
            // there. It is the ONLY shim naming cache/uv-tools, so a rule that
            // asked no more than "exactly one claimant" would hand a shared uv
            // root the pip row's fingerprint — and a `stale` verdict would then
            // name a unit that did not write the tree.
            Artifact shared = index.byId(ArtifactIds.provisionedTree("cache", "uv-tools"))
                    .orElseThrow();
            assertTrue(shared.owner() == null,
                    "a broken link is no evidence about who wrote a directory, but it named "
                            + shared.owner());
            assertEquals(List.of(), shared.inputs(), "so the tree declares nothing");
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE,
                    ArtifactFreshness.of(index, store)
                            .of(ArtifactIds.provisionedTree("cache", "uv-tools")).freshness(),
                    "and is reported undecided rather than guessed at");
        });

        suite.test("--kind filters the counts too, so a report cannot contradict itself", () -> {
            SkillStore store = fingerprinted();
            Files.writeString(store.root().resolve("skills/alpha/skill-scripts/install.sh"),
                    "# replaced\n");
            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);

            StaleReport all = StaleReport.of(store.root().toString(), freshness, null);
            StaleReport trees = StaleReport.of(store.root().toString(), freshness,
                    ArtifactKind.PROVISIONED_TREE);

            assertTrue(all.summary().stale() > trees.summary().stale(),
                    "the filter actually narrows the set");
            assertEquals(trees.stale().size(), trees.summary().stale(),
                    "and the count describes the rows beside it, not the whole home");
            assertTrue(trees.summary().artifacts() < all.summary().artifacts(),
                    "denominator included");
            for (StaleReport.VerdictView view : trees.stale()) {
                assertEquals(ArtifactKind.PROVISIONED_TREE.id(), view.kind(),
                        "every row is of the requested kind");
            }
        });

        suite.test("a missing input makes a verdict unverifiable rather than current", () -> {
            SkillStore store = fingerprinted();
            // The tree the shim execs into is gone; the shim itself is
            // untouched and its fingerprint still matches. "Its inputs agree"
            // is true and "it is current" is not.
            deleteRecursive(store.root().resolve("cache/skill-script-alpha-alpha-script"));

            ArtifactFreshness freshness = ArtifactFreshness.of(ArtifactIndex.of(store), store);
            var shim = freshness.of(ArtifactIds.cliShim("skill-script", "alpha-script"));
            assertFalse(shim.freshness() == ArtifactFreshness.Freshness.CURRENT,
                    "a shim that cannot reach its tree is not current: " + shim.reason());
        });

        suite.test("an installed plugin the marketplace does not list is stale", () -> {
            SkillStore store = ArtifactsFixture.seed();
            // The fixture's manifest lists `beta`. Rewrite it to list nothing:
            // the generated set has fallen behind the set it is generated from,
            // which is the question `RefreshHarnessPlugins` never asks.
            Files.writeString(
                    store.root().resolve("plugin-marketplace/.claude-plugin/marketplace.json"),
                    "{\"name\": \"fixture\", \"plugins\": []}\n");

            ArtifactIndex index = ArtifactIndex.of(store);
            var entry = index.byId(ArtifactIds.marketplaceEntry("beta"));
            assertTrue(entry.isPresent(),
                    "the artifact that should exist and does not is still listed");

            ArtifactFreshness freshness = ArtifactFreshness.of(index, store);
            var verdict = freshness.of(ArtifactIds.marketplaceEntry("beta"));
            assertEquals(ArtifactFreshness.Freshness.STALE, verdict.freshness(),
                    "the manifest is behind: " + verdict.reason());
        });

        suite.test("a declared MCP server nothing registered is a node, and it is stale", () -> {
            SkillStore store = ArtifactsFixture.withMcpUnit(ArtifactsFixture.seed());
            ArtifactIndex index = ArtifactIndex.of(store);

            var declared = index.byId(ArtifactIds.mcpRegistration("demo-unregistered"));
            assertTrue(declared.isPresent(),
                    "a dependency nothing registered is an artifact, not an absence");
            assertEquals("mcp-alpha", declared.orElseThrow().owner(),
                    "and it has the unit that declares it as its owner");
            assertContains(declared.orElseThrow().inputs().toString(), "unit:mcp-alpha",
                    "which is also its input, so a unit moving reaches it");

            ArtifactFreshness freshness = ArtifactFreshness.of(index, store);
            assertEquals(ArtifactFreshness.Freshness.STALE,
                    freshness.of(ArtifactIds.mcpRegistration("demo-unregistered")).freshness(),
                    "declared and never registered");
            assertEquals(ArtifactFreshness.Freshness.UNVERIFIABLE,
                    freshness.of(ArtifactIds.mcpRegistration("demo-mcp")).freshness(),
                    "and a registration with no declaration is not claimed to be current");
        });

        return suite.runAll();
    }

    /**
     * {@link ArtifactsFixture#seed} plus a real {@code skill-scripts/} tree and
     * the fingerprint its backend computes over it — the shape a home has after
     * one install, and the only shape in which "did my inputs move" is a
     * question with an answer.
     */
    private static SkillStore fingerprinted() throws Exception {
        SkillStore store = ArtifactsFixture.seed();
        Path scripts = store.root().resolve("skills/alpha/skill-scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install.sh"), "#!/bin/sh\necho installing\n");

        CliDependency dep = declaredScriptDep(store);
        var fingerprint = new InstallerRegistry().fingerprintFor(dep, store, "alpha");
        assertTrue(fingerprint.present(),
                "the fixture must produce a real digest, not a gap: " + fingerprint.gap());

        CliLock lock = CliLock.load(store);
        lock.recordInstall("skill-script", "alpha-script", "1.0.0", "skill-script:alpha-script",
                null, "alpha", fingerprint, "alpha-script");
        lock.save(store);
        return store;
    }

    private static CliDependency declaredScriptDep(SkillStore store) throws Exception {
        for (var unit : store.listInstalledUnits().units()) {
            for (CliDependency dep : unit.cliDependencies()) {
                if ("skill-script".equals(dep.backend())) return dep;
            }
        }
        throw new IllegalStateException("the fixture declares a skill-script dep");
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

}
