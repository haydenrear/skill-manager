"""Behavioural tests for AsyncRWLock — writers serialize, readers parallel."""
from __future__ import annotations

import asyncio

from gateway.rwlock import AsyncRWLock


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def test_writers_serialize() -> None:
    async def body() -> list[str]:
        lock = AsyncRWLock()
        events: list[str] = []

        async def writer(tag: str) -> None:
            async with lock.write():
                events.append(f"start-{tag}")
                await asyncio.sleep(0.02)
                events.append(f"end-{tag}")

        await asyncio.gather(writer("a"), writer("b"))
        return events

    events = _run(body())
    # Writers must not overlap: every "start" must be immediately followed by its "end".
    assert events == ["start-a", "end-a", "start-b", "end-b"] or \
           events == ["start-b", "end-b", "start-a", "end-a"]


def test_readers_parallel() -> None:
    async def body() -> int:
        lock = AsyncRWLock()
        live = 0
        max_live = 0
        counter = asyncio.Lock()

        async def reader() -> None:
            nonlocal live, max_live
            async with lock.read():
                async with counter:
                    live += 1
                    max_live = max(max_live, live)
                await asyncio.sleep(0.02)
                async with counter:
                    live -= 1

        await asyncio.gather(*(reader() for _ in range(5)))
        return max_live

    max_live = _run(body())
    assert max_live >= 2, f"readers should run concurrently (saw {max_live} live)"


def test_writer_excludes_reader() -> None:
    async def body() -> list[str]:
        lock = AsyncRWLock()
        events: list[str] = []

        async def writer() -> None:
            async with lock.write():
                events.append("writer-start")
                await asyncio.sleep(0.02)
                events.append("writer-end")

        async def reader() -> None:
            await asyncio.sleep(0.005)  # let writer acquire first
            async with lock.read():
                events.append("reader-inside")

        await asyncio.gather(writer(), reader())
        return events

    assert _run(body()) == ["writer-start", "writer-end", "reader-inside"]
