///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the SYNTHETIC fixture home this graph clones, and records the digest
 * that every later byte-identity assertion is measured against.
 *
 * <h2>Why synthetic</h2>
 *
 * The developer's real {@code ~/.skill-manager} is 5.4 GB, {@code /private/tmp}
 * has less free space than that, and the standing constraint on issue #1 forbids
 * writing to it at all. Cloning it would also be a 5.4 GB read for no benefit:
 * what the clone has to get right is one artifact per problem class, and issue
 * #20 enumerates them. This fixture carries exactly one of each:
 *
 * <ul>
 *   <li><b>Two installed units</b>, installed by the real CLI, so
 *       {@code installed/*.json} and {@code installed/*.projections.json} are
 *       genuine state and the clone's re-anchoring pass has something real to
 *       re-anchor.</li>
 *   <li><b>An ABSOLUTE in-unit symlink into the store</b>
 *       ({@code skills/hc-unit-a/vendor/linked}). Issue #20 found seven of
 *       these. A link target cannot hold an environment variable, so the clone
 *       must rewrite it RELATIVE.</li>
 *   <li><b>An ABSOLUTE in-home symlink under {@code bin/cli}</b>
 *       ({@code hc-link}) — the {@code jinja2 -> /Users/.../venvs/...} shape.</li>
 *   <li><b>A generated shim with the home path in its BODY</b>
 *       ({@code hc-tool}). Not a symlink and not a shebang: a shell variable
 *       assignment, which is what {@code bin/cli} shims actually are.</li>
 *   <li><b>A shim whose target is under a SKIPPED root</b>
 *       ({@code hc-venv-tool} → {@code venvs/}). Its re-anchored path will be
 *       correct and point at nothing, which the dangling-symlink scan cannot
 *       see. It must be REPORTED.</li>
 *   <li><b>A {@code pm/} entry</b> — must survive the skip, because it is what
 *       re-provisioning runs.</li>
 *   <li><b>A {@code venvs/} entry</b> — must not survive it.</li>
 *   <li><b>An authored file under {@code skills/} recording an absolute home
 *       path</b> ({@code history/run-0001.md}) — the declared exception. It must
 *       come out of the clone BYTE-IDENTICAL and be counted, not rewritten.</li>
 * </ul>
 *
 * <h2>The digest is taken last, and that ordering is load-bearing</h2>
 *
 * Every command that touches the fixture runs before {@code treeDigest}. Most
 * skill-manager commands call {@code store.init()}, which creates directories,
 * so a digest taken earlier would be invalidated by a later read-only-looking
 * command and the byte-identity assertions downstream would fail for the wrong
 * reason.
 */
public class HomeCloneFixtureBuilt {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.fixture.built")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("home-clone", "fixture")
            .timeout("300s")
            .output("fixtureHome", "string")
            .output("projectDir", "string")
            .output("cloneStore", "string")
            .output("sourceDigest", "string")
            .output("authoredDigest", "string")
            .output("realClaudeSkills", "string")
            .output("realCodexSkills", "string")
            .output("realGeminiSkills", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (sandbox == null) {
                return NodeResult.fail("home.clone.fixture.built", "missing env.prepared.home");
            }
            Path root = Path.of(sandbox, "home-clone");
            Path fixture = root.resolve("fixture-home");
            Path unitsDir = root.resolve("units");
            Path projectDir = root.resolve("project");
            Files.createDirectories(fixture);
            Files.createDirectories(unitsDir);
            Files.createDirectories(projectDir);

            // --- installable sources ---------------------------------------
            HomeCloneSupport.scaffoldSkill(unitsDir, HomeCloneSupport.UNIT_A, "rev1");
            HomeCloneSupport.scaffoldSkill(unitsDir, HomeCloneSupport.UNIT_B, "rev1");
            HomeCloneSupport.scaffoldSkill(unitsDir, HomeCloneSupport.LINKED, "rev1");
            Files.writeString(unitsDir.resolve(HomeCloneSupport.LINKED).resolve("NOTE.md"),
                    "linked rev1\n");

            String fixtureStr = fixture.toString();

            // --- install through the real CLI ------------------------------
            ProcessRecord installA = HomeCloneSupport.sm(ctx, "install-a", fixtureStr,
                    "install", unitsDir.resolve(HomeCloneSupport.UNIT_A).toString(), "--yes");
            ProcessRecord installB = HomeCloneSupport.sm(ctx, "install-b", fixtureStr,
                    "install", unitsDir.resolve(HomeCloneSupport.UNIT_B).toString(), "--yes");
            ProcessRecord installLinked = HomeCloneSupport.sm(ctx, "install-linked", fixtureStr,
                    "install", unitsDir.resolve(HomeCloneSupport.LINKED).toString(), "--yes");

            boolean unitsInstalled = installA.exitCode() == 0 && installB.exitCode() == 0
                    && installLinked.exitCode() == 0
                    && Files.isDirectory(HomeCloneSupport.unitDir(fixture, HomeCloneSupport.UNIT_A))
                    && Files.isDirectory(HomeCloneSupport.unitDir(fixture, HomeCloneSupport.LINKED));

            // --- write the descriptor into the SOURCE ----------------------
            // The clone has to carry a descriptor that already resolves under a
            // different root, so one is written here rather than relying on the
            // one `home clone` writes at the end.
            ProcessRecord describe = HomeCloneSupport.sm(ctx, "describe-source", fixtureStr,
                    "home", "describe", "--write");
            boolean descriptorWritten = describe.exitCode() == 0
                    && Files.isRegularFile(fixture.resolve("home.runtime.json"));

            // --- the seven problem artifacts -------------------------------
            // 1. absolute in-unit symlink into the store
            Path inUnitLink = HomeCloneSupport.unitDir(fixture, HomeCloneSupport.UNIT_A)
                    .resolve(HomeCloneSupport.IN_UNIT_LINK);
            Files.createDirectories(inUnitLink.getParent());
            Files.deleteIfExists(inUnitLink);
            Files.createSymbolicLink(inUnitLink,
                    HomeCloneSupport.unitDir(fixture, HomeCloneSupport.LINKED));

            // 2. absolute in-home symlink under bin/cli
            Path linkShim = HomeCloneSupport.shim(fixture, HomeCloneSupport.LINK_SHIM);
            Files.createDirectories(linkShim.getParent());
            Files.deleteIfExists(linkShim);
            Files.createSymbolicLink(linkShim,
                    HomeCloneSupport.unitDir(fixture, HomeCloneSupport.UNIT_B).resolve("SKILL.md"));

            // 3. generated shim, home path in the BODY. Self-relative shebang,
            //    absolute path in shell — exactly the shape #20 describes.
            HomeCloneSupport.writeExecutable(
                    HomeCloneSupport.shim(fixture, HomeCloneSupport.GOOD_SHIM), """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    SM_HOME="%s"
                    exec cat "$SM_HOME/skills/%s/SKILL.md"
                    """.formatted(fixtureStr, HomeCloneSupport.UNIT_B));

            // 4. shim whose exec target is under a SKIPPED root
            HomeCloneSupport.writeExecutable(
                    HomeCloneSupport.shim(fixture, HomeCloneSupport.DANGLING_SHIM), """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    exec "%s/venvs/hc-venv/bin/hc" "$@"
                    """.formatted(fixtureStr));

            // 5. pm/ entry — must be carried
            HomeCloneSupport.write(fixture.resolve("pm/uv/0.0.0/bin/uv-marker"),
                    "bundled package manager\n");

            // 6. venvs/ entry — must NOT be carried
            HomeCloneSupport.writeExecutable(fixture.resolve("venvs/hc-venv/bin/hc"), """
                    #!%s/venvs/hc-venv/bin/python
                    print("hc")
                    """.formatted(fixtureStr));

            // 7. LEGACY-FORM STATE, i.e. a home written by a pre-epic
            //    skill-manager. Current code tokenizes every self-reference at
            //    write time, so a freshly installed fixture holds NO absolute
            //    self-reference on the state surface and the clone's
            //    re-anchoring passes would be asserted against nothing. Measured:
            //    `installed/*.projections.json` comes out with
            //    "sourcePath": "$SKILL_MANAGER_HOME/skills/hc-unit-a", and every
            //    other absolute path in it (targetRoot, destPath, origin) is
            //    genuinely EXTERNAL and correctly left alone.
            //
            //    So the fixture is aged deliberately: the token is expanded back
            //    to the absolute path a pre-epic home would have held. One
            //    artifact per structured pass in HomeCloner, plus one for the
            //    default-deny catch-all.
            List<Path> agedLedgers = new ArrayList<>();
            for (String unit : new String[] {HomeCloneSupport.UNIT_A, HomeCloneSupport.UNIT_B,
                    HomeCloneSupport.LINKED}) {
                Path ledger = fixture.resolve("installed").resolve(unit + ".projections.json");
                if (!Files.isRegularFile(ledger)) continue;
                String text = Files.readString(ledger);
                String aged = text.replace("$SKILL_MANAGER_HOME", fixtureStr);
                if (!aged.equals(text)) {
                    Files.writeString(ledger, aged);
                    agedLedgers.add(ledger);
                }
            }
            // reanchorProjectRegistrations
            HomeCloneSupport.write(fixture.resolve("projects/hc-legacy/registration.toml"), """
                    [project]
                    name = "hc-legacy"
                    project_root = "%s"
                    manifest_path = "%s/skills/%s/skill-manager.toml"
                    """.formatted(projectDir, fixtureStr, HomeCloneSupport.UNIT_A));
            // reanchorRemainingState: a root-level record no structured writer
            // models. HomeCloner.classify is default-deny, so this is STATE and
            // must be re-anchored rather than silently exempted.
            HomeCloneSupport.write(fixture.resolve("project-lock.toml"), """
                    [lock]
                    target_root = "%s/skills"
                    env_root = "%s/pm"
                    """.formatted(fixtureStr, fixtureStr));

            // 8. authored content that legitimately records an absolute home path
            Path authored = HomeCloneSupport.unitDir(fixture, HomeCloneSupport.UNIT_B)
                    .resolve(HomeCloneSupport.AUTHORED_HISTORY);
            HomeCloneSupport.write(authored, """
                    # run 0001

                    Recorded 2026-07-26. This is an append-only record of a run that
                    really happened, and the home it used was:

                        %s

                    Rewriting this line would corrupt the record.
                    """.formatted(fixtureStr));
            String authoredDigest = HomeCloneSupport.treeDigest(authored);

            // --- what the fixture must actually contain --------------------
            boolean inUnitLinkAbsolute = Files.isSymbolicLink(inUnitLink)
                    && Files.readSymbolicLink(inUnitLink).isAbsolute();
            boolean linkShimAbsolute = Files.isSymbolicLink(linkShim)
                    && Files.readSymbolicLink(linkShim).isAbsolute();
            boolean toolchainRootsPresent =
                    Files.isRegularFile(fixture.resolve("venvs/hc-venv/bin/hc"))
                            && Files.isRegularFile(fixture.resolve("pm/uv/0.0.0/bin/uv-marker"));

            // The good shim must WORK in the fixture, otherwise "it still works
            // in the clone" proves nothing.
            ProcessRecord shimHere = HomeCloneSupport.exec(ctx, "shim-in-fixture", fixtureStr,
                    List.of(HomeCloneSupport.shim(fixture, HomeCloneSupport.GOOD_SHIM).toString()));
            boolean shimWorksInFixture = shimHere.exitCode() == 0
                    && HomeCloneSupport.log(ctx, "shim-in-fixture").contains(HomeCloneSupport.UNIT_B);

            // At least one STATE file must name the fixture home, or the clone's
            // re-anchoring pass is being asserted against nothing. Real state
            // written by the real installer, not planted.
            List<String> selfRefs = HomeCloneSupport.referencesTo(fixture, fixtureStr);
            long stateRefs = selfRefs.stream().filter(r -> r.startsWith("STATE ")).count();
            long contentRefs = selfRefs.stream().filter(r -> r.startsWith("CONTENT ")).count();
            long symlinkRefs = selfRefs.stream().filter(r -> r.startsWith("SYMLINK ")).count();
            boolean fixtureHasEverySurface = stateRefs > 0 && contentRefs > 0 && symlinkRefs > 0;
            boolean legacyLedgersAged = agedLedgers.size() == 3;

            // --- baseline for the agent-home leak check --------------------
            // READ ONLY, and deliberately shallow: the immediate child names of
            // the real agent skill roots. That is where `install` projects, so it
            // is where a #18-class leak lands.
            Path realHome = Path.of(System.getProperty("user.home"));
            String realClaude = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".claude/skills")));
            String realCodex = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".codex/skills")));
            String realGemini = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".gemini/skills")));

            // --- the digest, taken LAST ------------------------------------
            String sourceDigest = HomeCloneSupport.treeDigest(fixture);

            boolean pass = unitsInstalled && descriptorWritten && inUnitLinkAbsolute
                    && linkShimAbsolute && toolchainRootsPresent && shimWorksInFixture
                    && fixtureHasEverySurface && legacyLedgersAged;
            return (pass
                    ? NodeResult.pass("home.clone.fixture.built")
                    : NodeResult.fail("home.clone.fixture.built",
                            "unitsInstalled=" + unitsInstalled
                                    + " descriptorWritten=" + descriptorWritten
                                    + " inUnitLinkAbsolute=" + inUnitLinkAbsolute
                                    + " linkShimAbsolute=" + linkShimAbsolute
                                    + " toolchainRootsPresent=" + toolchainRootsPresent
                                    + " shimWorksInFixture=" + shimWorksInFixture
                                    + " state=" + stateRefs + " content=" + contentRefs
                                    + " symlink=" + symlinkRefs
                                    + " agedLedgers=" + agedLedgers.size()))
                    .process(installA).process(installB).process(installLinked)
                    .process(describe).process(shimHere)
                    .assertion("fixture_units_installed_by_the_real_cli", unitsInstalled)
                    .assertion("fixture_carries_a_home_runtime_descriptor", descriptorWritten)
                    .assertion("fixture_in_unit_symlink_is_absolute_into_the_store", inUnitLinkAbsolute)
                    .assertion("fixture_bin_cli_symlink_is_absolute_into_the_home", linkShimAbsolute)
                    .assertion("fixture_has_both_a_venvs_and_a_pm_entry", toolchainRootsPresent)
                    .assertion("fixture_shim_execs_correctly_before_cloning", shimWorksInFixture)
                    .assertion("fixture_names_its_own_home_on_state_content_and_symlink_surfaces",
                            fixtureHasEverySurface)
                    .assertion("fixture_ledgers_aged_to_pre_epic_absolute_form", legacyLedgersAged)
                    .metric("selfReferencingFiles", selfRefs.size())
                    .metric("stateSelfReferences", (int) stateRefs)
                    .metric("contentSelfReferences", (int) contentRefs)
                    .metric("symlinkSelfReferences", (int) symlinkRefs)
                    .publish("fixtureHome", fixtureStr)
                    .publish("projectDir", projectDir.toString())
                    .publish("cloneStore", HomeCloneSupport.storeOf(projectDir).toString())
                    .publish("sourceDigest", sourceDigest)
                    .publish("authoredDigest", authoredDigest)
                    .publish("realClaudeSkills", realClaude)
                    .publish("realCodexSkills", realCodex)
                    .publish("realGeminiSkills", realGemini);
        });
    }
}
