"""Cross-language test driver for the real Python merge; no model or network."""

import json
import sys
from pathlib import Path

from auralis_intelligence.preferences import ReferenceCatalog, merge_preferences


def run(path: Path) -> list[dict]:
    document = json.loads(path.read_text(encoding="utf-8"))
    reference = ReferenceCatalog(
        json.loads((path.parent / document["reference_fixture"]).read_text(encoding="utf-8"))[
            "reference"
        ]
    )
    results = []
    for case in document["cases"]:
        try:
            result = merge_preferences(
                case["raw"],
                case["proposal"],
                reference=reference,
                seed_descriptions=case["seed_descriptions"],
            )
            results.append({"name": case["name"], "result": result})
        except ValueError as error:
            results.append(
                {
                    "name": case["name"],
                    "error": str(error),
                    "outcome": getattr(error, "status", "FAILED"),
                }
            )
    return results


if __name__ == "__main__":
    print(json.dumps(run(Path(sys.argv[1])), ensure_ascii=True))
