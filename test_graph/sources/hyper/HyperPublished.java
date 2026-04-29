///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Registers the {@code hyper-experiments} skill with the local registry as
 * a github-pointer publish — the default backend in production.
 *
 * <p>Two-phase test:
 * <ol>
 *   <li>First call attempts {@code skill-manager publish --upload-tarball}.
 *       The server has {@code SKILL_REGISTRY_ALLOW_FILE_UPLOAD=false} via
 *       {@code env.hyper.prepared}, so the multipart endpoint returns 403.
 *       The CLI propagates the failure and exits non-zero — we assert that.</li>
 *   <li>Second call drops {@code --upload-tarball} and registers via
 *       {@code POST /skills/register}; expected to succeed.</li>
 * </ol>
 *
 * <p>Together those two calls prove the server-side gate is honored AND
 * that the github register path actually persists a row.
 */
public class HyperPublished {
    static final NodeSpec SPEC = NodeSpec.of("hyper.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.checkout", "registry.up", "ci.logged.in", "jwt.valid")
            .tags("hyper", "registry", "publish", "github")
            .timeout("120s")
            .output("skillName", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String skillDir = ctx.get("hyper.checkout", "skillDir").orElse(null);
            if (home == null || registryUrl == null || skillDir == null) {
                return NodeResult.fail("hyper.published", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Hard-coded so HYPER_LOCAL_DIR mode (which copies files without
            // a .git/) still has a github URL to register. HyperCheckout's
            // env override matches this default for the clone path.
            String githubUrl = System.getenv("HYPER_GIT_URL");
            if (githubUrl == null || githubUrl.isBlank()) {
                githubUrl = "https://github.com/haydenrear/hyper-experiments-skill";
            }
            // hyper.checkout shallow-clones --branch <ref>, so the tag for
            // the toml's [skill].version isn't necessarily reachable. Pass
            // --ref so the CLI skips its tag-detection and the server
            // resolves the branch/SHA directly.
            String ref = System.getenv("HYPER_GIT_REF");
            if (ref == null || ref.isBlank()) ref = "main";

            // Phase 1: legacy multipart upload — must be rejected by the
            // gated /skills/{name}/{version} endpoint.
            ProcessBuilder rejectPb = new ProcessBuilder(
                    sm.toString(), "publish", skillDir,
                    "--upload-tarball",
                    "--registry", registryUrl);
            rejectPb.environment().put("SKILL_MANAGER_HOME", home);
            rejectPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord rejectProc = Procs.run(ctx, "publish-tarball-rejected", rejectPb);
            int rejectRc = rejectProc.exitCode();
            boolean uploadRejected = rejectRc != 0;

            // Phase 2: github-pointer register — the production path.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skillDir,
                    "--github-url", githubUrl,
                    "--ref", ref,
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "publish", pb);
            int rc = proc.exitCode();
            boolean registerOk = rc == 0;

            boolean pass = uploadRejected && registerOk;
            NodeResult result = pass
                    ? NodeResult.pass("hyper.published")
                    : NodeResult.fail("hyper.published",
                            "uploadRejected=" + uploadRejected
                                    + " registerOk=" + registerOk
                                    + " (rejectRc=" + rejectRc + ", registerRc=" + rc + ")");
            return result
                    .process(rejectProc)
                    .process(proc)
                    .assertion("upload_tarball_rejected", uploadRejected)
                    .assertion("github_register_ok", registerOk)
                    .metric("rejectExitCode", rejectRc)
                    .metric("registerExitCode", rc)
                    .publish("skillName", "hyper-experiments");
        });
    }
}
