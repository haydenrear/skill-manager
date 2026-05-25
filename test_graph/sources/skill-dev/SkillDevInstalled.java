///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class SkillDevInstalled {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "registry.up")
            .tags("skill-dev", "install")
            .timeout("120s")
            .output("skillDev", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || gatewayPort == null || registryUrl == null) {
                return NodeResult.fail("skill-dev.installed", "missing upstream context");
            }
            Map<String, String> env = SkillDevGraphSupport.env(
                    home, claudeHome, codexHome, "http://127.0.0.1:" + gatewayPort, registryUrl);
            Path repoRoot = SkillDevGraphSupport.repoRoot();
            ProcessRecord proc = SkillDevGraphSupport.run(ctx, "install-skill-dev", env, repoRoot,
                    SkillDevGraphSupport.skillManager().toString(), "install",
                    "file://" + repoRoot.resolve("skill-dev-skill"), "--yes");
            Path bin = SkillDevGraphSupport.skillDev(Path.of(home));
            boolean installed = proc.exitCode() == 0 && Files.isExecutable(bin);
            return (installed ? NodeResult.pass("skill-dev.installed")
                    : NodeResult.fail("skill-dev.installed", "rc=" + proc.exitCode() + " bin=" + Files.exists(bin)))
                    .process(proc)
                    .assertion("install_ok", proc.exitCode() == 0)
                    .assertion("skill_dev_executable", Files.isExecutable(bin))
                    .publish("skillDev", bin.toString());
        });
    }
}
