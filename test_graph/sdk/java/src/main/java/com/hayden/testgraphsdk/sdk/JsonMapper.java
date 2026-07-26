package com.hayden.testgraphsdk.sdk;

//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared Jackson {@link ObjectMapper} for SDK-side JSON authorship.
 *
 * <p>The SDK only emits JSON for envelopes / specs / per-process
 * records, so a single static mapper covers every callsite. Configured
 * to indent output ({@link SerializationFeature#INDENT_OUTPUT}) so
 * envelope JSON is human-readable when an operator pops it open in
 * a CI artifact.
 *
 * <p>Null inclusion is left at Jackson's {@code ALWAYS} default —
 * {@link ProcessRecord} carries semantic nulls (a {@code pid=null}
 * means "spawn never happened") that have to round-trip through the
 * envelope. Callsites that want a field omitted instead just
 * don't put it into the {@link java.util.LinkedHashMap} they hand
 * to the mapper. Easier to reason about than a global null policy.
 *
 * <p>The {@code //DEPS} directive at the top of this file is collected
 * by JBang from the {@code //SOURCES} graph, so any node script that
 * pulls the SDK in via {@code //SOURCES ../../sdk/java/.../sdk/*.java}
 * picks up jackson-databind on its classpath without needing its own
 * {@code //DEPS} line. Verified empirically: a {@code //DEPS} on a
 * source file is additive across the {@code //SOURCES} closure.
 */
public final class JsonMapper {

    private JsonMapper() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
}
