///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeVerdictsSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A misanchored agent link: the subject's own {@code .claude/skills/hv-unit}
 * RESOLVES, into the neighbour home's store. Issue #159's measurement (24 links
 * under the operator's {@code ~/.claude/skills} pointing into a worktree home).
 *
 * <p>Today: {@code home repair} names {@code MISANCHORED_AGENT_LINK};
 * {@code home verify} exits 0 ("every reference resolves" — it does).
 * <b>OHV-2 flips verify to 1.</b>
 *
 * <p>Not the DANGLING link outside the home (DEF-OHV-002): neither reader
 * reports that today, so its node is OHV-2's.
 */
public class MisanchoredAgentLinkIsReported {

    static final NodeSpec SPEC = NodeSpec.of("home.verdicts.misanchored.agent.link")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn(HomeVerdictsSupport.FIXTURE)
            .tags("home", "verdicts", "agent-link", "ohv-0")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> HomeVerdictsSupport.runShape(ctx, SPEC,
                new HomeVerdictsSupport.Shape("misanchored-agent-link", "MISANCHORED_AGENT_LINK", 0, false),
                (Path subject, Path neighbour) -> {
                    Path link = subject.getParent().resolve(".claude/skills").resolve(HomeVerdictsSupport.UNIT);
                    Files.deleteIfExists(link);
                    Files.createSymbolicLink(link, neighbour.resolve("skills").resolve(HomeVerdictsSupport.UNIT));
                    return ".claude/skills/" + HomeVerdictsSupport.UNIT;
                }));
    }
}
