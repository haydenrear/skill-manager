///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * #311: an install does not delete this home's own installed plugins.
 *
 * <h2>The measurement, and it is a DESTRUCTIVE one</h2>
 *
 * <p>Measured 2026-09-05, on a clone of this repository's project home:
 *
 * <pre>
 *   ls &lt;home&gt;/plugins                                     -&gt; skt
 *   CODEX_HOME=&lt;home&gt; skill-manager install &lt;unit&gt; --yes   -&gt; exit 0
 *   ls &lt;home&gt;/plugins                                     -&gt; (empty)
 * </pre>
 *
 * <p>{@code CODEX_HOME} and {@code GEMINI_HOME} name the config directory
 * ITSELF, not its parent — unlike {@code CLAUDE_HOME}, which is why the same
 * experiment with that variable is harmless. So
 * {@code CodexAgent.pluginsDir()}, spelled {@code $CODEX_HOME/plugins},
 * became the STORE's own {@code plugins/}, and the legacy-layout cleanup at
 * the end of every install deleted each installed plugin out of it,
 * recursively, while the install reported success.
 *
 * <h2>Why this is a graph node and not only a unit test</h2>
 *
 * <p>{@code PluginMarketplaceTest} drives {@code cleanupLegacyAgentPluginEntries}
 * directly and asserts the refusal. What it cannot show is the thing an
 * operator meets: a whole {@code install} — resolve, commit, marketplace
 * regenerate, agent registration, cleanup — ending <b>exit 0</b> with the
 * home's plugins gone and nothing in the output saying so. This node runs the
 * real CLI against a real cloned home and reads {@code plugins/} before and
 * after.
 *
 * <h2>The victim is a CLONE, never the shared fixture home</h2>
 *
 * <p>Same rule as {@code SyncStaysInsideItsHome}: if the guard regresses, a
 * node pointed at the fixture home would delete the fixture's plugins and
 * every later node would fail for a reason unrelated to what it asserts. A
 * destructive node has to be destructive to something it owns.
 *
 * <h2>The controls that make the rest mean something</h2>
 *
 * <p>Two, because "the plugins are still there" is satisfied by several
 * uninteresting worlds:
 *
 * <ul>
 *   <li><b>there was something to lose</b> — the victim really holds an
 *       installed plugin before the bad install runs. Without this the node
 *       passes vacuously on any home with no plugins, which is most of them.</li>
 *   <li><b>the install really ran</b> — it exits 0 and the unit it installed
 *       is a real directory afterwards. Without this, an install that failed
 *       early would "preserve" the plugins by never reaching the cleanup.</li>
 * </ul>
 *
 * <h2>The second control found a second defect</h2>
 *
 * <p>On its first run this node passed the plugin assertion and failed the
 * install control: {@code exit=0 unitInstalled=false}. The unit WAS installed
 * — and then replaced by a symlink pointing at its own path, because
 * {@code CodexAgent.skillsDir()} is {@code $CODEX_HOME/skills} and that was
 * the store's own {@code skills/} too. One misconfiguration, two write paths,
 * and the first fix only covered the plugin one. The control that existed to
 * stop a vacuous pass is what caught it.
 */
public class InstallKeepsItsOwnPlugins {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.install.keeps.its.own.plugins")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "confinement", "plugins", "issue-311")
            .timeout("900s")
            // Published so home.fixpoint.law and home.membership.law can see
            // it — those walk upstream context values rather than the
            // filesystem, so a node that publishes nothing is a node they
            // never check. See the note on SyncStaysInsideItsHome.
            .output("victimHome", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
        String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
        if (homeStr == null || scratchStr == null) return unproven("missing fixture context");

        Path fixture = Path.of(homeStr);
        Path scratch = Path.of(scratchStr).resolve("issue311");
        Files.createDirectories(scratch);
        Path victim = scratch.resolve("victim-home/.skill-manager");

        ProcessRecord clone = HomeIntegritySupport.sm(ctx, "issue311-clone", fixture,
                "home", "clone", "--from", fixture.toString(), "--to", victim.toString());
        if (clone.exitCode() != 0) {
            return unproven("could not build the victim home: home clone exited "
                    + clone.exitCode()).process(clone);
        }

        // Give the victim a plugin to lose. The defect deletes
        // <agentPluginsDir>/<name> for each name the marketplace currently
        // lists, so a home with no plugins cannot demonstrate anything.
        Path pluginSrc = scaffoldPlugin(scratch.resolve("src"), "issue311-plugin");
        ProcessRecord installPlugin = HomeIntegritySupport.sm(ctx, "issue311-install-plugin",
                victim, "install", pluginSrc.toString(), "--yes");
        Path pluginsDir = victim.resolve("plugins");
        List<String> before = entries(pluginsDir);
        boolean hadSomethingToLose = before.contains("issue311-plugin");
        if (!hadSomethingToLose) {
            return unproven("the victim home holds no plugin after install (exit "
                    + installPlugin.exitCode() + ", entries " + before
                    + "), so nothing here could measure a deletion")
                    .process(clone).process(installPlugin);
        }

        // THE MISCONFIGURATION, one variable at a time. Each is written
        // through SmEnv, the one file sandbox.env.contract allows to write a
        // managed variable into a child.
        List<Result> runs = new ArrayList<>();
        for (String var : List.of("CODEX_HOME", "GEMINI_HOME")) {
            Path unitSrc = scaffoldSkill(scratch.resolve("src"), "issue311-unit-" + var.toLowerCase());
            ProcessBuilder pb = new ProcessBuilder(
                    SmEnv.cli().toString(), "install", unitSrc.toString(), "--yes");
            SmEnv.applyWithAgentHomeOverride(ctx, pb, victim, var, victim.toString());
            ProcessRecord run = Procs.run(ctx, "issue311-install-with-" + var.toLowerCase(), pb);
            List<String> after = entries(pluginsDir);
            // NOFOLLOW, and that is the whole point of this check. #311 has a
            // SECOND face: the agent projection targets `$CODEX_HOME/skills`,
            // which under this misconfiguration is the store's own skills/, so
            // the freshly installed unit was REPLACED by a symlink pointing at
            // its own path. `Files.isDirectory` following that link returns
            // false (ELOOP) — which is how this node found the second face —
            // but "a real directory" is the property actually wanted, so ask
            // for it directly rather than relying on a loop to fail.
            Path unitDir = victim.resolve("skills")
                    .resolve("issue311-unit-" + var.toLowerCase());
            boolean installed = Files.isDirectory(unitDir, LinkOption.NOFOLLOW_LINKS);
            runs.add(new Result(var, run, after, installed));
        }

        boolean pluginsIntact = runs.stream()
                .allMatch(r -> r.after().contains("issue311-plugin"));
        boolean installsRan = runs.stream()
                .allMatch(r -> r.record().exitCode() == 0 && r.installedUnit());

        NodeResult result = (pluginsIntact && installsRan)
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(), describe(before, runs));

        result = result.process(clone).process(installPlugin);
        for (Result r : runs) result = result.process(r.record());

        return result
                .assertion("an_install_does_not_delete_this_homes_own_plugins", pluginsIntact)
                .assertion("and_the_unit_it_installed_is_a_real_directory_not_a_self_link",
                        installsRan)
                .assertion("CONTROL_the_home_held_a_plugin_before_the_bad_install",
                        hadSomethingToLose)
                .metric("pluginsBefore", before.size())
                .metric("pluginsAfter", runs.isEmpty() ? -1 : runs.get(runs.size() - 1).after().size())
                .publish("victimHome", victim.toString())
                .log("#311, measured 2026-09-05: CODEX_HOME=<home> took this home's "
                        + "plugins/ from 1 entry to 0 and exited 0. CODEX_HOME and "
                        + "GEMINI_HOME name the config directory itself, so "
                        + "CodexAgent.pluginsDir() was the store's own plugins/.");
    }

    private record Result(String variable, ProcessRecord record, List<String> after,
                          boolean installedUnit) {
    }

    private static String describe(List<String> before, List<Result> runs) {
        StringBuilder sb = new StringBuilder("plugins before=" + before);
        for (Result r : runs) {
            sb.append(" | ").append(r.variable()).append(": exit=")
                    .append(r.record().exitCode())
                    .append(" plugins=").append(r.after())
                    .append(" unitInstalled=").append(r.installedUnit());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- fixtures

    private static Path scaffoldSkill(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: Fixture for issue #311.\n---\n\n# " + name + "\n");
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.0.1"
                description = "Fixture for issue #311."
                """.formatted(name));
        return dir;
    }

    private static Path scaffoldPlugin(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + name + "\",\"version\":\"0.0.1\","
                        + "\"description\":\"Fixture for issue #311.\"}\n");
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.0.1"
                description = "Fixture for issue #311."
                """.formatted(name));
        Path contained = dir.resolve("skills").resolve(name + "-impl");
        Files.createDirectories(contained);
        Files.writeString(contained.resolve("SKILL.md"),
                "---\nname: " + name + "-impl\ndescription: Contained fixture skill.\n---\n\nbody\n");
        Files.writeString(contained.resolve("skill-manager.toml"), """
                [skill]
                name = "%s-impl"
                version = "0.0.1"
                description = "Contained fixture skill."
                """.formatted(name));
        return dir;
    }

    // ------------------------------------------------------------- plumbing

    private static List<String> entries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), "UNPROVEN: " + why);
    }
}
