"""Bounded in-process scheduling with resource limits and priority admission."""

import asyncio
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable


@dataclass(order=True)
class _Job:
    priority: int
    sequence: int
    resource: str = field(compare=False)
    factory: Callable[[], Awaitable[Any]] = field(compare=False)
    future: asyncio.Future = field(compare=False)
    background: bool = field(compare=False, default=False)
    host: str | None = field(compare=False, default=None)
    key: str | None = field(compare=False, default=None)


class BoundedScheduler:
    def __init__(
        self,
        limits: dict[str, int | None],
        *,
        background_model_limit: int,
        per_host_limit: int | None,
        max_pending: int = 1024,
    ) -> None:
        if (
            any(value is not None and value < 1 for value in limits.values())
            or background_model_limit < 1
            or (per_host_limit is not None and per_host_limit < 1)
        ):
            raise ValueError("INVALID_CONCURRENCY")
        self.limits = dict(limits)
        self.background_model_limit = background_model_limit
        self.per_host_limit = per_host_limit
        self.max_pending = max_pending
        self.pending: list[_Job] = []
        self.running: dict[asyncio.Task, _Job] = {}
        self.counts = {kind: 0 for kind in limits}
        self.hosts: dict[str, int] = {}
        self.background_models = 0
        self.closed = False
        self._sequence = 0

    async def run(
        self,
        resource: str,
        factory: Callable[[], Awaitable[Any]],
        *,
        priority: int = 0,
        background: bool = False,
        host: str | None = None,
        key: str | None = None,
    ) -> Any:
        if self.closed:
            raise asyncio.CancelledError
        if resource not in self.limits:
            raise ValueError("UNKNOWN_RESOURCE")
        if len(self.pending) >= self.max_pending:
            raise RuntimeError("SCHEDULER_CAPACITY")
        self._sequence += 1
        future = asyncio.get_running_loop().create_future()
        job = _Job(priority, self._sequence, resource, factory, future, background, host, key)
        self.pending.append(job)
        self._dispatch()
        try:
            return await future
        except asyncio.CancelledError:
            if job in self.pending:
                self.pending.remove(job)
            tasks = [task for task, active in self.running.items() if active is job]
            for task in tasks:
                task.cancel()
            # Propagation waits for physical cleanup, including cancellation-resistant I/O.
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            raise

    def _available(self, job: _Job) -> bool:
        if (
            self.limits[job.resource] is not None
            and self.counts[job.resource] >= self.limits[job.resource]
        ):
            return False
        if (
            job.resource == "model"
            and job.background
            and self.background_models >= self.background_model_limit
        ):
            return False
        if (
            job.host
            and self.per_host_limit is not None
            and self.hosts.get(job.host, 0) >= self.per_host_limit
        ):
            return False
        return True

    def prioritize(self, key: str, priority: int) -> None:
        for job in self.pending:
            if job.key == key:
                job.priority = min(job.priority, priority)
        self._dispatch()

    def cancel_pending(self, key: str) -> None:
        for job in list(self.pending):
            if job.key == key:
                self.pending.remove(job)
                job.future.cancel()

    def _dispatch(self) -> None:
        if self.closed:
            return
        for job in sorted(self.pending):
            if job.future.cancelled():
                self.pending.remove(job)
                continue
            if not self._available(job):
                continue
            self.pending.remove(job)
            self.counts[job.resource] += 1
            if job.background and job.resource == "model":
                self.background_models += 1
            if job.host:
                self.hosts[job.host] = self.hosts.get(job.host, 0) + 1
            task = asyncio.create_task(self._execute(job))
            self.running[task] = job

    async def _execute(self, job: _Job) -> None:
        try:
            result = await job.factory()
        except asyncio.CancelledError:
            job.future.cancel()
        except Exception as error:
            if not job.future.done():
                job.future.set_exception(error)
        else:
            if not job.future.done():
                job.future.set_result(result)
        finally:
            self.running.pop(asyncio.current_task(), None)
            self.counts[job.resource] -= 1
            if job.background and job.resource == "model":
                self.background_models -= 1
            if job.host:
                self.hosts[job.host] -= 1
                if not self.hosts[job.host]:
                    del self.hosts[job.host]
            self._dispatch()

    async def close(self) -> None:
        self.closed = True
        for job in self.pending:
            job.future.cancel()
        self.pending.clear()
        tasks = list(self.running)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
