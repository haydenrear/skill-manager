"""Disk persistence for dynamically-registered MCP servers.

Dynamic registrations made via ``POST /servers`` survive gateway restarts by
being written to ``<data_dir>/dynamic-servers.json``. On startup the registry
rehydrates from that file — so a user whose ``skill-manager sync`` registered
five downstream servers doesn't lose them when the gateway process restarts.
"""
from __future__ import annotations

import json
import logging
import tempfile
from pathlib import Path
from typing import Any, Dict, List

logger = logging.getLogger(__name__)

FILENAME = "dynamic-servers.json"


class DynamicServerStore:
    """Append-only JSON store of dynamic registration payloads, keyed by server_id."""

    def __init__(self, data_dir: Path):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.path = self.data_dir / FILENAME

    def load(self) -> List[Dict[str, Any]]:
        if not self.path.exists():
            return []
        try:
            data = json.loads(self.path.read_text())
        except Exception:
            logger.exception("failed to parse %s; starting empty", self.path)
            return []
        if not isinstance(data, list):
            logger.warning("%s did not contain a JSON array; ignoring", self.path)
            return []
        return data

    def save(self, entries: List[Dict[str, Any]]) -> None:
        # Atomic-ish: write to a sibling file and rename.
        tmp = tempfile.NamedTemporaryFile(
            mode="w",
            delete=False,
            dir=str(self.data_dir),
            prefix=".dynamic-servers.",
            suffix=".json",
        )
        try:
            json.dump(entries, tmp, indent=2, sort_keys=True)
            tmp.flush()
            tmp.close()
            Path(tmp.name).replace(self.path)
        except Exception:
            Path(tmp.name).unlink(missing_ok=True)
            raise

    def upsert(self, entry: Dict[str, Any]) -> None:
        server_id = entry.get("server_id")
        if not server_id:
            raise ValueError("entry must include server_id")
        entries = self.load()
        filtered = [e for e in entries if e.get("server_id") != server_id]
        filtered.append(entry)
        self.save(filtered)

    def remove(self, server_id: str) -> bool:
        entries = self.load()
        before = len(entries)
        remaining = [e for e in entries if e.get("server_id") != server_id]
        if len(remaining) == before:
            return False
        self.save(remaining)
        return True
