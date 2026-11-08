# Auralis

[中文](README_ZH.md)

Auralis aims to help you discover albums through genre preferences, favorite albums, or a description of what you want to hear. Provide at least one type of input, then explore short reviews and open detailed reviews when interested.

## Availability

A standalone Python agent demo and an internal execution service are available. Java product integration and the browser application are still pending; there is no complete website release yet.

## Agent development

Use Python 3.10+ with the dependencies in [pyproject.toml](apps/intelligence/pyproject.toml). From the repository root, run the complete first-batch flow with synthetic models and pages and the local A-V2 implementation. This performs no paid requests.

```powershell
python -m pip install -e apps/intelligence
python -m auralis_intelligence.agents demo --scenario mixed
python -m auralis_intelligence.agents demo --scenario mixed --background --r 3 --k 2 --n 1
python -m auralis_intelligence.agents demo --scenario genre --r 5 --k 4 --n 2 --include-calls
```

The optional `--background` mode also runs replenishment and closes with a round report. The service supports prioritized long reviews, preemption, explicit regeneration and continuation.

The output reports first-batch composition, publication counts, stage timing and optional safe call records. Synthetic timing and zero-price fixtures do not measure real model quality, speed or cost. The other input scenarios are `album`, `text` and `no-positive`; the last hands control back to Java's curated-list branch.

For the loopback HTTP service, provide explicit local paths and credential environment-variable names using [agent-service.example.json](apps/intelligence/agent-service.example.json) and reviewed [model bindings](apps/intelligence/provider-bindings.example.json), then run `python -m auralis_intelligence.agents serve --config <config.json>`. A matching Java control service is required. The sample bindings keep live calls disabled.

## Recall development

A-V2 is the product recall baseline. Python callers use the typed port in
[`auralis_intelligence.recall`](apps/intelligence/src/auralis_intelligence/recall/__init__.py).
Backend developers can exercise the local JSON adapter using the same
[request](apps/intelligence/src/auralis_intelligence/recall/request-v1.schema.json) and
[result](apps/intelligence/src/auralis_intelligence/recall/result-v1.schema.json) schemas.

```powershell
$env:PYTHONPATH = 'apps/intelligence/src'
python -m auralis_intelligence.recall --snapshot apps/intelligence/tests/fixtures/synthetic-snapshot.json --request apps/intelligence/tests/fixtures/request.json --special-weight 2 --focus-weight 2
```

Use Python 3.10+ and the dependencies in [pyproject.toml](apps/intelligence/pyproject.toml).
Pass a caller-authorized local snapshot explicitly. The adapter emits candidate IDs and
snapshot binding for integration. The Python execution service includes this stage; Java product integration is pending.

This branch also supports the C-V0 comparison. Use `load_c_v0` from the same
Python port, or add `--c-v0-model <model.npz>` to the local command with the
matching snapshot. The trained artifact is supplied locally. A-V2 remains the default.

## Planned experience

- Provide genre preferences, favorite albums, or natural-language requests without logging in or saving a persistent personal taste profile.
- Choose familiar, expand, or surprise exploration, then view album artwork, artist, main genres, release year and a short review. Detailed reviews open on request.
- Mark an album as heard, or request a new album with the separate replacement button. Replace the whole group or edit the prefilled request in place. A replenishing empty slot fills automatically when ready.
- Receive the available number of results. With no results or an error, return to your prefilled input using the provided button.
