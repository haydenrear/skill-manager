from __future__ import annotations

import argparse
import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Annotated, Any, AsyncIterator

import uvicorn
from mcp.server.fastmcp import Context, FastMCP
from pydantic import Field
from starlette.requests import Request
from starlette.responses import JSONResponse

from .clients import MCPError
from .config import GatewayConfigModel, load_config
from .models import DEFAULT_SCOPE, InitSchemaField, VALID_SCOPES
from .persistence import DynamicServerStore, LastInitStore
from .provisioning import LoadSpec, Provisioner, default_data_dir
from .registry import ToolRegistry
from .semantic import SemanticMatcher
from pydantic import BaseModel, Field as PydField, ValidationError, field_validator
from typing import Literal

logger = logging.getLogger(__name__)

DISCLOSURE_SESSION_HEADER = "x-session-id"
MCP_SESSION_HEADER = "mcp-session-id"


class InitSchemaFieldPayload(BaseModel):
    name: str
    type: str = "string"
    description: str = ""
    required: bool = False
    secret: bool = False
    default: Any = None
    enum: list[str] = PydField(default_factory=list)


class RegisterServerRequest(BaseModel):
    server_id: str
    display_name: str | None = None
    description: str = ""
    load_spec: LoadSpec
    init_schema: list[InitSchemaFieldPayload] = PydField(default_factory=list)
    initialization_params: dict[str, Any] | None = None
    save_last_init: bool = True
    idle_timeout_seconds: int | None = 1800
    deploy: bool = False
    default_scope: str = DEFAULT_SCOPE

    @field_validator("default_scope")
    @classmethod
    def _check_scope(cls, value: str) -> str:
        if value not in VALID_SCOPES:
            raise ValueError(f"default_scope must be one of {VALID_SCOPES}")
        return value


class GatewayServer:
    def __init__(
            self,
            config: GatewayConfigModel,
            provisioner: Provisioner | None = None,
            persistence: DynamicServerStore | None = None,
    ):
        self.config = config
        servers = {item.server_id: item.to_internal() for item in config.mcp_servers}
        self.provisioner = provisioner or Provisioner(default_data_dir())
        data_dir = self.provisioner.data_dir
        self.persistence = persistence if persistence is not None else DynamicServerStore(data_dir)
        self.last_init_store = LastInitStore(data_dir)
        self.registry = ToolRegistry(
            servers=servers,
            matcher=SemanticMatcher.create(),
            provisioner=self.provisioner,
            persistence=self.persistence,
            last_init_store=self.last_init_store,
        )
        self.mcp = FastMCP(
            name="virtual-mcp-gateway",
            instructions="Virtual MCP gateway over deployable downstream MCP servers.",
            lifespan=self._lifespan,
            streamable_http_path="/mcp",
            # Plain JSON responses are cross-client compatible; SSE framing
            # has proven flaky with some Java SDK client versions.
            json_response=True,
        )
        self._register_tools()
        self._register_routes()
        self.app = self.mcp.streamable_http_app()
        # FastMCP's lifespan only runs when an MCP session opens. To make sure
        # rehydration from disk fires before the first REST request hits
        # /servers, we wrap the app in a one-shot middleware that awaits
        # registry.start() on first dispatch.
        self._app_started = False
        self._startup_lock = asyncio.Lock()
        self._wrap_app_with_startup_middleware()

    def _wrap_app_with_startup_middleware(self) -> None:
        inner = self.app

        async def middleware(scope, receive, send):
            if scope["type"] in ("http", "websocket") and not self._app_started:
                async with self._startup_lock:
                    if not self._app_started:
                        await self._app_startup()
            await inner(scope, receive, send)

        self.app = middleware  # ASGI callable replacement

    async def _app_startup(self) -> None:
        if self._app_started:
            return
        self._app_started = True
        await self.registry.start()
        logger.info(
            "Gateway started with %d catalog servers and %d global deployment(s)",
            len(self.registry.servers),
            len(self.registry.global_deployments),
        )

    async def _app_shutdown(self) -> None:
        if not self._app_started:
            return
        await self.registry.stop()

    @asynccontextmanager
    async def _lifespan(self, _: FastMCP) -> AsyncIterator[None]:
        # Delegates to the idempotent startup hook attached to the Starlette app.
        await self._app_startup()
        try:
            yield
        finally:
            # shutdown is handled by Starlette's on_shutdown; keep idempotent.
            pass

    async def startup(self) -> None:
        await self._app_startup()

    async def shutdown(self) -> None:
        await self._app_shutdown()

    async def _health_endpoint(self, _: Request) -> JSONResponse:
        return JSONResponse(self.health_payload())

    def health_payload(self) -> dict[str, Any]:
        session_deployment_count = sum(len(d) for d in self.registry.session_deployments.values())
        global_tool_count = sum(len(t) for t in self.registry.global_tools_by_server.values())
        return {
            "ok": True,
            "catalog_server_count": len(self.registry.servers),
            "deployed_server_count": len(self.registry.global_deployments),
            "session_deployment_count": session_deployment_count,
            "tool_count": global_tool_count,
            "spacy_model": self.registry.matcher.model_name,
        }

    def _register_tools(self) -> None:
        self.mcp.add_tool(self._browse_mcp_servers_tool, name="browse_mcp_servers")
        self.mcp.add_tool(self._describe_mcp_server_tool, name="describe_mcp_server")
        self.mcp.add_tool(self._deploy_mcp_server_tool, name="deploy_mcp_server")
        self.mcp.add_tool(self._undeploy_mcp_server_tool, name="undeploy_mcp_server")

        self.mcp.add_tool(self._browse_active_tools_tool, name="browse_active_tools")
        self.mcp.add_tool(self._search_tools_tool, name="search_tools")
        self.mcp.add_tool(self._describe_tool_tool, name="describe_tool")
        self.mcp.add_tool(self._invoke_tool_tool, name="invoke_tool")
        self.mcp.add_tool(self._refresh_registry_tool, name="refresh_registry")

    def _register_routes(self) -> None:
        self.mcp.custom_route("/health", methods=["GET"])(self._health_endpoint)
        self.mcp.custom_route("/servers", methods=["POST"])(self._register_server_endpoint)
        self.mcp.custom_route("/servers", methods=["GET"])(self._list_servers_endpoint)
        self.mcp.custom_route("/servers/{server_id}", methods=["GET"])(self._describe_server_endpoint)
        self.mcp.custom_route("/servers/{server_id}", methods=["DELETE"])(self._unregister_server_endpoint)

    async def _register_server_endpoint(self, request: Request) -> JSONResponse:
        try:
            body = await request.json()
        except Exception:
            return JSONResponse({"error": "invalid JSON body"}, status_code=400)
        try:
            payload = RegisterServerRequest.model_validate(body)
        except ValidationError as exc:
            details = [
                {"loc": list(err.get("loc", [])), "msg": err.get("msg"), "type": err.get("type")}
                for err in exc.errors()
            ]
            return JSONResponse({"error": "validation", "details": details}, status_code=422)

        init_schema = [
            InitSchemaField(
                name=field.name,
                type=field.type,
                description=field.description,
                required=field.required,
                secret=field.secret,
                default=field.default,
                enum=list(field.enum),
            )
            for field in payload.init_schema
        ]
        try:
            definition = await self.registry.register_dynamic_server(
                server_id=payload.server_id,
                load_spec=payload.load_spec,
                display_name=payload.display_name,
                description=payload.description,
                init_schema=init_schema,
                save_last_init=payload.save_last_init,
                idle_timeout_seconds=payload.idle_timeout_seconds,
                default_scope=payload.default_scope,  # type: ignore[arg-type]
                payload=payload.model_dump(mode="json"),
            )
        except Exception as exc:
            logger.exception("dynamic registration failed for %s", payload.server_id)
            return JSONResponse({"error": str(exc)}, status_code=500)

        response: dict[str, Any] = {
            "server_id": definition.server_id,
            "registered": True,
            "deployed": False,
            "transport": definition.client.transport,
            "default_scope": definition.default_scope,
        }

        # REST deploy is global-only: no session context available here.
        if payload.deploy:
            deploy_scope = definition.default_scope if definition.default_scope != "session" else "global"
            try:
                deployment = await self.registry.deploy_server(
                    server_id=payload.server_id,
                    scope=deploy_scope,
                    init_values=payload.initialization_params or {},
                    reuse_last=False,
                )
                response["deployed"] = True
                response["scope"] = deployment.scope
                response["initialized_at"] = deployment.initialized_at
                response["tool_count"] = len(self.registry.global_tools_by_server.get(payload.server_id, {}))
            except Exception as exc:
                logger.exception("deploy-after-register failed for %s", payload.server_id)
                response["deploy_error"] = str(exc)

        return JSONResponse(response, status_code=201)

    async def _list_servers_endpoint(self, _: Request) -> JSONResponse:
        items = []
        for server_id in sorted(self.registry.dynamic_server_ids):
            definition = self.registry.servers.get(server_id)
            if definition is None:
                continue
            items.append(
                {
                    "server_id": server_id,
                    "display_name": definition.display_name,
                    "description": definition.description,
                    "transport": definition.client.transport,
                    "default_scope": definition.default_scope,
                    "deployed": server_id in self.registry.global_deployments,
                    "deployed_globally": server_id in self.registry.global_deployments,
                    "tool_count": len(self.registry.global_tools_by_server.get(server_id, {})),
                }
            )
        return JSONResponse({"items": items, "count": len(items)})

    async def _describe_server_endpoint(self, request: Request) -> JSONResponse:
        server_id = request.path_params["server_id"]
        if server_id not in self.registry.servers:
            return JSONResponse({"error": "not found", "server_id": server_id}, status_code=404)
        # No session context on the REST path — describe returns the global view.
        payload = self.registry.describe_server(server_id, session_id=None)
        payload["registered"] = True
        payload["dynamic"] = server_id in self.registry.dynamic_server_ids
        # Surface the spec digest so clients (skill-manager's install-time
        # idempotency check) can decide whether a re-register is needed.
        dynamic_payload = self.registry.dynamic_payloads.get(server_id)
        if dynamic_payload is not None:
            from .models import stable_digest
            digest_input = {
                "load_spec": dynamic_payload.get("load_spec"),
                "init_schema": dynamic_payload.get("init_schema", []),
            }
            payload["spec_digest"] = stable_digest(digest_input)
        return JSONResponse(payload)

    async def _unregister_server_endpoint(self, request: Request) -> JSONResponse:
        server_id = request.path_params["server_id"]
        removed = await self.registry.unregister_dynamic_server(server_id)
        if not removed:
            return JSONResponse({"error": "not found or not dynamic"}, status_code=404)
        return JSONResponse({"server_id": server_id, "unregistered": True})

    async def _browse_mcp_servers_tool(
            self,
            ctx: Context,
            deployed: bool | None = None,
    ) -> dict[str, Any]:
        session_id = _session_id(_headers_from_context(ctx))
        items = self.registry.browse_servers(deployed=deployed, session_id=session_id)
        return {"items": items, "count": len(items)}

    async def _describe_mcp_server_tool(self, server_id: str, ctx: Context) -> dict[str, Any]:
        session_id = _session_id(_headers_from_context(ctx))
        return self.registry.describe_server(server_id, session_id=session_id)

    async def _deploy_mcp_server_tool(
            self,
            server_id: str,
            ctx: Context,
            scope: Literal["session", "global", "global-sticky"] | None = None,
            initialization: dict[str, Any] | None = None,
            reuse_last_initialization: bool = True,
    ) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers)
        definition = self.registry.servers.get(server_id)
        effective_scope = scope or (definition.default_scope if definition else DEFAULT_SCOPE)

        try:
            deployment = await self.registry.deploy_server(
                server_id=server_id,
                scope=effective_scope,  # type: ignore[arg-type]
                session_id=session_id if effective_scope == "session" else None,
                init_values=initialization or {},
                reuse_last=reuse_last_initialization,
            )
        except Exception as exc:
            last_error = self.registry._resolve_last_error(
                server_id,
                session_id if effective_scope == "session" else None,
            )
            raise ValueError(
                f"deploy failed for {server_id} (scope={effective_scope}): {exc}."
                + (f" last_error={last_error.to_public_dict()}" if last_error else "")
            ) from exc

        tool_count = (
            len(self.registry.session_tools_by_server.get(session_id, {}).get(server_id, {}))
            if effective_scope == "session"
            else len(self.registry.global_tools_by_server.get(server_id, {}))
        )
        return {
            "server_id": server_id,
            "deployed": True,
            "scope": deployment.scope,
            "session_id": deployment.session_id,
            "initialized_at": deployment.initialized_at,
            "last_used_at": deployment.last_used_at,
            "tool_count": tool_count,
        }

    async def _undeploy_mcp_server_tool(
            self,
            server_id: str,
            scope: Literal["session", "global", "global-sticky"],
            ctx: Context,
    ) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers) if scope == "session" else None
        await self.registry.undeploy_server(server_id, scope=scope, session_id=session_id)
        return {
            "server_id": server_id,
            "deployed": False,
            "scope": scope,
            "session_id": session_id,
        }

    async def _browse_active_tools_tool(
            self,
            ctx: Context,
            path_prefix: str = "",
            server_id: str | None = None,
            limit: Annotated[int, Field(ge=1, le=500)] = 100,
    ) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers)
        tools = self.registry.filtered_tools(
            headers=headers,
            path_prefix=path_prefix.strip(),
            session_id=session_id,
        )

        if server_id:
            tools = [tool for tool in tools if tool.server_id == server_id]

        tools = tools[:limit]
        return {
            "items": [
                {
                    "path": tool.path,
                    "tool_name": tool.tool_name,
                    "server_id": tool.server_id,
                    "description": tool.description,
                }
                for tool in tools
            ],
            "count": len(tools),
        }

    async def _search_tools_tool(
            self,
            query: str,
            ctx: Context,
            limit: Annotated[int, Field(ge=1, le=50)] = 10,
    ) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers)
        normalized_query = query.strip()
        if not normalized_query:
            raise ValueError("search_tools requires a non-empty query")
        suggestions = self.registry.suggest_tools(
            query=normalized_query,
            headers=headers,
            limit=limit,
            session_id=session_id,
        )
        return {
            "query": normalized_query,
            "matches": suggestions,
            "spacy_model": self.registry.matcher.model_name,
        }

    async def _describe_tool_tool(self, tool_path: str, ctx: Context) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers)
        normalized_tool_path = tool_path.strip()
        if not normalized_tool_path:
            raise ValueError("describe_tool requires tool_path")

        tool = self.registry.find_tool(normalized_tool_path, session_id=session_id)
        if tool is None:
            suggestions = self.registry.suggest_tools(
                normalized_tool_path, headers=headers, limit=5, session_id=session_id,
            )
            raise ValueError(f"Virtual tool not found: {normalized_tool_path}. Suggestions: {suggestions}")

        session = self.registry.get_or_create_session(session_id)
        session.last_headers = headers
        disclosure = session.disclose(tool.path, ttl_seconds=self.config.disclosure_ttl_seconds)

        server_description = self.registry.describe_server(tool.server_id, session_id=session_id)
        return {
            "tool_path": tool.path,
            "tool_name": tool.tool_name,
            "server_id": tool.server_id,
            "description": tool.description,
            "input_schema": tool.input_schema,
            "output_schema": tool.output_schema,
            "server_initialization_schema": server_description["init_schema"],
            "disclosed_at": disclosure.disclosed_at,
            "expires_at": disclosure.expires_at,
            "session_header_name": DISCLOSURE_SESSION_HEADER,
        }

    async def _invoke_tool_tool(
            self,
            tool_path: str,
            arguments: dict[str, Any],
            ctx: Context,
    ) -> dict[str, Any]:
        headers = _headers_from_context(ctx)
        session_id = _session_id(headers)
        normalized_tool_path = tool_path.strip()
        if not normalized_tool_path:
            raise ValueError("invoke_tool requires tool_path")

        tool = self.registry.find_tool(normalized_tool_path, session_id=session_id)
        if tool is None:
            suggestions = self.registry.suggest_tools(
                normalized_tool_path, headers=headers, limit=5, session_id=session_id,
            )
            raise ValueError(f"Virtual tool not found: {normalized_tool_path}. Suggestions: {suggestions}")

        session = self.registry.get_or_create_session(session_id)
        session.last_headers = headers
        if not session.validate(normalized_tool_path):
            raise ValueError(
                "Tool invocation denied. Call describe_tool first for the same path or an ancestor path in the "
                f"current {DISCLOSURE_SESSION_HEADER} session."
            )

        forwarded_headers = _forwardable_headers(headers)
        forwarded_headers["x-virtual-tool-path"] = tool.path
        forwarded_headers["x-virtual-tool-name"] = tool.tool_name
        forwarded_headers["x-virtual-tool-schema-digest"] = tool.schema_digest

        client = self.registry.get_active_client(tool.server_id, session_id=session_id)
        try:
            downstream_result = await client.call_tool(
                tool.tool_name,
                arguments,
                forwarded_headers=forwarded_headers,
            )
        except MCPError as exc:
            raise RuntimeError(f"Downstream tool call failed: {exc}") from exc

        if isinstance(downstream_result, dict):
            return downstream_result
        return {"value": downstream_result}

    async def _refresh_registry_tool(self) -> dict[str, Any]:
        await self.registry.refresh_all()
        session_deployment_count = sum(len(d) for d in self.registry.session_deployments.values())
        global_tool_count = sum(len(t) for t in self.registry.global_tools_by_server.values())
        return {
            "refreshed": True,
            "deployed_server_count": len(self.registry.global_deployments),
            "session_deployment_count": session_deployment_count,
            "tool_count": global_tool_count,
        }


def _session_id(headers: dict[str, str]) -> str:
    return headers.get(DISCLOSURE_SESSION_HEADER) or headers.get(MCP_SESSION_HEADER) or "default"


def _normalize_headers(headers: dict[str, str]) -> dict[str, str]:
    return {str(key).lower(): str(value) for key, value in headers.items()}


def _forwardable_headers(headers: dict[str, str]) -> dict[str, str]:
    blocked = {
        "accept",
        "connection",
        "content-length",
        "content-type",
        "host",
        "last-event-id",
        "mcp-protocol-version",
        MCP_SESSION_HEADER,
        DISCLOSURE_SESSION_HEADER,
    }
    return {key: value for key, value in headers.items() if key not in blocked}


def _headers_from_context(ctx: Context) -> dict[str, str]:
    request = ctx.request_context.request
    if request is None:
        return {}
    raw_headers = getattr(request, "headers", None)
    if raw_headers is None:
        return {}
    return _normalize_headers(dict(raw_headers))


def build_app(config_path: str) -> Any:
    config = load_config(config_path)
    gateway = GatewayServer(config)
    return gateway.app


def main() -> None:
    parser = argparse.ArgumentParser(description="Virtual MCP Gateway")
    parser.add_argument("--config", required=True, help="Path to gateway JSON config")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=51717)
    parser.add_argument("--log-level", default="info")
    args = parser.parse_args()

    logging.basicConfig(level=getattr(logging, args.log_level.upper(), logging.INFO))
    app = build_app(args.config)
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()