"""One-shot JSON adapter for local backend integration."""

import argparse
import json
import sys
from pathlib import Path

from . import WeightConfig, load_a_v2, load_c_v0
from .io import load_request


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--special-weight", type=float, required=True)
    parser.add_argument("--focus-weight", type=float, required=True)
    parser.add_argument("--c-v0-model", type=Path, help="explicit local comparison artifact")
    args = parser.parse_args()

    try:
        request = load_request(args.request)
        weights = WeightConfig(special=args.special_weight, focus=args.focus_weight)
        if args.c_v0_model is None:
            engine = load_a_v2(args.snapshot, weights=weights)
        else:
            engine = load_c_v0(args.snapshot, args.c_v0_model, weights=weights)
        result = engine.recall(request)
    except (OSError, ValueError) as exc:
        print(f"Recall failed: {exc}", file=sys.stderr)
        return 2

    print(
        json.dumps(
            {
                "format_version": 1,
                "algorithm": result.algorithm,
                "dataset_id": result.dataset_id,
                "window": result.window,
                "requested_count": result.requested_count,
                "returned_count": result.returned_count,
                "reason": result.reason,
                "candidate_ids": [item.album_id for item in result.candidates],
            },
            allow_nan=False,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
