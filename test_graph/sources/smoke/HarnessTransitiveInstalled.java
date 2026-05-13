///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scaffold a harness template in a tmpdir, then install it via the
 * local-install path ({@code install file://...}). The harness
 * references three sibling units by {@code file://} coord:
 *
 * <ul>
 *   <li>{@code pip-cli-skill} — bare skill carrying a {@code pip}
 *       CLI dep (the transitive-CLI assertion).</li>
 *   <li>{@code hello-plugin} — plugin whose contained-skill carries
 *       a {@code pip} CLI dep (transitive plugin + contained-skill
 *       union).</li>
 *   <li>{@code hello-doc-repo} — doc-repo with two markdown
 *       sources (transitive doc-repo install).</li>
 * </ul>
 *
 * <p>One {@code install} invocation drives the resolver across every
 * transitive unit, exercises the new
 * {@link dev.skillmanager.resolve.Resolver}'s harness + doc-repo
 * detection, lands every unit in the store, and bundles the
 * transitive CLI dep at {@code <home>/bin/cli/<binary>}.
 */
public class HarnessTransitiveInstalled {
    static final NodeSpec SPEC = NodeSpec.of("harness.transitive.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up")
            .tags("install", "harness", "transitive", "ticket-47")
            .timeout("120s")
            .output("harnessName", "string")
            .output("templateDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("harness.transitive.installed", "missing env.prepared.home");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Resolve the three transitive deps by absolute path so the
            // harness's file:// coords are unambiguous wherever the test
            // runs from.
            Path pipCliSkill = repoRoot.resolve(
                    "test_graph/fixtures/umbrella-skill/pip-dep");
            Path helloPlugin = repoRoot.resolve("examples/hello-plugin");
            Path helloDocRepo = repoRoot.resolve("examples/hello-doc-repo");

            // Scaffold a harness template at <home>/harness-tpl/. Use
            // <home> as the parent so test cleanup picks it up; the
            // harness install copies bytes into <store>/harnesses/<name>/
            // so the scaffolded tmpdir can be torn down after install.
            Path tplDir;
            try {
                tplDir = Files.createDirectory(Path.of(home, "harness-tpl"));
                Files.writeString(tplDir.resolve("harness.toml"), String.format(
                        "[harness]%n"
                                + "name = \"smoke-harness\"%n"
                                + "version = \"0.1.0\"%n"
                                + "description = \"transitive-deps fixture for harness-smoke\"%n"
                                + "%n"
                                + "units = [%n"
                                + "  \"file://%s\",%n"
                                + "  \"file://%s\"%n"
                                + "]%n"
                                + "docs = [\"file://%s\"]%n",
                        pipCliSkill.toAbsolutePath(),
                        helloPlugin.toAbsolutePath(),
                        helloDocRepo.toAbsolutePath()));
            } catch (Exception e) {
                return NodeResult.fail("harness.transitive.installed",
                        "could not scaffold harness template: " + e.getMessage());
            }

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file://" + tplDir.toAbsolutePath(), "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            // Verify the harness template + every transitive ref landed.
            Path harnessStore = Path.of(home, "harnesses", "smoke-harness");
            Path skillStore = Path.of(home, "skills", "pip-cli-skill");
            Path pluginStore = Path.of(home, "plugins", "hello-plugin");
            Path docStore = Path.of(home, "docs", "hello-doc-repo");
            boolean harnessLanded = Files.isRegularFile(harnessStore.resolve("harness.toml"));
            boolean skillLanded = Files.isRegularFile(skillStore.resolve("SKILL.md"));
            boolean pluginLanded = Files.isRegularFile(pluginStore.resolve(".claude-plugin/plugin.json"));
            boolean docLanded = Files.isRegularFile(docStore.resolve("skill-manager.toml"));

            // Transitive CLI dep — pip-cli-skill declares pip:pycowsay
            // with an unfindable on_path so the install always bundles
            // a fresh copy under bin/cli/.
            Path cliBin = Path.of(home, "bin", "cli", "pycowsay");
            boolean cliBundled = Files.isRegularFile(cliBin);

            boolean pass = rc == 0 && harnessLanded && skillLanded && pluginLanded
                    && docLanded && cliBundled;
            NodeResult result = pass
                    ? NodeResult.pass("harness.transitive.installed")
                    : NodeResult.fail("harness.transitive.installed",
                            "rc=" + rc + " harnessLanded=" + harnessLanded
                                    + " skillLanded=" + skillLanded
                                    + " pluginLanded=" + pluginLanded
                                    + " docLanded=" + docLanded
                                    + " cliBundled=" + cliBundled);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("harness_template_in_store", harnessLanded)
                    .assertion("transitive_skill_installed", skillLanded)
                    .assertion("transitive_plugin_installed", pluginLanded)
                    .assertion("transitive_doc_installed", docLanded)
                    .assertion("transitive_cli_bundled", cliBundled)
                    .metric("exitCode", rc)
                    .publish("harnessName", "smoke-harness")
                    .publish("templateDir", tplDir.toString());
        });
    }
}
