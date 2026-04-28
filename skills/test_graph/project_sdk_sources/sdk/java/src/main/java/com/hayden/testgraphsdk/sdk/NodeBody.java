package com.hayden.testgraphsdk.sdk;

/**
 * Throwing functional interface for a node's body.
 *
 * <p>Unlike {@link java.util.function.Function}, {@code apply} declares
 * {@code throws Exception}, so a node can do I/O (subprocesses, files, HTTP)
 * without wrapping the whole lambda in try/catch. Any exception that
 * escapes the body is caught by {@link Node#run} and converted to a
 * {@link NodeResult#error(String, Throwable)} envelope — pointed at the
 * runtime node id, so the id can't drift from the spec.
 */
@FunctionalInterface
public interface NodeBody {
    NodeResult apply(NodeContext ctx) throws Exception;
}
