from __future__ import annotations

import abc
import asyncio
import contextlib
import logging
import os
import tempfile
from typing import Any, Dict, List, Optional

import httpx

from .models import ClientConfig, DownstreamTool

logger = logging.getLogger(__name__)

CLIENT_NAME = "virtual-mcp-gateway"
CLIENT_VERSION = "0.1.0"

_MCP_IMPORT_ERROR: Exception | None = None

try:
    from mcp import ClientSession, StdioServerParameters, types as mcp_types
    from mcp.client.sse import sse_client
    from mcp.client.stdio import stdio_client
except ImportError as exc:  # pragma: no cover - exercised only in misconfigured environments.
    ClientSession = None  # type: ignore[assignment]
    StdioServerParameters = None  # type: ignore[assignment]
    mcp_types = None  # type: ignore[assignment]
    sse_client = None  # type: ignore[assignment]
    stdio_client = None  # type: ignore[assignment]
    _MCP_IMPORT_ERROR = exc

try:
    from mcp.client.streamable_http import streamable_http_client
except ImportError as exc:  # pragma: no cover - exercised only when an older SDK is installed.
    streamable_http_client = None  # type: ignore[assignment]
    if _MCP_IMPORT_ERROR is None:
        _MCP_IMPORT_ERROR = exc

try:
    import anyio

    _BROKEN_SESSION_ERRORS: tuple = (
        anyio.ClosedResourceError,
        anyio.BrokenResourceError,
        anyio.EndOfStream,
        BrokenPipeError,
        ConnectionResetError,
        ProcessLookupError,
        EOFError,
    )
except ImportError:  # pragma: no cover - anyio is an mcp dependency.
    _BROKEN_SESSION_ERRORS = (BrokenPipeError, ConnectionResetError, ProcessLookupError, EOFError)


# Message fragments the MCP SDK + downstream stdio servers use when the
# transport pipe dies mid-request. The SDK can wrap a typed
# `anyio.ClosedResourceError` in its own `McpError` whose class isn't any
# of the broken-session types above, so the typed-isinstance check alone
# misses real pipe deaths and surfaces them as "permanent" errors. Match
# by message AND by walking the cause chain so a stdio subprocess that
# closes its pipes during an in-flight `tools/list` still triggers a
# session reopen instead of being recorded as a deploy failure.
_BROKEN_SESSION_MESSAGE_PATTERNS: tuple = (
    "connection closed",
    "broken pipe",
    "end of file",
    "endofstream",
    "stream closed",
    "stream is closed",
    "process exited",
    "process terminated",
    "subprocess exited",
)


def _is_broken_session_error(exc: BaseException | None) -> bool:
    """True if `exc` indicates the underlying transport pipe died.

    Walks the chained cause / context so an SDK wrapper around a typed
    `_BROKEN_SESSION_ERRORS` is still detected, then falls back to a
    case-insensitive substring match on the message text. Matching by
    message is intentionally broad — a false positive triggers one
    extra reopen attempt (cheap) while a false negative leaves a real
    pipe death recorded as a permanent deploy failure (the symptom this
    helper exists to fix).
    """
    if exc is None:
        return False
    seen: set[int] = set()
    cur: BaseException | None = exc
    while cur is not None and id(cur) not in seen:
        seen.add(id(cur))
        if isinstance(cur, _BROKEN_SESSION_ERRORS):
            return True
        cur = cur.__cause__ or cur.__context__
    text = str(exc).lower()
    return any(p in text for p in _BROKEN_SESSION_MESSAGE_PATTERNS)


def _stable_subprocess_cwd() -> str:
    """A working directory guaranteed to exist for spawned stdio children.

    The gateway daemon is launched by ``GatewayRuntime`` with its cwd set
    to ``<install>/virtual-mcp-gateway`` — on a Homebrew install that path
    lives under the versioned cellar (e.g. ``…/Cellar/skill-manager/0.11.0/
    libexec/share/virtual-mcp-gateway``). ``brew upgrade`` removes the old
    cellar; if the daemon is still running, its cwd now points at a deleted
    directory and every stdio child it spawns inherits that dead cwd.
    Node-based servers (``npx``) call ``process.cwd()`` on startup and die
    with ``Error: ENOENT … uv_cwd`` before the MCP handshake even begins —
    surfacing to skill-manager as ``MCP_REGISTRATION_FAILED`` (e.g. the
    ``runpod`` server under ``hyper-experiments``).

    Picking an explicit, stable cwd here breaks that inheritance. Preference
    order:
      1. ``$VMG_DATA_DIR`` — set by ``GatewayRuntime`` to a stable directory
         under ``$SKILL_MANAGER_HOME`` that is independent of any cellar.
      2. ``$HOME`` — universally available and where ``npm``/``uv`` expect
         to read their per-user config.
      3. The system tmp dir — last-resort fallback.
    """
    home = os.path.expanduser("~")
    for candidate in (
        os.environ.get("VMG_DATA_DIR"),
        home if home != "~" else None,
    ):
        if candidate and os.path.isdir(candidate):
            return candidate
    return tempfile.gettempdir()


class MCPError(RuntimeError):
    pass


class DownstreamClient(abc.ABC):
    def __init__(self, config: ClientConfig):
        self.config = config
        self._initialize_lock = asyncio.Lock()
        self._initialized = False
        self.cached_tools: Dict[str, DownstreamTool] = {}

    @property
    def server_id(self) -> str:
        return self.config.server_id

    async def ensure_initialized(self) -> None:
        async with self._initialize_lock:
            if self._initialized:
                return
            await self.initialize()
            self._initialized = True

    @abc.abstractmethod
    async def initialize(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    async def notify_initialized(self) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    async def list_tools(self, forwarded_headers: Optional[Dict[str, str]] = None) -> List[DownstreamTool]:
        raise NotImplementedError

    @abc.abstractmethod
    async def call_tool(
        self,
        tool_name: str,
        arguments: Dict[str, Any],
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        raise NotImplementedError

    async def refresh_tools(self, forwarded_headers: Optional[Dict[str, str]] = None) -> List[DownstreamTool]:
        await self.ensure_initialized()
        tools = await self.list_tools(forwarded_headers=forwarded_headers)
        self.cached_tools = {tool.path: tool for tool in tools}
        return tools

    async def close(self) -> None:
        return None

    def _normalize_tools(self, payload: List[Any]) -> List[DownstreamTool]:
        results: List[DownstreamTool] = []
        prefix = self.config.tool_path_prefix.strip("/")
        namespace = prefix or self.server_id
        for item in payload:
            raw = _model_to_dict(item)
            tool = DownstreamTool(
                server_id=self.server_id,
                tool_name=str(raw["name"]),
                description=str(raw.get("description") or ""),
                input_schema=raw.get("inputSchema") or raw.get("input_schema") or {},
                annotations=raw.get("annotations"),
                title=raw.get("title"),
                output_schema=raw.get("outputSchema") or raw.get("output_schema"),
                namespace=namespace,
                tags=list(raw.get("tags") or []),
            )
            tool.finalize()
            results.append(tool)
        return results


class MCPClientLibraryClient(DownstreamClient):
    def __init__(self, config: ClientConfig):
        super().__init__(config)
        self._persistent_session: ClientSession | None = None
        self._persistent_stack: contextlib.AsyncExitStack | None = None
        self._session_lock = asyncio.Lock()

    async def initialize(self) -> None:
        # If this transport always uses transient sessions (streamable-http,
        # SSE), skip opening a persistent session — it would just get torn
        # down on first real call and the teardown is what trips anyio's
        # cross-task cancel-scope check.
        if self._should_use_transient_session(None):
            return
        await self._ensure_persistent_session()

    async def notify_initialized(self) -> None:
        return None

    async def list_tools(self, forwarded_headers: Optional[Dict[str, str]] = None) -> List[DownstreamTool]:
        try:
            result = await self._run_with_reopen(
                forwarded_headers,
                "list",
                lambda session: self._list_tools_with_session(
                    session, forwarded_headers=forwarded_headers
                ),
            )
            return self._normalize_tools(list(result.tools))
        except Exception as exc:
            raise MCPError(f"{self.config.transport} downstream error for tools/list: {exc}") from exc

    async def call_tool(
        self,
        tool_name: str,
        arguments: Dict[str, Any],
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        try:
            result = await self._run_with_reopen(
                forwarded_headers,
                "call",
                lambda session: self._call_tool_with_session(
                    session,
                    tool_name,
                    arguments,
                    forwarded_headers=forwarded_headers,
                ),
            )
            return _model_to_dict(result)
        except Exception as exc:
            raise MCPError(f"{self.config.transport} downstream error for tools/call: {exc}") from exc

    async def _run_with_reopen(
        self,
        forwarded_headers: Optional[Dict[str, str]],
        kind: str,
        op: Any,
    ) -> Any:
        """Run `op` with one retry on broken-session transport errors.

        Each retry opens a fresh session (transient path) or a fresh
        persistent session (after discarding the broken one). The retry is
        only triggered for connection-level failures listed in
        `_BROKEN_SESSION_ERRORS`; protocol-level errors (e.g. tool returns an
        MCP error result) propagate without retry.
        """
        last_exc: Optional[BaseException] = None
        for attempt in range(2):
            try:
                if self._should_use_transient_session(forwarded_headers):
                    return await self._run_with_temporary_session(forwarded_headers, op)
                session = await self._ensure_persistent_session()
                return await op(session)
            except Exception as exc:
                if not _is_broken_session_error(exc):
                    raise
                last_exc = exc
                logger.error(
                    "%s session for %s broken on tools/%s (%s: %s); reopening",
                    self.config.transport,
                    self.server_id,
                    kind,
                    type(exc).__name__,
                    str(exc),
                )
                if not self._should_use_transient_session(forwarded_headers):
                    await self._discard_persistent_session()
        assert last_exc is not None
        raise last_exc

    async def _discard_persistent_session(self) -> None:
        async with self._session_lock:
            self._persistent_session = None
            stack, self._persistent_stack = self._persistent_stack, None
        if stack is not None:
            with contextlib.suppress(BaseException):
                await stack.aclose()

    async def close(self) -> None:
        async with self._session_lock:
            self._initialized = False
            self.cached_tools = {}
            self._persistent_session = None
            stack, self._persistent_stack = self._persistent_stack, None
        if stack is not None:
            await stack.aclose()

    async def _ensure_persistent_session(self) -> ClientSession:
        async with self._session_lock:
            if self._persistent_session is not None:
                return self._persistent_session

            stack = contextlib.AsyncExitStack()
            try:
                session = await self._open_session(stack, forwarded_headers=None)
            except Exception:
                await stack.aclose()
                raise

            self._persistent_stack = stack
            self._persistent_session = session
            return session

    async def _run_with_temporary_session(self, forwarded_headers: Optional[Dict[str, str]], operation: Any) -> Any:
        async with contextlib.AsyncExitStack() as stack:
            session = await self._open_session(stack, forwarded_headers=forwarded_headers)
            return await operation(session)

    async def _open_session(
        self,
        stack: contextlib.AsyncExitStack,
        forwarded_headers: Optional[Dict[str, str]],
    ) -> ClientSession:
        _require_mcp_sdk(self.config.transport)
        streams = await stack.enter_async_context(self._transport_context(forwarded_headers))
        read_stream, write_stream = streams[:2]
        session = ClientSession(read_stream, write_stream)
        await stack.enter_async_context(session)
        await self._initialize_session(session)
        return session

    async def _initialize_session(self, session: ClientSession) -> None:
        if not self.config.protocol_version:
            await session.initialize()
            return

        assert mcp_types is not None
        await session.send_request(
            mcp_types.ClientRequest(
                mcp_types.InitializeRequest(
                    method="initialize",
                    params=mcp_types.InitializeRequestParams(
                        protocolVersion=self.config.protocol_version,
                        capabilities=mcp_types.ClientCapabilities(
                            sampling=mcp_types.SamplingCapability(),
                            roots=mcp_types.RootsCapability(listChanged=True),
                        ),
                        clientInfo=mcp_types.Implementation(
                            name=CLIENT_NAME,
                            version=CLIENT_VERSION,
                        ),
                    ),
                )
            ),
            mcp_types.InitializeResult,
        )
        await session.send_notification(
            mcp_types.ClientNotification(
                mcp_types.InitializedNotification(method="notifications/initialized")
            )
        )

    async def _list_tools_with_session(
        self,
        session: ClientSession,
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        return await session.list_tools()

    async def _call_tool_with_session(
        self,
        session: ClientSession,
        tool_name: str,
        arguments: Dict[str, Any],
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        return await session.call_tool(tool_name, arguments=arguments)

    def _should_use_transient_session(self, forwarded_headers: Optional[Dict[str, str]]) -> bool:
        return False

    @abc.abstractmethod
    def _transport_context(self, forwarded_headers: Optional[Dict[str, str]]) -> Any:
        raise NotImplementedError


class StreamableHTTPMCPClient(MCPClientLibraryClient):
    def __init__(self, config: ClientConfig):
        super().__init__(config)
        if not config.url:
            raise ValueError(f"streamable-http client {config.server_id} requires url")
        self.endpoint = config.url

    def _should_use_transient_session(self, forwarded_headers: Optional[Dict[str, str]]) -> bool:
        # Always transient: a persistent streamable-http session is owned by
        # the asyncio task that opened it, so closing it from a different
        # task (e.g. a redeploy arriving on a new request handler) trips
        # anyio's "exit cancel scope in a different task" guard. Transient
        # sessions open+close inside the caller task, so there's nothing
        # task-owned to unwind at teardown.
        return True

    @contextlib.asynccontextmanager
    async def _transport_context(self, forwarded_headers: Optional[Dict[str, str]]) -> Any:
        _require_mcp_sdk(self.config.transport, require_streamable_http=True)
        headers = _merge_headers(self.config.headers, forwarded_headers)
        timeout = httpx.Timeout(self.config.request_timeout_seconds)
        async with httpx.AsyncClient(
            headers=headers or None,
            timeout=timeout,
            follow_redirects=True,
        ) as client:
            async with streamable_http_client(
                self.endpoint,
                http_client=client,
                terminate_on_close=False,
            ) as streams:
                yield streams


class SSEMCPClient(MCPClientLibraryClient):
    def __init__(self, config: ClientConfig):
        super().__init__(config)
        if not config.url:
            raise ValueError(f"sse client {config.server_id} requires url")
        self.endpoint = config.url

    def _should_use_transient_session(self, forwarded_headers: Optional[Dict[str, str]]) -> bool:
        # Always transient — same rationale as StreamableHTTPMCPClient.
        return True

    @contextlib.asynccontextmanager
    async def _transport_context(self, forwarded_headers: Optional[Dict[str, str]]) -> Any:
        _require_mcp_sdk(self.config.transport)
        headers = _merge_headers(self.config.headers, forwarded_headers)
        async with sse_client(
            self.endpoint,
            headers=headers or None,
            timeout=self.config.request_timeout_seconds,
            sse_read_timeout=max(self.config.request_timeout_seconds, 300),
        ) as streams:
            yield streams


class StdioMCPClient(MCPClientLibraryClient):
    """Stdio downstream client backed by a single worker task.

    Stdio MCP servers are subprocesses with a single pair of pipes; the MCP
    `ClientSession` already serializes requests over them. Owning the streams
    in one long-lived worker task fixes anyio's cross-task cancel-scope guard
    (which was tripping `ClosedResourceError` when the refresh loop and a
    request handler took turns talking to a session opened in a third task)
    while preserving the global-sticky semantics of one shared subprocess
    per deployment.
    """

    def __init__(self, config: ClientConfig):
        super().__init__(config)
        if not config.command:
            raise ValueError(f"stdio client {config.server_id} requires command")
        self._worker_task: Optional[asyncio.Task[None]] = None
        self._worker_queue: Optional[asyncio.Queue[Optional[tuple]]] = None
        self._worker_lock = asyncio.Lock()

    async def initialize(self) -> None:
        async with self._worker_lock:
            if self._worker_task is not None and not self._worker_task.done():
                return
            self._worker_queue = asyncio.Queue()
            self._worker_task = asyncio.get_event_loop().create_task(
                self._worker_loop(),
                name=f"stdio-worker-{self.server_id}",
            )

    async def list_tools(self, forwarded_headers: Optional[Dict[str, str]] = None) -> List[DownstreamTool]:
        try:
            await self.ensure_initialized()
            result = await self._dispatch(
                lambda session: self._list_tools_with_session(
                    session, forwarded_headers=forwarded_headers
                )
            )
            return self._normalize_tools(list(result.tools))
        except Exception as exc:
            raise MCPError(
                f"{self.config.transport} downstream error for tools/list: {exc}"
            ) from exc

    async def call_tool(
        self,
        tool_name: str,
        arguments: Dict[str, Any],
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        try:
            await self.ensure_initialized()
            result = await self._dispatch(
                lambda session: self._call_tool_with_session(
                    session,
                    tool_name,
                    arguments,
                    forwarded_headers=forwarded_headers,
                )
            )
            return _model_to_dict(result)
        except Exception as exc:
            raise MCPError(
                f"{self.config.transport} downstream error for tools/call: {exc}"
            ) from exc

    async def close(self) -> None:
        async with self._worker_lock:
            queue = self._worker_queue
            task = self._worker_task
            self._worker_queue = None
            self._worker_task = None
            self._initialized = False
            self.cached_tools = {}
        if queue is not None:
            await queue.put(None)
        if task is not None:
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=10)
            except asyncio.TimeoutError:
                task.cancel()
                with contextlib.suppress(BaseException):
                    await task
            except BaseException:
                # Worker task errors during close are logged by the worker itself.
                pass

    async def _dispatch(self, op: Any) -> Any:
        queue = self._worker_queue
        task = self._worker_task
        if queue is None or task is None:
            raise MCPError(f"stdio client {self.server_id} is not initialized")
        if task.done():
            cause = task.exception() if not task.cancelled() else None
            raise MCPError(
                f"stdio client {self.server_id} worker is not running"
            ) from cause
        loop = asyncio.get_event_loop()
        future: asyncio.Future[Any] = loop.create_future()
        await queue.put((op, future))
        return await future

    async def _worker_loop(self) -> None:
        assert self._worker_queue is not None
        queue = self._worker_queue
        session: Optional[ClientSession] = None
        stack: Optional[contextlib.AsyncExitStack] = None
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                op, future = item
                if future.done():
                    continue
                last_exc: Optional[BaseException] = None
                for attempt in range(2):
                    try:
                        if session is None:
                            stack = contextlib.AsyncExitStack()
                            try:
                                session = await self._open_session(
                                    stack, forwarded_headers=None
                                )
                            except BaseException:
                                with contextlib.suppress(BaseException):
                                    await stack.aclose()
                                stack = None
                                raise
                        result = await op(session)
                        future.set_result(result)
                        last_exc = None
                        break
                    except Exception as exc:
                        if not _is_broken_session_error(exc):
                            future.set_exception(exc)
                            last_exc = None
                            break
                        last_exc = exc
                        logger.error(
                            "stdio session for %s broken (%s: %s); reopening subprocess",
                            self.server_id,
                            type(exc).__name__,
                            str(exc),
                        )
                        if stack is not None:
                            with contextlib.suppress(BaseException):
                                await stack.aclose()
                            stack = None
                        session = None
                if last_exc is not None:
                    future.set_exception(last_exc)
        except asyncio.CancelledError:
            raise
        except BaseException:
            logger.exception("stdio worker for %s crashed", self.server_id)
        finally:
            if stack is not None:
                with contextlib.suppress(BaseException):
                    await stack.aclose()
            self._drain_queue_with_error(queue)

    def _drain_queue_with_error(self, queue: "asyncio.Queue[Optional[tuple]]") -> None:
        while True:
            try:
                pending = queue.get_nowait()
            except asyncio.QueueEmpty:
                return
            if pending is None:
                continue
            _, pending_future = pending
            if not pending_future.done():
                pending_future.set_exception(
                    MCPError(f"stdio client {self.server_id} worker exited")
                )

    @contextlib.asynccontextmanager
    async def _transport_context(self, forwarded_headers: Optional[Dict[str, str]]) -> Any:
        _require_mcp_sdk(self.config.transport)
        assert StdioServerParameters is not None

        params = StdioServerParameters(
            command=self.config.command[0],
            args=self.config.command[1:],
            env=getattr(self.config, "env", None),  # optional later
            cwd=_stable_subprocess_cwd(),
        )
        async with stdio_client(params) as streams:
            yield streams

    async def _call_tool_with_session(
        self,
        session: ClientSession,
        tool_name: str,
        arguments: Dict[str, Any],
        forwarded_headers: Optional[Dict[str, str]] = None,
    ) -> Any:
        assert mcp_types is not None
        payload: Dict[str, Any] = {
            "name": tool_name,
            "arguments": arguments,
        }
        if forwarded_headers:
            payload["_forwarded_headers"] = dict(forwarded_headers)
        return await session.send_request(
            mcp_types.ClientRequest(
                mcp_types.CallToolRequest(
                    method="tools/call",
                    params=mcp_types.CallToolRequestParams(**payload),
                )
            ),
            mcp_types.CallToolResult,
        )


def build_client(config: ClientConfig) -> DownstreamClient:
    transport = config.transport.lower()
    if transport in {"http", "streamable-http", "streamable_http"}:
        return StreamableHTTPMCPClient(config)
    if transport == "sse":
        return SSEMCPClient(config)
    if transport == "stdio":
        return StdioMCPClient(config)
    raise ValueError(f"Unsupported transport: {config.transport}")


def _merge_headers(*groups: Optional[Dict[str, str]]) -> Dict[str, str]:
    merged: Dict[str, str] = {}
    for group in groups:
        if group:
            merged.update(group)
    return merged


def _model_to_dict(value: Any) -> Dict[str, Any]:
    if hasattr(value, "model_dump"):
        return value.model_dump(by_alias=True, mode="json", exclude_none=True)
    if isinstance(value, dict):
        return dict(value)
    raise TypeError(f"Unsupported MCP response payload type: {type(value)!r}")


def _require_mcp_sdk(transport: str, require_streamable_http: bool = False) -> None:
    if _MCP_IMPORT_ERROR is not None and (ClientSession is None or mcp_types is None):
        raise RuntimeError(
            "The MCP Python SDK is required for downstream clients. "
            "Install a recent `mcp` package before starting the gateway."
        ) from _MCP_IMPORT_ERROR
    if require_streamable_http and streamable_http_client is None:
        raise RuntimeError(
            f"Transport {transport!r} requires an MCP SDK version that includes "
            "`mcp.client.streamable_http.streamable_http_client`."
        ) from _MCP_IMPORT_ERROR
