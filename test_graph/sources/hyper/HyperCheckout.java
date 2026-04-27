///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Acquires a clean copy of {@code hyper-experiments-skill} for the rest of
 * the graph to publish + install.
 *
 * <p>Default: {@code git clone} the public repo at
 * {@code https://github.com/haydenrear/hyper-experiments-skill.git} (override
 * via env vars {@code HYPER_GIT_URL} and {@code HYPER_GIT_REF}). Sources
 * cleanly so the test exercises the same artifact a third party would
 * install from.
 *
 * <p>Local-iteration override: set {@code HYPER_LOCAL_DIR} to a directory
 * containing {@code SKILL.md} + {@code skill-manager.toml} (typically the
 * outer hyper-experiments-skill checkout). The node copies that tree
 * verbatim, skipping {@code .git/} and {@code libs/} to keep the published
 * tarball small. Use this when validating uncommitted manifest changes
 * before pushing to GitHub.
 */
public class HyperCheckout {
    static final String DEFAULT_GIT_URL =
            "https://github.com/haydenrear/hyper-experiments-skill.git";
    static final String DEFAULT_GIT_REF = "main";

    static final NodeSpec SPEC = NodeSpec.of("hyper.checkout")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("env.prepared")
            .tags("hyper", "git", "checkout")
            .sideEffects("net:remote", "fs:write")
            .timeout("90s")
            .output("skillDir", "string")
            .output("source", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("hyper.checkout", "missing env.prepared context");
            }

            Path checkoutDir = Path.of(home).resolve("hyper-checkout");
            Files.createDirectories(checkoutDir.getParent());

            String localDir = System.getenv("HYPER_LOCAL_DIR");
            if (localDir != null && !localDir.isBlank()) {
                return checkoutLocal(ctx, Path.of(localDir), checkoutDir);
            }

            String url = envOr("HYPER_GIT_URL", DEFAULT_GIT_URL);
            String ref = envOr("HYPER_GIT_REF", DEFAULT_GIT_REF);
            return checkoutGit(ctx, url, ref, checkoutDir);
        });
    }

    private static NodeResult checkoutGit(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            String url, String ref, Path dest) throws Exception {
        if (Files.exists(dest)) {
            // Per-run home is already fresh, but a partial clone could be
            // here from a previous failed attempt — be defensive.
            deleteRecursive(dest);
        }

        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", "--branch", ref,
                url, dest.toString());
        int rc = Procs.runLogged(ctx, "git-clone", pb);
        if (rc != 0) {
            return Procs.attach(
                    NodeResult.fail("hyper.checkout",
                            "git clone " + url + "@" + ref + " exited " + rc),
                    ctx, "git-clone", rc, 200);
        }

        boolean manifestOk = Files.isRegularFile(dest.resolve("skill-manager.toml"));
        boolean skillMdOk = Files.isRegularFile(dest.resolve("SKILL.md"));
        boolean pass = manifestOk && skillMdOk;

        NodeResult result = pass
                ? NodeResult.pass("hyper.checkout")
                : NodeResult.fail("hyper.checkout",
                        "missing manifest/SKILL.md after clone (toml=" + manifestOk
                                + " md=" + skillMdOk + ")");

        return Procs.attach(result, ctx, "git-clone", rc, 200)
                .assertion("clone_ok", rc == 0)
                .assertion("skill_md_present", skillMdOk)
                .assertion("manifest_present", manifestOk)
                .metric("ref", 0)
                .publish("skillDir", dest.toString())
                .publish("source", "github:" + url + "@" + ref);
    }

    private static NodeResult checkoutLocal(
            com.hayden.testgraphsdk.sdk.NodeContext ctx,
            Path src, Path dest) throws Exception {
        if (!Files.isDirectory(src)) {
            return NodeResult.fail("hyper.checkout",
                    "HYPER_LOCAL_DIR is not a directory: " + src);
        }
        if (!Files.isRegularFile(src.resolve("skill-manager.toml"))
                || !Files.isRegularFile(src.resolve("SKILL.md"))) {
            return NodeResult.fail("hyper.checkout",
                    "HYPER_LOCAL_DIR missing SKILL.md or skill-manager.toml at " + src);
        }
        if (Files.exists(dest)) deleteRecursive(dest);
        Files.createDirectories(dest);

        // Copy the working tree but skip the heavy / irrelevant subtrees.
        // This mirrors the .gitignore (libs/ is gitignored) and avoids
        // sending a 10 MB tarball that the registry would reject.
        copySelectively(src, dest);

        boolean manifestOk = Files.isRegularFile(dest.resolve("skill-manager.toml"));
        boolean skillMdOk = Files.isRegularFile(dest.resolve("SKILL.md"));
        boolean pass = manifestOk && skillMdOk;

        NodeResult result = pass
                ? NodeResult.pass("hyper.checkout")
                : NodeResult.fail("hyper.checkout",
                        "missing manifest/SKILL.md after local copy (toml=" + manifestOk
                                + " md=" + skillMdOk + ")");

        return result
                .assertion("local_copy_ok", pass)
                .assertion("skill_md_present", skillMdOk)
                .assertion("manifest_present", manifestOk)
                .publish("skillDir", dest.toString())
                .publish("source", "local:" + src);
    }

    private static void copySelectively(Path src, Path dest) throws java.io.IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(p -> {
                Path rel = src.relativize(p);
                String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
                if (first.equals(".git") || first.equals("libs")
                        || first.equals(".idea") || first.equals(".gradle")
                        || first.equals("build") || first.equals("node_modules")
                        || first.equals(".venv")) {
                    return;
                }
                try {
                    Path target = dest.resolve(rel);
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteRecursive(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(x -> {
                        try { Files.deleteIfExists(x); }
                        catch (java.io.IOException e) { throw new RuntimeException(e); }
                    });
        }
    }

    private static String envOr(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
