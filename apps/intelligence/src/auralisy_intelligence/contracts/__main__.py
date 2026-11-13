"""Validate and serialize messages for the Java consumer contract probe."""

import json
import sys

from . import ContractError, validate_definition, validate_message


def main() -> int:
    messages = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    results = []
    for message in messages:
        try:
            if sys.argv[1:] == ["--definitions"]:
                validate_definition(message["definition"], message["payload"])
            else:
                validate_message(message)
        except ContractError as error:
            results.append({"accepted": False, "code": str(error)})
        else:
            results.append({"accepted": True, "message": message})
    json.dump(results, sys.stdout, ensure_ascii=True, allow_nan=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
