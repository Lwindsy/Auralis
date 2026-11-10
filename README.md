# Auralis

[中文](README_ZH.md)

Auralis aims to help you discover albums through genre preferences, favorite albums, or a description of what you want to hear. Provide at least one type of input, then explore short reviews and open detailed reviews when interested.

## Availability

The Java recommendation APIs and production Python pipeline are connected and verified with isolated PostgreSQL and synthetic model/page adapters. Browser recommendation interactions have also been tested against Java. Real-provider requests and local capacity have also been measured. Overall recommendation quality and complete website acceptance remain pending; the product has not been publicly deployed.

The Java online bootstrap can be built with `mvn -f apps/backend/pom.xml verify` and started with `java -jar apps/backend/target/auralis-core-database-0.1.0-SNAPSHOT-online.jar`. It requires explicit `AURALIS_JDBC_URL`, `AURALIS_DATABASE_USER`, `AURALIS_DATABASE_PASSWORD`, `AURALIS_PRODUCT_ORIGIN`, `AURALIS_PYTHON_URL`, `AURALIS_SERVICE_TOKEN`, `AURALIS_CONTRACT_REVISION` and `AURALIS_SUBJECT_SECRET` environment variables. Use PostgreSQL 18 with the existing migrations applied by the offline CLI and a non-DDL runtime role. The service exposes health probes, anonymous sessions and recommendation APIs. Recommendations additionally require explicit `AURALIS_RECOMMENDATION_CONFIG` and `AURALIS_DISPATCH_ENABLED=true`, with matching approved artifacts, model bindings and budget configuration.

Apply the full checked-in migration chain before startup (currently V1–V15); use protocol revision 0.32 with matching Java and Python builds.

## Linux deployment

The [container deployment guide](apps/runtime/deployment.txt) describes the single-host Compose stack and its required external data and secrets. The [CI/CD workflow](.github/workflows/ci.yml) checks pull requests and deploys successful `main` updates over SSH, with a database backup, migration and readiness checks. It does not package music data or credentials into images.

## Browser development

Use Node.js 22.12 or newer. From the repository root:

```powershell
npm --prefix apps/frontend ci
npm --prefix apps/frontend run build
npm --prefix apps/frontend test
npm --prefix apps/frontend run check
npm --prefix apps/frontend run test:browser
npm --prefix apps/frontend run dev:fixture
```

Open `http://127.0.0.1:4173`. Explicit fixture mode displays a synthetic-data notice and does not call models. For Java, use `npm --prefix apps/frontend run dev`, set `AURALIS_JAVA_ORIGIN` to its loopback URL, and set Java's `AURALIS_PRODUCT_ORIGIN` to the frontend URL. Local HTTP also requires explicit `AURALIS_ALLOW_INSECURE_COOKIE=true`. Vite serves development modules or the built preview and forwards `/v1/` to Java; production hosting must provide its own same-origin API routing. Search requires Java's verified full catalog index. Recommendation commands are available when the explicit recommendation policy, dispatcher and approved data are configured.

Routes and validators are checked in, so normal builds need only the product checkout. `npm --prefix apps/frontend run generate` additionally requires the matching private OpenAPI workspace and regenerates from the shared schema; generated files must not be edited manually. Browser tests use installed Chrome and a dedicated port 4187. For real Java catalog browser tests, set `AURALIS_JAVA_ORIGIN` and run `npm --prefix apps/frontend run test:java`; Java must allow frontend origin `http://127.0.0.1:4188`.

## Anonymous sessions

Provide a separate, securely generated URL-safe signing key of at least 43 characters in `AURALIS_SUBJECT_SECRET`. Keep it stable across ordinary restarts and separate from database backups. HTTPS uses Secure/HttpOnly/SameSite=Lax cookies; local loopback HTTP requires the explicit `AURALIS_ALLOW_INSECURE_COOKIE=true` exception. Internal protocol configuration must match the packaged schema (currently 0.32).

`POST /v1/bootstrap` accepts an empty JSON object from the configured same origin and returns a CSRF token and recoverable state. `GET /v1/current-state` and `GET /v1/discovery-sessions/{session_id}` enforce credential ownership and never renew retention. Successful active operations retain the current process for exactly 30 days; expired credentials bootstrap a fresh empty identity. The lifecycle application port supports replacement, heard albums and deletion, and recommendation commands have been integrated with the Python pipeline using synthetic providers in validation.

V8 adds revocation receipts and a publication-owned recovery projection. Deletion first commits a barrier, then processes bounded cleanup batches; executions still occupying a slot remain pending physical release by the dispatcher. Database restore requires service isolation and replay of post-backup revocations before access is reopened. If post-backup revocations cannot be reconstructed, keep access isolated while rotating the independent signing key: all old credentials become invalid, users start blank, and old credential generations enter bounded cleanup. Ordinary restarts retain the same key. End-to-end backup operations are a separate deployment responsibility.

## Persistence development

The backend includes a V7 discovery-state migration and module-owned storage ports for sessions, executions, budgets, evidence, publication, preferences and audience counts. Apply migrations with the offline migration identity before starting the online application; the runtime identity only validates the schema and uses explicitly granted table permissions. Product APIs and bounded dispatch are implemented on top of this foundation; validate the complete current migration chain before startup. Never edit an applied migration or roll a V7 database back by deleting its tables.

`mvn -f apps/backend/pom.xml verify` runs formatting, unit/contract tests and the database integration suite. For the latter, explicitly provide `AURALIS_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:<port>/auralis_core_verify` and `AURALIS_TEST_POSTGRES_ADMIN_PASSWORD` for a **fresh disposable PostgreSQL 18 instance**. The suite creates synthetic databases and roles and tests migration-checksum rejection; destroy that instance after each run. Without these variables, database tests are skipped. Set `AURALIS_RECALL_PYTHON` to the existing intelligence Python environment when working in a separate worktree. No paid model calls are made.

## Agent development

For Windows single-host startup, readiness, graceful shutdown and isolated PostgreSQL recovery, use the [runtime package](apps/runtime/usage.txt). Java/Python stay on loopback; configuration, approved artifacts and budgets must be supplied explicitly. Startup does not make paid model requests.

Windows单机启动、就绪检查、正常停止和隔离恢复见[运行包](apps/runtime/usage.txt)。双端保持本机通信，数据、硬预算和凭据显式配置；启动本身不调用付费模型。

Use Python 3.10+ with the dependencies in [pyproject.toml](apps/intelligence/pyproject.toml). From the repository root, run the complete first-batch flow with synthetic models and pages and the local A-V2 implementation. This performs no paid requests.

```powershell
python -m pip install -e apps/intelligence
python -m auralis_intelligence.agents demo --scenario mixed
python -m auralis_intelligence.agents demo --scenario mixed --background --r 3 --k 2 --n 1
python -m auralis_intelligence.agents demo --scenario genre --r 5 --k 4 --n 2 --include-calls
```

The optional `--background` mode also runs replenishment and closes with a round report. The service supports prioritized long reviews, preemption, explicit regeneration and continuation.

The output reports first-batch composition, publication counts, stage timing and optional safe call records. Synthetic timing and zero-price fixtures do not measure real model quality, speed or cost. The other input scenarios are `album`, `text` and `no-positive`; the last hands control back to Java's curated-list branch.

For the loopback HTTP service, provide explicit local paths and credential environment-variable names using [agent-service.example.json](apps/intelligence/agent-service.example.json) and reviewed [model bindings](apps/intelligence/autonomous-bindings.example.json), then run `python -m auralis_intelligence.agents serve --config <config.json>`. A matching Java control service is required. The sample bindings keep live calls disabled.

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
snapshot binding for integration. The Python execution service includes this stage; Java product integration is connected; full website acceptance remains pending.

This branch also supports the C-V0 comparison. Use `load_c_v0` from the same
Python port, or add `--c-v0-model <model.npz>` to the local command with the
matching snapshot. The trained artifact is supplied locally. A-V2 remains the default.

## Current browser flow

- Enter text, genre preferences or favorite albums through three expandable panels; choose familiar, explore or surprise mode. The reach slider maps to 1–500 distinct sample listeners, with 50 as the initial value.
- Submit, see the actual queue/wait state, then browse one album at a time. Open the long review in place; its reading progress can be scrubbed like a track, without playing audio.
- “Like” and “Meh” are local page reactions; they do not write a backend preference. “Heard” writes the server state while retaining the album in the current page; the latest single-card action can be undone.
- At the end of a group, request more or return to prefilled input. Returning home alone does not submit a new request. Unsubmitted drafts stay in page memory, not browser storage.
- The page uses a pointer-responsive contour landscape and cover-derived room colours. Real cover lookup is not connected yet; placeholder covers remain in use.

## Full catalog search

Java catalog endpoints search an explicitly versioned, complete MusicBrainz release-group snapshot using a local read-only Lucene index, including album names, artist credits and aliases. Public search results exclude Single releases, refill pages after filtering and use admitted audience counts to promote commonly heard albums without excluding unknown counts. The retrieval subset is not used as the full directory. Configure `AURALIS_CATALOG_SEARCH_INDEX` and `AURALIS_CATALOG_SEARCH_MANIFEST_SHA256` with a verified offline index and its candidate-manifest digest. Missing configuration returns 503 for catalog requests; an invalid index prevents startup.

After anonymous bootstrap, read `/v1/catalog/albums?query=...`, `/v1/catalog/albums/{mb_release_group_id}` and `/v1/catalog/genres`. GET requests never renew retention; unknown IDs and empty search results are distinct. External albums remain selectable, while only verified supported information enters retrieval; covers remain placeholders. The offline `io.auralis.catalog.adapters.CatalogIndexBuild` importer requires a complete candidate directory, a new output directory and the expected manifest digest. Update paths and digests during maintenance and restart; restore the previous complete index if validation fails. There is no online download or remote fallback. Catalog and recommendation APIs are implemented; broader recommendation-quality validation and complete website acceptance remain pending.

### Prepared curated recommendations

No-positive input uses a fixed ordered pool of ten albums with separately stored short and long reviews. Configure `AURALIS_CURATED_CONTENT_DIRECTORY` for Java, or `curated_content_directory` in the Windows host configuration. The pool bypasses recall, online review generation and the default audience threshold when no threshold is explicitly requested, while preserving catalogue identity, current restrictions and deduplication. Content is local and is not distributed with Git. Import a prepared `auralis-curated-v1.json` into a new directory with `io.auralis.discovery.adapters.CuratedContentFiles <input.json> <new-directory>` on the backend runtime classpath.
