from __future__ import annotations

import abc
import asyncio
import contextlib
import logging
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
        await self._ensure_persistent_session()

    async def notify_initialized(self) -> None:
        return None

    async def list_tools(self, forwarded_headers: Optional[Dict[str, str]] = None) -> List[DownstreamTool]:
        try:
            if self._should_use_transient_session(forwarded_headers):
                result = await self._run_with_temporary_session(
                    forwarded_headers,
                    lambda session: self._list_tools_with_session(session, forwarded_headers=forwarded_headers),
                )
            else:
                session = await self._ensure_persistent_session()
                result = await self._list_tools_with_session(session, forwarded_headers=forwarded_headers)
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
            if self._should_use_transient_session(forwarded_headers):
                result = await self._run_with_temporary_session(
                    forwarded_headers,
                    lambda session: self._call_tool_with_session(
                        session,
                        tool_name,
                        arguments,
                        forwarded_headers=forwarded_headers,
                    ),
                )
            else:
                session = await self._ensure_persistent_session()
                result = await self._call_tool_with_session(
                    session,
                    tool_name,
                    arguments,
                    forwarded_headers=forwarded_headers,
                )
            return _model_to_dict(result)
        except Exception as exc:
            raise MCPError(f"{self.config.transport} downstream error for tools/call: {exc}") from exc

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
        return bool(forwarded_headers)

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
        return bool(forwarded_headers)

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
    def __init__(self, config: ClientConfig):
        super().__init__(config)
        if not config.command:
            raise ValueError(f"stdio client {config.server_id} requires command")

    @contextlib.asynccontextmanager
    async def _transport_context(self, forwarded_headers: Optional[Dict[str, str]]) -> Any:
        _require_mcp_sdk(self.config.transport)
        assert StdioServerParameters is not None

        params = StdioServerParameters(
            command=self.config.command[0],
            args=self.config.command[1:],
            env=getattr(self.config, "env", None),  # optional later
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
