"""English edition of the model-facing instruction written inline in ranking.

A faithful translation of the current Chinese text. Not wired into the runtime:
selection.py keeps sending the Chinese original until the backend chooses prompts by the
request's language. The system prompt itself is select.v1.en.txt.
"""

# selection.py repair instruction after an invalid select.v1 reply.
SELECT_REPAIR_INSTRUCTION = (
    "Give a complete ordering of every input C id with no duplicates, using only the "
    "input ids; every reject must state its rationale."
)
