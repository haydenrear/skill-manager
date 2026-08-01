///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

/** Probe node for Java-side rerun(false) describe and execution wiring. */
public class RerunJavaProbe {
    static final NodeSpec SPEC = NodeSpec.of("rerun.java.probe")
            .kind(NodeSpec.Kind.EVIDENCE)
            .tags("metadata", "java")
            .timeout("30s")
            .rerun(false)
            .output("rerun", "boolean")
            .output("runtime", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx ->
                NodeResult.pass("rerun.java.probe")
                        .publish("rerun", "false")
                        .publish("runtime", "java"));
    }
}
