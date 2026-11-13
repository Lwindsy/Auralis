"""CPU inference for a fitted C-V0 LightGCN artifact."""

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .common import dataset_fingerprint, query_weights, weighted_preferences
from .io import load_dataset
from .types import RecallDataset, RecallRequest, WeightConfig

_DIMENSIONS = 32
_CONFIG = {
    "dimensions": _DIMENSIONS,
    "layers": 2,
    "epochs": 5,
    "query_epochs": 5,
    "steps_per_epoch": 73,
    "batch_size": 1024,
    "learning_rate": 0.01,
    "regularization": 0.0001,
    "max_support": 10,
    "seed": 17,
    "score_norm_power": 0.0,
}


@dataclass
class CComparison:
    data: RecallDataset
    weights: WeightConfig
    item_embeddings: np.ndarray
    seed_projection: np.ndarray
    tag_embeddings: np.ndarray
    item_coverage: np.ndarray
    tag_coverage: np.ndarray
    name = "C-V0"

    def score(self, request: RecallRequest) -> np.ndarray:
        seeds, tags = query_weights(self.data, request, self.weights)
        seeds = {i: w for i, w in seeds.items() if self.item_coverage[i]}
        tags = {i: w for i, w in tags.items() if self.tag_coverage[i]}
        parts = []
        if seeds:
            parts.append(
                sum(self.item_embeddings[i] * w for i, w in seeds.items()) @ self.seed_projection
            )
        if tags:
            parts.append(sum(self.tag_embeddings[i] * w for i, w in tags.items()))
        if not parts:
            return np.full(len(self.data.album_ids), -np.inf)
        supported_mass = sum(seeds.values()) + sum(tags.values())
        query = np.sum(parts, axis=0) / supported_mass
        scores = np.asarray(self.item_embeddings @ query, dtype=np.float64)
        scores[~self.item_coverage] = -np.inf
        return scores


def load_comparison(snapshot: Path, artifact: Path, weights: WeightConfig) -> CComparison:
    weighted_preferences((), weights)
    data = load_dataset(snapshot)
    with np.load(artifact, allow_pickle=False) as archive:
        try:
            metadata = json.loads(str(archive["metadata"].item()))
            config = {"score_norm_power": 0.0, **metadata["config"]}
            config.pop("device", None)
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("invalid C-V0 artifact metadata") from exc
        if (
            metadata.get("format") != 1
            or metadata.get("algorithm") != "lightgcn"
            or config != _CONFIG
        ):
            raise ValueError("artifact configuration does not match C-V0")
        if metadata.get("fingerprint") != dataset_fingerprint(data):
            raise ValueError("C-V0 artifact does not match the recall snapshot")

        shapes = {
            "item_embeddings": ((len(data.album_ids), _DIMENSIONS), np.float32),
            "seed_projection": ((_DIMENSIONS, _DIMENSIONS), np.float32),
            "tag_embeddings": ((len(data.tag_ids), _DIMENSIONS), np.float32),
            "item_coverage": ((len(data.album_ids),), np.bool_),
            "tag_coverage": ((len(data.tag_ids),), np.bool_),
        }
        arrays = {}
        for name, (shape, dtype) in shapes.items():
            if name not in archive:
                raise ValueError(f"missing C-V0 array: {name}")
            array = archive[name]
            if array.shape != shape or array.dtype != dtype or not np.isfinite(array).all():
                raise ValueError(f"invalid C-V0 array: {name}")
            arrays[name] = array
    return CComparison(data, weights, **arrays)
