"""Process-owned admission and execution-scoped cancellation on one event loop."""

import asyncio
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from functools import partial
from time import monotonic

from .ports import CallDenied


@dataclass
class Job:
    scope: "ResourceScope"
    resource: str
    factory: object
    future: asyncio.Future
    priority: int = 0
    background: bool = False
    host: str | None = None
    key: str | None = None
    tokens: int = 0
    quota_record: list | None = None
    quota_group: str = "default"


class ServiceResources:
    def __init__(
        self,
        *,
        model=6,
        search=12,
        read=18,
        cpu=2,
        per_host=2,
        background=3,
        pending=64,
        rpm=None,
        tpm=None,
        window_seconds=60.0,
        quota_groups=None,
        binding_groups=None,
    ):
        values = (model, search, read, cpu, per_host, background, pending)
        if any(type(v) is not int or v < 1 for v in values):
            raise ValueError("INVALID_RESOURCE_LIMITS")
        if background > model or window_seconds <= 0:
            raise ValueError("INVALID_RESOURCE_LIMITS")
        if (rpm is None) != (tpm is None) or any(
            v is not None and (type(v) is not int or v < 1) for v in (rpm, tpm)
        ):
            raise ValueError("INVALID_PROVIDER_QUOTA")
        if quota_groups is not None and (rpm is not None or tpm is not None):
            raise ValueError("AMBIGUOUS_PROVIDER_QUOTA")
        groups = (
            quota_groups
            if quota_groups is not None
            else ({"default": {"rpm": rpm, "tpm": tpm}} if rpm is not None else {})
        )
        if quota_groups is not None and not groups:
            raise ValueError("INVALID_PROVIDER_QUOTA")
        for name, quota in groups.items():
            if (
                not isinstance(name, str)
                or not name
                or set(quota) != {"rpm", "tpm"}
                or any(type(v) is not int or v < 1 for v in quota.values())
            ):
                raise ValueError("INVALID_PROVIDER_QUOTA")
        self.quotas = {name: dict(quota) for name, quota in groups.items()}
        self.binding_groups = dict(binding_groups or {})
        if any(group not in self.quotas for group in self.binding_groups.values()):
            raise ValueError("UNKNOWN_QUOTA_GROUP")
        self.limits = dict(model=model, search=search, read=read, cpu=cpu)
        self.counts = dict.fromkeys(self.limits, 0)
        self.per_host, self.background, self.max_pending = per_host, background, pending
        self.rpm, self.tpm, self.window = rpm, tpm, window_seconds
        self.pending = []
        self.running = {}
        self.cancelling = set()
        self.hosts = {}
        self.history = {name: deque() for name in self.quotas}
        self.turns = {}
        self.sequence = 0
        self.last_lane = False
        self.timer = None
        self.closed = False
        self.pool = ThreadPoolExecutor(max_workers=cpu, thread_name_prefix="auralis-cpu")

    def group_for_binding(self, binding):
        if not self.binding_groups and set(self.quotas) <= {"default"}:
            return "default"
        if binding not in self.binding_groups:
            raise CallDenied("MODEL_QUOTA_NOT_CONFIGURED")
        return self.binding_groups[binding]

    def scope(self, owner, session, limits=None):
        return ResourceScope(self, owner, session, limits)

    def _eligible(self, job):
        local = job.scope.limits.get(job.resource)
        if (
            local is not None
            and sum(
                j.scope is job.scope and j.resource == job.resource for j in self.running.values()
            )
            >= local
        ):
            return False
        if job.resource == "model" and job.background:
            cap = job.scope.limits.get("background")
            if (
                cap is not None
                and sum(
                    j.scope is job.scope and j.resource == "model" and j.background
                    for j in self.running.values()
                )
                >= cap
            ):
                return False
        local_host = job.scope.limits.get("per_host")
        if (
            job.host
            and local_host is not None
            and sum(j.scope is job.scope and j.host == job.host for j in self.running.values())
            >= local_host
        ):
            return False
        if self.counts[job.resource] >= self.limits[job.resource]:
            return False
        if job.host and self.hosts.get(job.host, 0) >= self.per_host:
            return False
        if job.resource == "model" and job.background:
            foreground = any(j.resource == "model" and not j.background for j in self.pending)
            occupied = sum(j.resource == "model" and j.background for j in self.running.values())
            if foreground and occupied >= self.background:
                return False
        if job.resource in {"model", "search"} and self.quotas:
            if (
                len(self.history[job.quota_group]) >= self.quotas[job.quota_group]["rpm"]
                or sum(t for _, t in self.history[job.quota_group]) + job.tokens
                > self.quotas[job.quota_group]["tpm"]
            ):
                return False
        return True

    def _dispatch(self):
        if self.closed:
            return
        if self.timer is not None:
            self.timer.cancel()
            self.timer = None
        now = monotonic()
        self.history = {
            name: deque(record for record in records if record[0] + self.window > now)
            for name, records in self.history.items()
        }
        self.pending[:] = [j for j in self.pending if not j.future.cancelled()]
        while True:
            eligible = [j for j in self.pending if self._eligible(j)]
            if not eligible:
                break
            # Alternate lanes, then least-recently served session. Priority is local.
            job = min(
                eligible,
                key=lambda j: (
                    j.background == self.last_lane if j.resource == "model" else False,
                    self.turns.get((j.resource, j.scope.session), 0),
                    j.priority,
                    self.pending.index(j),
                ),
            )
            self.pending.remove(job)
            self.sequence += 1
            self.turns[job.resource, job.scope.session] = self.sequence
            if job.resource == "model":
                self.last_lane = job.background
            if job.resource in {"model", "search"} and self.quotas:
                # Conservative upper-bound reservation; unknown usage is never refunded.
                job.quota_record = [float("inf"), job.tokens]
                self.history[job.quota_group].append(job.quota_record)
            self.counts[job.resource] += 1
            if job.host:
                self.hosts[job.host] = self.hosts.get(job.host, 0) + 1
            task = asyncio.create_task(self._execute(job))
            self.running[task] = job
            task.add_done_callback(lambda t, j=job: self._finished(t, j))
        expiries = [
            record[0]
            for records in self.history.values()
            for record in records
            if record[0] != float("inf")
        ]
        if self.pending and expiries:
            self.timer = asyncio.get_running_loop().call_later(
                max(0.001, min(expiries) + self.window - monotonic()), self._dispatch
            )

    async def _execute(self, job):
        try:
            result = await job.factory()
            if not job.future.done():
                job.future.set_result(result)
        except asyncio.CancelledError:
            job.future.cancel()
        except Exception as error:
            if not job.future.done():
                job.future.set_exception(error)

    def _finished(self, task, job):
        if job.quota_record is not None:
            job.quota_record[0] = monotonic()
        if task.cancelled():
            job.future.cancel()
        self.running.pop(task, None)
        self.cancelling.discard(task)
        self.counts[job.resource] -= 1
        if job.host:
            self.hosts[job.host] -= 1
            if not self.hosts[job.host]:
                del self.hosts[job.host]
        self._dispatch()

    async def close(self):
        self.closed = True
        if self.timer:
            self.timer.cancel()
        scopes = {j.scope for j in self.pending} | {j.scope for j in self.running.values()}
        await asyncio.gather(*(scope.close() for scope in scopes))
        self.pool.shutdown(wait=True)


async def drain(tasks):
    """Repeated cancellation cannot abandon physical cleanup."""
    waiter = asyncio.gather(*tasks, return_exceptions=True)
    while not waiter.done():
        try:
            await asyncio.shield(waiter)
        except asyncio.CancelledError:
            pass


class ResourceScope:
    def __init__(self, service, owner, session, limits=None):
        self.limits = limits or {}
        self.service, self.owner, self.session = service, owner, session
        self.closed = False

    async def run(
        self,
        resource,
        factory,
        *,
        priority=0,
        background=False,
        host=None,
        key=None,
        tokens=0,
        quota_group="default",
    ):
        service = self.service
        if self.closed or service.closed:
            raise asyncio.CancelledError
        if resource not in service.limits:
            raise ValueError("UNKNOWN_RESOURCE")
        if type(tokens) is not int or tokens < 0:
            raise ValueError("INVALID_TOKEN_RESERVATION")
        if resource in {"model", "search"} and service.quotas:
            if quota_group not in service.quotas:
                raise CallDenied("MODEL_QUOTA_NOT_CONFIGURED")
            if tokens > service.quotas[quota_group]["tpm"]:
                raise CallDenied("PROVIDER_QUOTA_EXCEEDED")
        if sum(j.resource == resource for j in service.pending) >= service.max_pending:
            raise CallDenied("RESOURCE_QUEUE_FULL")
        future = asyncio.get_running_loop().create_future()
        job = Job(
            self,
            resource,
            factory,
            future,
            priority,
            background,
            host,
            key,
            tokens,
            quota_group=quota_group,
        )
        service.pending.append(job)
        service._dispatch()
        try:
            return await future
        except asyncio.CancelledError:
            if job in service.pending:
                service.pending.remove(job)
            tasks = [t for t, j in service.running.items() if j is job]
            for task in tasks:
                if task not in service.cancelling:
                    service.cancelling.add(task)
                    task.cancel()
            await drain(tasks)
            service._dispatch()
            raise

    async def cpu(self, function, *args):
        async def compute():
            worker = asyncio.get_running_loop().run_in_executor(
                self.service.pool, partial(function, *args)
            )
            try:
                return await asyncio.shield(worker)
            except asyncio.CancelledError:
                await drain([worker])
                raise

        return await self.run("cpu", compute)

    def prioritize(self, key, priority):
        for job in self.service.pending:
            if job.scope is self and job.key == key:
                job.priority = min(job.priority, priority)
        self.service._dispatch()

    def cancel_pending(self, key):
        for job in list(self.service.pending):
            if job.scope is self and job.key == key:
                self.service.pending.remove(job)
                job.future.cancel()
        self.service._dispatch()

    async def close(self):
        self.closed = True
        service = self.service
        for job in list(service.pending):
            if job.scope is self:
                service.pending.remove(job)
                job.future.cancel()
        tasks = [t for t, j in service.running.items() if j.scope is self]
        for task in tasks:
            if task not in service.cancelling:
                service.cancelling.add(task)
                task.cancel()
        await drain(tasks)
        active_sessions = {j.scope.session for j in service.pending} | {
            j.scope.session for j in service.running.values()
        }
        service.turns = {k: v for k, v in service.turns.items() if k[1] in active_sessions}
        service._dispatch()
