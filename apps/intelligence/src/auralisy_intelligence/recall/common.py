"""Shared input semantics and candidate selection for research recall models."""

import hashlib
import heapq
import json
import math
from collections import deque
from numbers import Real

import numpy as np
from scipy import sparse

from .types import (
    Candidate,
    Preference,
    RecallDataset,
    RecallModel,
    RecallRequest,
    RecallResult,
    WeightConfig,
)


def _number(value: object, name: str, *, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, Real):
        raise ValueError(f"{name} must be a finite number")
    value = float(value)
    if not math.isfinite(value) or value < 0 or (positive and value == 0):
        raise ValueError(f"{name} must be {'positive' if positive else 'nonnegative'} and finite")
    return value


def _ids(values: object, name: str, *, unique: bool = False) -> None:
    if not isinstance(values, (tuple, list)):
        raise ValueError(f"{name} must be an ID sequence")
    if any(not isinstance(value, str) or not value.strip() for value in values):
        raise ValueError(f"{name} contains an invalid ID")
    if unique and len(set(values)) != len(values):
        raise ValueError(f"{name} contains duplicate IDs")


def validate_dataset(data: RecallDataset) -> None:
    """Validate one-window data and canonicalize owned sparse matrix copies."""
    if not isinstance(data, RecallDataset):
        raise ValueError("data must be RecallDataset")
    _ids(data.album_ids, "album_ids", unique=True)
    _ids(data.tag_ids, "tag_ids", unique=True)
    if not isinstance(data.dataset_id, str) or not data.dataset_id.strip():
        raise ValueError("dataset_id is required")
    if not isinstance(data.window, str) or not data.window.strip():
        raise ValueError("one explicit window is required")
    for name in ("album_tags", "interactions"):
        matrix = getattr(data, name)
        if not sparse.issparse(matrix) or matrix.ndim != 2:
            raise ValueError(f"{name} must be a sparse matrix")
        if np.iscomplexobj(matrix.data):
            raise ValueError(f"{name} must be real")
        if not np.isfinite(matrix.data).all() or (matrix.data < 0).any():
            raise ValueError(f"{name} must contain finite nonnegative values")
        matrix = sparse.csr_matrix(matrix, dtype=np.float64, copy=True)
        matrix.sum_duplicates()
        matrix.eliminate_zeros()
        matrix.sort_indices()
        if not np.isfinite(matrix.data).all():
            raise ValueError(f"{name} overflows after duplicate aggregation")
        if name == "album_tags":
            if matrix.shape != (len(data.album_ids), len(data.tag_ids)):
                raise ValueError("album_tags shape does not match IDs")
            matrix.data[:] = 1.0
        elif matrix.shape[1] != len(data.album_ids):
            raise ValueError("interactions columns do not match album IDs")
        setattr(data, name, matrix)
    tags = set(data.tag_ids)
    children = {tag: set() for tag in tags}
    indegree = dict.fromkeys(tags, 0)
    for edge in data.tag_parents:
        if not isinstance(edge, (tuple, list)) or len(edge) != 2:
            raise ValueError("tag_parents must contain (parent, child) pairs")
        parent, child = edge
        if parent not in tags or child not in tags:
            raise ValueError("tag relation contains unknown ID")
        if child not in children[parent]:
            children[parent].add(child)
            indegree[child] += 1
    pending = deque(tag for tag, degree in indegree.items() if degree == 0)
    seen = 0
    while pending:
        node = pending.popleft()
        seen += 1
        for child in children[node]:
            indegree[child] -= 1
            if indegree[child] == 0:
                pending.append(child)
    if seen != len(tags):
        raise ValueError("subgenre relations contain a cycle")


def weighted_preferences(items: tuple[Preference, ...], weights: WeightConfig) -> dict[str, float]:
    """Apply independent strength/focus multipliers; duplicates take maximum."""
    if not isinstance(weights, WeightConfig):
        raise ValueError("weights must be WeightConfig")
    special = _number(weights.special, "special multiplier", positive=True)
    focus = _number(weights.focus, "focus multiplier", positive=True)
    if not isinstance(items, (tuple, list)):
        raise ValueError("preferences must be a sequence")
    result: dict[str, float] = {}
    for item in items:
        if not isinstance(item, Preference) or not isinstance(item.id, str) or not item.id.strip():
            raise ValueError("invalid preference")
        if item.strength not in ("normal", "special") or not isinstance(item.focused, bool):
            raise ValueError("invalid preference strength/focus")
        weight = _number(item.weight, "preference weight")
        weight *= special if item.strength == "special" else 1.0
        weight *= focus if item.focused else 1.0
        if not math.isfinite(weight):
            raise ValueError("effective preference weight overflow")
        result[item.id] = max(result.get(item.id, 0.0), weight)
    return result


def validate_request(
    data: RecallDataset, request: RecallRequest, weights: WeightConfig = WeightConfig()
) -> None:
    """Validate structured IDs; unsupported free text is never accepted here."""
    if not isinstance(request, RecallRequest):
        raise ValueError("request must be RecallRequest")
    if isinstance(request.limit, bool) or not isinstance(request.limit, int) or request.limit <= 0:
        raise ValueError("limit must be a positive integer")
    if request.mode not in ("familiar", "explore", "surprise"):
        raise ValueError("unknown exploration mode")
    albums, tags = set(data.album_ids), set(data.tag_ids)
    seeds = weighted_preferences(request.seeds, weights)
    preferred_tags = weighted_preferences(request.tags, weights)
    if set(seeds) - albums or set(preferred_tags) - tags:
        raise ValueError("unknown seed or tag ID")
    for name in (
        "disliked_tags",
        "forbidden_tags",
        "required_tags",
        "excluded_ids",
        "disliked_exceptions",
    ):
        values = getattr(request, name)
        _ids(values, name)
        known = albums if name == "excluded_ids" else tags
        if set(values) - known:
            raise ValueError(f"{name} contains unknown ID")
    if not any(
        (
            request.seeds,
            request.tags,
            request.disliked_tags,
            request.forbidden_tags,
            request.required_tags,
            request.excluded_ids,
        )
    ):
        raise ValueError("at least one structured input is required")


def _normalize(values: dict[int, float]) -> dict[int, float]:
    maximum = max(values.values(), default=0.0)
    if maximum == 0:
        return {}
    scaled = {key: value / maximum for key, value in values.items() if value > 0}
    total = sum(scaled.values())
    return {key: value / total for key, value in scaled.items()}


def query_weights(
    data: RecallDataset, request: RecallRequest, weights: WeightConfig
) -> tuple[dict[int, float], dict[int, float]]:
    """Jointly normalize seeds/tags, preserving cross-channel preference mass.

    Familiar: direct tags. Explore: one edge. Surprise: two edges.
    Soft expansion is bidirectional with 0.5 decay; hard closures are descendant-only.
    Each tag spreads its existing mass over its expansion, rather than gaining
    extra mass simply because it has more graph neighbors.
    """
    validate_request(data, request, weights)
    album_index = {value: index for index, value in enumerate(data.album_ids)}
    tag_index = {value: index for index, value in enumerate(data.tag_ids)}
    seeds = {
        album_index[key]: value
        for key, value in weighted_preferences(request.seeds, weights).items()
    }
    direct = {
        tag_index[key]: value for key, value in weighted_preferences(request.tags, weights).items()
    }
    maximum = max(max(seeds.values(), default=0.0), max(direct.values(), default=0.0))
    if maximum == 0:
        return {}, {}
    seeds = {key: value / maximum for key, value in seeds.items() if value > 0}
    direct = {key: value / maximum for key, value in direct.items() if value > 0}
    total = sum(seeds.values()) + sum(direct.values())
    seeds = {key: value / total for key, value in seeds.items()}
    direct = {key: value / total for key, value in direct.items()}
    neighbors: dict[int, set[int]] = {}
    for parent, child in data.tag_parents:
        p, c = tag_index[parent], tag_index[child]
        neighbors.setdefault(p, set()).add(c)
        neighbors.setdefault(c, set()).add(p)
    depth = {"familiar": 0, "explore": 1, "surprise": 2}[request.mode]
    expanded: dict[int, float] = {}
    for origin, value in direct.items():
        local = {origin: 1.0}
        pending = deque([(origin, 0)])
        visited = {origin}
        while pending:
            current, distance = pending.popleft()
            if distance == depth:
                continue
            for neighbor in sorted(neighbors.get(current, ())):
                if neighbor not in visited:
                    visited.add(neighbor)
                    local[neighbor] = 0.5 ** (distance + 1)
                    pending.append((neighbor, distance + 1))
        for neighbor, fraction in _normalize(local).items():
            expanded[neighbor] = expanded.get(neighbor, 0.0) + value * fraction
    return seeds, expanded


def _descendants(data: RecallDataset, roots: tuple[str, ...]) -> set[int]:
    children: dict[str, set[str]] = {}
    for parent, child in data.tag_parents:
        children.setdefault(parent, set()).add(child)
    visited = set(roots)
    pending = list(roots)
    while pending:
        for child in children.get(pending.pop(), ()):
            if child not in visited:
                visited.add(child)
                pending.append(child)
    return {index for index, tag in enumerate(data.tag_ids) if tag in visited}


def effective_disliked(
    data: RecallDataset, roots: tuple[str, ...], exceptions: tuple[str, ...]
) -> set[int]:
    """Explicit child interests exempt only their subtree from each broader preselection."""
    positions = {identifier: index for index, identifier in enumerate(data.tag_ids)}
    closures = {root: _descendants(data, (root,)) for root in set(roots) | set(exceptions)}
    result = set()
    for root in roots:
        blocked = set(closures[root])
        for exception in exceptions:
            if exception != root and positions[exception] in closures[root]:
                blocked.difference_update(closures[exception])
        result.update(blocked)
    return result


def recall(model: RecallModel, request: RecallRequest, *, eligibility_mask=None) -> RecallResult:
    """Call the actual provider, filter the full candidate pool, then select R."""
    data = getattr(model, "data", None)
    if data is None:
        raise RuntimeError("model must be fitted before recall")
    validate_request(data, request, model.weights)
    seeds, tags = query_weights(data, request, model.weights)
    if not seeds and not tags:
        return RecallResult(
            (),
            request.limit,
            0,
            "unsupported_positive_input",
            model.name,
            data.dataset_id,
            data.window,
        )
    scores = np.asarray(model.score(request), dtype=np.float64)
    if (
        scores.shape != (len(data.album_ids),)
        or np.isnan(scores).any()
        or np.isposinf(scores).any()
    ):
        raise ValueError("provider must return one finite or -inf score per album")
    disliked = effective_disliked(data, request.disliked_tags, request.disliked_exceptions)
    forbidden = _descendants(data, request.forbidden_tags)
    required = [_descendants(data, (tag,)) for tag in request.required_tags]
    excluded = set(request.excluded_ids) | {item.id for item in request.seeds}
    eligible: list[int] = []
    eligible_scores = np.isfinite(scores)
    if eligibility_mask is not None:
        if eligibility_mask.shape != scores.shape:
            raise ValueError("audience mask shape mismatch")
        eligible_scores &= eligibility_mask
    for index in np.flatnonzero(eligible_scores):
        if data.album_ids[index] in excluded:
            continue
        if not disliked and not forbidden and not required:
            eligible.append(index)
            continue
        start, end = data.album_tags.indptr[index : index + 2]
        direct = set(data.album_tags.indices[start:end])
        if (forbidden or required) and not direct:
            continue
        if direct & forbidden or (direct and direct <= disliked):
            continue
        if any(not direct.intersection(group) for group in required):
            continue
        eligible.append(index)
    selected = heapq.nsmallest(
        request.limit, eligible, key=lambda index: (-scores[index], data.album_ids[index])
    )
    result = tuple(Candidate(data.album_ids[index], float(scores[index])) for index in selected)
    reason = "complete" if len(result) == request.limit else "insufficient_candidates"
    return RecallResult(
        result, request.limit, len(result), reason, model.name, data.dataset_id, data.window
    )


def dataset_fingerprint(data: RecallDataset) -> str:
    """Bind model artifacts to ordered catalog IDs, relations and matrix content."""
    digest = hashlib.sha256()
    header = [
        data.dataset_id,
        data.window,
        data.album_ids,
        data.tag_ids,
        sorted(set(data.tag_parents)),
    ]
    digest.update(json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    for source in (data.album_tags, data.interactions):
        matrix = sparse.csr_matrix(source, dtype=np.float64, copy=True)
        matrix.sum_duplicates()
        matrix.eliminate_zeros()
        matrix.sort_indices()
        digest.update(np.asarray(matrix.shape, dtype="<i8").tobytes())
        digest.update(np.asarray(matrix.indptr, dtype="<i8").tobytes())
        digest.update(np.asarray(matrix.indices, dtype="<i8").tobytes())
        digest.update(np.asarray(matrix.data, dtype="<f8").tobytes())
    return digest.hexdigest()
