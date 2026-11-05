"""Sparse listener-neighborhood and direct-genre candidate retrieval."""

from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path

import numpy as np
from scipy import sparse

from .common import dataset_fingerprint, query_weights, validate_dataset, weighted_preferences
from .types import Preference, RecallDataset, RecallRequest, WeightConfig


@dataclass(frozen=True)
class NeighborhoodConfig:
    shrinkage: float = 10.0
    max_fraction: float = 0.5
    tag_fraction: float = 0.5
    rrf_constant: float = 60.0
    use_counts: bool = False
    count_cap: float = 1000.0
    activity_correction: bool = True


class NeighborhoodModel:
    name = "neighborhood"

    def __init__(self, config: NeighborhoodConfig = NeighborhoodConfig(), weights: WeightConfig = WeightConfig()):
        self.config = config
        self.weights = weights
        self.data: RecallDataset | None = None
        self._validate_config()

    def _validate_config(self) -> None:
        for name in ("shrinkage", "max_fraction", "tag_fraction", "rrf_constant", "count_cap"):
            value = getattr(self.config, name)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                raise ValueError(f"invalid {name}")
        if self.config.max_fraction > 1 or self.config.tag_fraction > 1:
            raise ValueError("fractions must be between zero and one")
        if self.config.count_cap == 0:
            raise ValueError("count_cap must be positive")
        if not isinstance(self.config.use_counts, bool) or not isinstance(self.config.activity_correction, bool):
            raise ValueError("count/activity options must be boolean")
        weighted_preferences((), self.weights)

    def fit(self, data: RecallDataset) -> "NeighborhoodModel":
        validate_dataset(data)
        positive = data.interactions.copy()
        positive.data[:] = 1.0
        self._binary = positive.tocsc()
        counts = data.interactions.copy()
        counts.data = np.log1p(np.minimum(counts.data, self.config.count_cap)) if self.config.use_counts else np.ones_like(counts.data)
        activity = np.asarray(positive.getnnz(axis=1)).ravel()
        correction = 1.0 / np.log(2.0 + activity) if self.config.activity_correction else np.ones(len(activity))
        weighted = sparse.diags(np.sqrt(correction)) @ counts
        norms = np.sqrt(np.asarray(weighted.power(2).sum(axis=0)).ravel())
        inverse = np.divide(1.0, norms, out=np.zeros_like(norms), where=norms > 0)
        self._normalized = (weighted @ sparse.diags(inverse)).tocsc()
        frequencies = np.asarray(data.album_tags.getnnz(axis=0)).ravel()
        self._idf = np.log((1.0 + len(data.album_ids)) / (1.0 + frequencies)) + 1.0
        genre = data.album_tags.multiply(self._idf).tocsr()
        genre_norm = np.sqrt(np.asarray(genre.power(2).sum(axis=1)).ravel())
        inverse_genre = np.divide(1.0, genre_norm, out=np.zeros_like(genre_norm), where=genre_norm > 0)
        self._genre = (sparse.diags(inverse_genre) @ genre).tocsr()
        self.data = data
        return self

    def _tag_query(self, request: RecallRequest, seeds: dict[int, float], tags: dict[int, float]) -> np.ndarray:
        data = self.data
        assert data is not None
        # Seed labels are a recall hint, not inherited per-album musical facts.
        derived: dict[str, float] = {}
        for index, weight in seeds.items():
            row = data.album_tags.getrow(index)
            for tag in row.indices:
                key = data.tag_ids[tag]
                derived[key] = derived.get(key, 0.0) + weight / max(1, len(row.indices))
        seed_tags: dict[int, float] = {}
        if derived:
            proxy = RecallRequest(limit=request.limit, tags=tuple(Preference(key, weight=value) for key, value in derived.items()), mode=request.mode)
            _, seed_tags = query_weights(data, proxy, self.weights)
        result = np.zeros(len(data.tag_ids), dtype=np.float64)
        for channel in (tags, seed_tags):
            for index, weight in channel.items():
                result[index] += weight
        result *= self._idf
        norm = np.linalg.norm(result)
        return result / norm if norm > 0 else result

    def _reciprocal_ranks(self, scores: np.ndarray) -> np.ndarray:
        assert self.data is not None
        result = np.zeros(len(scores))
        indices = np.flatnonzero(scores > 0).tolist()
        indices.sort(key=lambda index: (-scores[index], self.data.album_ids[index]))
        for rank, index in enumerate(indices, 1):
            result[index] = 1.0 / (self.config.rrf_constant + rank)
        return result

    def score(self, request: RecallRequest) -> np.ndarray:
        if self.data is None:
            raise RuntimeError("model must be fitted before scoring")
        seeds, tags = query_weights(self.data, request, self.weights)
        count = len(self.data.album_ids)
        mean = np.zeros(count)
        maximum = np.zeros(count)
        seed_mass = sum(seeds.values())
        tag_mass = sum(tags.values())
        strongest = max(seeds.values(), default=1.0)
        for index, weight in seeds.items():
            similarity = (self._normalized.T @ self._normalized[:, index]).toarray().ravel()
            common = (self._binary.T @ self._binary[:, index]).toarray().ravel()
            denominator = common + self.config.shrinkage
            shrink = np.divide(common, denominator, out=np.zeros_like(common), where=denominator > 0)
            similarity *= shrink
            mean += (weight / seed_mass) * similarity
            maximum = np.maximum(maximum, (weight / strongest) * similarity)
        neighbor = self.config.max_fraction * maximum + (1.0 - self.config.max_fraction) * mean
        inherited_genre = np.asarray(self._genre @ self._tag_query(request, seeds, {})).ravel()
        explicit_genre = np.asarray(self._genre @ self._tag_query(request, {}, tags)).ravel()
        result = np.zeros(count)
        mass = 0.0
        # Split only the seed channel between behavior and inherited genre;
        # explicit tags retain their independently weighted input mass.
        channels = ((neighbor, (1.0 - self.config.tag_fraction) * seed_mass),
                    (inherited_genre, self.config.tag_fraction * seed_mass),
                    (explicit_genre, tag_mass))
        for scores, fraction in channels:
            if fraction > 0 and np.any(scores > 0):
                result += fraction * self._reciprocal_ranks(scores)
                mass += fraction
        if mass:
            result /= mass
        result[result <= 0] = -np.inf
        return result

    def save(self, path: Path) -> None:
        if self.data is None:
            raise RuntimeError("model must be fitted before saving")
        path = Path(path)
        path.mkdir(parents=True, exist_ok=True)
        payload = {"format_version": 1, "algorithm": self.name, "config": asdict(self.config),
                   "weights": asdict(self.weights), "dataset_fingerprint": dataset_fingerprint(self.data)}
        (path / "model.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: Path, data: RecallDataset) -> "NeighborhoodModel":
        validate_dataset(data)
        payload = json.loads((Path(path) / "model.json").read_text(encoding="utf-8"))
        if payload.get("format_version") != 1 or payload.get("algorithm") != cls.name:
            raise ValueError("unsupported neighborhood artifact")
        if payload.get("dataset_fingerprint") != dataset_fingerprint(data):
            raise ValueError("artifact dataset does not match")
        return cls(NeighborhoodConfig(**payload["config"]), WeightConfig(**payload["weights"])).fit(data)
