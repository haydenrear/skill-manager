///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * External regression for ISSUE-86. Resolves two profiles for one skill
 * project checkout and verifies each selected profile gets its own lock,
 * child Skill Manager home, and agent-home projection.
 */
public class ProjectProfilesResolved {
    static final NodeSpec SPEC = NodeSpec.of("project.profiles.resolved")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("project", "profiles", "issue-86")
            .timeout("120s")
            .output("projectName", "string")
            .output("projectDir", "string")
            .output("devChildHome", "string")
            .output("reviewChildHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("project.profiles.resolved", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path projectDir;
            try {
                projectDir = Files.createTempDirectory("sm-project-profiles-");
                Path units = projectDir.resolve("units");
                Path common = scaffoldSkill(units, "tg-profile-common");
                Path dev = scaffoldSkill(units, "tg-profile-dev");
                Path review = scaffoldSkill(units, "tg-profile-review");
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-profiled-project"

                        [skills.common]
                        source = "%s"

                        [skills.dev]
                        source = "%s"

                        [skills.review]
                        source = "%s"

                        [envs.dev]
                        python = "3.12"
                        tools = ["pytest"]

                        [envs.review]
                        python = "3.11"
                        tools = ["ruff"]

                        [profiles.dev]
                        skills = ["common", "dev"]
                        envs = ["dev"]

                        [profiles.review]
                        skills = ["common", "review"]
                        envs = ["review"]
                        """.formatted(common, dev, review));
            } catch (Exception e) {
                return NodeResult.fail("project.profiles.resolved",
                        "could not scaffold profile project: " + e.getMessage());
            }

            ProcessRecord listProfiles = run(ctx, "profiles-list", home, repoRoot, sm,
                    "project", "profiles", "list", "--project-dir", projectDir.toString());
            ProcessRecord resolveDev = run(ctx, "resolve-dev", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--profile", "dev",
                    "--project-dir", projectDir.toString());
            ProcessRecord resolveReview = run(ctx, "resolve-review", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--profile", "review",
                    "--project-dir", projectDir.toString());

            Path devHome = projectDir.resolve(".skill-manager/profiles/dev");
            Path reviewHome = projectDir.resolve(".skill-manager/profiles/review");
            Path devLock = Path.of(home, "projects", "tg-profiled-project--dev", "project-lock.toml");
            Path reviewLock = Path.of(home, "projects", "tg-profiled-project--review", "project-lock.toml");
            String devLockText = read(devLock);
            String reviewLockText = read(reviewLock);
            String profilesLog = readLog(ctx, "profiles-list");

            boolean listShowsProfiles = listProfiles.exitCode() == 0
                    && profilesLog.contains("dev")
                    && profilesLog.contains("review");
            boolean devLockOk = Files.isRegularFile(devLock)
                    && devLockText.contains("profile = \"dev\"")
                    && devLockText.contains("name = \"tg-profile-common\"")
                    && devLockText.contains("name = \"tg-profile-dev\"")
                    && !devLockText.contains("name = \"tg-profile-review\"");
            boolean reviewLockOk = Files.isRegularFile(reviewLock)
                    && reviewLockText.contains("profile = \"review\"")
                    && reviewLockText.contains("name = \"tg-profile-common\"")
                    && reviewLockText.contains("name = \"tg-profile-review\"")
                    && !reviewLockText.contains("name = \"tg-profile-dev\"");
            boolean devHomeOk = Files.isRegularFile(devHome.resolve("skills/tg-profile-common/SKILL.md"))
                    && Files.isRegularFile(devHome.resolve("skills/tg-profile-dev/SKILL.md"))
                    && !Files.exists(devHome.resolve("skills/tg-profile-review"))
                    && Files.isDirectory(devHome.resolve("agents/codex"))
                    && Files.isDirectory(devHome.resolve("agents/claude"))
                    && Files.isDirectory(devHome.resolve("agents/gemini"));
            boolean reviewHomeOk = Files.isRegularFile(reviewHome.resolve("skills/tg-profile-common/SKILL.md"))
                    && Files.isRegularFile(reviewHome.resolve("skills/tg-profile-review/SKILL.md"))
                    && !Files.exists(reviewHome.resolve("skills/tg-profile-dev"))
                    && Files.isDirectory(reviewHome.resolve("agents/codex"))
                    && Files.isDirectory(reviewHome.resolve("agents/claude"))
                    && Files.isDirectory(reviewHome.resolve("agents/gemini"));
            // Independence. The two profiles both claim tg-profile-common, so under
            // the pre-epic symlink child home they were literally the SAME
            // directory — "isolated" above (all follow-links) was true then too.
            // What isolation has to mean is that they are different trees and
            // that neither is the parent store's tree.
            Path parentHome = Path.of(home);
            java.util.List<Path> parentUnitRoots = parentUnitRoots(parentHome);
            Path devCommon = devHome.resolve("skills/tg-profile-common");
            Path reviewCommon = reviewHome.resolve("skills/tg-profile-common");
            boolean profileUnitsAreRealCopies = isRealDirectory(devCommon)
                    && isRealDirectory(reviewCommon)
                    && !realPathInsideAny(devCommon, parentUnitRoots)
                    && !realPathInsideAny(reviewCommon, parentUnitRoots)
                    && !sameRealPath(devCommon, reviewCommon);
            boolean profileUnitsRecordedAsCopies =
                    read(devHome.resolve(".materialization/skill/tg-profile-common.json"))
                            .contains("\"mode\" : \"COPY\"")
                            && read(reviewHome.resolve(".materialization/skill/tg-profile-common.json"))
                            .contains("\"mode\" : \"COPY\"");

            // Empirically: an edit in one profile's child home must reach
            // neither the other profile nor the parent store. Restored after,
            // so nothing downstream sees a modified child home.
            Path devCommonSkill = devCommon.resolve("SKILL.md");
            Path reviewCommonSkill = reviewCommon.resolve("SKILL.md");
            Path parentCommonSkill = parentHome.resolve("skills/tg-profile-common/SKILL.md");
            String devBefore = read(devCommonSkill);
            String reviewBefore = read(reviewCommonSkill);
            String parentBefore = read(parentCommonSkill);
            boolean profileEditIsolated;
            try {
                Files.writeString(devCommonSkill, devBefore + "AGENT-EDIT-PROBE\n");
                profileEditIsolated = !devBefore.isEmpty()
                        && read(devCommonSkill).contains("AGENT-EDIT-PROBE")
                        && read(reviewCommonSkill).equals(reviewBefore)
                        && !read(reviewCommonSkill).contains("AGENT-EDIT-PROBE")
                        && read(parentCommonSkill).equals(parentBefore)
                        && !read(parentCommonSkill).contains("AGENT-EDIT-PROBE");
                Files.writeString(devCommonSkill, devBefore);
            } catch (Exception e) {
                profileEditIsolated = false;
            }
            boolean profileEditRestored = read(devCommonSkill).equals(devBefore);

            boolean registryOk = childRecord(home, "project_tg-profiled-project_profile_dev")
                    .contains("\"id\" : \"project:tg-profiled-project:profile:dev\"")
                    && childRecord(home, "project_tg-profiled-project_profile_review")
                    .contains("\"id\" : \"project:tg-profiled-project:profile:review\"");

            boolean pass = listShowsProfiles
                    && resolveDev.exitCode() == 0
                    && resolveReview.exitCode() == 0
                    && devLockOk
                    && reviewLockOk
                    && devHomeOk
                    && reviewHomeOk
                    && profileUnitsAreRealCopies
                    && profileUnitsRecordedAsCopies
                    && profileEditIsolated
                    && profileEditRestored
                    && registryOk;

            return (pass
                    ? NodeResult.pass("project.profiles.resolved")
                    : NodeResult.fail("project.profiles.resolved",
                            "profiles=" + listProfiles.exitCode()
                                    + " dev=" + resolveDev.exitCode()
                                    + " review=" + resolveReview.exitCode()
                                    + " listShowsProfiles=" + listShowsProfiles
                                    + " devLockOk=" + devLockOk
                                    + " reviewLockOk=" + reviewLockOk
                                    + " devHomeOk=" + devHomeOk
                                    + " reviewHomeOk=" + reviewHomeOk
                                    + " profileUnitsAreRealCopies=" + profileUnitsAreRealCopies
                                    + " profileUnitsRecordedAsCopies=" + profileUnitsRecordedAsCopies
                                    + " profileEditIsolated=" + profileEditIsolated
                                    + " profileEditRestored=" + profileEditRestored
                                    + " registryOk=" + registryOk))
                    .process(listProfiles)
                    .process(resolveDev)
                    .process(resolveReview)
                    .assertion("profiles_list_reports_declared_profiles", listShowsProfiles)
                    .assertion("dev_profile_resolve_ok", resolveDev.exitCode() == 0)
                    .assertion("review_profile_resolve_ok", resolveReview.exitCode() == 0)
                    .assertion("profile_locks_are_distinct_and_selected", devLockOk && reviewLockOk)
                    .assertion("dev_profile_child_home_is_isolated", devHomeOk)
                    .assertion("review_profile_child_home_is_isolated", reviewHomeOk)
                    .assertion("shared_unit_is_a_distinct_copy_in_each_profile_child_home",
                            profileUnitsAreRealCopies)
                    .assertion("profile_child_home_units_recorded_as_copies", profileUnitsRecordedAsCopies)
                    .assertion("profile_child_home_edit_reaches_neither_sibling_nor_parent",
                            profileEditIsolated)
                    .assertion("profile_child_home_edit_probe_restored", profileEditRestored)
                    .assertion("parent_registry_records_profile_child_homes", registryOk)
                    .publish("projectName", "tg-profiled-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("devChildHome", devHome.toString())
                    .publish("reviewChildHome", reviewHome.toString());
        });
    }

    private static Path scaffoldSkill(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: graph fixture
                ---
                Body.
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "graph fixture"
                """.formatted(name));
        return dir;
    }

    private static ProcessRecord run(NodeContext ctx, String label, String home,
                                     Path repoRoot, Path sm, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = sm.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        return Procs.run(ctx, label, pb);
    }

    private static String childRecord(String home, String safeId) {
        return read(Path.of(home, "child-homes", safeId, "child-home.json"));
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
        }
    }

    /** Real directory, not a symlink — the check {@code Files.isDirectory} cannot make. */
    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private static boolean realPathInside(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The parent store's four unit roots. A child home may legitimately sit
     * under the parent home, so "inside the parent store's units" is the
     * isolation boundary, not "inside the parent home".
     */
    private static java.util.List<Path> parentUnitRoots(Path parentHome) {
        return java.util.List.of(
                parentHome.resolve("skills"),
                parentHome.resolve("plugins"),
                parentHome.resolve("docs"),
                parentHome.resolve("harnesses"));
    }

    private static boolean realPathInsideAny(Path path, java.util.List<Path> roots) {
        for (Path root : roots) {
            if (realPathInside(path, root)) return true;
        }
        return false;
    }

    private static boolean sameRealPath(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "";
        }
    }
}
