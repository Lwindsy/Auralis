"""Compute Java-only interval unions using an injectable monotonic clock."""

from contextlib import contextmanager
import time
from typing import Callable, Iterator


class AgentClock:
    def __init__(self, now: Callable[[], float] = time.perf_counter) -> None:
        self.now = now
        self.intervals: list[tuple[str, float, float]] = []
        self.active: dict[int, tuple[str, float]] = {}
        self._sequence = 0
        self.agent_start: float | None = None
        self.agent_end: float | None = None
        self.phase_floor: float | None = None

    @contextmanager
    def measure(self, category: str) -> Iterator[None]:
        self._sequence += 1
        identifier = self._sequence
        start = self.now()
        self.active[identifier] = (category, start)
        try:
            yield
        finally:
            self.active.pop(identifier, None)
            self.intervals.append((category, start, self.now()))

    def start_agent(self, *, reset_phases: bool = False) -> None:
        self.agent_start = self.now()
        self.agent_end = None
        self.phase_floor = self.agent_start if reset_phases else None

    def finish_agent(self) -> dict[str, int]:
        self.agent_end = self.now()
        return self.snapshot()

    def snapshot(self) -> dict[str, int]:
        end = self.agent_end if self.agent_end is not None else self.now()
        start = self.agent_start if self.agent_start is not None else end
        intervals = self.intervals + [(kind, since, end) for kind, since in self.active.values()]
        boundaries = {start, end}
        relevant = []
        for kind, left, right in intervals:
            left, right = max(start, left), min(end, right)
            if right > left:
                relevant.append((kind, left, right))
                boundaries.update((left, right))
        java_only = 0.0
        points = sorted(boundaries)
        for left, right in zip(points, points[1:]):
            kinds = {kind for kind, a, b in relevant if a <= left and b >= right}
            if "java" in kinds and not kinds.intersection({"model", "search", "read"}):
                java_only += right - left
        wall = max(0, round((end - start) * 1000))
        blocked = max(0, round(java_only * 1000))
        result = {"agent_wall_ms": wall, "java_blocked_ms": blocked, "agent_wait_ms": max(0, wall - blocked)}
        for category in ("interpret", "recall", "candidate_validation"):
            result[category + "_ms"] = round(sum(max(0, b - max(a, self.phase_floor)) if self.phase_floor is not None else b - a for kind, a, b in intervals if kind == category) * 1000)
        return result

    def clear(self) -> None:
        self.intervals.clear()
        self.active.clear()
