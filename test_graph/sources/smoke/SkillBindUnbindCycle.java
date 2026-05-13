///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bind an already-installed skill ({@code hello-skill}) to a custom
 * project root and round-trip through unbind. Locks in the
 * ticket-49 contract for {@link
 * dev.skillmanager.bindings.BindingSource#EXPLICIT} skill bindings:
 *
 * <ul>
 *   <li>A {@code bind hello-skill --to <project>} drops a SYMLINK at
 *       {@code <project>/hello-skill} pointing back at the store
 *       path.</li>
 *   <li>The per-unit ledger gains a new EXPLICIT row alongside the
 *       pre-existing DEFAULT_AGENT rows install wrote — count
 *       expectation: at least one EXPLICIT plus the DEFAULT_AGENT
 *       ones.</li>
 *   <li>After {@code unbind <id>}, the SYMLINK is gone and the
 *       EXPLICIT row drops from the ledger; the DEFAULT_AGENT
 *       rows survive (other consumers / agents need them).</li>
 * </ul>
 */
public class SkillBindUnbindCycle {
    static final NodeSpec SPEC = NodeSpec.of("skill.bind.unbind.cycle")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.installed")
            .tags("bind", "skill", "ticket-49")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("skill.bind.unbind.cycle", "missing env.prepared.home");
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            Path project;
            try {
                project = Files.createTempDirectory(Path.of(home), "explicit-bind-");
            } catch (Exception e) {
                return NodeResult.fail("skill.bind.unbind.cycle",
                        "could not mkdir project: " + e.getMessage());
            }

            // bind hello-skill --to <project>
            ProcessBuilder bindPb = new ProcessBuilder(
                    sm.toString(), "bind", "hello-skill", "--to", project.toString());
            bindPb.environment().put("SKILL_MANAGER_HOME", home);
            bindPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord bindProc = Procs.run(ctx, "bind", bindPb);
            int bindRc = bindProc.exitCode();

            Path link = project.resolve("hello-skill");
            boolean linkPresent = Files.isSymbolicLink(link)
                    && Files.exists(link, LinkOption.NOFOLLOW_LINKS);

            // The new EXPLICIT binding's id is the only ULID-shaped
            // bindingId in the ledger (DEFAULT_AGENT bindings use
            // deterministic non-ULID ids like default:claude:hello-skill).
            Path ledger = Path.of(home, "installed", "hello-skill.projections.json");
            String ledgerJson;
            try {
                ledgerJson = Files.readString(ledger);
            } catch (Exception e) {
                return NodeResult.fail("skill.bind.unbind.cycle",
                        "could not read ledger: " + e.getMessage());
            }
            boolean ledgerHasExplicit = ledgerJson.contains("\"EXPLICIT\"");
            boolean ledgerHasDefault = ledgerJson.contains("\"DEFAULT_AGENT\"");
            Matcher m = Pattern.compile("\"bindingId\"\\s*:\\s*\"([0-9A-HJKMNP-TV-Z]{26})\"")
                    .matcher(ledgerJson);
            String explicitId = m.find() ? m.group(1) : null;

            // unbind <id>
            int unbindRc = -1;
            ProcessRecord unbindProc = null;
            if (explicitId != null) {
                ProcessBuilder unbindPb = new ProcessBuilder(
                        sm.toString(), "unbind", explicitId);
                unbindPb.environment().put("SKILL_MANAGER_HOME", home);
                unbindPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
                unbindProc = Procs.run(ctx, "unbind", unbindPb);
                unbindRc = unbindProc.exitCode();
            }

            boolean linkGone = !Files.exists(link, LinkOption.NOFOLLOW_LINKS);
            String ledgerAfter = "";
            try { ledgerAfter = Files.readString(ledger); } catch (Exception ignored) {}
            boolean explicitGone = explicitId == null || !ledgerAfter.contains(explicitId);
            boolean defaultsSurvive = ledgerAfter.contains("\"DEFAULT_AGENT\"");

            boolean pass = bindRc == 0 && linkPresent
                    && ledgerHasExplicit && ledgerHasDefault && explicitId != null
                    && unbindRc == 0 && linkGone && explicitGone && defaultsSurvive;
            NodeResult result = pass
                    ? NodeResult.pass("skill.bind.unbind.cycle")
                    : NodeResult.fail("skill.bind.unbind.cycle",
                            "bindRc=" + bindRc + " linkPresent=" + linkPresent
                                    + " ledgerHasExplicit=" + ledgerHasExplicit
                                    + " ledgerHasDefault=" + ledgerHasDefault
                                    + " explicitId=" + explicitId
                                    + " unbindRc=" + unbindRc + " linkGone=" + linkGone
                                    + " explicitGone=" + explicitGone
                                    + " defaultsSurvive=" + defaultsSurvive);
            result = result.process(bindProc);
            if (unbindProc != null) result = result.process(unbindProc);
            return result
                    .assertion("bind_ok", bindRc == 0)
                    .assertion("explicit_symlink_present", linkPresent)
                    .assertion("ledger_carries_explicit_and_default", ledgerHasExplicit && ledgerHasDefault)
                    .assertion("unbind_ok", unbindRc == 0)
                    .assertion("explicit_symlink_removed", linkGone)
                    .assertion("default_agent_bindings_survive", defaultsSurvive)
                    .metric("bindExitCode", bindRc)
                    .metric("unbindExitCode", unbindRc);
        });
    }
}
