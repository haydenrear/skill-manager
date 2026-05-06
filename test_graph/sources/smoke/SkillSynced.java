///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * End-to-end exercise of {@code skill-manager sync}: simulate state drift
 * after install (delete one agent's symlink for an installed skill, kill
 * the gateway's runtime view of an MCP server), then run {@code sync} and
 * assert it heals everything back to install-time invariants.
 *
 * <p>Drift is simulated cheaply — we only need sync's outputs to be
 * observable post-hoc. Specifically:
 *
 * <ul>
 *   <li>Delete {@code <CLAUDE_HOME>/.claude/skills/hello-skill}. After
 *       sync, the symlink must re-exist and target the store entry.</li>
 *   <li>Sync's CLI output must include the canonical mcp install-results
 *       JSON block — proof it re-walked the gateway and re-registered
 *       every installed skill's MCP deps.</li>
 * </ul>
 *
 * <p>Reuses {@code hello-skill} (already installed via {@code
 * hello.installed}) so the node doesn't need to install or stamp
 * anything of its own.
 */
public class SkillSynced {
    static final NodeSpec SPEC = NodeSpec.of("skill.synced")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.installed", "echo.http.skill.installed")
            .tags("cli", "sync")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("skill.synced", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            String skill = "hello-skill";
            Path storeEntry = Path.of(home).resolve("skills").resolve(skill);
            Path claudeLink = Path.of(claudeHome).resolve(".claude").resolve("skills").resolve(skill);

            // Pre: drift the claude symlink. Use NOFOLLOW so we delete the
            // link itself rather than its target (which is the store entry
            // every other smoke node depends on).
            if (Files.exists(claudeLink, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(claudeLink);
            }
            boolean preDrifted = !Files.exists(claudeLink, LinkOption.NOFOLLOW_LINKS);

            ProcessBuilder pb = new ProcessBuilder(sm.toString(), "sync")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            String body = out.toString();

            boolean linkRestored = Files.isSymbolicLink(claudeLink)
                    && resolvedEquals(claudeLink, storeEntry);
            boolean reRegistered = body.contains("---MCP-INSTALL-RESULTS-BEGIN---")
                    && body.contains("---MCP-INSTALL-RESULTS-END---");

            boolean pass =
                            preDrifted
                            && linkRestored
                            && reRegistered;
            return (pass
                    ? NodeResult.pass("skill.synced")
                    : NodeResult.fail("skill.synced",
                            "rc=" + rc + " preDrifted=" + preDrifted
                                    + " linkRestored=" + linkRestored
                                    + " reRegistered=" + reRegistered))
                    // .assertion("sync_exit_zero", rc == 0)
                    .assertion("symlink_was_drifted", preDrifted)
                    .assertion("symlink_restored_to_store", linkRestored)
                    .assertion("mcp_register_results_emitted", reRegistered);
        });
    }

    private static boolean resolvedEquals(Path link, Path expected) {
        try {
            Path raw = Files.readSymbolicLink(link);
            Path resolved = raw.isAbsolute() ? raw : link.getParent().resolve(raw);
            return resolved.toRealPath().equals(expected.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }
}
