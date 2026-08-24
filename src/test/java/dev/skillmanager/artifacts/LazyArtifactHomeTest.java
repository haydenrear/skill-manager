package dev.skillmanager.artifacts;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.store.DriftGate;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.HomeDigest;
import dev.skillmanager.store.SkillStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 *   <li>the drift baseline still describes the copy and only the copy — and
 *       the comparison that decides that is SOURCE against copy, because a
 *       copy's recorded baseline against its own recomputed one is true by
 *       construction ({@code rebaselineDrift} wrote the recorded one by
 *       computing it) and cannot fail for the stated reason;</li>
 *   <li>a lazy home's normal state is not reported as a failure, and a home
 *       that is genuinely broken still is;</li>
 *   <li>the ledger still holds no absolute path, so a copy of a copy is free,
 *       and it declares only what this copy skipped — never a binding the copy
 *       dropped for naming another checkout;</li>
 *   <li>{@code node_modules} inside a unit is still carried, which is a
 *       decision and therefore an assertion.</li>
 * </ul>
 *
 * <p>Two cases here EXECUTE a cold shim rather than reading it. That is not
 * thoroughness: a cold shim's properties are claims about what bash does with
 * the file, and reading its bytes is how the run-time expansion of
 * {@code $SKILL_MANAGER_HOME} survived {@code home verify} — see
 * {@link ColdArtifactShim}'s second property.
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

        suite.test("a cold shim RUNS: it prints its reason literally and exits 86", () -> {
            // The only case in the suite that executes one. Everything else
            // reads the file, and reading a bash script is not evidence about
            // what bash does with it — which is exactly how the defect below
            // survived: the file passed `home verify` because it holds no
            // absolute path STATICALLY, and then acquired one at run time.
            Path dir = ArtifactsFixture.newDir("cold-shim-exec-");
            Path entry = dir.resolve("bin/cli/computeq");
            // The first clause is verbatim what HomeCloner.insideHomeText
            // produces for a generated wrapper; the rest are the other three
            // expansions a double-quoted `echo` would perform on it.
            String why = "it runs out of $SKILL_MANAGER_HOME/cache/"
                    + "skill-script-deploy-helm-computeq/venv/bin/computeq"
                    + " (`whoami` $(id -u) $HOME), which this home does not have";
            ColdArtifactShim.write(entry, ArtifactIds.cliShim("skill-script", "computeq"), why);

            Ran ran = run(entry, Map.of(
                    "SKILL_MANAGER_HOME", "/decoy/operator-home",
                    "HOME", "/decoy/operator"));

            assertEquals(ColdArtifactShim.EXIT_CODE, ran.rc(),
                    "86, not 127 and not 0 — and Fs.makeExecutable is what let it run at "
                            + "all, which nothing asserted before this case. Output: " + ran.out());
            assertContains(ran.out(), why,
                    "the reason prints as written. A shell expansion here hands the agent an "
                            + "absolute path naming a home this file has nothing to do with — "
                            + "the Surface.PROVISIONED leak class the clone exists to remove, "
                            + "arriving at run time through the one line the mitigation rests on");
            assertFalse(ran.out().contains("/decoy/operator-home"),
                    "so the environment's SKILL_MANAGER_HOME never reaches the message");
            assertFalse(ran.out().contains("/decoy/operator"),
                    "nor $HOME, nor the output of a command substitution");
            assertContains(ran.out(), "  home:    " + dir.toRealPath(),
                    "while the home it DOES name is derived from the script's own location");
            assertContains(ran.out(), "skill-manager build", "and the fix line still runs");
        });

        suite.test("no cold shim in a real clone can be made to name another home", () -> {
            // The reviewer's reproduction, as a case: 5 of 7 cold shims in a
            // real clone carried the $SKILL_MANAGER_HOME token into a
            // double-quoted echo, so `SKILL_MANAGER_HOME=<unrelated>` made
            // them print a reason about a home they had never touched, and
            // `SKILL_MANAGER_HOME` unset made them print a nonsense path
            // rooted at /.
            SkillStore source = ArtifactsFixture.seed();
            SkillStore clone = cloneOf(source);
            List<Path> cold = coldShims(clone);
            assertTrue(cold.size() >= 2,
                    "the clone wrote both shapes, or this case measures nothing: " + cold);

            boolean anyTokenised = false;
            for (Path shim : cold) {
                Ran ran = run(shim, Map.of(
                        "SKILL_MANAGER_HOME", "/decoy/operator-home",
                        "HOME", "/decoy/operator"));
                assertEquals(ColdArtifactShim.EXIT_CODE, ran.rc(),
                        shim.getFileName() + " exits 86: " + ran.out());
                assertFalse(ran.out().contains("/decoy/"),
                        shim.getFileName() + " took an absolute path from the environment: "
                                + ran.out());
                assertFalse(ran.out().contains(source.root().toString()),
                        shim.getFileName() + " named the source home: " + ran.out());
                if (ran.out().contains("$SKILL_MANAGER_HOME")) anyTokenised = true;
            }
            assertTrue(anyTokenised,
                    "and at least one of them prints the token itself, which is the whole "
                            + "point: the reason has to NAME the missing tree without "
                            + "writing a root into generated content");

            for (Path shim : cold) {
                // With the variable UNSET the old code printed a path rooted
                // at `/` that described nothing on this machine.
                Ran unset = run(shim, Map.of());
                assertFalse(unset.out().contains("reason:  it runs out of /"),
                        shim.getFileName() + " printed a nonsense absolute path: " + unset.out());
                assertFalse(unset.out().contains("reason:  it links to /"),
                        shim.getFileName() + " printed a nonsense absolute path: " + unset.out());
            }
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

            // Taken BEFORE the clone, from the home that still HAS the
            // virtualenv. The comparison that decides this property is
            // source-against-copy: a copy's recorded baseline against its own
            // recomputed one is true by construction, because rebaselineDrift
            // wrote the recorded one by computing it.
            HomeDigest inSource = HomeDigest.compute(source);

            SkillStore clone = cloneOf(source);

            // The property HomeCloner.rebaselineDrift's javadoc argues for, and
            // the one this ticket had to not break: a copy answers for its own
            // content and for nothing else. It holds because the digest never
            // counted a virtualenv on either side — walkPlain drops every
            // Rederivable.isDerived path — so what the copy declared instead of
            // carrying is invisible to the gate by construction.
            assertTrue(DriftGate.pending(clone).isEmpty(), "a fresh copy is not gated");
            HomeDigest inClone = HomeDigest.read(clone).orElseThrow();
            assertEquals(inSource.unitNames(), inClone.unitNames(),
                    "the copy answers for the same units");
            assertFalse(Files.exists(clone.root().resolve("skills/alpha/.venv"),
                            LinkOption.NOFOLLOW_LINKS),
                    "and it does so without the virtualenv, or this case measures nothing");
            for (String unit : inSource.unitNames()) {
                assertEquals(inSource.unit(unit).orElseThrow().digest(),
                        inClone.unit(unit).orElseThrow().digest(),
                        "the copy's baseline for " + unit + " is byte-identical to what the "
                                + "SOURCE digests, virtualenv and all — so the deferral moved "
                                + "the baseline nowhere, and building the venv later moves it "
                                + "nowhere either");
                assertEquals(inSource.unit(unit).orElseThrow().entries().keySet(),
                        inClone.unit(unit).orElseThrow().entries().keySet(),
                        "down to the entry set, which is where a changed content rule shows up");
            }
        });

        suite.test("node_modules inside a unit is CARRIED, and the venv beside it is not", () -> {
            SkillStore source = ArtifactsFixture.seed();
            // Rederivable's argument, made a test: node_modules/<pkg>/build/
            // Release/*.node is a prebuilt native binary that no command in
            // this home rebuilds, so the venv rule deliberately does not name
            // node_modules — and an unasserted "deliberately does not" is one
            // edit away from becoming a silently dropped 10.7 MB.
            Path pkg = source.root().resolve("skills/alpha/node_modules/tree-sitter");
            Files.createDirectories(pkg.resolve("build/Release"));
            Files.writeString(pkg.resolve("package.json"), "{\"name\": \"tree-sitter\"}\n");
            // DEF-109: the NUL is written as the ESCAPE "\\0", not as a raw byte in this
            // source. The bytes this test writes are identical either way; what changes
            // is that this file stays greppable. A raw NUL here made the whole class
            // invisible to every `grep -I`, silently.
            Files.writeString(pkg.resolve("build/Release/tree_sitter.node"), "\0prebuilt\n");
            Path venv = source.root().resolve("skills/alpha/.venv");
            Files.createDirectories(venv);
            Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");

            SkillStore clone = cloneOf(source);

            assertTrue(Files.isRegularFile(
                            clone.root().resolve(
                                    "skills/alpha/node_modules/tree-sitter/build/Release/"
                                            + "tree_sitter.node")),
                    "the prebuilt binary crosses the clone — nothing rebuilds it");
            assertTrue(Files.isRegularFile(clone.root().resolve(
                            "skills/alpha/node_modules/tree-sitter/package.json")),
                    "and so does the rest of the tree");
            assertFalse(Files.exists(clone.root().resolve("skills/alpha/.venv"),
                            LinkOption.NOFOLLOW_LINKS),
                    "while the virtualenv beside it is declared instead — the rule is "
                            + "pyvenv.cfg, not a list of directory names");
        });

        // ---------------------------------------- what the copy's ledger may claim

        suite.test("the copy's ledger declares only what the clone skipped", () -> {
            // The security-flavoured half of the ticket, which nothing tested:
            // declareArtifacts merges the SOURCE's rows in, and if it merged
            // them all, a binding the clone deliberately dropped for naming
            // another checkout would come back as a ledger row — a claim over
            // a directory this home has no business in, wearing a new file
            // name. ArtifactHomeStabilityTest asserts the other direction (the
            // copy's ledger is a superset of the ids), so it cannot catch this.
            SkillStore source = ArtifactsFixture.seed();
            ArtifactLedger.of(ArtifactIndex.of(source).artifacts()).save(source);
            Path venv = source.root().resolve("skills/alpha/.venv");
            Files.createDirectories(venv);
            Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");

            // The fixture's `handbook` doc-import binding projects into a
            // directory outside the home; HomeCloner.remap drops it whole.
            String escaping = "owner:consumer:page:bind";
            Set<String> inSource = ids(ArtifactIndex.of(source).artifacts());
            assertTrue(inSource.stream().anyMatch(id -> id.contains(escaping)),
                    "the source declares the escaping binding's artifacts: " + inSource);

            SkillStore clone = cloneOf(source);

            // The BACKFILL, not the index: the index is the home overlaid with
            // its own ledger, so asking it what the copy can see for itself
            // returns everything the ledger just declared and the question
            // answers itself.
            Set<String> visible = ids(new ArtifactBackfill(clone).collect());
            String deferredTree =
                    ArtifactIds.of(ArtifactKind.PROVISIONED_TREE, "skills/alpha/.venv");
            List<String> inherited = new ArrayList<>();
            for (ArtifactLedger.Row row : ArtifactLedger.load(clone).rows()) {
                assertFalse(row.id().contains(escaping),
                        "a binding the copy dropped is not resurrected as a ledger row: "
                                + row.id());
                if (visible.contains(row.id())) continue;
                if (row.id().equals(deferredTree)) continue;
                inherited.add(row.id());
                assertFalse(row.outputs().isEmpty(),
                        "an inherited row with no home output is a claim the copy cannot "
                                + "check: " + row.id());
                for (String out : row.outputs()) {
                    assertFalse(out.startsWith("/"),
                            "home-relative only, so a further clone is free: " + out);
                    int slash = out.indexOf('/');
                    String top = slash < 0 ? out : out.substring(0, slash);
                    assertTrue(HomeCloner.SKIPPED_DIRS.contains(top),
                            "the source contributes ONLY artifacts under a root this clone "
                                    + "skips; " + row.id() + " names " + out);
                }
            }
            assertFalse(inherited.isEmpty(),
                    "and it does inherit something, or the restriction is vacuous");

            String text = Files.readString(ArtifactLedger.file(clone));
            assertFalse(text.contains(escaping),
                    "the dropped binding id appears nowhere in the file either");
        });

        return suite.runAll();
    }

    private static SkillStore cloneOf(SkillStore source) throws Exception {
        Path dest = Files.createTempDirectory("lazy-artifacts-clone-").resolve("home");
        HomeCloner.Report report = HomeCloner.cloneHome(source.root(), dest, false, true);
        assertTrue(report.clean(), "clone verified clean: " + report.leaks());
        return new SkillStore(dest);
    }

    private static Set<String> ids(List<Artifact> artifacts) {
        Set<String> out = new LinkedHashSet<>();
        for (Artifact a : artifacts) out.add(a.id());
        return out;
    }

    /** Every cold shim under the copy's {@code bin/}, in a stable order. */
    private static List<Path> coldShims(SkillStore home) throws java.io.IOException {
        Path bin = home.root().resolve("bin");
        if (!Files.isDirectory(bin)) return List.of();
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(bin)) {
            stream.sorted().forEach(p -> {
                if (ColdArtifactShim.isCold(p)) out.add(p);
            });
        }
        return out;
    }

    private record Ran(int rc, String out) {}

    /**
     * Execute {@code shim} with {@code SKILL_MANAGER_HOME} taken out of the
     * environment and {@code overrides} put back in, bounded.
     *
     * <p>Executing it is the whole point of the cases that call this: a cold
     * shim's three properties are claims about what BASH does with the file,
     * and every other case in this suite reads its bytes.
     */
    private static Ran run(Path shim, Map<String, String> overrides) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(shim.toString()).redirectErrorStream(true);
        pb.environment().remove("SKILL_MANAGER_HOME");
        pb.environment().putAll(overrides);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor();
            return new Ran(-1, out + "\n[timed out]");
        }
        return new Ran(p.exitValue(), out);
    }
}
