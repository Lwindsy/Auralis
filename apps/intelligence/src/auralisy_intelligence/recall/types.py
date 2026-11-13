"""Version 1 in-process contract for album recall.

Matrices: interactions are listener x album counts in ONE window;
album_tags are album x tag direct associations. IDs are aligned with columns.
No raw user text, database credentials, or final recommendation objects.
"""

from dataclasses import dataclass
from typing import Literal, Protocol

import numpy as np
from scipy.sparse import csr_matrix


@dataclass(frozen=True)
class Preference:
    id: str
    strength: Literal["normal", "special"] = "normal"
    focused: bool = False
    weight: float = 1.0


@dataclass(frozen=True)
class WeightConfig:
    special: float = 2.0
    focus: float = 2.0


@dataclass(frozen=True)
class RecallRequest:
    limit: int
    seeds: tuple[Preference, ...] = ()
    tags: tuple[Preference, ...] = ()
    disliked_tags: tuple[str, ...] = ()
    forbidden_tags: tuple[str, ...] = ()
    required_tags: tuple[str, ...] = ()
    excluded_ids: tuple[str, ...] = ()
    mode: Literal["familiar", "explore", "surprise"] = "explore"
    disliked_exceptions: tuple[str, ...] = ()


@dataclass
class RecallDataset:
    album_ids: tuple[str, ...]
    tag_ids: tuple[str, ...]
    album_tags: csr_matrix
    interactions: csr_matrix
    dataset_id: str
    window: str
    # (parent, child), ONLY subgenre edges; never influence/fusion aliases.
    tag_parents: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class Candidate:
    album_id: str
    score: float


@dataclass(frozen=True)
class RecallResult:
    candidates: tuple[Candidate, ...]
    requested_count: int
    returned_count: int
    reason: str
    algorithm: str
    dataset_id: str
    window: str


class RecallModel(Protocol):
    """A fitted provider scores EVERY album in dataset order.

    Lower-bound unsupported candidates may be -inf; no NaN or +inf.
    Shared common.recall applies input checks, constraints, sorting and limit.
    """

    name: str
    data: RecallDataset
    weights: WeightConfig

    def score(self, request: RecallRequest) -> np.ndarray: ...
