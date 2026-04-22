from __future__ import annotations

import argparse
from typing import Any

from mcp.server.fastmcp import FastMCP
from starlette.requests import Request
from starlette.responses import JSONResponse


def build_server(name: str, transport: str, host: str, port: int) -> FastMCP:
    server = FastMCP(
        name=name,
        host=host,
        port=port,
        streamable_http_path="/mcp",
        sse_path="/sse",
        message_path="/messages/",
    )

    @server.tool(name="echo")
    def echo(message: str) -> dict[str, Any]:
        return {
            "transport": transport,
            "server": name,
            "message": message,
        }

    @server.custom_route("/health", methods=["GET"])
    async def health(_: Request) -> JSONResponse:
        return JSONResponse(
            {
                "ok": True,
                "transport": transport,
                "server": name,
            }
        )

    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Fixture MCP server")
    parser.add_argument("--transport", choices=["stdio", "sse", "streamable-http"], required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()

    server = build_server(
        name=args.name,
        transport=args.transport,
        host=args.host,
        port=args.port,
    )
    server.run(transport=args.transport)


if __name__ == "__main__":
    main()
