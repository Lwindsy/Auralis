"""Album recall port for the intelligence application."""

from dataclasses import replace
from pathlib import Path

from .common import recall as _rank
from .io import load_dataset
from .neighborhood import NeighborhoodConfig, NeighborhoodModel
from .types import Candidate, Preference, RecallModel, RecallRequest, RecallResult, WeightConfig

__all__ = ["Candidate", "Preference", "RecallEngine", "RecallRequest", "RecallResult", "WeightConfig", "load_a_v2"]


class RecallEngine:
    """A reusable scorer bound to a snapshot and caller-provided preference weights."""

    def __init__(self, model: RecallModel, algorithm: str):
        self._model = model
        self._algorithm = algorithm

    def recall(self, request: RecallRequest) -> RecallResult:
        return replace(_rank(self._model, request), algorithm=self._algorithm)


def load_a_v2(snapshot: Path, *, weights: WeightConfig) -> RecallEngine:
    """Load a caller-authorized snapshot and build the A-V2 CPU statistics."""
    data = load_dataset(Path(snapshot))
    model = NeighborhoodModel(NeighborhoodConfig(tag_fraction=0.0), weights).fit(data)
    return RecallEngine(model, "A-V2")
