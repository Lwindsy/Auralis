"""Album recall port for the intelligence application."""

from dataclasses import replace
from pathlib import Path

import numpy as np

from .c_v0 import load_comparison
from .common import recall as _rank
from .io import load_dataset
from .neighborhood import NeighborhoodConfig, NeighborhoodModel
from .types import Candidate, Preference, RecallModel, RecallRequest, RecallResult, WeightConfig

__all__ = [
    "Candidate",
    "Preference",
    "RecallEngine",
    "RecallRequest",
    "RecallResult",
    "WeightConfig",
    "load_a_v2",
    "load_c_v0",
]


class RecallEngine:
    """A reusable scorer bound to a snapshot and caller-provided preference weights."""

    def __init__(self, model: RecallModel, algorithm: str):
        self._model = model
        self._algorithm = algorithm
        self._full_audience = None

    def bind_full_audience(self, audience) -> None:
        """Install an independently admitted population; never mutate training statistics."""
        self._full_audience = audience

    @property
    def audience_binding(self) -> dict | None:
        return self._full_audience.binding_copy() if self._full_audience is not None else None

    def recall(self, request: RecallRequest, *, min_listeners: int = 0) -> RecallResult:
        if min_listeners < 0:
            raise ValueError("negative audience threshold")
        mask = None
        if min_listeners:
            if self._full_audience is None:
                raise ValueError("FULL_AUDIENCE_REQUIRED")
            mask = np.fromiter(
                (
                    self._full_audience.counts.get(identifier, -1) >= min_listeners
                    for identifier in self._model.data.album_ids
                ),
                dtype=bool,
            )
        return replace(
            _rank(self._model, request, eligibility_mask=mask), algorithm=self._algorithm
        )

    def audience(self, album_ids: list[str]) -> dict:
        """Product admission requires the independently bound full population."""
        if self._full_audience is None:
            raise ValueError("FULL_AUDIENCE_REQUIRED")
        return self._full_audience.audience(album_ids)

    def training_audience(self, album_ids: list[str]) -> dict:
        """Research-only statistics; never used for product eligibility."""
        if not hasattr(self, "_audience"):
            data = self._model.data
            counts = data.interactions.getnnz(axis=0)
            self._audience = dict(zip(data.album_ids, map(int, counts)))
        return {
            "dataset_id": self._model.data.dataset_id,
            "window": self._model.data.window,
            "sample_listeners": self._model.data.interactions.shape[0],
            "counts": {identifier: self._audience.get(identifier) for identifier in album_ids},
        }


def load_a_v2(snapshot: Path, *, weights: WeightConfig) -> RecallEngine:
    """Load a caller-authorized snapshot and build the A-V2 CPU statistics."""
    data = load_dataset(Path(snapshot))
    model = NeighborhoodModel(NeighborhoodConfig(tag_fraction=0.0), weights).fit(data)
    return RecallEngine(model, "A-V2")


def load_c_v0(snapshot: Path, artifact: Path, *, weights: WeightConfig) -> RecallEngine:
    """Load the comparison baseline using a caller-authorized local artifact."""
    return RecallEngine(load_comparison(Path(snapshot), Path(artifact), weights), "C-V0")
