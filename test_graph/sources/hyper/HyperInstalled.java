///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Installs the {@code hyper-experiments} skill by COORDINATE into the per-run
 * {@code SKILL_MANAGER_HOME} — {@code git+<HYPER_GIT_URL>} at
 * {@code HYPER_GIT_REF} by default, or {@code file:<hyper.checkout's copy>}
 * when {@code HYPER_LOCAL_DIR} is set — and asserts the SKILL.md
 * + skill-manager.toml landed, proves its pinned direct-git ACP dependency
 * landed under the manifest-declared unit name, and triggers registration of
 * the declared MCP servers (runpod) with the gateway.
 *
 * <p>The install runs from a temp working directory rather than inside the
 * checkout — the user explicitly wanted the install path exercised from a
 * "fresh folder", not the source repo.
 *
 * <p>#427: this used to install {@code hyper-experiments} BY NAME through a
 * per-run registry that {@code hyper.published} had registered it with. The
 * registry is gone; a bare name no longer resolves, so the install names its
 * source directly.
 */
public class HyperInstalled {
    private static final String ACP_PIN = "dde427ed315409f9e869466f9ad45e11c37cfbb0";

    static final NodeSpec SPEC = NodeSpec.of("hyper.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.checkout", "gateway.up")
            .tags("hyper", "git", "install", "mcp")
            // 120s was too short once hyper-experiments' closure grew: the
            // coordinate install resolves and clones fine, then spends minutes
            // in transitive brew/npm/skill-script installs (measured at HP-12:
            // still installing deploy-helm's skill-scripts at the 120s kill).
            .timeout("600s")
            .output("skillDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String checkoutDir = ctx.get("hyper.checkout", "skillDir").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null || checkoutDir == null) {
                return NodeResult.fail("hyper.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Run from a fresh cwd that is not the checkout, so the install
            // resolves its coordinate rather than anything in the cwd.
            Path freshCwd = Path.of(home).resolve("install-cwd");
            Files.createDirectories(freshCwd);

            List<String> argv = new java.util.ArrayList<>(List.of(sm.toString(), "install"));
            String localDir = System.getenv("HYPER_LOCAL_DIR");
            if (localDir != null && !localDir.isBlank()) {
                argv.add("file:" + checkoutDir);
            } else {
                argv.add("git+" + envOr("HYPER_GIT_URL", HyperDefaults.GIT_URL));
                argv.add("--ref");
                argv.add(envOr("HYPER_GIT_REF", HyperDefaults.GIT_REF));
            }
            argv.add("--yes");
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(freshCwd.toFile());
            SmEnv.apply(ctx, pb, home);

            // Map X_RUNPOD_KEY -> RUNPOD_API_KEY so McpWriter's env-init
            // scan finds the runpod manifest's required field and the
            // gateway can auto-deploy at install time. CI provisions
            // X_RUNPOD_KEY from secrets.X_RUNPOD_KEY; locally the operator
            // exports it before invoking ./gradlew hyper-experiments.
            String runpodKey = System.getenv("X_RUNPOD_KEY");
            if (runpodKey != null && !runpodKey.isBlank()) {
                pb.environment().put("RUNPOD_API_KEY", runpodKey);
            }

            // Force the pip backend to actually bundle tb-query under
            // $home/bin/cli/ instead of short-circuiting on the host's
            // pre-existing copy. PipBackend.install checks isOnPath(onPath)
            // and bails out when the binary is already reachable; our
            // hyper.cli.tbquery assertion needs the bundled artifact to
            // exist, so we strip the host-tb-query directory from the
            // install subprocess's PATH.
            String hostTbQuery = locateOnPath(System.getenv("PATH"), "tb-query");
            if (hostTbQuery != null) {
                Path tbqDir = Path.of(hostTbQuery).getParent();
                String scrubbed = java.util.Arrays.stream(
                                pb.environment().getOrDefault("PATH", "")
                                        .split(java.io.File.pathSeparator))
                        .filter(p -> tbqDir == null || !tbqDir.toString().equals(p))
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
                pb.environment().put("PATH", scrubbed);
            }

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();
            Path installLogFile = Procs.logFile(ctx, "install");
            String installLog = Files.isRegularFile(installLogFile)
                    ? Files.readString(installLogFile)
                    : "";

            Path skillDir = Path.of(home).resolve("skills/hyper-experiments");
            boolean mdOk = Files.isRegularFile(skillDir.resolve("SKILL.md"));
            boolean tomlOk = Files.isRegularFile(skillDir.resolve("skill-manager.toml"));

            Path acpDir = Path.of(home).resolve("skills/acp-cdc-ai-python");
            boolean acpMdOk = Files.isRegularFile(acpDir.resolve("SKILL.md"));
            boolean acpTomlOk = Files.isRegularFile(acpDir.resolve("skill-manager.toml"));
            Path acpRecordFile = Path.of(home).resolve("installed/acp-cdc-ai-python.json");
            String acpRecord = Files.isRegularFile(acpRecordFile)
                    ? Files.readString(acpRecordFile)
                    : "";
            boolean acpPinRecorded = acpRecord.contains(ACP_PIN)
                    && acpRecord.contains("acp-cdc-ai-python-skill");
            CommandResult acpHead = run(acpDir, List.of("git", "rev-parse", "HEAD"));
            boolean acpHeadPinned = acpHead.rc() == 0
                    && ACP_PIN.equals(acpHead.output().trim());
            boolean transitiveClosureResolved = resolvedUnitCount(installLog) >= 2;
            boolean noSkippedReferences =
                    !installLog.contains("skipping reference with no name or path");

            boolean pass = rc == 0
                    && mdOk
                    && tomlOk
                    && acpMdOk
                    && acpTomlOk
                    && acpPinRecorded
                    && acpHeadPinned
                    && transitiveClosureResolved
                    && noSkippedReferences;
            NodeResult result = pass
                    ? NodeResult.pass("hyper.installed")
                    : NodeResult.fail("hyper.installed",
                            "rc=" + rc
                                    + " hyperMd=" + mdOk
                                    + " hyperToml=" + tomlOk
                                    + " acpMd=" + acpMdOk
                                    + " acpToml=" + acpTomlOk
                                    + " acpPin=" + acpPinRecorded
                                    + " acpHeadPinned=" + acpHeadPinned
                                    + " resolvedClosure=" + transitiveClosureResolved
                                    + " noSkippedRefs=" + noSkippedReferences);
            return result
                    .process(proc)
                    .assertion("install_ok", rc == 0)
                    .assertion("skill_md_present", mdOk)
                    .assertion("skill_manager_toml_present", tomlOk)
                    .assertion("acp_skill_md_present", acpMdOk)
                    .assertion("acp_skill_manager_toml_present", acpTomlOk)
                    .assertion("acp_direct_git_pin_recorded", acpPinRecorded)
                    .assertion("acp_checkout_head_matches_pin", acpHeadPinned)
                    .assertion("transitive_closure_resolved", transitiveClosureResolved)
                    .assertion("no_skipped_transitive_references", noSkippedReferences)
                    .metric("exitCode", rc)
                    .publish("skillDir", skillDir.toString());
        });
    }

    private static CommandResult run(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.directory(dir.toFile());
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            return new CommandResult(p.waitFor(), output);
        } catch (Exception e) {
            return new CommandResult(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private record CommandResult(int rc, String output) {}

    /**
     * The unit count from the installer's {@code resolve: N unit(s)} line,
     * or {@code -1} when the line is absent.
     *
     * <p>Parsed rather than string-matched on an exact count. The closure
     * size is set by a live upstream repository: {@code hyper-experiments}
     * declares {@code deploy-helm}, which declares
     * {@code tracing-observability}, and those references are unpinned
     * branch refs. An exact-count assertion therefore fails whenever any
     * upstream skill gains a dependency — which is what happened here: the
     * closure grew from 2 to 4 with no change on this side. What this node
     * is actually for is that transitive resolution ran and pulled in more
     * than the root unit; the identity of the unit it cares about,
     * {@code acp-cdc-ai-python}, is asserted separately and precisely,
     * on-disk and at its pinned sha.
     */
    private static int resolvedUnitCount(String installLog) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("resolve: (\\d+) unit\\(s\\)")
                .matcher(installLog);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Resolve {@code tool} against the supplied {@code pathEnv} string.
     * Used (instead of {@link System#getenv}) so the scrubbed PATH the
     * install subprocess will actually run with is deterministic — we
     * read the host PATH once, find the entry to remove, then write the
     * scrubbed version back into {@code pb.environment()}.
     */
    private static String locateOnPath(String pathEnv, String tool) {
        if (pathEnv == null || tool == null) return null;
        for (String part : pathEnv.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(part, tool);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    /** Same defaults as {@code HyperCheckout} (kept in step by hand). */
    private static final class HyperDefaults {
        static final String GIT_URL = "https://github.com/haydenrear/hyper-experiments-skill.git";
        static final String GIT_REF = "main";
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
