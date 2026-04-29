///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Locks in the install-time symlink contract: every {@code install} drops
 * a symlink at {@code <CLAUDE_HOME>/.claude/skills/<name>} and
 * {@code <CODEX_HOME>/skills/<name>} pointing back at the store path
 * {@code <SKILL_MANAGER_HOME>/skills/<name>}.
 *
 * <p>Without that, the agent runtime can't see the skill. The behavior
 * is implemented in {@code SkillSync} and called per-agent at the tail of
 * {@code InstallCommand}; this node keeps that wiring honest.
 *
 * <p>The check piggybacks on {@code hello.installed} and
 * {@code umbrella.installed} so the assertions cover both a registry
 * install (hello) and a transitive multi-skill install (umbrella +
 * pip-cli-skill + npm-cli-skill).
 */
public class AgentSkillSymlinks {
    static final NodeSpec SPEC = NodeSpec.of("agent.skill.symlinks")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.installed", "umbrella.installed")
            .tags("agents", "symlinks", "install")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("agent.skill.symlinks", "missing env.prepared context");
            }

            Path storeSkills = Path.of(home, "skills");
            Path claudeSkills = Path.of(claudeHome).resolve(".claude").resolve("skills");
            Path codexSkills = Path.of(codexHome).resolve("skills");

            String[] skills = {"hello-skill", "umbrella-skill", "pip-cli-skill", "npm-cli-skill"};

            StringBuilder errs = new StringBuilder();
            boolean[] claudePresent = new boolean[skills.length];
            boolean[] codexPresent = new boolean[skills.length];
            boolean[] claudeIsLink = new boolean[skills.length];
            boolean[] codexIsLink = new boolean[skills.length];
            boolean[] claudePointsToStore = new boolean[skills.length];
            boolean[] codexPointsToStore = new boolean[skills.length];

            for (int i = 0; i < skills.length; i++) {
                String s = skills[i];
                Path expectedTarget = storeSkills.resolve(s);
                Path claudeLink = claudeSkills.resolve(s);
                Path codexLink = codexSkills.resolve(s);

                claudePresent[i] = Files.exists(claudeLink, LinkOption.NOFOLLOW_LINKS);
                claudeIsLink[i] = Files.isSymbolicLink(claudeLink);
                claudePointsToStore[i] = claudeIsLink[i]
                        && resolvedEquals(claudeLink, expectedTarget);

                codexPresent[i] = Files.exists(codexLink, LinkOption.NOFOLLOW_LINKS);
                codexIsLink[i] = Files.isSymbolicLink(codexLink);
                codexPointsToStore[i] = codexIsLink[i]
                        && resolvedEquals(codexLink, expectedTarget);

                if (!(claudePresent[i] && claudeIsLink[i] && claudePointsToStore[i])) {
                    errs.append("claude/").append(s).append(": present=").append(claudePresent[i])
                            .append(" link=").append(claudeIsLink[i])
                            .append(" -> ").append(readLinkSafe(claudeLink))
                            .append(" (want ").append(expectedTarget).append("); ");
                }
                if (!(codexPresent[i] && codexIsLink[i] && codexPointsToStore[i])) {
                    errs.append("codex/").append(s).append(": present=").append(codexPresent[i])
                            .append(" link=").append(codexIsLink[i])
                            .append(" -> ").append(readLinkSafe(codexLink))
                            .append(" (want ").append(expectedTarget).append("); ");
                }
            }

            boolean pass = errs.length() == 0;
            NodeResult result = pass
                    ? NodeResult.pass("agent.skill.symlinks")
                    : NodeResult.fail("agent.skill.symlinks", errs.toString());
            for (int i = 0; i < skills.length; i++) {
                String s = skills[i].replace('-', '_');
                result = result
                        .assertion("claude_" + s + "_symlinked", claudePresent[i] && claudeIsLink[i])
                        .assertion("claude_" + s + "_targets_store", claudePointsToStore[i])
                        .assertion("codex_" + s + "_symlinked", codexPresent[i] && codexIsLink[i])
                        .assertion("codex_" + s + "_targets_store", codexPointsToStore[i]);
            }
            return result;
        });
    }

    private static boolean resolvedEquals(Path link, Path expected) {
        try {
            // Files.readSymbolicLink returns the raw target; normalize both
            // sides to absolute, real paths so /private vs / on macOS or a
            // relative target don't cause false negatives.
            Path raw = Files.readSymbolicLink(link);
            Path resolved = raw.isAbsolute() ? raw : link.getParent().resolve(raw);
            return resolved.toRealPath().equals(expected.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String readLinkSafe(Path p) {
        try {
            return Files.readSymbolicLink(p).toString();
        } catch (Exception e) {
            return "<no-link>";
        }
    }
}
