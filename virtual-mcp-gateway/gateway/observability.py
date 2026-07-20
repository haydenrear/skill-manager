from __future__ import annotations

import logging
import os
import time
from contextlib import contextmanager
from dataclasses import dataclass
from typing import (
    Any,
    ContextManager,
    Iterator,
    Mapping,
    Protocol,
)

from opentelemetry import trace
from opentelemetry.context import Context as OtelContext
from opentelemetry.context import attach, detach
from opentelemetry.trace.propagation.tracecontext import (
    TraceContextTextMapPropagator,
)
from tracing_skill_observability import (
    configure_observability,
    current_trace_id,
    get_logger,
    get_meter,
    span,
)

SERVICE_NAME = "virtual-mcp-gateway"
# x-release-please-start-version
SERVICE_VERSION = "0.19.1"
# x-release-please-end
DEFAULT_FLUSH_TIMEOUT_MILLIS = 5_000

_W3C_PROPAGATOR = TraceContextTextMapPropagator()


@dataclass(frozen=True, slots=True)
class RequestTrace:
    carrier: dict[str, str]
    trace_id: str | None
    started_at: float
    method: str
    route: str


class GatewayObservabilitySink(Protocol):
    def begin_request(
        self,
        headers: Mapping[str, str],
        *,
        method: str,
        route: str,
    ) -> RequestTrace: ...

    def activate(self, request_trace: RequestTrace) -> ContextManager[None]: ...

    def finish_request(
        self,
        request_trace: RequestTrace,
        *,
        status_code: int,
        outcome: str,
    ) -> None: ...

    def flush(self, timeout_millis: int = DEFAULT_FLUSH_TIMEOUT_MILLIS) -> bool: ...


class ObservabilityRuntimeHandle(Protocol):
    def inject(
        self,
        carrier: dict[str, str] | None = None,
    ) -> Mapping[str, str]: ...

    def extract(self, carrier: Mapping[str, str] | None) -> Any: ...

    def flush(self, timeout_millis: int = DEFAULT_FLUSH_TIMEOUT_MILLIS) -> bool: ...


class DisabledGatewayObservability:
    """No-provider sink used by direct constructors and fail-open startup."""

    def __init__(self) -> None:
        self._process_carrier = process_w3c_carrier()

    def begin_request(
        self,
        headers: Mapping[str, str],
        *,
        method: str,
        route: str,
    ) -> RequestTrace:
        carrier = w3c_carrier(headers) or dict(self._process_carrier)
        return RequestTrace(
            carrier=carrier,
            trace_id=trace_id_from_carrier(carrier),
            started_at=time.monotonic(),
            method=method,
            route=route,
        )

    @contextmanager
    def activate(self, request_trace: RequestTrace) -> Iterator[None]:
        del request_trace
        yield

    def finish_request(
        self,
        request_trace: RequestTrace,
        *,
        status_code: int,
        outcome: str,
    ) -> None:
        del request_trace, status_code, outcome

    def flush(self, timeout_millis: int = DEFAULT_FLUSH_TIMEOUT_MILLIS) -> bool:
        del timeout_millis
        return True


class GatewayObservability:
    """Thin gateway adapter over the shared observability provider."""

    def __init__(
        self,
        handle: ObservabilityRuntimeHandle,
        *,
        meter: Any,
        logger: logging.Logger,
    ) -> None:
        self._handle = handle
        self._logger = logger
        self._request_counter = meter.create_counter(
            "virtual_mcp_gateway.request.count",
            unit="{request}",
            description="Completed virtual MCP gateway HTTP requests.",
        )
        self._request_duration = meter.create_histogram(
            "virtual_mcp_gateway.request.duration",
            unit="s",
            description="Virtual MCP gateway HTTP request duration.",
        )
        self._process_carrier = self._start_process_marker()

    @property
    def process_carrier(self) -> dict[str, str]:
        return dict(self._process_carrier)

    def _start_process_marker(self) -> dict[str, str]:
        incoming = process_w3c_carrier()
        carrier = dict(incoming)
        token = None
        try:
            token = attach(self._handle.extract(incoming))
            with span("virtual_mcp_gateway.process.started"):
                injected = w3c_carrier(self._handle.inject({}))
                if injected:
                    carrier = injected
                self._logger.info(
                    "virtual_mcp_gateway.process.started",
                    extra={
                        "event.name": "virtual_mcp_gateway.process.started",
                    },
                )
        except Exception:
            self._logger.exception(
                "virtual_mcp_gateway.observability.process_marker_failed",
                extra={
                    "event.name": (
                        "virtual_mcp_gateway.observability.process_marker_failed"
                    ),
                },
            )
        finally:
            if token is not None:
                try:
                    detach(token)
                except Exception:
                    self._logger.exception(
                        "virtual_mcp_gateway.observability.context_detach_failed"
                    )
        return carrier

    def begin_request(
        self,
        headers: Mapping[str, str],
        *,
        method: str,
        route: str,
    ) -> RequestTrace:
        started_at = time.monotonic()
        incoming = w3c_carrier(headers) or dict(self._process_carrier)
        fallback_trace_id = trace_id_from_carrier(incoming)
        carrier = dict(incoming)
        trace_id = fallback_trace_id
        token = None
        try:
            token = attach(self._handle.extract(incoming))
            attributes = {
                "http.request.method": method,
                "http.route": route,
            }
            with span(
                "virtual_mcp_gateway.request.accepted",
                **attributes,
            ):
                injected = w3c_carrier(self._handle.inject({}))
                if injected:
                    carrier = injected
                trace_id = (
                    current_trace_id()
                    or trace_id_from_carrier(carrier)
                    or fallback_trace_id
                )
                self._logger.info(
                    "virtual_mcp_gateway.request.accepted",
                    extra={
                        "event.name": "virtual_mcp_gateway.request.accepted",
                        **attributes,
                    },
                )
        except Exception:
            self._logger.exception(
                "virtual_mcp_gateway.observability.request_begin_failed",
                extra={
                    "event.name": (
                        "virtual_mcp_gateway.observability.request_begin_failed"
                    ),
                    "http.request.method": method,
                    "http.route": route,
                },
            )
        finally:
            if token is not None:
                try:
                    detach(token)
                except Exception:
                    self._logger.exception(
                        "virtual_mcp_gateway.observability.context_detach_failed"
                    )
        return RequestTrace(
            carrier=w3c_carrier(carrier),
            trace_id=trace_id,
            started_at=started_at,
            method=method,
            route=route,
        )

    @contextmanager
    def activate(self, request_trace: RequestTrace) -> Iterator[None]:
        token = None
        try:
            token = attach(self._handle.extract(request_trace.carrier))
        except Exception:
            self._logger.exception(
                "virtual_mcp_gateway.observability.context_attach_failed"
            )
        try:
            yield
        finally:
            if token is not None:
                try:
                    detach(token)
                except Exception:
                    self._logger.exception(
                        "virtual_mcp_gateway.observability.context_detach_failed"
                    )

    def finish_request(
        self,
        request_trace: RequestTrace,
        *,
        status_code: int,
        outcome: str,
    ) -> None:
        attributes = {
            "http.request.method": request_trace.method,
            "http.route": request_trace.route,
            "http.response.status_code": status_code,
            "request.outcome": outcome,
        }
        token = None
        try:
            token = attach(self._handle.extract(request_trace.carrier))
            elapsed = max(0.0, time.monotonic() - request_trace.started_at)
            self._request_counter.add(1, attributes)
            self._request_duration.record(elapsed, attributes)
            with span(
                "virtual_mcp_gateway.request.completed",
                **attributes,
            ):
                self._logger.info(
                    "virtual_mcp_gateway.request.completed",
                    extra={
                        "event.name": "virtual_mcp_gateway.request.completed",
                        **attributes,
                        "request.duration_seconds": elapsed,
                    },
                )
        except Exception:
            self._logger.exception(
                "virtual_mcp_gateway.observability.request_finish_failed",
                extra={
                    "event.name": (
                        "virtual_mcp_gateway.observability.request_finish_failed"
                    ),
                    "http.request.method": request_trace.method,
                    "http.route": request_trace.route,
                },
            )
        finally:
            if token is not None:
                try:
                    detach(token)
                except Exception:
                    self._logger.exception(
                        "virtual_mcp_gateway.observability.context_detach_failed"
                    )

    def flush(self, timeout_millis: int = DEFAULT_FLUSH_TIMEOUT_MILLIS) -> bool:
        try:
            return bool(self._handle.flush(timeout_millis=timeout_millis))
        except Exception:
            self._logger.exception(
                "virtual_mcp_gateway.observability.flush_failed",
                extra={
                    "event.name": "virtual_mcp_gateway.observability.flush_failed"
                },
            )
            return False


def configure_gateway_observability(
    log_level: str = "INFO",
) -> GatewayObservabilitySink:
    """Configure the shared provider once at the production entrypoint."""

    try:
        handle = configure_observability(
            service_name=SERVICE_NAME,
            service_version=SERVICE_VERSION,
            log_level=log_level,
            log_mode=os.getenv("VMG_OBSERVABILITY_LOG_MODE", "otlp"),
            metrics_enabled=True,
        )
        observability = GatewayObservability(
            handle,
            meter=get_meter(SERVICE_NAME, SERVICE_VERSION),
            logger=get_logger(__name__),
        )
        _publish_process_carrier(observability.process_carrier)
        return observability
    except Exception:
        logging.getLogger(__name__).exception(
            "virtual_mcp_gateway.observability.configure_failed",
            extra={
                "event.name": "virtual_mcp_gateway.observability.configure_failed"
            },
        )
        return DisabledGatewayObservability()


def w3c_carrier(headers: Mapping[str, str] | None) -> dict[str, str]:
    """Canonicalize a valid carrier with the maintained W3C propagator."""

    normalized = {
        str(key).lower(): str(value).strip()
        for key, value in (headers or {}).items()
    }
    try:
        context = _W3C_PROPAGATOR.extract(normalized, context=OtelContext())
        if not trace.get_current_span(context).get_span_context().is_valid:
            return {}
        carrier: dict[str, str] = {}
        _W3C_PROPAGATOR.inject(carrier, context=context)
    except Exception:
        logging.getLogger(__name__).exception(
            "virtual_mcp_gateway.observability.w3c_carrier_failed",
            extra={
                "event.name": (
                    "virtual_mcp_gateway.observability.w3c_carrier_failed"
                ),
            },
        )
        return {}
    return {
        str(key).lower(): str(value)
        for key, value in carrier.items()
        if str(key).lower() in {"traceparent", "tracestate"}
    }


def process_w3c_carrier() -> dict[str, str]:
    """Read the CLI-injected process parent from standard W3C env keys."""

    return w3c_carrier(os.environ)


def _publish_process_carrier(carrier: Mapping[str, str]) -> None:
    """Retain the bounded startup marker for background child boundaries."""

    propagated = w3c_carrier(carrier)
    if not propagated:
        return
    for key in ("traceparent", "tracestate"):
        value = propagated.get(key)
        if value is None:
            os.environ.pop(key, None)
            os.environ.pop(key.upper(), None)
            continue
        os.environ[key] = value
        os.environ[key.upper()] = value


def trace_id_from_carrier(carrier: Mapping[str, str] | None) -> str | None:
    normalized = w3c_carrier(carrier)
    traceparent = normalized.get("traceparent")
    if traceparent is None:
        return None
    return traceparent.split("-", 3)[1]
