from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Literal, Optional
import hashlib
import json
import time


JsonObject = Dict[str, Any]

Scope = Literal["session", "global", "global-sticky"]
VALID_SCOPES: tuple[Scope, ...] = ("session", "global", "global-sticky")
DEFAULT_SCOPE: Scope = "global-sticky"


@dataclass(slots=True)
class InitSchemaField:
    name: str
    type: str = "string"
    description: str = ""
    required: bool = False
    secret: bool = False
    default: Any = None
    enum: List[str] = field(default_factory=list)

    def to_public_dict(self) -> JsonObject:
        payload: JsonObject = {
            "name": self.name,
            "type": self.type,
            "description": self.description,
            "required": self.required,
            "secret": self.secret,
        }
        if self.default is not None and not self.secret:
            payload["default"] = self.default
        if self.enum:
            payload["enum"] = list(self.enum)
        return payload


@dataclass(slots=True)
class ClientConfig:
    server_id: str
    transport: str
    command: List[str] | None = None
    url: str | None = None
    headers: Dict[str, str] = field(default_factory=dict)
    namespace: str = ""
    tool_path_prefix: str = ""
    protocol_version: str | None = None
    refresh_interval_seconds: int = 30
    startup_timeout_seconds: int = 20
    request_timeout_seconds: int = 60
    env_header_prefix: str = "x-env-"
    include_header_names: List[str] = field(default_factory=list)
    exclude_header_names: List[str] = field(default_factory=list)
    # Stdio-only: extra env vars injected into the spawned subprocess.
    # Used by the npm load type for host-env passthrough of secrets such
    # as API keys. The stdio client picks this up via getattr in
    # clients.py:_transport_context; ``None`` means "inherit the gateway's
    # environment unchanged".
    env: Dict[str, str] | None = None


@dataclass(slots=True)
class MCPServerDefinition:
    server_id: str
    display_name: str
    description: str
    client: ClientConfig
    init_schema: List[InitSchemaField] = field(default_factory=list)
    save_last_init: bool = True
    idle_timeout_seconds: int | None = 1800
    auto_deploy: bool = False
    default_scope: Scope = DEFAULT_SCOPE

    def schema_dict(self) -> List[JsonObject]:
        return [field.to_public_dict() for field in self.init_schema]

    def missing_required_init(self, values: JsonObject | None) -> List[str]:
        provided = dict(values or {})
        missing: List[str] = []
        for f in self.init_schema:
            if not f.required:
                continue
            if f.name in provided:
                continue
            if f.default is not None:
                continue
            missing.append(f.name)
        return missing

    def secret_field_names(self) -> set[str]:
        return {field.name for field in self.init_schema if field.secret}

    def validate_init(self, values: JsonObject) -> JsonObject:
        incoming = dict(values or {})
        result: JsonObject = {}
        for field_def in self.init_schema:
            if field_def.name in incoming:
                value = incoming[field_def.name]
            elif field_def.default is not None:
                value = field_def.default
            elif field_def.required:
                raise ValueError(f"Missing required initialization field: {field_def.name}")
            else:
                continue

            if field_def.enum and value not in field_def.enum:
                raise ValueError(
                    f"Invalid value for {field_def.name}: {value!r}. "
                    f"Allowed: {field_def.enum}"
                )
            result[field_def.name] = value
        return result

    def redact_init(self, values: JsonObject | None) -> JsonObject:
        source = dict(values or {})
        secrets = self.secret_field_names()
        return {
            key: ("***" if key in secrets else value)
            for key, value in source.items()
        }


@dataclass(slots=True)
class MCPServerDeployment:
    server_id: str
    initialized_at: float
    last_used_at: float
    init_values: JsonObject = field(default_factory=dict)
    deployed: bool = True
    scope: Scope = "global"
    session_id: Optional[str] = None

    def touch(self) -> None:
        self.last_used_at = time.time()

    def expires_at(self, idle_timeout_seconds: int | None) -> float | None:
        if idle_timeout_seconds is None:
            return None
        return self.last_used_at + idle_timeout_seconds

    def is_expired(self, idle_timeout_seconds: int | None, now: float | None = None) -> bool:
        if idle_timeout_seconds is None:
            return False
        current = time.time() if now is None else now
        return current > (self.last_used_at + idle_timeout_seconds)


@dataclass(slots=True)
class DownstreamTool:
    server_id: str
    tool_name: str
    description: str
    input_schema: JsonObject
    annotations: JsonObject | None = None
    title: str | None = None
    output_schema: JsonObject | None = None
    namespace: str = ""
    tags: List[str] = field(default_factory=list)
    path: str = ""
    schema_digest: str = ""
    last_seen_at: float = field(default_factory=time.time)

    def finalize(self) -> None:
        if not self.path:
            prefix = self.namespace.strip("/")
            self.path = f"{prefix}/{self.tool_name}" if prefix else self.tool_name
        if not self.schema_digest:
            self.schema_digest = stable_digest(
                {
                    "tool_name": self.tool_name,
                    "description": self.description,
                    "input_schema": self.input_schema,
                    "annotations": self.annotations,
                    "title": self.title,
                    "output_schema": self.output_schema,
                }
            )


@dataclass(slots=True)
class ToolDisclosure:
    path: str
    disclosed_at: float
    expires_at: Optional[float] = None

    def is_valid(self, now: float | None = None) -> bool:
        current = time.time() if now is None else now
        if self.expires_at is not None and current > self.expires_at:
            return False
        return True

    def allows(self, tool_path: str, now: float | None = None) -> bool:
        if not self.is_valid(now=now):
            return False
        normalized_disclosure = self.path.strip("/")
        normalized_tool_path = tool_path.strip("/")
        if normalized_disclosure == normalized_tool_path:
            return True
        return normalized_tool_path.startswith(f"{normalized_disclosure}/")


@dataclass(slots=True)
class SessionState:
    session_id: str
    disclosures: Dict[str, ToolDisclosure] = field(default_factory=dict)
    last_headers: Dict[str, str] = field(default_factory=dict)

    def disclose(self, path: str, ttl_seconds: int | None = None) -> ToolDisclosure:
        now = time.time()
        disclosure = ToolDisclosure(
            path=path.strip("/"),
            disclosed_at=now,
            expires_at=None if ttl_seconds is None else now + ttl_seconds,
        )
        self.disclosures[disclosure.path] = disclosure
        return disclosure

    def validate(self, tool_path: str) -> bool:
        normalized_tool_path = tool_path.strip("/")
        return any(disclosure.allows(normalized_tool_path) for disclosure in self.disclosures.values())


@dataclass(slots=True)
class SearchHit:
    tool: DownstreamTool
    score: float
    reason: str


@dataclass(slots=True)
class DeploymentError:
    server_id: str
    scope: Scope
    attempted_at: float
    message: str
    session_id: Optional[str] = None
    missing_required_init: List[str] = field(default_factory=list)

    def to_public_dict(self) -> JsonObject:
        payload: JsonObject = {
            "server_id": self.server_id,
            "scope": self.scope,
            "attempted_at": self.attempted_at,
            "message": self.message,
        }
        if self.session_id is not None:
            payload["session_id"] = self.session_id
        if self.missing_required_init:
            payload["missing_required_init"] = list(self.missing_required_init)
        return payload


def stable_digest(value: Any) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()