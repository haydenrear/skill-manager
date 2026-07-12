///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** External regression matrix for resolver cycles whose coord names differ from unit names. */
public class ResolverCyclesVerified {
    static final NodeSpec SPEC = NodeSpec.of("resolver.cycles.verified")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("resolver", "cycle", "termination", "issue-109")
            .sideEffects("fs:tmp", "net:local")
            .timeout("180s");

    static final Duration CASE_TIMEOUT = Duration.ofSeconds(25);
    static String gatewayUrl;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String parentHome = ctx.get("env.prepared", "home").orElse(null);
            if (parentHome == null) {
                return NodeResult.fail(SPEC.id(), "missing env.prepared.home");
            }

            HttpServer gateway = null;
            List<CaseResult> results = new ArrayList<>();
            try {
                gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                gateway.createContext("/health", exchange -> {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
                gateway.start();
                gatewayUrl = "http://127.0.0.1:" + gateway.getAddress().getPort();

                Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
                Path cli = repoRoot.resolve("skill-manager");
                results.add(runSelfCycle(cli, Path.of(parentHome)));
                results.add(runTwoSkillCycle(cli, Path.of(parentHome)));
                results.add(runThreeSkillCycle(cli, Path.of(parentHome)));
                results.add(runSkillPluginCycle(cli, Path.of(parentHome)));
                results.add(runPluginPluginCycle(cli, Path.of(parentHome)));
            } catch (Exception e) {
                return NodeResult.error(SPEC.id(), e);
            } finally {
                if (gateway != null) gateway.stop(0);
            }

            boolean pass = results.stream().allMatch(CaseResult::passed);
            NodeResult out = pass
                    ? NodeResult.pass(SPEC.id())
                    : NodeResult.fail(SPEC.id(), results.stream()
                            .filter(result -> !result.passed())
                            .map(CaseResult::summary)
                            .reduce((left, right) -> left + "; " + right)
                            .orElse("cycle matrix failed"));
            for (CaseResult result : results) {
                String id = result.id();
                out.assertion(id + ".terminated", result.terminated())
                        .assertion(id + ".install_exit_zero", result.exitCode() == 0)
                        .assertion(id + ".cycle_path_reported", result.cycleReported())
                        .assertion(id + ".each_unit_installed_once", result.installedOnce())
                        .assertion(id + ".no_stage_dirs_leaked", result.noStageDirs())
                        .assertion(id + ".gateway_stopped", result.gatewayStopped())
                        .log(result.summary());
            }
            return out.metric("cases", results.size());
        });
    }

    private static CaseResult runSelfCycle(Path cli, Path parentHome) throws Exception {
        CaseFixture fixture = fixture(parentHome, "self-cycle");
        Path self = fixture.repos().resolve("coord-self-repository");
        scaffoldSkill(self, "semantic-self-unit", self.toString());
        return execute(cli, fixture, self, List.of(new ExpectedUnit("skills", "semantic-self-unit")));
    }

    private static CaseResult runTwoSkillCycle(Path cli, Path parentHome) throws Exception {
        CaseFixture fixture = fixture(parentHome, "two-skill-cycle");
        Path alpha = fixture.repos().resolve("coord-alpha-repository");
        Path beta = fixture.repos().resolve("coord-beta-repository");
        scaffoldSkill(alpha, "semantic-alpha-unit", "file:" + beta);
        scaffoldSkill(beta, "semantic-beta-unit", alpha.toString());
        return execute(cli, fixture, alpha, List.of(
                new ExpectedUnit("skills", "semantic-alpha-unit"),
                new ExpectedUnit("skills", "semantic-beta-unit")));
    }

    private static CaseResult runThreeSkillCycle(Path cli, Path parentHome) throws Exception {
        CaseFixture fixture = fixture(parentHome, "three-skill-cycle");
        Path first = fixture.repos().resolve("coord-first-repository");
        Path second = fixture.repos().resolve("coord-second-repository");
        Path third = fixture.repos().resolve("coord-third-repository");
        scaffoldSkill(first, "semantic-first-unit", second.toString());
        scaffoldSkill(second, "semantic-second-unit", "file:" + third);
        scaffoldSkill(third, "semantic-third-unit", "file:" + first);
        return execute(cli, fixture, first, List.of(
                new ExpectedUnit("skills", "semantic-first-unit"),
                new ExpectedUnit("skills", "semantic-second-unit"),
                new ExpectedUnit("skills", "semantic-third-unit")));
    }

    private static CaseResult runSkillPluginCycle(Path cli, Path parentHome) throws Exception {
        CaseFixture fixture = fixture(parentHome, "skill-plugin-cycle");
        Path skill = fixture.repos().resolve("coord-skill-repository");
        Path plugin = fixture.repos().resolve("coord-plugin-repository");
        scaffoldSkill(skill, "semantic-skill-unit", "file:" + plugin);
        scaffoldPlugin(plugin, "semantic-plugin-unit", skill.toString());
        return execute(cli, fixture, skill, List.of(
                new ExpectedUnit("skills", "semantic-skill-unit"),
                new ExpectedUnit("plugins", "semantic-plugin-unit")));
    }

    private static CaseResult runPluginPluginCycle(Path cli, Path parentHome) throws Exception {
        CaseFixture fixture = fixture(parentHome, "plugin-plugin-cycle");
        Path left = fixture.repos().resolve("coord-left-plugin-repository");
        Path right = fixture.repos().resolve("coord-right-plugin-repository");
        scaffoldPlugin(left, "semantic-left-plugin", right.toString());
        scaffoldPlugin(right, "semantic-right-plugin", "file:" + left);
        return execute(cli, fixture, left, List.of(
                new ExpectedUnit("plugins", "semantic-left-plugin"),
                new ExpectedUnit("plugins", "semantic-right-plugin")));
    }

    private static CaseFixture fixture(Path parentHome, String id) throws Exception {
        Path root = parentHome.resolve("resolver-cycle-matrix").resolve(id);
        Path home = root.resolve("home");
        Path repos = root.resolve("repos");
        Path output = root.resolve("install.log");
        Files.createDirectories(home);
        Files.createDirectories(repos);
        Files.writeString(home.resolve("policy.toml"), """
                require_confirmation = false
                [install]
                require_confirmation_for_hooks = false
                require_confirmation_for_mcp = false
                require_confirmation_for_cli_deps = false
                require_confirmation_for_executable_commands = false
                """);
        return new CaseFixture(id, home, repos, output);
    }

    private static void scaffoldSkill(Path dir, String name, String reference) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: resolver cycle graph fixture
                ---
                Fixture.
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "resolver cycle graph fixture"
                skill_references = ["%s"]
                """.formatted(name, toml(reference)));
    }

    private static void scaffoldPlugin(Path dir, String name, String reference) throws Exception {
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), """
                {"name":"%s","version":"0.1.0","description":"resolver cycle graph fixture"}
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "resolver cycle graph fixture"
                references = ["%s"]
                """.formatted(name, toml(reference)));
    }

    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static CaseResult execute(
            Path cli,
            CaseFixture fixture,
            Path entry,
            List<ExpectedUnit> expected
    ) throws Exception {
        Path agentHome = fixture.home().resolve("agent-home");
        Files.createDirectories(agentHome.resolve(".codex"));
        Files.createDirectories(agentHome.resolve(".gemini"));
        ProcessBuilder builder = new ProcessBuilder(
                cli.toString(), "install", "file:" + entry, "--yes", "--no-bind-default")
                .redirectErrorStream(true)
                .redirectOutput(fixture.output().toFile());
        builder.environment().put("SKILL_MANAGER_HOME", fixture.home().toString());
        builder.environment().put("SKILL_MANAGER_INSTALL_DIR", cli.getParent().toString());
        builder.environment().put("SKILL_MANAGER_GATEWAY_URL", gatewayUrl);
        builder.environment().put("CLAUDE_HOME", agentHome.toString());
        builder.environment().put("CODEX_HOME", agentHome.resolve(".codex").toString());
        builder.environment().put("GEMINI_HOME", agentHome.resolve(".gemini").toString());

        Process process = builder.start();
        boolean terminated = process.waitFor(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!terminated) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        int exitCode = terminated ? process.exitValue() : -1;
        String output = Files.exists(fixture.output()) ? Files.readString(fixture.output()) : "";
        boolean cycleReported = output.contains("reference cycle:")
                && expected.stream().allMatch(unit -> output.contains(unit.name()));
        boolean installedOnce = expected.stream().allMatch(unit ->
                Files.isDirectory(fixture.home().resolve(unit.kindDir()).resolve(unit.name())))
                && lockContainsEachOnce(fixture.home().resolve("units.lock.toml"), expected);
        boolean noStageDirs = stageDirCount(fixture.home().resolve("cache")) == 0;
        boolean gatewayStopped = stopGateway(fixture.home());
        return new CaseResult(
                fixture.id(), terminated, exitCode, cycleReported, installedOnce, noStageDirs,
                gatewayStopped,
                output.lines().filter(line -> line.contains("cycle") || line.contains("failed"))
                        .reduce((left, right) -> left + " | " + right).orElse(""));
    }

    private static boolean stopGateway(Path home) {
        Path pidFile = home.resolve("gateway.pid");
        try {
            if (!Files.isRegularFile(pidFile)) return true;
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null && handle.isAlive()) {
                handle.destroy();
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (handle.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                if (handle.isAlive()) handle.destroyForcibly();
            }
            Files.deleteIfExists(pidFile);
            return handle == null || !handle.isAlive();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean lockContainsEachOnce(Path lock, List<ExpectedUnit> expected) {
        try {
            String text = Files.readString(lock);
            return expected.stream().allMatch(unit ->
                    occurrences(text, "name = \"" + unit.name() + "\"") == 1);
        } catch (Exception e) {
            return false;
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static long stageDirCount(Path cache) {
        if (!Files.isDirectory(cache)) return 0;
        try (Stream<Path> entries = Files.list(cache)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("stage-")).count();
        } catch (Exception e) {
            return -1;
        }
    }

    private record CaseFixture(String id, Path home, Path repos, Path output) {}
    private record ExpectedUnit(String kindDir, String name) {}
    private record CaseResult(
            String id,
            boolean terminated,
            int exitCode,
            boolean cycleReported,
            boolean installedOnce,
            boolean noStageDirs,
            boolean gatewayStopped,
            String detail
    ) {
        boolean passed() {
            return terminated && exitCode == 0 && cycleReported && installedOnce
                    && noStageDirs && gatewayStopped;
        }

        String summary() {
            return id + " terminated=" + terminated + " exit=" + exitCode
                    + " cycle=" + cycleReported + " installedOnce=" + installedOnce
                    + " noStages=" + noStageDirs + " gatewayStopped=" + gatewayStopped
                    + (detail.isBlank() ? "" : " output=" + detail);
        }
    }
}
