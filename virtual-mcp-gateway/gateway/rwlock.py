"""A lightweight async readers-writer lock for coordinating dynamic server registration.

Writers serialize with each other and with readers. Readers can run in parallel
with each other but wait for writers to finish. This is exactly the semantics
we need for registration (write: in-memory mutation + file write, atomic) and
rehydration (read: file load).
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from typing import AsyncIterator


class AsyncRWLock:
    def __init__(self) -> None:
        self._cond = asyncio.Condition()
        self._readers = 0
        self._writer_active = False
        self._writers_waiting = 0

    @asynccontextmanager
    async def read(self) -> AsyncIterator[None]:
        async with self._cond:
            # Yield to any waiting writer to avoid reader starvation of writers.
            await self._cond.wait_for(lambda: not self._writer_active and self._writers_waiting == 0)
            self._readers += 1
        try:
            yield
        finally:
            async with self._cond:
                self._readers -= 1
                if self._readers == 0:
                    self._cond.notify_all()

    @asynccontextmanager
    async def write(self) -> AsyncIterator[None]:
        async with self._cond:
            self._writers_waiting += 1
            try:
                await self._cond.wait_for(lambda: not self._writer_active and self._readers == 0)
            finally:
                self._writers_waiting -= 1
            self._writer_active = True
        try:
            yield
        finally:
            async with self._cond:
                self._writer_active = False
                self._cond.notify_all()
