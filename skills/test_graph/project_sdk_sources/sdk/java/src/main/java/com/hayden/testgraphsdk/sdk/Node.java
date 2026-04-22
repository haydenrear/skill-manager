package com.hayden.testgraphsdk.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

/**
 * Entry point for JBang-executed validation nodes.
 *
 * The same script serves two purposes:
 *
 *   (1) Discovery — the plugin invokes with {@code --describe-out=<path>}.
 *       We serialize the {@link NodeSpec} to that path and exit.
 *   (2) Execution — the plugin invokes with full context args. We parse
 *       a {@link NodeContext}, invoke the body, and write the result
 *       envelope to {@code reportDir/envelope/<nodeId>.json}.
 *
 * Usage:
 *   Node.run(args,
 *       NodeSpec.of("my.node").kind(NodeSpec.Kind.ASSERTION).dependsOn("other"),
 *       ctx -> NodeResult.pass("my.node").assertion("ok", true));
 */
public final class Node {

    private Node() {}

    public static void run(String[] args, NodeSpec spec, Function<NodeContext, NodeResult> body) {
        String describeOut = findArg(args, "--describe-out=");
        if (describeOut != null) {
            writeDescribe(describeOut, spec);
            return;
        }

        NodeContext ctx = NodeContext.parse(args);
        if (!ctx.nodeId().equals(spec.id())) {
            throw new IllegalStateException(
                    "spec/runtime id mismatch: spec=" + spec.id() + ", arg=" + ctx.nodeId());
        }

        Instant startedAt = Instant.now();
        NodeResult result;
        try {
            result = body.apply(ctx).startedAt(startedAt).endedAt(Instant.now());
        } catch (Throwable t) {
            result = NodeResult.error(ctx.nodeId(), t)
                    .startedAt(startedAt)
                    .endedAt(Instant.now());
        }
        writeEnvelope(ctx, result);
        System.exit(result.status() == NodeStatus.PASSED ? 0 : 1);
    }

    private static String findArg(String[] args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return null;
    }

    private static void writeDescribe(String outPath, NodeSpec spec) {
        try {
            Path out = Path.of(outPath);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, spec.toJson());
            System.exit(0);
        } catch (Exception e) {
            System.err.println("failed to write describe output: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void writeEnvelope(NodeContext ctx, NodeResult result) {
        try {
            Path dir = ctx.reportDir().resolve("envelope");
            Files.createDirectories(dir);
            Path out = dir.resolve(ctx.nodeId() + ".json");
            Files.writeString(out, result.toJson());
        } catch (Exception e) {
            throw new RuntimeException("failed to write node envelope", e);
        }
    }
}
