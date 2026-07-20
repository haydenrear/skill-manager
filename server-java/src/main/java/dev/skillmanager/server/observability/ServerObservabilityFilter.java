package dev.skillmanager.server.observability;

import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Outer request boundary for W3C extraction and agent-visible trace
 * identity. It is ordered ahead of Spring Security so auth failures and
 * generated OAuth endpoints receive the same correlation header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ServerObservabilityFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ServerObservability observability;

    public ServerObservabilityFilter(ServerObservability observability) {
        this.observability = observability;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        ServerObservability.Operation operation = observability.beginHttp(request);
        if (operation.hasTraceId()) {
            response.setHeader(TRACE_ID_HEADER, operation.traceId());
        }

        Scope scope = observability.makeCurrent(operation);
        boolean chainFailed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            chainFailed = true;
            throw failure;
        } finally {
            try {
                scope.close();
            } catch (Throwable ignored) {
                // Context cleanup cannot change the HTTP response.
            }
            observability.endHttp(
                    operation,
                    request.getMethod(),
                    route(request),
                    telemetryStatus(response.getStatus(), chainFailed),
                    elapsedMillis(startedAt));
        }
    }

    static int telemetryStatus(int responseStatus, boolean chainFailed) {
        return chainFailed && responseStatus < 500 ? 500 : responseStatus;
    }

    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route && !route.isBlank()) return route;
        return "unmatched";
    }

    private static double elapsedMillis(long startedAt) {
        return (double) (System.nanoTime() - startedAt) / TimeUnit.MILLISECONDS.toNanos(1);
    }
}
