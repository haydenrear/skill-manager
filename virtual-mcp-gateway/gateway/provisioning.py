"""Binary + Docker provisioning for dynamically registered MCP servers.

Skill-manager compatible MCP servers must ship one of two load specs:

1. ``docker``     — a container image that speaks MCP over stdio (``docker run -i``)
                    or exposes a streamable-HTTP endpoint.
2. ``binary``     — a downloadable archive containing an executable, optionally
                    preceded by an ``init_script``. Binary servers speak MCP over
                    stdio by default.

This module handles downloading, verifying, extracting, and turning a load spec
into a :class:`ClientConfig` that the registry can use.
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import platform
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse
from urllib.request import urlopen

from pydantic import BaseModel, Field, model_validator

from .models import ClientConfig

logger = logging.getLogger(__name__)


class BinaryInstallTarget(BaseModel):
    """Platform-specific download entry for a binary MCP server."""

    url: str
    archive: str | None = None  # "tar.gz" | "zip" | "raw"
    binary: str | None = None  # path within the extracted tree to the executable
    sha256: str | None = None


class BinaryLoadSpec(BaseModel):
    type: str = Field(default="binary", frozen=True)
    install: Dict[str, BinaryInstallTarget] = Field(default_factory=dict)
    init_script: str | None = None  # run once after download, cwd = install dir
    bin_path: str | None = None  # override: path to executable (relative to install dir)
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    transport: str = "stdio"  # "stdio" | "streamable-http" | "sse"
    url: str | None = None  # required when transport != stdio


class UvLoadSpec(BaseModel):
    """MCP server distributed as a Python package, run via skill-manager's
    bundled ``uv`` as ``uv tool run [--from <pkg>==<ver>] <entry> [args]``
    (or ``uv tool run <pkg> [args]`` when no version is pinned).

    Resolution order for ``uv``:
      1. ``$SKILL_MANAGER_HOME/pm/uv/current/bin/uv`` (bundled by
         skill-manager when a pip-CLI dep is installed, or proactively
         by ``McpWriter.ensureGatewayPrerequisites`` when this load type
         is registered).
      2. ``shutil.which("uv")`` (system PATH fallback).
    """

    type: str = Field(default="uv", frozen=True)
    package: str
    version: str | None = None  # None = `uv tool run <pkg>` without --from pin
    entry_point: str | None = None  # None = use package's default script
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    transport: str = "stdio"
    url: str | None = None


class ShellLoadSpec(BaseModel):
    """Escape hatch — run an arbitrary shell command as a stdio MCP server.

    No download, no resolution, no PATH magic; whatever's named in
    ``command[0]`` must already be reachable from the gateway. The
    ``env`` map follows the same host-passthrough convention as the
    other load types: empty values are pulled from the gateway's
    environment at registration time.
    """

    type: str = Field(default="shell", frozen=True)
    command: List[str]
    env: Dict[str, str] = Field(default_factory=dict)
    transport: str = "stdio"
    url: str | None = None


class NpmLoadSpec(BaseModel):
    """MCP server distributed as an npm package, run as
    ``npx -y <package>@<version> [args]`` over stdio.

    The provisioner resolves ``npx`` from skill-manager's bundled Node
    first (``$SKILL_MANAGER_HOME/pm/node/current/bin/npx``), falling
    back to ``shutil.which("npx")`` on the gateway's PATH. Empty-value
    entries in :attr:`env` are passed through from the gateway process
    environment, mirroring the docker convention so a manifest can
    declare ``env = {{API_KEY = ""}}`` without committing the value.
    """

    type: str = Field(default="npm", frozen=True)
    package: str
    version: str = "latest"
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    transport: str = "stdio"  # "stdio" | "streamable-http" | "sse"
    url: str | None = None  # required when transport != stdio


class DockerLoadSpec(BaseModel):
    type: str = Field(default="docker", frozen=True)
    image: str
    pull: bool = True  # run `docker pull` when registering
    platform: str | None = None  # optional --platform flag
    command: List[str] | None = None  # override container entrypoint
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    volumes: List[str] = Field(default_factory=list)  # "host:container[:ro]"
    transport: str = "stdio"  # "stdio" | "streamable-http" | "sse"
    url: str | None = None  # required when transport != stdio
    container_name: str | None = None  # reserved for future HTTP container mgmt


class LoadSpec(BaseModel):
    """Tagged-union wrapper. Exactly one of
    ``docker`` / ``binary`` / ``npm`` / ``uv`` / ``shell`` is set."""

    type: str
    docker: DockerLoadSpec | None = None
    binary: BinaryLoadSpec | None = None
    npm: NpmLoadSpec | None = None
    uv: UvLoadSpec | None = None
    shell: ShellLoadSpec | None = None

    @model_validator(mode="after")
    def _populate_variant(self) -> "LoadSpec":
        excluded = {"type", "docker", "binary", "npm", "uv", "shell"}
        # Allow flat form: {type: "docker", image: "..."} instead of {type: "docker", docker: {...}}
        if self.type == "docker" and self.docker is None:
            extras = self.model_dump(exclude=excluded, exclude_none=True)
            if extras:
                self.docker = DockerLoadSpec(**extras)
        elif self.type == "binary" and self.binary is None:
            extras = self.model_dump(exclude=excluded, exclude_none=True)
            if extras:
                self.binary = BinaryLoadSpec(**extras)
        elif self.type == "npm" and self.npm is None:
            extras = self.model_dump(exclude=excluded, exclude_none=True)
            if extras:
                self.npm = NpmLoadSpec(**extras)
        elif self.type == "uv" and self.uv is None:
            extras = self.model_dump(exclude=excluded, exclude_none=True)
            if extras:
                self.uv = UvLoadSpec(**extras)
        elif self.type == "shell" and self.shell is None:
            extras = self.model_dump(exclude=excluded, exclude_none=True)
            if extras:
                self.shell = ShellLoadSpec(**extras)
        if self.type == "docker" and self.docker is None:
            raise ValueError("docker load spec requires 'docker' block or flat fields")
        if self.type == "binary" and self.binary is None:
            raise ValueError("binary load spec requires 'binary' block or flat fields")
        if self.type == "npm" and self.npm is None:
            raise ValueError("npm load spec requires 'npm' block or flat fields")
        if self.type == "uv" and self.uv is None:
            raise ValueError("uv load spec requires 'uv' block or flat fields")
        if self.type == "shell" and self.shell is None:
            raise ValueError("shell load spec requires 'shell' block or flat fields")
        return self

    model_config = {"extra": "allow"}


def current_platform_key() -> str:
    system = platform.system().lower()
    if system == "darwin":
        os_key = "darwin"
    elif system == "linux":
        os_key = "linux"
    elif system.startswith("win"):
        os_key = "windows"
    else:
        os_key = system
    machine = platform.machine().lower()
    if machine in ("arm64", "aarch64"):
        arch_key = "arm64"
    elif machine in ("x86_64", "amd64"):
        arch_key = "x64"
    else:
        arch_key = machine
    return f"{os_key}-{arch_key}"


def match_platform(key: str, current: str | None = None) -> bool:
    current = current or current_platform_key()
    if key == current:
        return True
    parts = key.lower().split("-")
    cur_parts = current.split("-")
    if len(parts) == 1:
        return parts[0] == cur_parts[0]
    return parts[0] == cur_parts[0] and (parts[1] == cur_parts[1] or parts[1] == "any")


class Provisioner:
    """Materialize load specs into :class:`ClientConfig` instances."""

    def __init__(self, data_dir: Path):
        self.data_dir = Path(data_dir)
        self.bin_dir = self.data_dir / "mcp_binaries"
        self.bin_dir.mkdir(parents=True, exist_ok=True)

    async def provision(self, server_id: str, load_spec: LoadSpec) -> ClientConfig:
        if load_spec.type == "docker":
            assert load_spec.docker is not None
            return await self._provision_docker(server_id, load_spec.docker)
        if load_spec.type == "binary":
            assert load_spec.binary is not None
            return await self._provision_binary(server_id, load_spec.binary)
        if load_spec.type == "npm":
            assert load_spec.npm is not None
            return await self._provision_npm(server_id, load_spec.npm)
        if load_spec.type == "uv":
            assert load_spec.uv is not None
            return await self._provision_uv(server_id, load_spec.uv)
        if load_spec.type == "shell":
            assert load_spec.shell is not None
            return await self._provision_shell(server_id, load_spec.shell)
        raise ValueError(f"Unknown load type: {load_spec.type}")

    # ------------------------------------------------------------------ docker
    async def _provision_docker(self, server_id: str, spec: DockerLoadSpec) -> ClientConfig:
        if spec.pull:
            await _run(["docker", "pull", spec.image])

        if spec.transport != "stdio":
            if not spec.url:
                raise ValueError("non-stdio docker load spec requires 'url'")
            return ClientConfig(
                server_id=server_id,
                transport=spec.transport,
                url=spec.url,
                headers={},
            )

        cmd: List[str] = ["docker", "run", "-i", "--rm"]
        if spec.platform:
            cmd += ["--platform", spec.platform]
        for key, value in spec.env.items():
            # Empty value is treated as host-env passthrough: docker forwards
            # whatever `key` is set to in the gateway process. This lets a
            # skill manifest declare `env = { API_KEY = "" }` to surface a
            # secret requirement without hard-coding the value.
            if value == "":
                cmd += ["-e", key]
            else:
                cmd += ["-e", f"{key}={value}"]
        for volume in spec.volumes:
            cmd += ["-v", volume]
        cmd.append(spec.image)
        if spec.command:
            cmd += list(spec.command)
        cmd += list(spec.args)

        return ClientConfig(server_id=server_id, transport="stdio", command=cmd)

    # ------------------------------------------------------------------ npm
    async def _provision_npm(self, server_id: str, spec: NpmLoadSpec) -> ClientConfig:
        if spec.transport != "stdio":
            if not spec.url:
                raise ValueError("non-stdio npm load spec requires 'url'")
            return ClientConfig(
                server_id=server_id,
                transport=spec.transport,
                url=spec.url,
                headers={},
            )

        npx = _resolve_npx()
        if npx is None:
            raise RuntimeError(
                "npm load spec requires `npx` to be available — install Node "
                "(e.g. `skill-manager install <skill-with-npm-cli-dep>` will "
                "bootstrap a bundled copy under "
                "$SKILL_MANAGER_HOME/pm/node/current/bin/npx) "
                "or put npx on the gateway's PATH."
            )

        version = spec.version or "latest"
        package_at_version = f"{spec.package}@{version}" if version else spec.package

        cmd: List[str] = [npx, "-y", package_at_version, *spec.args]

        env = _materialize_env_passthrough(spec.env)
        return ClientConfig(
            server_id=server_id,
            transport="stdio",
            command=cmd,
            env=env or None,
        )

    # ------------------------------------------------------------------ uv
    async def _provision_uv(self, server_id: str, spec: UvLoadSpec) -> ClientConfig:
        if spec.transport != "stdio":
            if not spec.url:
                raise ValueError("non-stdio uv load spec requires 'url'")
            return ClientConfig(
                server_id=server_id,
                transport=spec.transport,
                url=spec.url,
                headers={},
            )

        uv = _resolve_uv()
        if uv is None:
            raise RuntimeError(
                "uv load spec requires `uv` to be available — install Python's "
                "uv (https://docs.astral.sh/uv/) or let skill-manager bootstrap "
                "a bundled copy under "
                "$SKILL_MANAGER_HOME/pm/uv/current/bin/uv (happens automatically "
                "when a skill with a uv MCP load is installed)."
            )

        cmd: List[str] = [uv, "tool", "run"]
        if spec.version:
            cmd += ["--from", f"{spec.package}=={spec.version}"]
            cmd.append(spec.entry_point or spec.package)
        else:
            # No version pin: rely on uv tool run's default-script resolution.
            cmd.append(spec.entry_point or spec.package)
        cmd += list(spec.args)

        env = _materialize_env_passthrough(spec.env)
        return ClientConfig(
            server_id=server_id,
            transport="stdio",
            command=cmd,
            env=env or None,
        )

    # ------------------------------------------------------------------ shell
    async def _provision_shell(self, server_id: str, spec: ShellLoadSpec) -> ClientConfig:
        if spec.transport != "stdio":
            if not spec.url:
                raise ValueError("non-stdio shell load spec requires 'url'")
            return ClientConfig(
                server_id=server_id,
                transport=spec.transport,
                url=spec.url,
                headers={},
            )
        if not spec.command:
            raise ValueError("shell load spec requires non-empty 'command'")
        env = _materialize_env_passthrough(spec.env)
        return ClientConfig(
            server_id=server_id,
            transport="stdio",
            command=list(spec.command),
            env=env or None,
        )

    # ------------------------------------------------------------------ binary
    async def _provision_binary(self, server_id: str, spec: BinaryLoadSpec) -> ClientConfig:
        if spec.transport != "stdio":
            if not spec.url:
                raise ValueError("non-stdio binary load spec requires 'url'")
            # HTTP/SSE binary servers manage their own lifecycle; no download needed.
            return ClientConfig(
                server_id=server_id,
                transport=spec.transport,
                url=spec.url,
                headers={},
            )

        install_dir = self.bin_dir / server_id
        binary_path = await asyncio.to_thread(self._ensure_binary_installed, server_id, spec, install_dir)

        cmd = [str(binary_path), *spec.args]
        if spec.env:
            # stdio transport uses os env; we can't set per-process env via ClientConfig
            # so we document this limitation. For now, set in current process if fresh key.
            for key, value in spec.env.items():
                os.environ.setdefault(key, value)
        return ClientConfig(server_id=server_id, transport="stdio", command=cmd)

    def _ensure_binary_installed(
            self,
            server_id: str,
            spec: BinaryLoadSpec,
            install_dir: Path,
    ) -> Path:
        marker = install_dir / ".skill-manager.installed"
        if marker.exists() and spec.bin_path:
            candidate = install_dir / spec.bin_path
            if candidate.exists():
                return candidate

        target = _pick_install_target(spec)
        if target is None:
            raise ValueError(
                f"binary load spec for {server_id} has no install target for {current_platform_key()}"
            )

        if install_dir.exists():
            shutil.rmtree(install_dir)
        install_dir.mkdir(parents=True, exist_ok=True)

        downloaded = _download(target.url, install_dir)
        if target.sha256:
            _verify_sha256(downloaded, target.sha256)

        archive_kind = target.archive or _guess_archive(target.url)
        if archive_kind in ("tar.gz", "tgz"):
            _extract_tar_gz(downloaded, install_dir)
            downloaded.unlink(missing_ok=True)
        elif archive_kind == "zip":
            _extract_zip(downloaded, install_dir)
            downloaded.unlink(missing_ok=True)

        if spec.init_script:
            logger.info("running init_script for %s", server_id)
            subprocess.run(
                spec.init_script,
                shell=True,
                cwd=install_dir,
                check=True,
            )

        bin_rel = spec.bin_path or target.binary
        if not bin_rel:
            # Fall back to the downloaded file itself (raw binary).
            if downloaded.exists():
                bin_rel = downloaded.name
            else:
                raise ValueError(f"{server_id}: bin_path not set and archive contains no hint")

        binary_path = install_dir / bin_rel
        if not binary_path.exists():
            raise FileNotFoundError(f"{server_id}: binary not found at {binary_path}")

        _make_executable(binary_path)
        marker.write_text("ok")
        return binary_path


def _pick_install_target(spec: BinaryLoadSpec) -> Optional[BinaryInstallTarget]:
    current = current_platform_key()
    for key, target in spec.install.items():
        if match_platform(key, current):
            return target
    return spec.install.get("any")


def _download(url: str, dir: Path) -> Path:
    parsed = urlparse(url)
    filename = Path(parsed.path).name or "download"
    dst = dir / filename
    logger.info("downloading %s", url)
    with urlopen(url) as resp, open(dst, "wb") as out:  # nosec B310
        shutil.copyfileobj(resp, out)
    return dst


def _verify_sha256(path: Path, expected: str) -> None:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual.lower() != expected.lower():
        raise ValueError(f"sha256 mismatch: expected {expected} got {actual}")


def _guess_archive(url: str) -> str:
    lower = url.lower()
    if lower.endswith(".tar.gz") or lower.endswith(".tgz"):
        return "tar.gz"
    if lower.endswith(".zip"):
        return "zip"
    return "raw"


def _extract_tar_gz(archive: Path, dst: Path) -> None:
    with tarfile.open(archive, "r:gz") as tf:
        for member in tf.getmembers():
            member_path = (dst / member.name).resolve()
            if not str(member_path).startswith(str(dst.resolve())):
                raise ValueError(f"tar slip: {member.name}")
        tf.extractall(dst)  # nosec B202 (guarded above)


def _extract_zip(archive: Path, dst: Path) -> None:
    with zipfile.ZipFile(archive) as zf:
        for name in zf.namelist():
            member_path = (dst / name).resolve()
            if not str(member_path).startswith(str(dst.resolve())):
                raise ValueError(f"zip slip: {name}")
        zf.extractall(dst)  # nosec B202 (guarded above)


def _make_executable(path: Path) -> None:
    mode = path.stat().st_mode
    path.chmod(mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


async def _run(cmd: List[str]) -> None:
    logger.info("exec: %s", " ".join(cmd))
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise RuntimeError(
            f"command failed ({proc.returncode}): {' '.join(cmd)}\n{stderr.decode(errors='replace')}"
        )


def default_data_dir() -> Path:
    override = os.environ.get("VMG_DATA_DIR")
    if override:
        return Path(override)
    return Path(tempfile.gettempdir()) / "virtual-mcp-gateway"


def _materialize_env_passthrough(env: Dict[str, str]) -> Dict[str, str]:
    """Resolve a load spec's ``env`` map.

    Empty values are pulled from the gateway process environment (host
    passthrough); concrete values are kept as-is. Missing host vars are
    dropped silently — let the spawned MCP server raise its own clearer
    "missing API key" error rather than the gateway second-guessing.
    """
    out: Dict[str, str] = {}
    for key, value in env.items():
        if value == "":
            pulled = os.environ.get(key)
            if pulled is not None:
                out[key] = pulled
        else:
            out[key] = value
    return out


def _resolve_npx() -> str | None:
    """Locate ``npx`` for spawning npm-distributed MCP servers.

    Resolution order (mirrors skill-manager's bundled-PM convention):

    1. ``$SKILL_MANAGER_HOME/pm/node/current/bin/npx`` — the bundled Node
       skill-manager installs when a skill declares an ``npm:`` CLI dep
       or, after this change, an ``npm`` MCP load. Preferred so the
       gateway uses the same Node version skill-manager pinned at install
       time.
    2. ``shutil.which("npx")`` — system PATH fallback for environments
       where Node is provided by the OS / a developer's existing
       toolchain.

    Returns ``None`` when neither path resolves; the caller raises a
    user-facing error rather than letting the spawn fail with an opaque
    "no such file or directory".
    """
    return _resolve_bundled_tool("node", "npx")


def _resolve_uv() -> str | None:
    """Locate ``uv`` for spawning Python-distributed MCP servers.

    Same resolution order as :func:`_resolve_npx`:

    1. ``$SKILL_MANAGER_HOME/pm/uv/current/bin/uv`` — bundled by
       skill-manager when a {pip CLI dep | uv MCP load} is installed.
    2. ``shutil.which("uv")`` — system PATH fallback.
    """
    return _resolve_bundled_tool("uv", "uv")


def _resolve_bundled_tool(pm_id: str, tool_name: str) -> str | None:
    """Shared resolution for tools provided by skill-manager's bundled
    package managers (pm/<pm_id>/current/bin/<tool>), with a system-PATH
    fallback. Both npm-load and uv-load go through here so the gateway
    has one place that knows about the bundled-PM convention.

    Mirrors the Java-side ``PackageManagerRuntime.resolveCurrentBinary``
    logic for the ``current`` pointer:

    - Symlink (POSIX default) → follow it; the OS resolves the executable.
    - Regular directory (filesystems where the symlink wasn't possible
      and skill-manager copied the version dir) → use directly.
    - Regular file (skill-manager's text-pointer fallback for
      filesystems without symlink support, e.g. Windows without
      elevation, some network mounts) → read its contents as the
      version name and resolve ``pm/<pm_id>/<version>/bin/<tool>``.

    Returns ``None`` when neither the bundled path nor system PATH
    resolves; callers raise a user-facing error in that case.
    """
    sm_home = os.environ.get("SKILL_MANAGER_HOME")
    if sm_home:
        pm_dir = Path(sm_home) / "pm" / pm_id
        current = pm_dir / "current"
        bundled = _resolve_via_current_pointer(pm_dir, current, tool_name)
        if bundled is not None:
            return bundled
    return shutil.which(tool_name)


def _resolve_via_current_pointer(
        pm_dir: Path, current: Path, tool_name: str) -> str | None:
    """Resolve ``current/bin/<tool_name>`` honoring all three layouts
    skill-manager might write: symlink, real directory, or text-pointer
    file. Returns the executable path or ``None``."""
    # Symlink or real directory: resolve through normal path semantics.
    if current.is_symlink() or current.is_dir():
        candidate = current / "bin" / tool_name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
        return None
    # Text-pointer fallback: contents are the version dir name under pm_dir.
    if current.is_file():
        try:
            version = current.read_text().strip()
        except OSError:
            return None
        if not version:
            return None
        candidate = pm_dir / version / "bin" / tool_name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None
