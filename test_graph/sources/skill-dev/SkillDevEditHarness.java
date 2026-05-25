///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java
//SOURCES SkillDevEditSkill.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

public class SkillDevEditHarness {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.edit.harness")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("skill-dev.units.installed")
            .tags("skill-dev", "harness")
            .timeout("120s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> SkillDevEditNode.run(ctx, "skill-dev.edit.harness",
                SkillDevGraphSupport.HARNESS,
                Path.of(ctx.get("env.prepared", "home").orElseThrow(), "harnesses", SkillDevGraphSupport.HARNESS,
                        "README.md"),
                "HARNESS_EDIT_APPLIED"));
    }
}
