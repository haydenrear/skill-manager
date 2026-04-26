///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * End-to-end test for {@code skill-manager-skill/scripts/env.sh}.
 *
 * <p>After {@code umbrella.installed} has installed the {@code pip-cli-skill}
 * and {@code npm-cli-skill} transitive sub-skills (which respectively force
 * a bundled-{@code uv} install and a bundled-{@code node/npm} install), this
 * node invokes {@code env.sh} two ways and validates the JSON contract:
 *
 * <ol>
 *   <li>{@code --skills pip-cli-skill npm-cli-skill nonexistent-skill}
 *       — assert resolved/unknown lists, that both backends' CLIs were
 *       found at absolute paths under {@code <home>/bin/cli/}, that the
 *       reported package managers are bundled (paths under {@code <home>/pm/}),
 *       and that {@code missing} is empty.</li>
 *   <li>No {@code --skills} flag — assert dump-all behavior surfaces both
 *       skills.</li>
 * </ol>
 */
public class EnvScriptReports {
    static final NodeSpec SPEC = NodeSpec.of("env.script.reports")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("umbrella.installed")
            .tags("cli", "transitive", "env-script")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("env.script.reports", "missing env.prepared context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path envScript = repoRoot.resolve("skill-manager-skill/scripts/env.sh");
            if (!Files.isRegularFile(envScript)) {
                return NodeResult.fail("env.script.reports",
                        "env.sh not found at " + envScript);
            }

            ObjectMapper om = new ObjectMapper();
            List<String> errors = new ArrayList<>();
            int run1Rc = -1;
            int run2Rc = -1;

            // ------ Run 1: explicit --skills (including a nonexistent one) ------
            JsonNode r1;
            try {
                RunResult rr = runEnvScript(om, envScript, home, ctx, "env-run1", List.of(
                        "--skills", "pip-cli-skill", "npm-cli-skill", "nonexistent-skill"));
                run1Rc = rr.rc;
                r1 = rr.json;
            } catch (Exception e) {
                return Procs.attach(
                        NodeResult.fail("env.script.reports",
                                "failed to invoke env.sh (run 1): " + e.getMessage()),
                        ctx, "env-run1", 1, 200);
            }

            String reportedHome = textOrEmpty(r1.path("skill_manager_home"));
            boolean homeMatches = reportedHome.equals(home);
            if (!homeMatches) errors.add("home mismatch: got " + reportedHome);

            List<String> resolved = arrayToStrings(r1.path("skills_resolved"));
            boolean pipResolved = resolved.contains("pip-cli-skill");
            boolean npmResolved = resolved.contains("npm-cli-skill");
            if (!pipResolved) errors.add("pip-cli-skill not in skills_resolved=" + resolved);
            if (!npmResolved) errors.add("npm-cli-skill not in skills_resolved=" + resolved);

            List<String> unknown = arrayToStrings(r1.path("skills_unknown"));
            boolean unknownReported = unknown.contains("nonexistent-skill");
            if (!unknownReported) errors.add("nonexistent-skill missing from skills_unknown=" + unknown);

            JsonNode clis = r1.path("clis");
            JsonNode pyc = clis.path("pycowsay");
            JsonNode cow = clis.path("cowsay");

            String pycPath = textOrEmpty(pyc.path("path"));
            String cowPath = textOrEmpty(cow.path("path"));
            String cliPrefix = home + "/bin/cli/";

            boolean pipCliInstalled = pyc.path("installed").asBoolean(false);
            boolean pipCliBackend = "pip".equals(textOrEmpty(pyc.path("backend")));
            boolean pipCliPath = pycPath.startsWith(cliPrefix);
            boolean pipCliFromSkill = "pip-cli-skill".equals(textOrEmpty(pyc.path("from_skill")));
            if (!pipCliInstalled) errors.add("pycowsay.installed=false");
            if (!pipCliBackend) errors.add("pycowsay.backend!=pip got=" + textOrEmpty(pyc.path("backend")));
            if (!pipCliPath) errors.add("pycowsay.path not under bin/cli: " + pycPath);
            if (!pipCliFromSkill) errors.add("pycowsay.from_skill mismatch: " + textOrEmpty(pyc.path("from_skill")));

            boolean npmCliInstalled = cow.path("installed").asBoolean(false);
            boolean npmCliBackend = "npm".equals(textOrEmpty(cow.path("backend")));
            boolean npmCliPath = cowPath.startsWith(cliPrefix);
            boolean npmCliFromSkill = "npm-cli-skill".equals(textOrEmpty(cow.path("from_skill")));
            if (!npmCliInstalled) errors.add("cowsay.installed=false");
            if (!npmCliBackend) errors.add("cowsay.backend!=npm got=" + textOrEmpty(cow.path("backend")));
            if (!npmCliPath) errors.add("cowsay.path not under bin/cli: " + cowPath);
            if (!npmCliFromSkill) errors.add("cowsay.from_skill mismatch: " + textOrEmpty(cow.path("from_skill")));

            // Bundled package managers: umbrella triggered both uv and node installs.
            JsonNode pms = r1.path("package_managers");
            JsonNode uv = pms.path("uv");
            JsonNode npm = pms.path("npm");
            JsonNode node = pms.path("node");
            JsonNode brew = pms.path("brew");

            String uvPath = textOrEmpty(uv.path("path"));
            String npmPath = textOrEmpty(npm.path("path"));
            String nodePath = textOrEmpty(node.path("path"));
            String pmPrefix = home + "/pm/";

            boolean uvBundled = uv.path("bundled").asBoolean(false) && uvPath.startsWith(pmPrefix + "uv/");
            boolean npmBundled = npm.path("bundled").asBoolean(false) && npmPath.startsWith(pmPrefix + "node/");
            boolean nodeBundled = node.path("bundled").asBoolean(false) && nodePath.startsWith(pmPrefix + "node/");
            boolean brewNotBundled = !brew.path("bundled").asBoolean(true);
            if (!uvBundled) errors.add("uv not reported as bundled under <home>/pm/uv: " + uv);
            if (!npmBundled) errors.add("npm not reported as bundled under <home>/pm/node: " + npm);
            if (!nodeBundled) errors.add("node not reported as bundled under <home>/pm/node: " + node);
            if (!brewNotBundled) errors.add("brew should never be bundled: " + brew);

            JsonNode missing = r1.path("missing");
            boolean missingEmpty = missing.isArray() && missing.size() == 0;
            if (!missingEmpty) errors.add("missing array non-empty: " + missing);

            // ------ Run 2: no --skills, dump-all ------
            JsonNode r2;
            try {
                RunResult rr = runEnvScript(om, envScript, home, ctx, "env-run2", List.of());
                run2Rc = rr.rc;
                r2 = rr.json;
            } catch (Exception e) {
                return Procs.attach(
                        Procs.attach(
                                NodeResult.fail("env.script.reports",
                                        "failed to invoke env.sh (run 2): " + e.getMessage()),
                                ctx, "env-run1", run1Rc, 200),
                        ctx, "env-run2", 1, 200);
            }

            boolean run2RequestedNull = r2.path("skills_requested").isNull();
            List<String> resolved2 = arrayToStrings(r2.path("skills_resolved"));
            boolean run2HasPip = resolved2.contains("pip-cli-skill");
            boolean run2HasNpm = resolved2.contains("npm-cli-skill");
            boolean run2ClisHavePyc = r2.path("clis").has("pycowsay");
            boolean run2ClisHaveCow = r2.path("clis").has("cowsay");
            if (!run2RequestedNull) errors.add("dump-all: skills_requested should be null");
            if (!run2HasPip) errors.add("dump-all: skills_resolved missing pip-cli-skill: " + resolved2);
            if (!run2HasNpm) errors.add("dump-all: skills_resolved missing npm-cli-skill: " + resolved2);
            if (!run2ClisHavePyc) errors.add("dump-all: clis missing pycowsay");
            if (!run2ClisHaveCow) errors.add("dump-all: clis missing cowsay");

            boolean pass = errors.isEmpty();
            NodeResult result = pass
                    ? NodeResult.pass("env.script.reports")
                    : NodeResult.fail("env.script.reports", String.join("; ", errors));

            // Attach the per-run env.sh logs as artifacts so the captured
            // JSON / stderr survives the run even when assertions pass.
            result = Procs.attach(result, ctx, "env-run1", pass ? 0 : 1, 200);
            result = Procs.attach(result, ctx, "env-run2", pass ? 0 : 1, 200);

            return result
                    .assertion("env_script_exists", true)
                    .assertion("home_matches", homeMatches)
                    .assertion("pip_skill_resolved", pipResolved)
                    .assertion("npm_skill_resolved", npmResolved)
                    .assertion("unknown_skill_reported", unknownReported)
                    .assertion("pycowsay_installed_under_bin_cli", pipCliInstalled && pipCliPath)
                    .assertion("pycowsay_backend_pip", pipCliBackend)
                    .assertion("pycowsay_from_pip_skill", pipCliFromSkill)
                    .assertion("cowsay_installed_under_bin_cli", npmCliInstalled && npmCliPath)
                    .assertion("cowsay_backend_npm", npmCliBackend)
                    .assertion("cowsay_from_npm_skill", npmCliFromSkill)
                    .assertion("uv_bundled_under_home_pm", uvBundled)
                    .assertion("npm_bundled_under_home_pm", npmBundled)
                    .assertion("node_bundled_under_home_pm", nodeBundled)
                    .assertion("brew_not_bundled", brewNotBundled)
                    .assertion("missing_array_empty", missingEmpty)
                    .assertion("dumpall_skills_requested_null", run2RequestedNull)
                    .assertion("dumpall_includes_pip_skill", run2HasPip)
                    .assertion("dumpall_includes_npm_skill", run2HasNpm)
                    .assertion("dumpall_clis_has_pycowsay", run2ClisHavePyc)
                    .assertion("dumpall_clis_has_cowsay", run2ClisHaveCow);
        });
    }

    private static final class RunResult {
        final int rc;
        final JsonNode json;
        RunResult(int rc, JsonNode json) { this.rc = rc; this.json = json; }
    }

    private static RunResult runEnvScript(ObjectMapper om, Path script, String home,
                                          NodeContext ctx, String label, List<String> extraArgs)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(script.toString());
        cmd.addAll(extraArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        // Sequential drain is safe: env.py emits a small JSON blob plus a
        // few lines of stderr — neither is large enough to fill an OS pipe.
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                err.append(line).append('\n');
            }
        }
        int rc = p.waitFor();

        // Persist a per-invocation log in the standard Procs location so it
        // surfaces both as a node-logs/<nodeId>.<label>.log file on disk and
        // as an artifact pointer on the envelope (via Procs.attach above).
        Path log = Procs.logFile(ctx, label);
        Files.writeString(log,
                "$ " + String.join(" ", cmd) + "\nrc=" + rc
                        + "\n--- stdout ---\n" + out
                        + "\n--- stderr ---\n" + err);

        if (rc != 0) {
            throw new RuntimeException("env.sh exited rc=" + rc + " stderr=" + err);
        }
        return new RunResult(rc, om.readTree(out.toString()));
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? "" : n.asText("");
    }

    private static List<String> arrayToStrings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (Iterator<JsonNode> it = n.elements(); it.hasNext(); ) {
            JsonNode e = it.next();
            if (e.isTextual()) out.add(e.asText());
        }
        return out;
    }
}
