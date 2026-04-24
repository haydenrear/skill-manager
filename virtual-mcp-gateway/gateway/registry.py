from __future__ import annotations

import asyncio
import dataclasses
import logging
import time
from typing import Dict, Iterable, List, Optional, Tuple

from .clients import DownstreamClient, build_client
from .models import (
    ClientConfig,
    DEFAULT_SCOPE,
    DeploymentError,
    DownstreamTool,
    InitSchemaField,
    MCPServerDefinition,
    MCPServerDeployment,
    Scope,
    SessionState,
    VALID_SCOPES,
)
from .persistence import DynamicServerStore, LastInitStore
from .provisioning import LoadSpec, Provisioner
from .rwlock import AsyncRWLock
from .semantic import SemanticMatcher

logger = logging.getLogger(__name__)


GLOBAL_SESSION_KEY = "__global__"


class ToolRegistry:
    def __init__(
            self,
            servers: Dict[str, MCPServerDefinition],
            matcher: SemanticMatcher,
            provisioner: Provisioner | None = None,
            persistence: DynamicServerStore | None = None,
            last_init_store: LastInitStore | None = None,
    ):
        self.servers = servers
        self.matcher = matcher
        self.provisioner = provisioner
        self.persistence = persistence
        self.last_init_store = last_init_store

        # Global deployments: shared across sessions. Keyed by server_id.
        self.global_clients: Dict[str, DownstreamClient] = {}
        self.global_deployments: Dict[str, MCPServerDeployment] = {}
        self.global_tools_by_server: Dict[str, Dict[str, DownstreamTool]] = {}
        self.global_errors: Dict[str, DeploymentError] = {}

        # Session-scoped deployments: isolated per agent session. Keyed by
        # (session_id, server_id).
        self.session_clients: Dict[str, Dict[str, DownstreamClient]] = {}
        self.session_deployments: Dict[str, Dict[str, MCPServerDeployment]] = {}
        self.session_tools_by_server: Dict[str, Dict[str, Dict[str, DownstreamTool]]] = {}
        self.session_errors: Dict[str, Dict[str, DeploymentError]] = {}

        # Last-deployed init values, per server_id. Populated only by global
        # and global-sticky deploys. global-sticky values are also mirrored to
        # disk via last_init_store so they survive gateway restart.
        self.last_init_by_server: Dict[str, dict] = {}

        self.dynamic_server_ids: set[str] = set()
        self.load_specs: Dict[str, LoadSpec] = {}
        self.dynamic_payloads: Dict[str, dict] = {}
        self.sessions: Dict[str, SessionState] = {}

        self._refresh_task: asyncio.Task[None] | None = None
        self._lock = asyncio.Lock()
        self._reg_rwlock = AsyncRWLock()

        if self.last_init_store is not None:
            try:
                self.last_init_by_server.update(self.last_init_store.load())
            except Exception:
                logger.exception("failed to load last-init store; starting empty")

    # ------------------------------------------------------------------ registration

    async def register_dynamic_server(
            self,
            server_id: str,
            load_spec: LoadSpec,
            display_name: str | None = None,
            description: str = "",
            init_schema: List[InitSchemaField] | None = None,
            save_last_init: bool = True,
            idle_timeout_seconds: int | None = 1800,
            default_scope: Scope = DEFAULT_SCOPE,
            payload: dict | None = None,
    ) -> MCPServerDefinition:
        if self.provisioner is None:
            raise RuntimeError("dynamic registration requires a Provisioner")

        if default_scope not in VALID_SCOPES:
            raise ValueError(f"invalid default_scope: {default_scope!r}")

        async with self._reg_rwlock.write():
            # Re-registration: drop any existing global deployment so the next
            # deploy picks up fresh client config. Per-session deployments are
            # left in place intentionally — they're owned by live agents.
            if server_id in self.global_deployments:
                logger.info("re-registering %s — tearing down existing global deployment", server_id)
                try:
                    await self.undeploy_server(server_id, scope="global")
                except Exception:
                    logger.exception("undeploy failed during re-register for %s", server_id)

            client_config = await self.provisioner.provision(server_id, load_spec)
            definition = MCPServerDefinition(
                server_id=server_id,
                display_name=display_name or server_id,
                description=description,
                client=client_config,
                init_schema=list(init_schema or []),
                save_last_init=save_last_init,
                idle_timeout_seconds=idle_timeout_seconds,
                auto_deploy=False,
                default_scope=default_scope,
            )
            async with self._lock:
                self.servers[server_id] = definition
                self.dynamic_server_ids.add(server_id)
                self.load_specs[server_id] = load_spec
                if payload is not None:
                    self.dynamic_payloads[server_id] = payload

            if self.persistence is not None and payload is not None:
                try:
                    self.persistence.upsert(payload)
                except Exception:
                    logger.exception("failed to persist dynamic server %s", server_id)

            return definition

    async def unregister_dynamic_server(self, server_id: str) -> bool:
        if server_id not in self.dynamic_server_ids:
            return False
        async with self._reg_rwlock.write():
            # Tear down every deployment of this server regardless of scope.
            try:
                await self._undeploy_all_scopes(server_id)
            except Exception:
                logger.exception("undeploy failed during unregister for %s", server_id)
            async with self._lock:
                self.servers.pop(server_id, None)
                self.dynamic_server_ids.discard(server_id)
                self.load_specs.pop(server_id, None)
                self.last_init_by_server.pop(server_id, None)
                self.dynamic_payloads.pop(server_id, None)
                self.global_errors.pop(server_id, None)
                for session_errs in self.session_errors.values():
                    session_errs.pop(server_id, None)

            if self.last_init_store is not None:
                try:
                    self.last_init_store.remove(server_id)
                except Exception:
                    logger.exception("failed to remove last-init entry for %s", server_id)

            if self.persistence is not None:
                try:
                    self.persistence.remove(server_id)
                except Exception:
                    logger.exception("failed to remove persistence entry for %s", server_id)
        return True

    # ------------------------------------------------------------------ lifecycle

    def get_or_create_session(self, session_id: str) -> SessionState:
        state = self.sessions.get(session_id)
        if state is None:
            state = SessionState(session_id=session_id)
            self.sessions[session_id] = state
        return state

    def iter_tools(self, session_id: Optional[str] = None) -> Iterable[DownstreamTool]:
        return self._visible_tools(session_id).values()

    async def start(self) -> None:
        await self._rehydrate_dynamic_servers()
        await self._auto_redeploy_sticky()

        # Legacy auto_deploy=True path: static catalog servers that want eager
        # deploy at startup. Treated as global for back-compat.
        for server in self.servers.values():
            if server.auto_deploy and server.server_id not in self.global_deployments:
                try:
                    await self.deploy_server(server.server_id, scope="global", init_values={})
                except Exception:
                    logger.exception("auto_deploy failed for %s", server.server_id)

        await self.refresh_all()
        if self._refresh_task is None:
            self._refresh_task = asyncio.create_task(self._refresh_loop())

    async def _rehydrate_dynamic_servers(self) -> None:
        if self.persistence is None:
            return
        async with self._reg_rwlock.read():
            entries = self.persistence.load()
        if not entries:
            return
        logger.info("rehydrating %d dynamic MCP server(s) from disk", len(entries))
        for entry in entries:
            try:
                from .server import RegisterServerRequest  # local import to avoid cycle
                req = RegisterServerRequest.model_validate(entry)
                schema = [
                    InitSchemaField(
                        name=f.name, type=f.type, description=f.description,
                        required=f.required, secret=f.secret, default=f.default,
                        enum=list(f.enum),
                    )
                    for f in req.init_schema
                ]
                await self.register_dynamic_server(
                    server_id=req.server_id,
                    load_spec=req.load_spec,
                    display_name=req.display_name,
                    description=req.description,
                    init_schema=schema,
                    save_last_init=req.save_last_init,
                    idle_timeout_seconds=req.idle_timeout_seconds,
                    default_scope=req.default_scope,
                    payload=entry,
                )
            except Exception:
                logger.exception("failed to rehydrate dynamic server: %s", entry.get("server_id"))

    async def _auto_redeploy_sticky(self) -> None:
        """Redeploy every global-sticky server whose last init was persisted.

        A server that has never been successfully deployed (no
        ``last_init_by_server`` entry) is left alone — we only resurrect
        previously-working deployments.
        """
        for server_id, server_def in list(self.servers.items()):
            if server_def.default_scope != "global-sticky":
                continue
            last_init = self.last_init_by_server.get(server_id)
            if last_init is None:
                # Never deployed before — nothing to resurrect.
                continue
            missing = server_def.missing_required_init(last_init)
            if missing:
                # Init was saved but the schema changed since; record and skip.
                self._record_global_error(
                    server_id,
                    "global-sticky",
                    f"skipped auto-redeploy: missing required init {missing}",
                    missing_required_init=missing,
                )
                logger.info(
                    "skipped auto-redeploy for %s: missing required init %s",
                    server_id, missing,
                )
                continue
            try:
                await self.deploy_server(
                    server_id,
                    scope="global-sticky",
                    init_values=dict(last_init),
                    reuse_last=False,
                )
                logger.info("auto-redeployed global-sticky server %s", server_id)
            except Exception as exc:
                logger.exception("auto-redeploy failed for %s", server_id)
                self._record_global_error(server_id, "global-sticky", str(exc))

    async def stop(self) -> None:
        if self._refresh_task is not None:
            self._refresh_task.cancel()
            self._refresh_task = None

        # Tear down globals.
        for server_id in list(self.global_clients.keys()):
            try:
                await self.undeploy_server(server_id, scope="global")
            except Exception:
                logger.exception("failed undeploying global %s", server_id)

        # Tear down per-session.
        for session_id in list(self.session_clients.keys()):
            for server_id in list(self.session_clients.get(session_id, {}).keys()):
                try:
                    await self.undeploy_server(server_id, scope="session", session_id=session_id)
                except Exception:
                    logger.exception("failed undeploying session %s/%s", session_id, server_id)

    async def _refresh_loop(self) -> None:
        while True:
            await asyncio.sleep(10)
            try:
                await self.expire_idle_servers()
                await self.refresh_all()
            except Exception:
                logger.exception("Background registry refresh failed")

    async def expire_idle_servers(self) -> None:
        # Global deployments.
        expired_globals: List[str] = []
        for server_id, deployment in self.global_deployments.items():
            server_def = self.servers.get(server_id)
            if server_def is None:
                continue
            if deployment.is_expired(server_def.idle_timeout_seconds):
                expired_globals.append(server_id)
        for server_id in expired_globals:
            logger.info("undeploying idle global server %s", server_id)
            await self.undeploy_server(server_id, scope="global")

        # Per-session deployments.
        expired_sessions: List[Tuple[str, str]] = []
        for session_id, deployments in self.session_deployments.items():
            for server_id, deployment in deployments.items():
                server_def = self.servers.get(server_id)
                if server_def is None:
                    continue
                if deployment.is_expired(server_def.idle_timeout_seconds):
                    expired_sessions.append((session_id, server_id))
        for session_id, server_id in expired_sessions:
            logger.info("undeploying idle session server %s/%s", session_id, server_id)
            await self.undeploy_server(server_id, scope="session", session_id=session_id)

        # GC session dicts that no longer hold any deployments, errors, or
        # disclosures. Keep SessionState around if it still has live
        # disclosures — disclosures alone have value for global tools.
        for session_id in list(self.session_deployments.keys()):
            if not self.session_deployments[session_id]:
                del self.session_deployments[session_id]
                self.session_clients.pop(session_id, None)
                self.session_tools_by_server.pop(session_id, None)
        for session_id in list(self.sessions.keys()):
            state = self.sessions[session_id]
            has_deployments = bool(self.session_deployments.get(session_id))
            has_errors = bool(self.session_errors.get(session_id))
            has_disclosures = bool(state.disclosures)
            if not (has_deployments or has_errors or has_disclosures):
                del self.sessions[session_id]
                self.session_errors.pop(session_id, None)

    # ------------------------------------------------------------------ deploy / undeploy

    async def deploy_server(
            self,
            server_id: str,
            *,
            scope: Scope | None = None,
            session_id: Optional[str] = None,
            init_values: Optional[dict] = None,
            reuse_last: bool = True,
    ) -> MCPServerDeployment:
        server_def = self._require_server(server_id)
        effective_scope: Scope = scope or server_def.default_scope
        if effective_scope not in VALID_SCOPES:
            raise ValueError(f"invalid scope: {effective_scope!r}")
        if effective_scope == "session" and not session_id:
            raise ValueError("scope='session' requires a session_id")

        async with self._lock:
            supplied = dict(init_values or {})

            # Only global-scope deploys reuse stored last-init values.
            # Session deploys always start from explicit init.
            if not supplied and reuse_last and server_def.save_last_init and effective_scope != "session":
                supplied = dict(self.last_init_by_server.get(server_id, {}))

            try:
                validated_init = server_def.validate_init(supplied)
            except ValueError as exc:
                missing = server_def.missing_required_init(supplied)
                self._record_error(server_id, effective_scope, session_id, str(exc), missing)
                raise

            # Tear down any existing deployment in the same scope.
            await self._teardown_existing_scope(server_id, effective_scope, session_id)

            client_config = self._materialize_client_config(server_def, validated_init)
            client = build_client(client_config)
            try:
                await client.ensure_initialized()
            except Exception as exc:
                self._record_error(server_id, effective_scope, session_id, f"initialize failed: {exc}")
                try:
                    await client.close()
                except Exception:
                    logger.exception("close after failed init errored for %s", server_id)
                raise

            deployment = MCPServerDeployment(
                server_id=server_id,
                initialized_at=time.time(),
                last_used_at=time.time(),
                init_values=validated_init,
                deployed=True,
                scope=effective_scope,
                session_id=session_id if effective_scope == "session" else None,
            )

            self._store_deployment(server_id, effective_scope, session_id, client, deployment)

            try:
                tools = await client.refresh_tools()
            except Exception as exc:
                self._record_error(server_id, effective_scope, session_id, f"refresh_tools failed: {exc}")
                await self._teardown_existing_scope(server_id, effective_scope, session_id)
                # _teardown_existing_scope only releases client + deployment;
                # orphan tool entries would otherwise linger for ~10s until
                # _refresh_scope wipes them, leaving describe/invoke divergent.
                self._clear_server_tools(server_id, effective_scope, session_id)
                raise

            self._replace_server_tools(server_id, effective_scope, session_id, tools)

            # Sticky init: persist for global/global-sticky only, and only
            # after refresh_tools succeeded — otherwise a failed redeploy
            # would poison last-init.json and _auto_redeploy_sticky would
            # retry the bad init on every gateway restart. Session deploys
            # never touch the disk-persisted last-init.
            if server_def.save_last_init and effective_scope != "session":
                self.last_init_by_server[server_id] = dict(validated_init)
                if effective_scope == "global-sticky" and self.last_init_store is not None:
                    try:
                        self.last_init_store.set(server_id, dict(validated_init))
                    except Exception:
                        logger.exception("failed to persist last init for %s", server_id)

            # Successful deploy clears any matching error.
            self._clear_error(server_id, effective_scope, session_id)

            return deployment

    async def undeploy_server(
            self,
            server_id: str,
            *,
            scope: Scope,
            session_id: Optional[str] = None,
    ) -> None:
        if scope not in VALID_SCOPES:
            raise ValueError(f"invalid scope: {scope!r}")
        if scope == "session" and not session_id:
            raise ValueError("scope='session' requires a session_id")

        async with self._lock:
            client = self._pop_client(server_id, scope, session_id)
            self._pop_deployment(server_id, scope, session_id)
            self._clear_server_tools(server_id, scope, session_id)

        if client is not None:
            try:
                await client.close()
            except Exception:
                logger.exception("close failed for %s", server_id)

    async def _teardown_existing_scope(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
    ) -> None:
        old_client: Optional[DownstreamClient] = None
        if scope == "session":
            session_map = self.session_clients.get(session_id or "")
            if session_map:
                old_client = session_map.pop(server_id, None)
            sess_dep = self.session_deployments.get(session_id or "")
            if sess_dep:
                sess_dep.pop(server_id, None)
        else:
            old_client = self.global_clients.pop(server_id, None)
            self.global_deployments.pop(server_id, None)

        if old_client is not None:
            try:
                await old_client.close()
            except Exception:
                logger.exception("close failed for %s", server_id)

    async def _undeploy_all_scopes(self, server_id: str) -> None:
        if server_id in self.global_clients:
            await self.undeploy_server(server_id, scope="global")
        for session_id in list(self.session_clients.keys()):
            if server_id in self.session_clients.get(session_id, {}):
                await self.undeploy_server(server_id, scope="session", session_id=session_id)

    # ------------------------------------------------------------------ storage helpers

    def _store_deployment(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
            client: DownstreamClient,
            deployment: MCPServerDeployment,
    ) -> None:
        if scope == "session":
            assert session_id is not None
            self.session_clients.setdefault(session_id, {})[server_id] = client
            self.session_deployments.setdefault(session_id, {})[server_id] = deployment
        else:
            self.global_clients[server_id] = client
            self.global_deployments[server_id] = deployment

    def _pop_client(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
    ) -> Optional[DownstreamClient]:
        if scope == "session":
            session_map = self.session_clients.get(session_id or "")
            if session_map is None:
                return None
            client = session_map.pop(server_id, None)
            if not session_map:
                self.session_clients.pop(session_id or "", None)
            return client
        return self.global_clients.pop(server_id, None)

    def _pop_deployment(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
    ) -> None:
        if scope == "session":
            dep_map = self.session_deployments.get(session_id or "")
            if dep_map is not None:
                dep_map.pop(server_id, None)
                if not dep_map:
                    self.session_deployments.pop(session_id or "", None)
        else:
            self.global_deployments.pop(server_id, None)

    def _replace_server_tools(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
            tools: List[DownstreamTool],
    ) -> None:
        per_server: Dict[str, DownstreamTool] = {tool.path: tool for tool in tools}
        if scope == "session":
            assert session_id is not None
            self.session_tools_by_server.setdefault(session_id, {})[server_id] = per_server
        else:
            self.global_tools_by_server[server_id] = per_server

    def _clear_server_tools(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
    ) -> None:
        if scope == "session":
            per_session = self.session_tools_by_server.get(session_id or "")
            if per_session is not None:
                per_session.pop(server_id, None)
                if not per_session:
                    self.session_tools_by_server.pop(session_id or "", None)
        else:
            self.global_tools_by_server.pop(server_id, None)

    # ------------------------------------------------------------------ error tracking

    def _record_error(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
            message: str,
            missing_required_init: Optional[List[str]] = None,
    ) -> None:
        err = DeploymentError(
            server_id=server_id,
            scope=scope,
            session_id=session_id if scope == "session" else None,
            attempted_at=time.time(),
            message=message,
            missing_required_init=list(missing_required_init or []),
        )
        if scope == "session":
            assert session_id is not None
            self.session_errors.setdefault(session_id, {})[server_id] = err
        else:
            self.global_errors[server_id] = err

    def _record_global_error(
            self,
            server_id: str,
            scope: Scope,
            message: str,
            missing_required_init: Optional[List[str]] = None,
    ) -> None:
        self._record_error(server_id, scope, None, message, missing_required_init)

    def _clear_error(
            self,
            server_id: str,
            scope: Scope,
            session_id: Optional[str],
    ) -> None:
        if scope == "session":
            sess_map = self.session_errors.get(session_id or "")
            if sess_map is not None:
                sess_map.pop(server_id, None)
                if not sess_map:
                    self.session_errors.pop(session_id or "", None)
        else:
            self.global_errors.pop(server_id, None)

    def _resolve_last_error(
            self,
            server_id: str,
            session_id: Optional[str],
    ) -> Optional[DeploymentError]:
        """Session error wins over global error for the calling session."""
        if session_id:
            sess_err = self.session_errors.get(session_id, {}).get(server_id)
            if sess_err is not None:
                return sess_err
        return self.global_errors.get(server_id)

    # ------------------------------------------------------------------ refresh / reads

    async def refresh_all(self) -> None:
        async with self._lock:
            await self._refresh_scope("global", self.global_clients, self.global_deployments, self.global_tools_by_server)

            for session_id, clients in list(self.session_clients.items()):
                deployments = self.session_deployments.get(session_id, {})
                tools_by_server = self.session_tools_by_server.setdefault(session_id, {})
                await self._refresh_scope("session", clients, deployments, tools_by_server)

    async def _refresh_scope(
            self,
            scope: str,
            clients: Dict[str, DownstreamClient],
            deployments: Dict[str, MCPServerDeployment],
            tools_by_server: Dict[str, Dict[str, DownstreamTool]],
    ) -> None:
        new_tools_by_server: Dict[str, Dict[str, DownstreamTool]] = {}
        for server_id, client in list(clients.items()):
            try:
                tools = await client.refresh_tools()
            except Exception:
                logger.exception("failed refreshing %s server %s", scope, server_id)
                # Preserve previous snapshot so transient refresh failures
                # don't blank the cache.
                prior = tools_by_server.get(server_id)
                if prior:
                    new_tools_by_server[server_id] = prior
                continue

            deployment = deployments.get(server_id)
            if deployment is not None:
                deployment.touch()

            per_server: Dict[str, DownstreamTool] = {}
            for tool in tools:
                tool.last_seen_at = time.time()
                per_server[tool.path] = tool
            new_tools_by_server[server_id] = per_server

        tools_by_server.clear()
        tools_by_server.update(new_tools_by_server)

    def _visible_tools(self, session_id: Optional[str]) -> Dict[str, DownstreamTool]:
        """Merged view for a session: globals + that session's overrides (session wins)."""
        merged: Dict[str, DownstreamTool] = {}
        for per_server in self.global_tools_by_server.values():
            merged.update(per_server)
        if session_id:
            session_tbs = self.session_tools_by_server.get(session_id)
            if session_tbs:
                for per_server in session_tbs.values():
                    merged.update(per_server)
        return merged

    def filtered_tools(
            self,
            headers: Dict[str, str],
            path_prefix: str | None = None,
            session_id: Optional[str] = None,
    ) -> List[DownstreamTool]:
        excluded_raw = headers.get("x-exclude-tools", "")
        excluded = {item.strip() for item in excluded_raw.split(",") if item.strip()}
        allow_prefix = headers.get("x-allow-tool-prefix", "").strip()

        tools = list(self._visible_tools(session_id).values())
        if path_prefix:
            normalized = path_prefix.strip("/")
            tools = [tool for tool in tools if tool.path.startswith(normalized)]
        if excluded:
            tools = [tool for tool in tools if tool.path not in excluded and tool.tool_name not in excluded]
        if allow_prefix:
            tools = [tool for tool in tools if tool.path.startswith(allow_prefix)]
        return sorted(tools, key=lambda tool: tool.path)

    def browse_servers(
            self,
            deployed: Optional[bool] = None,
            session_id: Optional[str] = None,
    ) -> List[dict]:
        items = []
        for server_id, server_def in sorted(self.servers.items()):
            deployed_globally = server_id in self.global_deployments
            deployed_in_session = False
            if session_id:
                deployed_in_session = server_id in self.session_deployments.get(session_id, {})
            is_deployed = deployed_globally or deployed_in_session
            if deployed is not None and is_deployed != deployed:
                continue
            last_error = self._resolve_last_error(server_id, session_id)
            tool_count = self._tool_count_for(server_id, session_id)
            items.append(
                {
                    "server_id": server_id,
                    "display_name": server_def.display_name,
                    "description": server_def.description,
                    "default_scope": server_def.default_scope,
                    "deployed": is_deployed,
                    "deployed_globally": deployed_globally,
                    "deployed_in_session": deployed_in_session,
                    "tool_count": tool_count,
                    "last_error": last_error.to_public_dict() if last_error else None,
                }
            )
        return items

    def _tool_count_for(self, server_id: str, session_id: Optional[str]) -> int:
        count = len(self.global_tools_by_server.get(server_id, {}))
        if session_id:
            count += len(self.session_tools_by_server.get(session_id, {}).get(server_id, {}))
        return count

    def describe_server(self, server_id: str, session_id: Optional[str] = None) -> dict:
        server_def = self._require_server(server_id)

        deployed_globally = server_id in self.global_deployments
        deployed_in_session = False
        deployment_payload: Optional[dict] = None
        if session_id:
            session_deployment = self.session_deployments.get(session_id, {}).get(server_id)
            if session_deployment is not None:
                deployed_in_session = True
                deployment_payload = self._deployment_payload(server_def, session_deployment)
        if deployment_payload is None and deployed_globally:
            deployment_payload = self._deployment_payload(server_def, self.global_deployments[server_id])

        payload = {
            "server_id": server_def.server_id,
            "display_name": server_def.display_name,
            "description": server_def.description,
            "transport": server_def.client.transport,
            "init_schema": server_def.schema_dict(),
            "save_last_init": server_def.save_last_init,
            "idle_timeout_seconds": server_def.idle_timeout_seconds,
            "default_scope": server_def.default_scope,
            "deployed": deployed_globally or deployed_in_session,
            "deployed_globally": deployed_globally,
            "deployed_in_session": deployed_in_session,
        }

        if deployment_payload is not None:
            payload["deployment"] = deployment_payload

        remembered = self.last_init_by_server.get(server_id)
        if remembered:
            payload["last_init_values"] = server_def.redact_init(remembered)

        last_error = self._resolve_last_error(server_id, session_id)
        payload["last_error"] = last_error.to_public_dict() if last_error else None

        return payload

    @staticmethod
    def _deployment_payload(server_def: MCPServerDefinition, deployment: MCPServerDeployment) -> dict:
        return {
            "initialized_at": deployment.initialized_at,
            "last_used_at": deployment.last_used_at,
            "expires_at": deployment.expires_at(server_def.idle_timeout_seconds),
            "init_values": server_def.redact_init(deployment.init_values),
            "scope": deployment.scope,
            "session_id": deployment.session_id,
        }

    def find_tool(self, tool_path: str, session_id: Optional[str] = None) -> Optional[DownstreamTool]:
        return self._visible_tools(session_id).get(tool_path)

    def get_active_client(
            self,
            server_id: str,
            session_id: Optional[str] = None,
    ) -> DownstreamClient:
        # Session-scoped deployment wins over global for the calling session.
        if session_id:
            sess_clients = self.session_clients.get(session_id, {})
            client = sess_clients.get(server_id)
            if client is not None:
                deployment = self.session_deployments.get(session_id, {}).get(server_id)
                if deployment is not None:
                    deployment.touch()
                return client
        client = self.global_clients.get(server_id)
        if client is None:
            raise ValueError(f"MCP server is not deployed: {server_id}")
        deployment = self.global_deployments.get(server_id)
        if deployment is not None:
            deployment.touch()
        return client

    def suggest_tools(
            self,
            query: str,
            headers: Dict[str, str],
            limit: int = 5,
            session_id: Optional[str] = None,
    ) -> List[Dict[str, object]]:
        tools = self.filtered_tools(headers, session_id=session_id)
        hits = self.matcher.rank(query=query, tools=tools, limit=limit)
        return [
            {
                "path": hit.tool.path,
                "tool_name": hit.tool.tool_name,
                "server_id": hit.tool.server_id,
                "description": hit.tool.description,
                "score": hit.score,
                "reason": hit.reason,
            }
            for hit in hits
        ]

    # ------------------------------------------------------------------ internals

    def _materialize_client_config(
            self,
            server_def: MCPServerDefinition,
            init_values: dict,
    ) -> ClientConfig:
        base = server_def.client
        config = ClientConfig(**{f.name: getattr(base, f.name) for f in dataclasses.fields(base)})

        if "url" in init_values:
            config.url = init_values["url"]
        if "headers" in init_values and isinstance(init_values["headers"], dict):
            merged = dict(config.headers)
            merged.update(init_values["headers"])
            config.headers = merged
        if "command" in init_values and isinstance(init_values["command"], list):
            config.command = list(init_values["command"])
        if "tool_path_prefix" in init_values:
            config.tool_path_prefix = str(init_values["tool_path_prefix"])
        if "namespace" in init_values:
            config.namespace = str(init_values["namespace"])

        return config

    def _require_server(self, server_id: str) -> MCPServerDefinition:
        server = self.servers.get(server_id)
        if server is None:
            raise ValueError(f"Unknown MCP server: {server_id}")
        return server
