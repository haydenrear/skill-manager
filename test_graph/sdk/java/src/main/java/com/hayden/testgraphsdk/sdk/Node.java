package com.hayden.testgraphsdk.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Entry point for JBang-executed validation nodes.
 *
 * The same script serves two purposes:
 *
 *   (1) Discovery — the plugin invokes with {@code --describe-out=<path>}.
 *       We serialize the {@link NodeSpec} to that path and exit 0.
 *   (2) Execution — the plugin invokes with full context args plus
 *       {@code --result-out=<path>}. We parse a {@link NodeContext},
 *       invoke the body, write the resulting {@link NodeResult} JSON
 *       to {@code --result-out}, and exit 0.
 *
 * The envelope under {@code reportDir/envelope/} is no longer this
 * script's responsibility. The build-logic {@code PlanExecutor}
 * post-processes {@code --result-out} into the canonical envelope —
 * stamping executor-measured timing, recording the captured-stdout
 * path, and synthesizing a fallback envelope when this script never
 * gets a chance to write its result. Centralizing that authorship
 * means features like the unified {@code report.md} live in one
 * Kotlin code path, not duplicated across Java and Python SDKs.
 *
 * Usage:
 *   Node.run(args,
 *       NodeSpec.of("my.node").kind(NodeSpec.Kind.ASSERTION).dependsOn("other"),
 *       ctx -> NodeResult.pass("my.node").assertion("ok", true));
 */
public final class Node {

    private Node() {}

    public static void run(String[] args, NodeSpec spec, NodeBody body) {
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

        String resultOut = findArg(args, "--result-out=");
        if (resultOut != null) {
            writeResultOut(resultOut, result);
        }
        // Exit 0 regardless of status: the executor decides pass/fail
        // from the parsed envelope's status field. A non-zero exit
        // would be redundant for the executor and would mislead
        // operators who run a node script directly to inspect output.
        System.exit(0);
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

    private static void writeResultOut(String outPath, NodeResult result) {
        try {
            Path out = Path.of(outPath);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, result.toJson());
        } catch (Exception e) {
            // The executor will detect a missing / empty result-out
            // and synthesize an error envelope — so a write failure
            // here doesn't lose the run, it just downgrades it to a
            // synthesized "missing result-out" envelope with the
            // captured stdout as evidence.
            System.err.println("failed to write --result-out: " + e.getMessage());
        }
    }
}
