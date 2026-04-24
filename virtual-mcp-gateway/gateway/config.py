from __future__ import annotations

from pathlib import Path
from typing import Any, List
import json

from pydantic import BaseModel, Field

from .models import ClientConfig, DEFAULT_SCOPE, InitSchemaField, MCPServerDefinition, Scope


class InitSchemaFieldModel(BaseModel):
    name: str
    type: str = "string"
    description: str = ""
    required: bool = False
    secret: bool = False
    default: Any = None
    enum: List[str] = Field(default_factory=list)

    def to_internal(self) -> InitSchemaField:
        return InitSchemaField(**self.model_dump())


class ClientConfigModel(BaseModel):
    transport: str
    command: List[str] | None = None
    url: str | None = None
    headers: dict[str, str] = Field(default_factory=dict)
    namespace: str = ""
    tool_path_prefix: str = ""
    protocol_version: str | None = None
    refresh_interval_seconds: int = 30
    startup_timeout_seconds: int = 20
    request_timeout_seconds: int = 60
    env_header_prefix: str = "x-env-"
    include_header_names: List[str] = Field(default_factory=list)
    exclude_header_names: List[str] = Field(default_factory=list)

    def to_internal(self, server_id: str) -> ClientConfig:
        payload = self.model_dump()
        payload["server_id"] = server_id
        return ClientConfig(**payload)


class MCPServerDefinitionModel(BaseModel):
    server_id: str
    display_name: str | None = None
    description: str = ""
    client: ClientConfigModel
    init_schema: List[InitSchemaFieldModel] = Field(default_factory=list)
    save_last_init: bool = True
    idle_timeout_seconds: int | None = 1800
    auto_deploy: bool = False
    default_scope: Scope = DEFAULT_SCOPE

    def to_internal(self) -> MCPServerDefinition:
        return MCPServerDefinition(
            server_id=self.server_id,
            display_name=self.display_name or self.server_id,
            description=self.description,
            client=self.client.to_internal(server_id=self.server_id),
            init_schema=[item.to_internal() for item in self.init_schema],
            save_last_init=self.save_last_init,
            idle_timeout_seconds=self.idle_timeout_seconds,
            auto_deploy=self.auto_deploy,
            default_scope=self.default_scope,
        )


class GatewayConfigModel(BaseModel):
    protocol_version: str = "2025-11-05"
    disclosure_ttl_seconds: int | None = 1800
    mcp_servers: List[MCPServerDefinitionModel] = Field(default_factory=list)

    # Backward-compat shim
    clients: List[ClientConfigModel] = Field(default_factory=list)

    def normalize(self) -> "GatewayConfigModel":
        if self.mcp_servers:
            return self
        if not self.clients:
            return self

        converted = []
        for idx, client in enumerate(self.clients):
            server_id = f"client_{idx + 1}"
            converted.append(
                MCPServerDefinitionModel(
                    server_id=server_id,
                    display_name=server_id,
                    client=client,
                    init_schema=[],
                    save_last_init=True,
                    idle_timeout_seconds=1800,
                    auto_deploy=True,
                )
            )
        self.mcp_servers = converted
        return self


def load_config(path: str | Path) -> GatewayConfigModel:
    raw = Path(path).read_text(encoding="utf-8")
    model = GatewayConfigModel.model_validate(json.loads(raw))
    return model.normalize()