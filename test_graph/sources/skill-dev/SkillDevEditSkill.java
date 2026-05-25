///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

public class SkillDevEditSkill {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.edit.skill")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("skill-dev.units.installed")
            .tags("skill-dev", "skill")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> SkillDevEditNode.run(ctx, "skill-dev.edit.skill",
                SkillDevGraphSupport.SKILL,
                Path.of(ctx.get("env.prepared", "home").orElseThrow(), "skills", SkillDevGraphSupport.SKILL, "SKILL.md"),
                "SKILL_EDIT_APPLIED"));
    }
}

final class SkillDevEditNode {
    static NodeResult run(com.hayden.testgraphsdk.sdk.NodeContext ctx, String nodeId,
                          String unit, Path storeFile, String marker) throws Exception {
        String home = ctx.get("env.prepared", "home").orElseThrow();
        String claudeHome = ctx.get("env.prepared", "claudeHome").orElseThrow();
        String codexHome = ctx.get("env.prepared", "codexHome").orElseThrow();
        String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElseThrow();
        String registryUrl = ctx.get("registry.up", "baseUrl").orElseThrow();
        Path project = Path.of(ctx.get("skill-dev.units.installed", "projectDir").orElseThrow());
        Map<String, String> env = SkillDevGraphSupport.env(
                home, claudeHome, codexHome, "http://127.0.0.1:" + gatewayPort, registryUrl);
        java.util.List<ProcessRecord> procs = new ArrayList<>();
        boolean ok = SkillDevGraphSupport.runEditCycle(ctx, env, project, Path.of(home), unit, storeFile, marker, procs);
        NodeResult result = ok ? NodeResult.pass(nodeId) : NodeResult.fail(nodeId, "edit cycle failed for " + unit);
        for (ProcessRecord p : procs) result.process(p);
        return result
                .assertion("edit_cycle_ok", ok)
                .assertion("store_contains_marker", java.nio.file.Files.readString(storeFile).contains(marker));
    }
}
