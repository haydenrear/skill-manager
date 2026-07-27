///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Proves this graph did not do what issue #18 describes: leak agent-home
 * projections into the developer's real {@code ~/.claude}, {@code ~/.codex} and
 * {@code ~/.gemini}.
 *
 * <p>Existing nodes pass agent homes through {@link HomeCloneSupport#sm} and
 * that is where the sandboxing lives — but "a helper sets the right env vars" is
 * a claim about code, and #18 exists because
 * {@code ProjectDependenciesResolved:466-474} makes exactly that claim while
 * passing only {@code SKILL_MANAGER_HOME} and {@code SKILL_MANAGER_INSTALL_DIR}.
 * The agent fallbacks end at {@code user.home}
 * ({@code AgentHomes:167}, {@code CodexAgent:34}, {@code GeminiAgent:34}), so a
 * single subprocess spawned without them projects into the real home and the
 * symlinks outlive the run, dangling once the temp home is deleted.
 *
 * <p>So this node asserts the OUTCOME. The immediate child names of the three
 * real agent skill roots were recorded before anything ran; they must be
 * unchanged, and none of them may be one of this graph's units. Read-only
 * throughout, and deliberately shallow — that is where {@code install} projects,
 * so that is where the leak lands, and a recursive walk of a 5.4 GB tree would
 * buy nothing.
 *
 * <p>The projections that SHOULD exist are checked too, in the sandbox. Without
 * that half the node would also pass if the agent homes were never written at
 * all, which is a different bug wearing this one's clothes.
 */
public class HomeCloneNoAgentHomeLeak {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.no.agent.home.leak")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.clone.works.with.source.renamed")
            .tags("home-clone", "sandbox")
            .timeout("120s");

    private static final String[] OUR_UNITS = {
            HomeCloneSupport.UNIT_A, HomeCloneSupport.UNIT_B, HomeCloneSupport.LINKED };

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String claudeBefore = ctx.get("home.clone.fixture.built", "realClaudeSkills").orElse(null);
            String codexBefore = ctx.get("home.clone.fixture.built", "realCodexSkills").orElse(null);
            String geminiBefore = ctx.get("home.clone.fixture.built", "realGeminiSkills").orElse(null);
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            if (claudeBefore == null || codexBefore == null || geminiBefore == null
                    || sandbox == null || claudeHome == null) {
                return NodeResult.fail("home.clone.no.agent.home.leak", "missing upstream context");
            }
            Path realHome = Path.of(System.getProperty("user.home"));

            String claudeNow = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".claude/skills")));
            String codexNow = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".codex/skills")));
            String geminiNow = String.join(",",
                    HomeCloneSupport.names(realHome.resolve(".gemini/skills")));

            boolean realClaudeUnchanged = claudeNow.equals(claudeBefore);
            boolean realCodexUnchanged = codexNow.equals(codexBefore);
            boolean realGeminiUnchanged = geminiNow.equals(geminiBefore);

            // Stronger and independent of the baseline: none of OUR units may
            // appear in a real agent home, whatever else changed there. A
            // concurrent session editing the developer's own skills cannot make
            // this one pass or fail.
            List<String> ours = new java.util.ArrayList<>();
            for (String agentDir : new String[] {".claude/skills", ".codex/skills", ".gemini/skills"}) {
                for (String unit : OUR_UNITS) {
                    Path leaked = realHome.resolve(agentDir).resolve(unit);
                    if (Files.exists(leaked, LinkOption.NOFOLLOW_LINKS)) {
                        ours.add(agentDir + "/" + unit);
                    }
                }
            }
            boolean noneOfOurUnitsAreInARealAgentHome = ours.isEmpty();

            // The other half: the projections DID happen, in the sandbox.
            Path sandboxClaudeSkills = Path.of(claudeHome).resolve(".claude/skills");
            List<String> sandboxNames = HomeCloneSupport.names(sandboxClaudeSkills);
            boolean projectionsLandedInTheSandbox =
                    sandboxNames.contains(HomeCloneSupport.UNIT_A)
                            && sandboxNames.contains(HomeCloneSupport.UNIT_B);

            boolean pass = realClaudeUnchanged && realCodexUnchanged && realGeminiUnchanged
                    && noneOfOurUnitsAreInARealAgentHome && projectionsLandedInTheSandbox;
            return (pass
                    ? NodeResult.pass("home.clone.no.agent.home.leak")
                    : NodeResult.fail("home.clone.no.agent.home.leak",
                            "claudeBefore=[" + claudeBefore + "] claudeNow=[" + claudeNow + "]"
                                    + " codexBefore=[" + codexBefore + "] codexNow=[" + codexNow + "]"
                                    + " geminiBefore=[" + geminiBefore + "] geminiNow=["
                                    + geminiNow + "]"
                                    + " ourUnitsInRealAgentHomes=" + ours
                                    + " sandboxClaudeSkills=" + sandboxNames))
                    .assertion("the_real_claude_skills_root_is_unchanged", realClaudeUnchanged)
                    .assertion("the_real_codex_skills_root_is_unchanged", realCodexUnchanged)
                    .assertion("the_real_gemini_skills_root_is_unchanged", realGeminiUnchanged)
                    .assertion("none_of_this_graphs_units_appear_in_a_real_agent_home",
                            noneOfOurUnitsAreInARealAgentHome)
                    .assertion("the_agent_projections_landed_in_the_sandbox_instead",
                            projectionsLandedInTheSandbox)
                    .metric("sandboxProjectedUnits", sandboxNames.size());
        });
    }
}
