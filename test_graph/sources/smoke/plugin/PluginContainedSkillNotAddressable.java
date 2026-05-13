///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Plugin-specific contract: a contained skill name (e.g. {@code hello-impl}
 * inside {@code hello-plugin}) is NOT separately addressable via the
 * registry. Attempting {@code skill-manager install hello-impl} after
 * the parent plugin is installed must fail — the contained skill lives
 * inside the plugin and has no independent identity.
 *
 * <p>No skill parallel — bare skills don't have nested children.
 */
public class PluginContainedSkillNotAddressable {
    static final NodeSpec SPEC = NodeSpec.of("plugin.contained.skill.not.addressable")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.plugin.installed")
            .tags("plugin", "registry")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("plugin.contained.skill.not.addressable",
                        "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // hello-impl is the contained skill of hello-plugin; the
            // registry should report it doesn't exist as a top-level unit.
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "hello-impl",
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "install-contained", pb);
            int rc = proc.exitCode();

            // Non-zero exit = contract holds. The contained skill is
            // unaddressable from the registry side.
            boolean pass = rc != 0;
            return (pass
                    ? NodeResult.pass("plugin.contained.skill.not.addressable")
                    : NodeResult.fail("plugin.contained.skill.not.addressable",
                            "expected non-zero exit, got rc=" + rc))
                    .process(proc)
                    .assertion("install_rejected", pass)
                    .metric("exitCode", rc);
        });
    }
}
