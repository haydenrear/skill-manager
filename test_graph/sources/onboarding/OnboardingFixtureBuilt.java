///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java
//SOURCES ../tripwire/TripwireSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The shared fixture.</b> Everything the onboarding walk needs, built once,
 * with every realism precondition stated rather than assumed.
 *
 * <h2>What is built</h2>
 *
 * <ul>
 *   <li><b>{@code src-home}</b> — a populated source home: six units, one of
 *       them ({@code ob-transitive}) reached only through another's
 *       {@code skill_references}, one ({@code ob-script}) whose CLI dep uses
 *       the {@code skill-script} backend and therefore lands a REGULAR FILE at
 *       {@code bin/cli/ob-script-shim}, plus a planted shim dangling into
 *       {@code venvs/} — a directory {@code home clone} deliberately skips.</li>
 *   <li><b>Inherited-state pollution</b> in that home: two binding-ledger
 *       records and two {@code child-homes/} records naming checkouts OUTSIDE
 *       it. Without these the "a clone drops foreign claims" assertion is about
 *       nothing, and being about nothing is exactly how that defect survived
 *       four evaluations of this path.</li>
 *   <li><b>{@code proj}</b> — a fresh git repo with a {@code CLAUDE.md} and a
 *       {@code docs/architecture.md} each carrying a valid markdown
 *       {@code skill-imports} block, a {@code skill-project.toml}, and a
 *       {@code .gitignore} holding EXACTLY the four rules
 *       {@code references/skill-homes.md} prescribes. The absence of a fifth is
 *       deliberate: {@code /.claude.json} not being covered is the subject of
 *       the work-tree-cleanliness assertion, so the fixture must not pre-fix
 *       it.</li>
 *   <li><b>{@code empty-home}</b> and <b>{@code fake-home}</b> — the two
 *       refusal fixtures, kept on their own disk state because the exit-1 and
 *       exit-5 paths are about a home that is absent and a home that is empty,
 *       and neither survives contact with the populated one.</li>
 * </ul>
 *
 * <h2>The build under test is pinned, and a release FAILS the fixture</h2>
 *
 * <p>{@code skill-manager 0.20.0} means the launcher resolved a released jar;
 * {@code 0.20.0+g68ce6ec} means it compiled this checkout. The distinction is
 * not cosmetic — a release run measures a binary that does not contain the
 * change under test, and reports it as evidence. So the version string is
 * matched against {@link OnboardingSupport#SOURCE_BUILD} and a mismatch is a
 * FAILURE, never a skip.
 *
 * <h2>The isolation baseline covers the file the last eval's own filter missed</h2>
 *
 * <p>{@code TripwireSupport} watches {@code ~/.skill-manager}, {@code ~/.claude},
 * {@code ~/.codex} and {@code ~/.gemini}. It does not watch
 * <b>{@code ~/.claude.json}</b>, which is a sibling of those roots rather than
 * a child of one — and that file is precisely where this product writes a
 * Claude MCP registration. The hand-run eval's first isolation filter excluded
 * it BY NAME and would have missed a write to it; that near-miss is preserved
 * here as a fixture requirement rather than as a footnote. So this node hashes
 * the four agent config files separately, and the closing node compares them.
 */
public class OnboardingFixtureBuilt {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.fixture.built")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("onboarding", "fixture")
            .timeout("900s")
            .output("workspace", "string")
            .output("sourcesDir", "string")
            .output("srcHome", "string")
            .output("srcAgents", "string")
            .output("foreignBase", "string")
            .output("emptyHome", "string")
            .output("fakeHome", "string")
            .output("fakeProj", "string")
            .output("proj", "string")
            .output("ambient", "string")
            .output("scriptsDir", "string")
            .output("skillRoot", "string")
            .output("sandboxGlobalHome", "string")
            .output("leakBaseline", "string")
            .output("leakRoots", "string")
            .output("configBaseline", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (sandbox == null) {
                return NodeResult.fail("onboarding.fixture.built", "missing env.prepared.home");
            }

            // --- the scripts under test ------------------------------------
            //
            // A refusal, not a skip: bootstrap-home.sh, wt and close-change.sh
            // ARE half the subject of this graph, and a run that could not find
            // them measured nothing.
            TicketLifecycleSupport.Scripts scripts = OnboardingSupport.scripts(ctx);
            if (!scripts.found()) {
                return NodeResult.fail("onboarding.fixture.built",
                        "could not locate git-integration-repo's scripts — " + scripts.how()
                                + ". Set $" + TicketLifecycleSupport.SCRIPTS_ENV
                                + " to <git-integration-repo>/scripts and re-run.")
                        .assertion("the_integration_scripts_under_test_were_found", false);
            }

            Path workspace = Path.of(sandbox, "onboarding");
            Path sources = workspace.resolve("sources");
            Path srcHome = workspace.resolve("src-home");
            Path srcAgents = workspace.resolve("src-agents");
            // OUTSIDE the workspace on purpose. The predicate under test is
            // "not under the home being created's own root", and the source
            // home's root IS the workspace — so a foreign checkout placed
            // inside it is not foreign to the source, and the mandatory
            // "the source really is polluted" precondition reads zero.
            // Measured: it did, and the clone assertion below it went green on
            // a source that had nothing to inherit.
            Path foreign = Path.of(sandbox, "foreign-checkouts");
            Path emptyHome = workspace.resolve("empty-home");
            Path fakeHome = workspace.resolve("fake-home");
            Path fakeProj = workspace.resolve("fake-proj");
            Path proj = workspace.resolve("proj");
            Path ambient = workspace.resolve("ambient");
            for (Path dir : List.of(sources, srcAgents, foreign, fakeHome, fakeProj, proj,
                    ambient)) {
                Files.createDirectories(dir);
            }
            Files.createDirectories(emptyHome.resolve("installed"));
            Files.createDirectories(emptyHome.resolve("skills"));

            // --- the version pin -------------------------------------------
            ProcessRecord version = OnboardingSupport.sm(ctx, "version", ambient, srcAgents,
                    "--version");
            String versionText = OnboardingSupport.log(ctx, version);
            boolean theBuildUnderTestIsCompiledFromSource = version.exitCode() == 0
                    && OnboardingSupport.SOURCE_BUILD.matcher(versionText).find();

            // --- the unit sources -------------------------------------------
            for (String unit : List.of(OnboardingSupport.ALPHA, OnboardingSupport.BETA,
                    OnboardingSupport.GAMMA)) {
                OnboardingSupport.mkUnit(sources, unit, "onboarding fixture unit " + unit, null);
            }
            // Transitive resolution, offline. github: coordinates need the
            // network; a file: reference does not, and the property under test
            // — "a unit nobody declared is installed and bound like a declared
            // one" — is the same either way.
            Path transitive = OnboardingSupport.mkUnit(sources, OnboardingSupport.TRANSITIVE,
                    "reached only through ob-umbrella's skill_references", null);
            OnboardingSupport.mkUnit(sources, OnboardingSupport.UMBRELLA,
                    "umbrella that references ob-transitive", null);
            Files.writeString(sources.resolve(OnboardingSupport.UMBRELLA)
                            .resolve("skill-manager.toml"),
                    "skill_references = [\n  \"file://" + transitive + "\",\n]\n\n"
                            + "[skill]\nname = \"" + OnboardingSupport.UMBRELLA + "\"\n"
                            + "version = \"0.1.0\"\ndescription = \"umbrella\"\n");
            mkScriptUnit(sources);

            // acme-lint: two VALID markdown imports, one in SKILL.md and one in
            // a references page — the positive control for the half of import
            // resolution that works.
            Path lint = OnboardingSupport.mkUnit(sources, OnboardingSupport.LINT,
                    "local unit with valid skill-imports",
                    OnboardingSupport.imports(OnboardingSupport.entry(
                            OnboardingSupport.ALPHA, "SKILL.md", "the alpha unit's own page")));
            Files.writeString(lint.resolve("references").resolve("rules.md"),
                    "---\n" + OnboardingSupport.imports(OnboardingSupport.entry(
                            OnboardingSupport.BETA, "references/page.md", "beta's reference"))
                            + "---\n\n# rules\n");

            // acme-broken: exactly TWO invalid imports, and two DIFFERENT code
            // paths — a missing unit and a present unit with a missing path.
            // Two, not one, because the count is the assertion.
            OnboardingSupport.mkUnit(sources, OnboardingSupport.BROKEN,
                    "local unit with two invalid skill-imports",
                    OnboardingSupport.imports(
                            OnboardingSupport.entry("no-such-unit", "SKILL.md",
                                    "names a unit that does not exist"),
                            OnboardingSupport.entry(OnboardingSupport.ALPHA,
                                    "references/definitely-missing.md",
                                    "names a present unit and a missing path")));

            // --- the source home, installed by the real CLI ------------------
            List<ProcessRecord> installs = new ArrayList<>();
            for (String unit : List.of(OnboardingSupport.ALPHA, OnboardingSupport.BETA,
                    OnboardingSupport.GAMMA, OnboardingSupport.UMBRELLA,
                    OnboardingSupport.SCRIPT_UNIT)) {
                installs.add(OnboardingSupport.sm(ctx, "install-" + unit, srcHome, srcAgents,
                        "install", sources.resolve(unit).toString(), "--yes"));
            }
            boolean everyInstallExitedZero = installs.stream().allMatch(p -> p.exitCode() == 0);

            // --- a cause the dedup guard can measure without a defect ----------
            //
            // Every unit here is installed from a local path, and until the
            // `file:` contract was fixed every local install carried a
            // permanent NEEDS_GIT_MIGRATION record. The dedup-and-clear guard
            // therefore got its "two units sharing one cause" for free — from
            // the very defect it shares a node with. Fixing that defect would
            // have taken the guard's subject with it and left
            // `unitsAffected: 0` reading as a pass, which is the fourth time
            // this graph's history would have recorded an assertion that only
            // held while something was broken.
            //
            // So the cause is planted, as the shape the error was written for:
            // the provenance record is DELETED and the reconciler in the
            // product re-onboards the unit as `installSource: UNKNOWN`. Such a
            // unit genuinely cannot sync and nobody chose it. The reconcile
            // happens on the next CLI call — the foreign-checkout registers
            // immediately below — and the outcome is asserted, not assumed, by
            // assertSourceHomeIsRealistic.
            for (String unit : OnboardingSupport.PROVENANCELESS) {
                OnboardingSupport.stripProvenanceRecord(srcHome, unit);
            }

            // --- the inherited-state pollution --------------------------------
            //
            // ORDER MATTERS, AND IT IS NOT OBVIOUS. The registrations go in
            // first, through the real CLI; the LEDGER records are spliced in
            // last, once no further skill-manager command will run against this
            // home. Measured the other way round: `project register` reconciles
            // and REWRITES installed/<u>.projections.json from live state, which
            // silently erased every planted binding and left the clone assertion
            // measuring a source that had nothing to inherit — the exact vacuity
            // that node exists to prevent, arrived at from inside the fixture.
            List<Path> foreignCheckouts = new ArrayList<>();
            for (int i = 1; i <= 2; i++) {
                Path checkout = foreign.resolve("foreign-checkout-" + i);
                Files.createDirectories(checkout);
                // `project register` needs a manifest; without one it refuses,
                // and the refusal is a stack trace — see
                // onboarding.refusals.are.messages.
                Files.writeString(checkout.resolve("skill-project.toml"),
                        "[project]\nname = \"foreign-checkout-" + i + "\"\n");
                foreignCheckouts.add(checkout);
                OnboardingSupport.sm(ctx, "register-foreign-" + i, srcHome, srcAgents,
                        "project", "register", "--project-dir", checkout.toString());
            }
            ProcessRecord srcProjects = OnboardingSupport.sm(ctx, "src-project-list", srcHome,
                    srcAgents, "project", "list");
            int srcRegistrations = 0;
            for (Path checkout : foreignCheckouts) {
                if (OnboardingSupport.log(ctx, srcProjects)
                        .contains(checkout.getFileName().toString())) {
                    srcRegistrations++;
                }
            }

            // Now, and only now, the records no CLI command may rewrite.
            Path danglingShim = OnboardingSupport.plantDanglingShim(srcHome);
            OnboardingSupport.plantForeignClaims(srcHome, foreign,
                    List.of(OnboardingSupport.ALPHA, OnboardingSupport.BETA));

            OnboardingSupport.Realism realism =
                    OnboardingSupport.assertSourceHomeIsRealistic(srcHome);

            // --- the project checkout ----------------------------------------
            List<String> gitFailures = new ArrayList<>();
            OnboardingSupport.git(gitFailures, proj, "init", "-q", "-b", "main");
            OnboardingSupport.git(gitFailures, proj, "config", "user.email", "graph@localhost");
            OnboardingSupport.git(gitFailures, proj, "config", "user.name", "graph");
            Files.createDirectories(proj.resolve("docs"));
            Files.writeString(proj.resolve(".gitignore"),
                    String.join("\n", OnboardingSupport.DOCUMENTED_IGNORES) + "\n");
            Files.writeString(proj.resolve("CLAUDE.md"),
                    "---\n" + OnboardingSupport.imports(OnboardingSupport.entry(
                            OnboardingSupport.ALPHA, "SKILL.md",
                            "the project's own instructions cite the alpha unit"))
                            + "---\n\n# acme widgets\n");
            Files.writeString(proj.resolve("docs").resolve("architecture.md"),
                    "---\n" + OnboardingSupport.imports(OnboardingSupport.entry(
                            OnboardingSupport.BETA, "references/page.md",
                            "the architecture note cites beta's reference"))
                            + "---\n\n# architecture\n");
            Files.writeString(proj.resolve("skill-project.toml"),
                    projectManifest(sources));
            Files.writeString(proj.resolve("README.md"), "acme widgets\n");
            OnboardingSupport.git(gitFailures, proj, "add", "-A");
            OnboardingSupport.git(gitFailures, proj, "commit", "-qm", "acme-widgets fixture");
            boolean theCheckoutIsACleanGitRepo = gitFailures.isEmpty()
                    && HomeSyncSupport.git(proj, "rev-parse", "HEAD").ok()
                    && HomeSyncSupport.git(proj, "status", "--porcelain").trimmed().isEmpty();

            // The fixture must NOT pre-fix the gitignore gap. Asserted, because
            // a .gitignore copied from a working setup already carries
            // /.claude.json and would make the cleanliness node about nothing.
            List<String> ignoreRules = new ArrayList<>();
            for (String line : Files.readAllLines(proj.resolve(".gitignore"))) {
                if (!line.isBlank()) ignoreRules.add(line.strip());
            }
            boolean theGitignoreIsExactlyTheDocumentedFour =
                    ignoreRules.equals(OnboardingSupport.DOCUMENTED_IGNORES);

            // The refusal branch's project, kept separate: the exit-1 and
            // exit-5 paths assert that NOTHING was written, and a project that
            // had already been bootstrapped could not show that.
            OnboardingSupport.git(gitFailures, fakeProj, "init", "-q", "-b", "main");
            Files.writeString(fakeProj.resolve("README.md"), "refusal fixture\n");

            // --- the isolation baselines --------------------------------------
            Path sandboxGlobalHome = Path.of(sandbox).resolve(".skill-manager");
            boolean noGlobalHomeYet = !Files.exists(sandboxGlobalHome);

            Path leakBaseline = ctx.reportDir().resolve("onboarding.leak-baseline.txt");
            Path configBaseline = ctx.reportDir().resolve("onboarding.config-baseline.txt");
            List<Path> leakRoots = new ArrayList<>();
            String leakError = null;
            try {
                Path realHome = TripwireSupport.realHome();
                leakRoots = TripwireSupport.presentRoots(realHome);
                TripwireSupport.writeLines(leakBaseline,
                        TripwireSupport.collectAll(leakRoots, realHome,
                                TripwireSupport.Fidelity.METADATA));
                TripwireSupport.writeLines(configBaseline, configHashes(realHome));
            } catch (RuntimeException e) {
                leakError = String.valueOf(e.getMessage());
            }
            boolean theLeakOracleIsArmed = leakError == null && !leakRoots.isEmpty()
                    && Files.isRegularFile(leakBaseline);
            // The config baseline must have SEEN the file the last eval's own
            // filter would have missed. A hash list that does not name
            // ~/.claude.json cannot detect a write to ~/.claude.json.
            boolean theConfigBaselineCoversClaudeJson =
                    OnboardingSupport.read(configBaseline).contains("/.claude.json\t");

            boolean pass = theBuildUnderTestIsCompiledFromSource && everyInstallExitedZero
                    && realism.ok() && theCheckoutIsACleanGitRepo
                    && theGitignoreIsExactlyTheDocumentedFour && srcRegistrations >= 2
                    && noGlobalHomeYet && theLeakOracleIsArmed
                    && theConfigBaselineCoversClaudeJson;

            NodeResult result = pass
                    ? NodeResult.pass("onboarding.fixture.built")
                    : NodeResult.fail("onboarding.fixture.built",
                            "version=" + versionText.strip()
                                    + " installs=" + installs.stream()
                                            .map(ProcessRecord::exitCode).toList()
                                    + " realism=" + realism
                                    + " gitFailures=" + gitFailures
                                    + " ignoreRules=" + ignoreRules
                                    + " srcRegistrations=" + srcRegistrations
                                    + " leak=" + leakError);
            for (ProcessRecord p : installs) result = result.process(p);
            return result.process(version)
                    .assertion("the_integration_scripts_under_test_were_found", true)
                    .assertion("the_cli_under_test_is_a_source_build_not_a_release",
                            theBuildUnderTestIsCompiledFromSource)
                    .assertion("every_install_into_the_source_home_exited_zero",
                            everyInstallExitedZero)
                    .assertion("the_source_home_holds_a_transitively_resolved_unit",
                            realism.transitivelyResolvedUnit())
                    .assertion("the_source_home_holds_a_regular_file_cli_shim",
                            realism.regularFileCliShim())
                    .assertion("the_source_home_holds_a_shim_dangling_into_a_skipped_dir",
                            realism.danglingShim())
                    .assertion("the_source_home_carries_foreign_binding_and_child_home_claims",
                            realism.foreignClaims())
                    .assertion("the_source_home_carries_two_units_with_no_provenance_record",
                            realism.provenancelessUnits() >= 2)
                    .assertion("the_source_home_carries_at_least_two_foreign_registrations",
                            srcRegistrations >= 2)
                    .assertion("the_checkout_is_a_clean_git_repository",
                            theCheckoutIsACleanGitRepo)
                    .assertion("the_gitignore_carries_exactly_the_four_documented_rules",
                            theGitignoreIsExactlyTheDocumentedFour)
                    .assertion("no_global_home_exists_in_the_sandbox_yet", noGlobalHomeYet)
                    .assertion("the_leak_oracle_is_armed_over_the_operators_real_homes",
                            theLeakOracleIsArmed)
                    .assertion("the_config_baseline_covers_the_sibling_claude_json_file",
                            theConfigBaselineCoversClaudeJson)
                    .metric("sourceHomeUnits", OnboardingSupport.storeUnits(srcHome).size())
                    .metric("foreignBindingRecords", realism.foreignBindingRecords())
                    .metric("foreignChildHomes", realism.foreignChildHomes())
                    .metric("provenancelessUnits", realism.provenancelessUnits())
                    .metric("foreignRegistrations", srcRegistrations)
                    .log("scripts: " + scripts.how())
                    .log("dangling shim planted at " + danglingShim)
                    .publish("workspace", workspace.toString())
                    .publish("sourcesDir", sources.toString())
                    .publish("srcHome", srcHome.toString())
                    .publish("srcAgents", srcAgents.toString())
                    .publish("foreignBase", foreign.toString())
                    .publish("emptyHome", emptyHome.toString())
                    .publish("fakeHome", fakeHome.toString())
                    .publish("fakeProj", fakeProj.toString())
                    .publish("proj", proj.toString())
                    .publish("ambient", ambient.toString())
                    .publish("scriptsDir", scripts.dir().toString())
                    .publish("skillRoot", OnboardingSupport.skillRoot(scripts).toString())
                    .publish("sandboxGlobalHome", sandboxGlobalHome.toString())
                    .publish("leakBaseline", leakBaseline.toString())
                    .publish("configBaseline", configBaseline.toString())
                    .publish("leakRoots", leakRoots.stream().map(Path::toString)
                            .reduce((a, b) -> a + File.pathSeparator + b).orElse(""));
        });
    }

    /**
     * The unit whose CLI dep uses the {@code skill-script} backend.
     *
     * <p>Its install script lands a plain executable at
     * {@code <home>/bin/cli/ob-script-shim}. That REGULAR FILE — not a symlink —
     * is the shape that used to make a second projection throw, so the
     * idempotency guard needs one to exist or it proves nothing.
     */
    private static void mkScriptUnit(Path sources) throws java.io.IOException {
        Path dir = OnboardingSupport.mkUnit(sources, OnboardingSupport.SCRIPT_UNIT,
                "unit with a skill-script CLI dependency", null);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [[cli_dependencies]]
                spec = "skill-script:%s"
                on_path = "__zzz_nope_%s"

                [cli_dependencies.install.any]
                script = "install.sh"
                binary = "%s"

                [skill]
                name = "%s"
                version = "0.1.0"
                description = "unit with a skill-script CLI dependency"
                """.formatted(OnboardingSupport.SCRIPT_SHIM,
                        OnboardingSupport.SCRIPT_SHIM.replace('-', '_'),
                        OnboardingSupport.SCRIPT_SHIM, OnboardingSupport.SCRIPT_UNIT));
        Path scriptsDir = dir.resolve("skill-scripts");
        Files.createDirectories(scriptsDir);
        Path install = scriptsDir.resolve("install.sh");
        Files.writeString(install, """
                #!/usr/bin/env bash
                set -euo pipefail
                : "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                printf '#!/bin/sh\\nexit 0\\n' > "$SKILL_MANAGER_BIN_DIR/%s"
                chmod +x "$SKILL_MANAGER_BIN_DIR/%s"
                """.formatted(OnboardingSupport.SCRIPT_SHIM, OnboardingSupport.SCRIPT_SHIM));
        install.toFile().setExecutable(true);
    }

    /**
     * {@code skill-project.toml} declaring three units.
     *
     * <p>All three by {@code file://} source, deliberately: the manifest schema
     * accepts it, {@code project resolve} celebrates it, and the permanent
     * {@code NEEDS_GIT_MIGRATION} error state that follows is itself under test.
     * A fixture that used {@code github:} coordinates would need the network AND
     * would hide that contract.
     */
    private static String projectManifest(Path sources) {
        return """
                [project]
                name = "acme-widgets"

                [skills.%s]
                source = "file://%s"

                [skills.%s]
                source = "file://%s"

                [skills.%s]
                source = "file://%s"
                """.formatted(
                OnboardingSupport.ALPHA, sources.resolve(OnboardingSupport.ALPHA),
                OnboardingSupport.BETA, sources.resolve(OnboardingSupport.BETA),
                OnboardingSupport.LINT, sources.resolve(OnboardingSupport.LINT));
    }

    /**
     * SHA-256 of the four agent config files, by path.
     *
     * <p>Whole-file rather than "the mcpServers block": parsing them to compare
     * one key means a parser bug can only ever make the check WEAKER, and these
     * files are small. {@code ~/.claude.json} is first in the list because it is
     * the one a root-scoped tree walk does not reach and the one this product
     * writes MCP registrations into.
     */
    private static List<String> configHashes(Path realHome) {
        List<String> out = new ArrayList<>();
        for (String rel : List.of(".claude.json", ".codex/config.toml",
                ".gemini/settings.json", ".claude/settings.json")) {
            Path file = realHome.resolve(rel);
            out.add("/" + rel + "\t" + digest(file));
        }
        return out;
    }

    private static String digest(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "ABSENT";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "UNREADABLE:" + e.getClass().getSimpleName();
        }
    }
}
