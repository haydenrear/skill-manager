///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A dangling agent link INTO the home (DEF-OHV-002, OHV-2 (b)): the subject's
 * {@code .codex/skills/hv-gone} points at {@code <store>/skills/hv-gone}, which
 * is not there — the shape a retirement leaves. Measured on this repo's own
 * checkout ({@code .claude/.codex/.gemini/skills/skill-manager}) and in 75
 * entries across 25 checkouts after the {@code skill-dev-skill} retirement.
 *
 * <p>Before OHV-2 neither reader named it: {@code home verify} walks the store,
 * and {@code home repair}'s agent-link arm only asks whether a link reaches
 * ANOTHER home. Now {@code home repair} names {@code DANGLING_AGENT_LINK},
 * {@code home verify} exits 1 naming it, and {@code --fix} removes the link
 * (it resolves to nothing, and no {@code installed/hv-gone.json} claims it).
 */
public class DanglingAgentLinkIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.dangling.agent.link")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "agent-link", "ohv-2")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("dangling-agent-link", "DANGLING_AGENT_LINK", 1, true),
                (Path subject, Path neighbour) -> {
                    Path link = subject.getParent().resolve(".codex/skills").resolve("hv-gone");
                    Files.createSymbolicLink(link, subject.resolve("skills").resolve("hv-gone"));
                    return ".codex/skills/hv-gone";
                }));
    }
}
