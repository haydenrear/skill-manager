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
 * <p>Drives {@code skill-manager publish} from inside the checkout produced
 * by {@code hyper.checkout}; the CLI auto-detects {@code remote.origin.url}
 * and we pin the ref via {@code --ref} (default {@code main}, override via
 * {@code HYPER_GIT_REF} on {@code hyper.checkout}). The server fetches the
 * toml at that ref over the GitHub REST API and persists a metadata-only
 * row keyed on the resolved SHA.
 */
public class HyperPublished {
    static final NodeSpec SPEC = NodeSpec.of("hyper.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.checkout", "registry.up", "ci.logged.in", "jwt.valid")
            .tags("hyper", "registry", "publish", "github")
            .timeout("90s")
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

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skillDir,
                    "--github-url", githubUrl,
                    "--ref", ref,
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "publish", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("hyper.published")
                    : NodeResult.fail("hyper.published", "publish exited " + rc);
            return result
                    .process(proc)
                    .assertion("published_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("skillName", "hyper-experiments");
        });
    }
}
