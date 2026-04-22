"""Tests for dynamic MCP server registration via POST /servers."""
from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from gateway.config import GatewayConfigModel
from gateway.provisioning import Provisioner
from gateway.server import GatewayServer


@pytest.fixture
def gateway(tmp_path: Path) -> GatewayServer:
    config = GatewayConfigModel.model_validate({"mcp_servers": []})
    return GatewayServer(config=config, provisioner=Provisioner(tmp_path / "data"))


def test_register_docker_stdio(gateway: GatewayServer) -> None:
    with TestClient(gateway.app) as client:
        resp = client.post(
            "/servers",
            json={
                "server_id": "alpine-demo",
                "display_name": "Alpine Demo",
                "description": "demo",
                "load_spec": {
                    "type": "docker",
                    "image": "alpine:3",
                    "pull": False,
                    "args": ["echo", "hi"],
                },
                "init_schema": [
                    {"name": "token", "required": True, "secret": True},
                ],
            },
        )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["server_id"] == "alpine-demo"
    assert body["registered"] is True
    assert body["deployed"] is False
    assert body["transport"] == "stdio"

    definition = gateway.registry.servers["alpine-demo"]
    assert definition.display_name == "Alpine Demo"
    assert definition.client.transport == "stdio"
    assert definition.client.command[:3] == ["docker", "run", "-i"]
    assert "alpine:3" in definition.client.command
    assert [field.name for field in definition.init_schema] == ["token"]


def test_register_binary_streamable_http(gateway: GatewayServer) -> None:
    with TestClient(gateway.app) as client:
        resp = client.post(
            "/servers",
            json={
                "server_id": "remote-http",
                "load_spec": {
                    "type": "binary",
                    "transport": "streamable-http",
                    "url": "https://example.com/mcp",
                    "install": {},
                },
            },
        )
    assert resp.status_code == 201, resp.text
    definition = gateway.registry.servers["remote-http"]
    assert definition.client.transport == "streamable-http"
    assert definition.client.url == "https://example.com/mcp"


def test_register_missing_image_returns_422(gateway: GatewayServer) -> None:
    with TestClient(gateway.app) as client:
        resp = client.post(
            "/servers",
            json={"server_id": "bad", "load_spec": {"type": "docker"}},
        )
    assert resp.status_code == 422, resp.text
    body = resp.json()
    assert body["error"] == "validation"


def test_persistence_survives_restart(tmp_path: Path) -> None:
    """A dynamic registration is rehydrated by a fresh GatewayServer pointed at the same data dir."""
    config = GatewayConfigModel.model_validate({"mcp_servers": []})
    data_dir = tmp_path / "data"

    gw1 = GatewayServer(config=config, provisioner=Provisioner(data_dir))
    with TestClient(gw1.app) as client:
        resp = client.post(
            "/servers",
            json={
                "server_id": "persisted-one",
                "display_name": "Persisted One",
                "load_spec": {"type": "docker", "image": "alpine:3", "pull": False},
            },
        )
    assert resp.status_code == 201

    persistence_file = data_dir / "dynamic-servers.json"
    assert persistence_file.exists()

    # Second gateway instance, same data dir, no config servers — should pick up the persisted one.
    gw2 = GatewayServer(config=config, provisioner=Provisioner(data_dir))
    with TestClient(gw2.app) as client2:
        listed = client2.get("/servers").json()
        assert listed["count"] == 1
        assert listed["items"][0]["server_id"] == "persisted-one"
        assert listed["items"][0]["display_name"] == "Persisted One"

        # Unregister removes from disk so the next fresh instance sees none.
        deleted = client2.delete("/servers/persisted-one")
        assert deleted.status_code == 200

    gw3 = GatewayServer(config=config, provisioner=Provisioner(data_dir))
    with TestClient(gw3.app) as client3:
        assert client3.get("/servers").json()["count"] == 0


def test_list_and_unregister(gateway: GatewayServer) -> None:
    with TestClient(gateway.app) as client:
        client.post(
            "/servers",
            json={
                "server_id": "s1",
                "load_spec": {"type": "docker", "image": "alpine:3", "pull": False},
            },
        )
        listed = client.get("/servers").json()
        assert listed["count"] == 1
        assert listed["items"][0]["server_id"] == "s1"

        deleted = client.delete("/servers/s1")
        assert deleted.status_code == 200
        assert deleted.json()["unregistered"] is True

        assert client.get("/servers").json()["count"] == 0

        missing = client.delete("/servers/does-not-exist")
        assert missing.status_code == 404
