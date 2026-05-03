///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

/**
 * Right after install, {@code skill-manager sync hyper-experiments}
 * (no {@code --from}, no {@code --merge}) must succeed without
 * touching the working tree. The implicit-origin path uses the
 * github URL pinned by install: a fetch returns no new commits
 * (or fast-forwards if upstream advanced in the meantime), the
 * working tree stays clean, and the existing MCP / agent refresh
 * runs.
 */
public class HyperSyncCleanNoOp {
    static final NodeSpec SPEC = NodeSpec.of("hyper.sync.clean.noop")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.source.recorded")
            .tags("hyper", "source-tracking", "sync", "clean")
            .sideEffects("net:remote")
            .timeout("90s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("hyper.sync.clean.noop", "missing upstream context");
            }
            Path storeDir = Path.of(home).resolve("skills").resolve("hyper-experiments");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "sync", "hyper-experiments")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println(line);
            }
            int rc = p.waitFor();

            String porcelain = run(storeDir, List.of("git", "status", "--porcelain"));
            boolean wtClean = porcelain.isBlank();

            boolean pass = rc == 0 && wtClean;
            return (pass
                    ? NodeResult.pass("hyper.sync.clean.noop")
                    : NodeResult.fail("hyper.sync.clean.noop",
                            "rc=" + rc + " wtClean=" + wtClean
                                    + " porcelain=[" + porcelain.trim() + "]"))
                    .assertion("sync_exit_zero", rc == 0)
                    .assertion("working_tree_clean_after_sync", wtClean);
        });
    }

    private static String run(Path dir, List<String> argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.directory(dir.toFile());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
