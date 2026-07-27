///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * {@code ToolchainRootsAreNeverShared},
 * {@code AHomeMissingItsToolchainsStillHasItsPackageManagers} and
 * {@code EveryHomeMissingItsToolchainsSaysSo}: the toolchain roots are
 * <b>absent</b>, not stale, and their absence is <b>reported</b>.
 *
 * <p>Three separate claims, and each one fails a different wrong
 * implementation:
 *
 * <ul>
 *   <li><b>Absent.</b> {@code venvs/}, {@code tools/} and {@code npm/} do not
 *       exist in the copy. Copying them costs 1.5 GB on a real home; SHARING
 *       them — the option considered and rejected on #20 — is worse, because
 *       installers write into them ({@code PipBackend} points
 *       {@code UV_TOOL_DIR} at {@code venvs/}, {@code SkillScriptBackend} hands
 *       {@code SKILL_MANAGER_CACHE_DIR} to arbitrary install scripts) so one
 *       project's {@code skill-manager cli} would mutate another's toolchain
 *       through unbounded user code.</li>
 *   <li><b>{@code pm/} is carried.</b> It holds the bundled node and uv, which
 *       is what re-provisioning RUNS. Skipping it leaves a clone permanently
 *       unable to rebuild what it is permanently missing — strictly worse than
 *       either copying or sharing.</li>
 *   <li><b>Reported.</b> The shim whose exec target is under a skipped root has
 *       a re-anchored path that is CORRECT and points at nothing. It is a path
 *       in a script body, so the dangling-symlink scan cannot see it; without
 *       {@code danglingReferences} the clone hands over broken CLI tools while
 *       exiting 0. Asserted as an exact count against the shim this fixture
 *       planted, not as "greater than zero", so a report that names the wrong
 *       thing fails.</li>
 * </ul>
 */
public class HomeCloneToolchainsReprovisionable {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.toolchains.reprovisionable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.cloned.into.project")
            .tags("home-clone", "toolchain")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String cloneStoreRaw = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
            String cloneJson = ctx.get("home.cloned.into.project", "cloneJson").orElse(null);
            if (cloneStoreRaw == null || fixture == null || cloneJson == null) {
                return NodeResult.fail("home.clone.toolchains.reprovisionable",
                        "missing upstream context");
            }
            Path cloneStore = Path.of(cloneStoreRaw);
            Path fixtureHome = Path.of(fixture);

            // --- absent ----------------------------------------------------
            // "Empty or absent", not "absent". Every skill-manager command calls
            // SkillStore.init(), which creates the standard layout, so a later
            // read-only-looking command re-creates venvs/ as an empty directory.
            // Measured, not assumed: an earlier revision of this node asserted
            // non-existence and failed on `bindings list`. The property that
            // matters is that no toolchain CONTENT was carried, which is what
            // this checks — and it is still violated by a clone that copies them.
            boolean venvsAbsent = emptyOrAbsent(cloneStore.resolve("venvs"));
            boolean toolsAbsent = emptyOrAbsent(cloneStore.resolve("tools"));
            boolean npmAbsent = emptyOrAbsent(cloneStore.resolve("npm"));
            boolean cacheAbsent = emptyOrAbsent(cloneStore.resolve("cache"));
            // Named directly as well, so the assertion cannot be satisfied by a
            // clone that copies venvs/ under some other name.
            boolean fixtureVenvNotCarried =
                    !Files.exists(cloneStore.resolve("venvs/hc-venv"), LinkOption.NOFOLLOW_LINKS)
                            && !Files.exists(cloneStore.resolve("venvs/hc-venv/bin/hc"),
                                    LinkOption.NOFOLLOW_LINKS);
            boolean toolchainRootsAbsent = venvsAbsent && toolsAbsent && npmAbsent && cacheAbsent
                    && fixtureVenvNotCarried;

            // Absent, not a symlink at the source's. "Does not exist" already
            // rules that out, but state it separately: a future clone that
            // "helpfully" linked them would satisfy an isExecutable check and
            // silently share mutable state, which is the failure #20 rejected.
            boolean venvsIsNotALinkAtTheSource =
                    !Files.isSymbolicLink(cloneStore.resolve("venvs"))
                            && Files.isRegularFile(fixtureHome.resolve("venvs/hc-venv/bin/hc"));

            // --- pm/ carried -----------------------------------------------
            boolean packageManagersCarried =
                    Files.isRegularFile(cloneStore.resolve("pm/uv/0.0.0/bin/uv-marker"))
                            && !Files.isSymbolicLink(cloneStore.resolve("pm"));

            // --- reported --------------------------------------------------
            // Exactly one dangling reference: the hc-venv-tool shim. Exact, not
            // "at least one" — a report that counts something else has not
            // reported this.
            int danglingReferences = HomeCloneSupport.jsonInt(cloneJson, "danglingReferences");
            int danglingLinks = HomeCloneSupport.jsonInt(cloneJson, "danglingLinks");
            boolean missingToolchainIsReported = danglingReferences == 1;

            // And the shim is really broken, so the report is about something
            // real rather than a number that happens to be 1.
            Path danglingShim = HomeCloneSupport.shim(cloneStore, HomeCloneSupport.DANGLING_SHIM);
            String shimBody = HomeCloneSupport.read(danglingShim);
            boolean danglingShimWasReanchored = shimBody.contains(cloneStoreRaw)
                    && !shimBody.contains(fixture);
            boolean danglingShimTargetReallyMissing =
                    !Files.exists(cloneStore.resolve("venvs/hc-venv/bin/hc"));

            boolean pass = toolchainRootsAbsent && venvsIsNotALinkAtTheSource
                    && packageManagersCarried && missingToolchainIsReported
                    && danglingShimWasReanchored && danglingShimTargetReallyMissing;
            return (pass
                    ? NodeResult.pass("home.clone.toolchains.reprovisionable")
                    : NodeResult.fail("home.clone.toolchains.reprovisionable",
                            "venvsAbsent=" + venvsAbsent + " toolsAbsent=" + toolsAbsent
                                    + " npmAbsent=" + npmAbsent + " cacheAbsent=" + cacheAbsent
                                    + " fixtureVenvNotCarried=" + fixtureVenvNotCarried
                                    + " venvsIsNotALinkAtTheSource=" + venvsIsNotALinkAtTheSource
                                    + " packageManagersCarried=" + packageManagersCarried
                                    + " danglingReferences=" + danglingReferences
                                    + " danglingShimWasReanchored=" + danglingShimWasReanchored
                                    + " danglingShimTargetReallyMissing="
                                    + danglingShimTargetReallyMissing))
                    .assertion("venvs_tools_npm_and_cache_carry_no_content_in_the_clone",
                            toolchainRootsAbsent)
                    .assertion("the_clone_does_not_link_venvs_at_the_source_home",
                            venvsIsNotALinkAtTheSource)
                    .assertion("pm_is_carried_so_the_clone_can_reprovision", packageManagersCarried)
                    .assertion("the_shim_whose_target_was_skipped_is_reported_exactly_once",
                            missingToolchainIsReported)
                    .assertion("the_skipped_shim_was_reanchored_to_the_clone_not_the_source",
                            danglingShimWasReanchored)
                    .assertion("the_skipped_shims_target_really_is_missing",
                            danglingShimTargetReallyMissing)
                    .metric("danglingReferences", danglingReferences)
                    .metric("danglingLinks", danglingLinks);
        });
    }

    /** True when {@code dir} does not exist or holds nothing. */
    private static boolean emptyOrAbsent(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return true;
        if (Files.isSymbolicLink(dir)) return false;
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
