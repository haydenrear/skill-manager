///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * External regression for ISSUE-75-4. Resolves a project [[libs]] git source
 * through the public CLI and validates checkout materialization, gitignore,
 * project-lock provenance, and project show reporting.
 */
public class ProjectLibsResolved {
    static final NodeSpec SPEC = NodeSpec.of("project.libs.resolved")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("project", "libs", "git", "issue-75")
            .timeout("120s")
            .output("projectName", "string")
            .output("projectDir", "string")
            .output("libSha", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("project.libs.resolved", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path projectDir;
            Path noOriginProjectDir;
            Path libRepo;
            String libSha;
            try {
                projectDir = Files.createTempDirectory("sm-project-libs-");
                libRepo = Files.createTempDirectory("sm-project-lib-upstream-");
                Files.writeString(libRepo.resolve("marker.txt"), "project lib fixture\n");
                git(libRepo, "init");
                git(libRepo, "checkout", "-b", "main");
                git(libRepo, "add", ".");
                git(libRepo, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                        "commit", "-m", "initial");
                libSha = readCommand(libRepo, "git", "rev-parse", "HEAD").trim();
                Files.writeString(projectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-libs-project"

                        [[libs]]
                        name = "fixture-lib"
                        source = "git+file://%s#main"
                        """.formatted(libRepo));

                noOriginProjectDir = Files.createTempDirectory("sm-project-libs-no-origin-");
                Path orphanCheckout = noOriginProjectDir.resolve("libs/orphan-lib");
                Files.createDirectories(orphanCheckout);
                Files.writeString(orphanCheckout.resolve("marker.txt"), "orphan checkout\n");
                git(orphanCheckout, "init");
                git(orphanCheckout, "checkout", "-b", "main");
                git(orphanCheckout, "add", ".");
                git(orphanCheckout, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                        "commit", "-m", "orphan");
                Files.writeString(noOriginProjectDir.resolve("skill-project.toml"), """
                        [project]
                        name = "tg-libs-no-origin-project"

                        [[libs]]
                        name = "orphan-lib"
                        source = "git+file://%s"
                        """.formatted(libRepo));
            } catch (Exception e) {
                return NodeResult.fail("project.libs.resolved",
                        "could not scaffold project lib fixture: " + e.getMessage());
            }

            ProcessRecord resolve = run(ctx, "resolve-libs", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--resolve-libs",
                    "--project-dir", projectDir.toString());
            ProcessRecord show = run(ctx, "show", home, repoRoot, sm,
                    "project", "show", "tg-libs-project");
            ProcessRecord noOrigin = run(ctx, "resolve-libs-no-origin", home, repoRoot, sm,
                    "project", "resolve", "--skip-gateway", "--resolve-libs",
                    "--project-dir", noOriginProjectDir.toString());

            Path checkout = projectDir.resolve("libs/fixture-lib");
            Path lock = Path.of(home, "projects", "tg-libs-project", "project-lock.toml");
            String lockText = read(lock);
            String showLog = readLog(ctx, "show");
            String noOriginLog = readLog(ctx, "resolve-libs-no-origin");
            String checkoutSha = "";
            try {
                checkoutSha = readCommand(checkout, "git", "rev-parse", "HEAD").trim();
            } catch (Exception ignored) {}

            boolean checkoutRendered = Files.isRegularFile(checkout.resolve("marker.txt"))
                    && Files.isDirectory(checkout.resolve(".git"))
                    && libSha.equals(checkoutSha);
            boolean gitignored = read(projectDir.resolve(".gitignore")).contains("libs/");
            boolean lockRendered = Files.isRegularFile(lock)
                    && lockText.contains("[[libs]]")
                    && lockText.contains("name = \"fixture-lib\"")
                    && lockText.contains("source = \"git+file://")
                    && lockText.contains("ref = \"main\"")
                    && lockText.contains("resolved_sha = \"" + libSha + "\"")
                    && lockText.contains("checkout_dir = \"" + checkout);
            boolean showReportsLibLock = show.exitCode() == 0 && showLog.contains("lib locks:1");
            boolean noOriginRejected = noOrigin.exitCode() != 0
                    && noOriginLog.contains("has no origin");

            boolean pass = resolve.exitCode() == 0
                    && checkoutRendered
                    && gitignored
                    && lockRendered
                    && showReportsLibLock
                    && noOriginRejected;

            return (pass
                    ? NodeResult.pass("project.libs.resolved")
                    : NodeResult.fail("project.libs.resolved",
                            "resolve=" + resolve.exitCode()
                                    + " show=" + show.exitCode()
                                    + " noOrigin=" + noOrigin.exitCode()
                                    + " checkout=" + checkoutRendered
                                    + " gitignored=" + gitignored
                                    + " lock=" + lockRendered
                                    + " showLibLock=" + showReportsLibLock
                                    + " noOriginRejected=" + noOriginRejected
                                    + " expectedSha=" + libSha
                                    + " checkoutSha=" + checkoutSha))
                    .process(resolve)
                    .process(show)
                    .process(noOrigin)
                    .assertion("resolve_libs_command_ok", resolve.exitCode() == 0)
                    .assertion("lib_checkout_materialized_under_project_libs", checkoutRendered)
                    .assertion("libs_directory_gitignored", gitignored)
                    .assertion("project_lock_records_lib_provenance", lockRendered)
                    .assertion("project_show_reports_lib_lock_count", showReportsLibLock)
                    .assertion("existing_checkout_without_origin_rejected", noOriginRejected)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("showExitCode", show.exitCode())
                    .metric("noOriginResolveExitCode", noOrigin.exitCode())
                    .publish("projectName", "tg-libs-project")
                    .publish("projectDir", projectDir.toString())
                    .publish("libSha", libSha);
        });
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

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException("git " + String.join(" ", args)
                    + " failed with " + rc + ": " + output);
        }
    }

    private static String readCommand(Path repo, String... args) throws Exception {
        Process p = new ProcessBuilder(args).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException(String.join(" ", args) + " failed with " + rc + ": " + output);
        }
        return output;
    }

    private static String readLog(NodeContext ctx, String label) {
        try {
            return Files.readString(Procs.logFile(ctx, label));
        } catch (Exception e) {
            return "";
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
