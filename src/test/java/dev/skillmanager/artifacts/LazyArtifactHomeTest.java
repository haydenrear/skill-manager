package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ARTI-07: <b>a cloned home declares its artifacts and builds them on demand.</b>
 *
 * <p>The cases are ordered by the ticket's own risk statement. The one that
 * matters most is not the footprint — it is that an agent which reaches a cold
 * artifact mid-task is told what to run, in one line, instead of being handed
 * {@code bad interpreter}. Everything else in this file is a property that
 * must not have been broken to get there:
 *
 * <ul>
 *   <li>the drift baseline still describes the copy and only the copy;</li>
 *   <li>a lazy home's normal state is not reported as a failure, and a home
 *       that is genuinely broken still is;</li>
 *   <li>the ledger still holds no absolute path, so a copy of a copy is free.</li>
 * </ul>
 */
public final class LazyArtifactHomeTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LazyArtifactHomeTest");

        // ------------------------------------------------ the whole mitigation

        suite.test("a cold entry point names the command, and the command is the real id", () -> {
            SkillStore source = ArtifactsFixture.seed();
            SkillStore clone = cloneOf(source);

            // The generated-wrapper shape: bin/cli/alpha-script execs into
            // cache/skill-script-alpha-alpha-script, which no clone carries.
            Path shim = clone.cliBinDir().resolve("alpha-script");
            assertTrue(Files.isRegularFile(shim), "the entry point is still there");
            assertTrue(ColdArtifactShim.isCold(shim), "and it is now a cold shim");

            String body = Files.readString(shim);
            assertContains(body, "skill-manager build",
                    "the fix line names the verb ARTI-06 added");
            assertContains(body, "skt build", "and the front door agents actually use");
            assertContains(body, ArtifactIds.cliShim("skill-script", "alpha-script"),
                    "with the artifact's REAL id — the printed command is executed by "
                            + "whoever reads it, so a guessed id is worse than none");
            assertFalse(body.contains(clone.root().toString()),
                    "and it holds no absolute path, so a copy of this copy is still correct");
            assertFalse(body.contains(source.root().toString()),
                    "least of all the source home's");
        });

        suite.test("the dangling-symlink shape gets the same treatment", () -> {
            SkillStore source = ArtifactsFixture.seed();
            // bin/cli/dangler -> ../../cache/uv-tools/alpha/bin/dangler, which
            // is the bin/cli/jinja2 and bin/cli/skill-dev pair ARTI-01 measured
            // on all five of its probe homes.
            SkillStore clone = cloneOf(source);
            Path shim = clone.cliBinDir().resolve("dangler");
            assertFalse(Files.isSymbolicLink(shim),
                    "the link that resolved to nothing is gone");
            assertTrue(ColdArtifactShim.isCold(shim), "replaced by an entry point that runs");
        });

        suite.test("a cold shim lists as declared, never as materialized", () -> {
            SkillStore clone = cloneOf(ArtifactsFixture.seed());
            Artifact shim = ArtifactIndex.of(clone)
                    .byId(ArtifactIds.cliShim("skill-script", "alpha-script")).orElseThrow();
            // The trap this case exists for: a cold shim is a regular,
            // executable file that resolves and runs, so every presence check
            // in the system passes on it. Reporting it as materialized would be
            // the presence proxy this epic exists to remove, wearing a better
            // error message.
            assertEquals(Artifact.Materialization.DECLARED_ONLY, shim.materialization(),
                    "the file runs and the tool is not there");
            assertContains(shim.actual().get("unusable_because"), "declared and not built",
                    "and the listing says which of the three states it is in");
        });

        // ------------------------------------------------------------- the tier

        suite.test("the tier decides it, and the root home is eager", () -> {
            SkillStore project = new SkillStore(ArtifactsFixture.newDir("lazy-tier-project-"));
            assertTrue(HomePolicy.lazyArtifactsDefault(project),
                    "a project or worktree home defaults on");
            SkillStore root = new SkillStore(
                    dev.skillmanager.agent.AgentHomes.userHome().resolve(".skill-manager"));
            assertFalse(HomePolicy.lazyArtifactsDefault(root),
                    "and the operator root defaults off — the same comparison skt's "
                            + "classify_tier makes, not a second notion of tier");
        });

        suite.test("the clone records its decision, and `home policy live` preserves it", () -> {
            SkillStore clone = cloneOf(ArtifactsFixture.seed());
            assertEquals(Boolean.TRUE, HomePolicy.declaredLazyArtifacts(clone),
                    "the copy says what it did rather than leaving it to be re-derived");

            // bootstrap-home.sh runs exactly this on every bootstrap, right
            // after the clone. A rewrite that dropped the other key would turn
            // every lazy home eager with no visible cause.
            HomePolicy.write(clone, HomePolicy.LIVE);
            assertEquals(Boolean.TRUE, HomePolicy.declaredLazyArtifacts(clone),
                    "and `home policy live` does not drop it");
            assertEquals(HomePolicy.LIVE, HomePolicy.load(clone), "while still declaring live");
        });

        // ------------------------------------------------- verify's three states

        suite.test("a lazy home's declared artifacts are reported and not counted", () -> {
            SkillStore clone = cloneOf(ArtifactsFixture.seed());
            HomeCloner.Verification result = HomeCloner.verify(clone.root(), false);
            assertTrue(result.unresolved().isEmpty(),
                    "nothing is broken: " + result.unresolved());
            assertTrue(result.declaredNotBuilt().isEmpty(),
                    "and after the cold shims there is nothing left dangling either: "
                            + result.declaredNotBuilt());
        });

        suite.test("a declared artifact is the third state; an undeclared one is still broken",
                () -> {
                    SkillStore clone = cloneOf(ArtifactsFixture.seed());
                    // Two entry points the clone did not write: one the ledger
                    // declares, one nothing ever claimed to produce. The first
                    // is normal in a lazy home; the second is a defect in any
                    // home, and a gate that cannot tell them apart is a gate
                    // somebody turns off.
                    Path declared = clone.cliBinDir().resolve("declared-tool");
                    Files.createSymbolicLink(declared, Path.of("../../cache/nowhere/bin/x"));
                    Path stranger = clone.cliBinDir().resolve("stranger");
                    Files.createSymbolicLink(stranger, Path.of("../../cache/elsewhere/bin/y"));
                    ArtifactLedger.of(List.of(new Artifact(
                            ArtifactIds.cliShim("pip", "declared-tool"), ArtifactKind.CLI_SHIM,
                            "alpha", List.of(),
                            List.of(Artifact.Output.inHome("bin/cli/declared-tool",
                                    Artifact.Presence.MISSING)),
                            null, java.util.Map.of(), java.util.Map.of(),
                            Artifact.Agreement.UNRECORDED, Artifact.Origin.LEDGER)))
                            .save(clone);

                    HomeCloner.Verification result = HomeCloner.verify(clone.root(), false);
                    assertEquals(1, result.declaredNotBuilt().size(),
                            "the declared one is the third state: " + result.declaredNotBuilt());
                    assertContains(result.declaredNotBuilt().get(0), "bin/cli/declared-tool",
                            "and it is the one the ledger names");
                    assertEquals(1, result.unresolved().size(),
                            "the other one is still broken: " + result.unresolved());
                    assertContains(result.unresolved().get(0), "bin/cli/stranger",
                            "and it is the one nothing declared");
                });

        suite.test("an eager home excuses nothing", () -> {
            SkillStore clone = cloneOf(ArtifactsFixture.seed());
            Path declared = clone.cliBinDir().resolve("declared-tool");
            Files.createSymbolicLink(declared, Path.of("../../cache/nowhere/bin/x"));
            ArtifactLedger.of(List.of(new Artifact(
                    ArtifactIds.cliShim("pip", "declared-tool"), ArtifactKind.CLI_SHIM,
                    "alpha", List.of(),
                    List.of(Artifact.Output.inHome("bin/cli/declared-tool",
                            Artifact.Presence.MISSING)),
                    null, java.util.Map.of(), java.util.Map.of(),
                    Artifact.Agreement.UNRECORDED, Artifact.Origin.LEDGER))).save(clone);
            HomePolicy.writeLazyArtifacts(clone, false);

            HomeCloner.Verification result = HomeCloner.verify(clone.root(), false);
            assertTrue(result.declaredNotBuilt().isEmpty(),
                    "without the policy there is no third state — an unresolved reference in an "
                            + "eager home means an install broke");
            assertEquals(1, result.unresolved().size(), "so it is a failure: " + result.unresolved());
        });

        // ------------------------------------------- what a lazy copy defers

        suite.test("a virtualenv inside a unit is declared, not copied", () -> {
            SkillStore source = ArtifactsFixture.seed();
            Path venv = source.root().resolve("skills/alpha/.venv");
            Files.createDirectories(venv.resolve("bin"));
            Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");
            Files.writeString(venv.resolve("bin/python"), "#!/usr/bin/env python3\n");

            SkillStore clone = cloneOf(source);

            assertFalse(Files.exists(clone.root().resolve("skills/alpha/.venv"),
                            LinkOption.NOFOLLOW_LINKS),
                    "361 MB of the operator's home is one of these, and a clone carried it");
            assertTrue(Files.isRegularFile(clone.root().resolve("skills/alpha/SKILL.md")),
                    "while the unit's authored content is untouched");
            Artifact tree = ArtifactIndex.of(clone)
                    .byId(ArtifactIds.of(ArtifactKind.PROVISIONED_TREE, "skills/alpha/.venv"))
                    .orElseThrow();
            assertEquals(Artifact.Materialization.DECLARED_ONLY, tree.materialization(),
                    "declared here, materialized nowhere");
            assertEquals("alpha", tree.owner(), "and credited to the unit it sits in");
        });

        suite.test("a directory that only LOOKS derived is copied", () -> {
            SkillStore source = ArtifactsFixture.seed();
            // Rederivable's own warning, made a test: `build`, `target` and
            // `venv` are ordinary words used by convention, and a rule that
            // matched on the name would silently drop authored content. The
            // marker is what decides, and an authored directory has none.
            Path notAVenv = source.root().resolve("skills/alpha/venv");
            Files.createDirectories(notAVenv);
            Files.writeString(notAVenv.resolve("README.md"), "authored, despite the name\n");
            Path build = source.root().resolve("skills/alpha/build");
            Files.createDirectories(build);
            Files.writeString(build.resolve("notes.md"), "also authored\n");

            SkillStore clone = cloneOf(source);

            assertTrue(Files.isRegularFile(clone.root().resolve("skills/alpha/venv/README.md")),
                    "a directory named venv with no pyvenv.cfg is not a virtualenv");
            assertTrue(Files.isRegularFile(clone.root().resolve("skills/alpha/build/notes.md")),
                    "and build/ is never in this rule at all");
        });

        suite.test("deferring a virtualenv does not move the drift baseline", () -> {
            SkillStore source = ArtifactsFixture.seed();
            Path venv = source.root().resolve("skills/alpha/.venv");
            Files.createDirectories(venv.resolve("bin"));
            Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");
            Files.writeString(venv.resolve("bin/python"), "#!/usr/bin/env python3\n");

            SkillStore clone = cloneOf(source);

            // The property HomeCloner.rebaselineDrift's javadoc argues for, and
            // the one this ticket had to not break: a copy answers for its own
            // content and for nothing else. It holds because the digest never
            // counted a virtualenv on either side — walkPlain drops every
            // Rederivable.isDerived path — so what the copy declared instead of
            // carrying is invisible to the gate by construction.
            assertTrue(DriftGate.pending(clone).isEmpty(), "a fresh copy is not gated");
            HomeDigest recorded = HomeDigest.read(clone).orElseThrow();
            HomeDigest recomputed = HomeDigest.compute(clone);
            assertEquals(recorded.unit("alpha").orElseThrow().digest(),
                    recomputed.unit("alpha").orElseThrow().digest(),
                    "and its baseline is a statement about the bytes it actually holds");
        });

        return suite.runAll();
    }

    private static SkillStore cloneOf(SkillStore source) throws Exception {
        Path dest = Files.createTempDirectory("lazy-artifacts-clone-").resolve("home");
        HomeCloner.Report report = HomeCloner.cloneHome(source.root(), dest, false, true);
        assertTrue(report.clean(), "clone verified clean: " + report.leaks());
        return new SkillStore(dest);
    }
}
