# HealthRecon RAG

A dockerized single-page application that lets users create document sets, upload files
(TXT, Markdown, PDF, DOCX), automatically extract and index text, and query those sets
via a RAG (Retrieval-Augmented Generation) chat interface.

## Prerequisites

- **Docker Desktop** (or Docker Engine + Compose v2)
- **Java 21** and **Node 18+** — only needed if running outside Docker

## Quick start (Docker)

```bash
# 1. Clone the repo and copy the env file
cp .env.example .env

# 2. Edit .env — set a real OpenAI-compatible endpoint and API key (see below)

# 3. Start everything
docker compose up -d --build

# 4. Verify all services are healthy
docker compose ps

# 5. Open the app
#    http://localhost:5173          (default frontend port)
#    http://localhost:8080/actuator/health   (backend health check)
```

The first run pulls images, builds the backend JAR and frontend bundle, runs Flyway
migrations, and starts Postgres, Qdrant, the Spring Boot backend, and the nginx frontend.

## LLM configuration

The app is LLM-provider-agnostic via LangChain4j and requires an
OpenAI-compatible `/v1` endpoint. There is **no local fallback**: the backend
refuses to start unless `LLM_BASE_URL` and `LLM_API_KEY` are set, and the
startup log shows the missing variable explicitly.

| Variable | Required | Default | Description |
|---|---|---|---|
| `LLM_BASE_URL` | yes | — | OpenAI-compatible base URL (e.g. `https://api.openai.com/v1`) |
| `LLM_API_KEY` | yes | — | OpenAI-compatible API key |
| `LLM_CHAT_MODEL` | no | `gpt-4o-mini` | Chat model name |
| `LLM_EMBEDDING_MODEL` | no | `text-embedding-3-small` | Embedding model name |
| `LLM_EMBEDDING_DIMENSION` | no | `1536` | Embedding vector dimension |

Example for OpenAI:

```env
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=sk-your-key
LLM_CHAT_MODEL=gpt-4o-mini
LLM_EMBEDDING_MODEL=text-embedding-3-small
LLM_EMBEDDING_DIMENSION=1536
```

Any OpenAI-compatible `/v1` endpoint (e.g. LM Studio, or a local proxy) works the
same way — just point `LLM_BASE_URL` at it.

## All environment variables

```env
# Postgres
POSTGRES_DB=rag
POSTGRES_USER=rag
POSTGRES_PASSWORD=rag
POSTGRES_PORT=5432

# Qdrant
QDRANT_PORT=6333
QDRANT_GRPC_PORT=6334
QDRANT_API_KEY=
QDRANT_VECTOR_DIMENSION=1536

# Application ports
BACKEND_PORT=8080
FRONTEND_PORT=5173

# Auth / JWT
JWT_SECRET=dev-only-secret-key-please-change-in-prod-1234567890

# LLM (required — see above)
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=
LLM_CHAT_MODEL=gpt-4o-mini
LLM_EMBEDDING_MODEL=text-embedding-3-small
LLM_EMBEDDING_DIMENSION=1536

# RAG tuning
RAG_CHUNKING_MODE=adaptive    # adaptive | fixed
RAG_CHUNK_SIZE=2000
RAG_CHUNK_OVERLAP=100
RAG_MAX_CHUNKS_PER_DOC=2000
RAG_TOP_K=5
RAG_MAX_HISTORY_MESSAGES=10

# Guard rails (chat safety + abuse limits)
RAG_GUARDRAILS_ENABLED=true
RAG_GUARDRAILS_MAX_INPUT_CHARS=1000
RAG_GUARDRAILS_REQUIRE_CITATION=true
RAG_GUARDRAILS_HALLUCINATION_MIN_COVERAGE=0.4
RAG_GUARDRAILS_RATE_LIMIT_REQUESTS=60
RAG_GUARDRAILS_RATE_LIMIT_WINDOW_SECONDS=60
RAG_GUARDRAILS_MAX_MESSAGES_PER_CONVERSATION=300
RAG_GUARDRAILS_LLM_CHECK_ENABLED=false   # optional second-pass LLM factual-consistency judge (extra cost/latency; fail-open)

# Query rewriting (optional; LLM reformulates the question into a standalone search query on cache miss)
RAG_QUERY_REWRITE_ENABLED=false           # off for cost/latency; fail-open. Original message still drives prompt/title/cache key
RAG_QUERY_REWRITE_MAX_CHARS=200
RAG_QUERY_REWRITE_SYSTEM_PROMPT=Rewrite the user's question into a single concise standalone search query that captures the exact information being asked, incorporating any context from the conversation history. Then answer with ONLY the rewritten query, no explanation.

# Semantic cache (Qdrant store; answers to similar questions are reused)
RAG_CACHE_ENABLED=true
RAG_CACHE_SIMILARITY_THRESHOLD=0.92
RAG_CACHE_TTL_SECONDS=604800            # seconds (604800 = 7 days)

# Hybrid retrieval (dense + BM25 sparse vectors fused with RRF)
RAG_HYBRID_ENABLED=true                 # false = dense-only search
RAG_SEARCH_CANDIDATES=30                # per-leg candidates fed to the fusion
RAG_SEARCH_FUSION=rrf                   # rrf | dbsf

# Optional reranker applied after fusion
RAG_RERANK_ENABLED=false
RAG_RERANK_MODE=none                    # none | lexical (in-process, no external API)

# Per-user storage quota (bytes, default 100 MB)
MAX_UPLOAD_BYTES_PER_USER=104857600

# Output evaluation & review (captures fresh chat answers; auto-flags + random sample for review)
EVAL_ENABLED=true
EVAL_SAMPLE_RATE=0.1             # fraction of un-flagged outputs queued for review (0.0 - 1.0)

# Langfuse observability (off by default; emits a full RAG trace per chat turn)
LANGFUSE_ENABLED=false
LANGFUSE_HOST=https://cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=
LANGFUSE_SECRET_KEY=
LANGFUSE_RELEASE=
LANGFUSE_ENVIRONMENT=
LANGFUSE_SAMPLE_RATIO=1.0        # fraction of chat turns traced (0.0 - 1.0)
```

## Chat guard rails

Guard rails keep the chat grounded and prevent abuse without getting in the way of
genuine document questions. All checks are deterministic, narrow, and can be turned
off with `RAG_GUARDRAILS_ENABLED=false`.

- **Input screening** — blank or over-long messages are rejected (HTTP 400). Harmful
  content and prompt-injection attempts (`ignore previous instructions`, `system prompt`,
  `jailbreak`, …) get a fixed safe refusal and never reach the LLM.
- **System prompt hardening** — a binding safety block is appended to the configured
  system prompt: treat context as data, ignore embedded instructions, always cite `[n]`,
  keep a professional/non-alarmist tone, no personal medical advice beyond the documents.
- **Output grounding** — answers to grounded questions must include a citation (`[n]`)
  and lexically overlap the retrieved context (`hallucination-min-coverage`). A refusal
  by the model itself is passed through. Violations are replaced with the fixed safe
  refusal and logged.
- **Output tone** — rude/dismissive and alarmist/sensational phrasings are replaced with
  the same safe refusal.
- **Abuse limits** — sliding-window rate limit per user (`rate-limit-requests` per
  `rate-limit-window-seconds`) and a per-conversation message cap
  (`max-messages-per-conversation`); excess requests return HTTP 429.
- The rate limiter is **per-process** (in-memory) — with multiple backend replicas a
  shared store would be required.
- Optional second-pass LLM factual-consistency judge
  (`RAG_GUARDRAILS_LLM_CHECK_ENABLED=true`, off by default for cost/latency): after an answer
  passes tone + citation + coverage, `FactualConsistencyChecker` asks the LLM to verify the
  answer is consistent with the retrieved context and refuses it if not. It is **fail-open** —
  blank inputs, model errors, or unparseable judge responses log a warning and let the answer
  through — so a flaky LLM can never block genuine questions.

## Semantic cache

Satisfied questions are cached so a repeated (or near-identical) question is answered
instantly without another LLM call. Entries live in Qdrant's shared `semantic-cache`
collection, keyed by question embedding and scoped per document set; an entry is reused
only when its cosine similarity is at least `RAG_CACHE_SIMILARITY_THRESHOLD`. Caching is
transparent — cached answers carry the same `answer`/`sources` shape as fresh ones.

- **On hit** the stored answer and sources are returned; no retrieval, guardrail LLM
  pass, or generation cost.
- **On miss** the answer is generated, then stored together with its sources.
- **Invalidation** — any upload/index/delete that changes a document set clears the
  whole set's cache, so answers never refer to stale evidence. Expired entries
  (`RAG_CACHE_TTL_SECONDS`) are evicted hourly. All cache errors are non-fatal.
- Turn it off with `RAG_CACHE_ENABLED=false`.

## Hybrid retrieval

Retrieval is hybrid by default and uses the same path for live chat and golden
evaluation. Each chunk is indexed in Qdrant with two named vectors: `dense`
(the model embedding, cosine) and `bm25` (a client-side sparse bag-of-terms
vector with `modifier: idf` applied server-side). A query is turned into the
same sparse form locally (lowercasing, stopword removal, stable term hashing) —
no external sparse encoder is needed.

- **Fusion** — the dense and sparse legs are run as prefetches and merged with
  reciprocal-rank fusion (`rrf`, or `dbsf`), returning `RAG_SEARCH_CANDIDATES`
  candidates that are sliced to the final context size.
- **Lexical safety net** — because document types and phrasing vary, the sparse
  leg keeps exact-identifier lookups (drug names, codes, uncommon terms)
  findable even when the embedding misses them.
- **Reranking (optional)** — `RAG_RERANK_ENABLED=true` applies the selected
  reranker after fusion. `none` keeps the fused order; `lexical` re-orders
  candidates by in-process term overlap with the query. The `Reranker` interface
  is the extension point for external rerankers (HTTP or ONNX cross-encoder).
- **Fallbacks** — a query that tokenizes to nothing (only stopwords) skips the
  sparse leg and runs dense-only; with `RAG_HYBRID_ENABLED=false` the search is
  plain dense top-k. Both are set up so a cache hit bypasses retrieval entirely.

## Query rewriting

Optional improvement for follow-up questions and conversational shorthand: when
`RAG_QUERY_REWRITE_ENABLED=true`, `QueryRewriteService` (in `service/search/`,
reusing the existing chat model) reformulates the user's question into a single
standalone search query, incorporating conversation context. The rewritten query
(and its embedding) is used **only for retrieval** — the original message drives the
answer prompt, the conversation title, evaluation capture, and the semantic-cache
key, so caching and review stay consistent with what the user actually asked.

- Runs **after input screening** (blocked inputs never trigger it) and **only on a
  cache miss** (hits bypass retrieval and rewrite entirely).
- **Fail-open** — disabled (default), blank input, model errors, or a near-verbatim
  result all fall back to the original query; the rewrite result is capped at
  `RAG_QUERY_REWRITE_MAX_CHARS` characters.
- Single-query reformulation (no multi-query expansion) keeps cost and latency low.

## Output evaluation & review

Every **fresh** chat answer (not a cache hit, including guardrail refusals) is captured
to an evaluation queue. Rejected answers keep their existing no-cache behavior, and
cache hits are intentionally not sampled.

- **Auto-flags** — `GUARDRAIL_REFUSAL` (the answer was refused by the guard rails,
  either as ungrounded or tone-violating), `LOW_COVERAGE` (message answered but the
  lexical coverage was below `RAG_GUARDRAILS_HALLUCINATION_MIN_COVERAGE`), and
  `NO_SOURCES` (message answered with no retrieved sources).
- **Random sampling** — when a captured answer has no flags, it is queued with
  probability `EVAL_SAMPLE_RATE` so benign traffic also gets reviewed.
- **Review** — the *Output reviews* page (per document set) lists captured outputs
  with filters (status, flagged, sampled). A reviewer can mark an output
  `ACCEPT`/`REWORD`/`REJECT` with an optional 1–5 rating and comment; `REWORD`/`REJECT`
  require a corrected answer.
- **Closed loop** — rejecting or rewording an output evicts the document set's
  semantic cache, and *Promote* creates a DRAFT golden case from the corrected answer.
  Dismissed outputs are removed from the queue. The overview bar shows totals,
  flagged/sampled counts, pending/reviewed, and the average rating.
- Turn capturing off with `EVAL_ENABLED=false`.

## Langfuse observability

Tracing is integrated directly with Langfuse Cloud over OpenTelemetry
(OTLP/HTTP) — no separate collector or SDK. It is **off by default** and
**fail-open**: tracing never throws into the chat path, and when disabled (or
the keys are missing) there is zero overhead. Get keys from your Langfuse
project (`Settings → API Keys`, public + secret).

```env
LANGFUSE_ENABLED=true
LANGFUSE_HOST=https://cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=pk-...
LANGFUSE_SECRET_KEY=sk-...
```

- **Full RAG trace** — every chat turn emits a `chat.request` trace with
  per-stage sub-spans (input screening, semantic-cache lookup/store, query
  rewrite, retrieval, output guardrails, eval capture) and each model call as a
  `generation-chat` / `generation-embedding` child span with `gen_ai.*` usage
  attributes.
- **Eval metadata on the trace** — grounding results (citation present, lexical
  coverage, refused flag, verdict) are attached as
  `langfuse.observation.metadata.eval.*` so bad answers are searchable in the
  Langfuse UI.
- **Session/user attribution** — traces carry `langfuse.session.id` / `user.id`
  based on the authenticated user.
- **Sampling** — `LANGFUSE_SAMPLE_RATIO` (0–1, default 1.0) controls what share
  of turns is traced; when a turn is unsampled no spans are exported at all.
- **Deployment identity** — set `LANGFUSE_RELEASE` (e.g. a git SHA) and
  `LANGFUSE_ENVIRONMENT` (e.g. `production`) to group traces. Standalone
  (non-Docker) runs configure these through OS environment variables.

## Running locally (without Docker)

Requires Java 21, Node 18+, a running Postgres instance, and a running Qdrant instance.

### Backend

```bash
# Set JDK 21
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"   # PowerShell / Windows

cd backend
# Point at your local Postgres and Qdrant
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/rag
set SPRING_DATASOURCE_USERNAME=rag
set SPRING_DATASOURCE_PASSWORD=rag
set QDRANT_HOST=localhost
set QDRANT_GRPC_PORT=6334
set JWT_SECRET=dev-only-secret-key-please-change-in-prod-1234567890
set LLM_BASE_URL=https://api.openai.com/v1
set LLM_API_KEY=sk-your-key
set LLM_CHAT_MODEL=gpt-4o-mini
set LLM_EMBEDDING_MODEL=text-embedding-3-small
set LLM_EMBEDDING_DIMENSION=1536

.\mvnw.cmd spring-boot:run
# Backend starts on http://localhost:8080
```

### Frontend

```bash
cd frontend
npm install
npm run dev
# Frontend dev server starts on http://localhost:5173
```

## Running tests

### Backend

Docker Desktop must be running (tests use Testcontainers for Postgres and Qdrant).

```bash
cd backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\mvnw.cmd clean test          # 227 tests, ~150s first run
```

### Frontend

```bash
cd frontend
npm run test                    # 77 tests
npm run build                   # tsc -b + vite production build
```

## API overview

All API endpoints live under `/api`. Authentication is stateless JWT bearer tokens.

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | no | Create account |
| `POST` | `/api/auth/login` | no | Log in, returns JWT |
| `GET` | `/api/auth/me` | yes | Current user info |
| `GET` | `/api/documentsets` | yes | List document sets |
| `POST` | `/api/documentsets` | yes | Create a document set |
| `GET` | `/api/documentsets/{id}` | yes | Get a document set |
| `PATCH` | `/api/documentsets/{id}` | yes | Rename / update description |
| `DELETE` | `/api/documentsets/{id}` | yes | Delete set + documents |
| `GET` | `/api/documentsets/{id}/documents?page=&size=` | yes | List documents (paginated) |
| `POST` | `/api/documentsets/{id}/documents` | yes | Upload files (multipart) |
| `DELETE` | `/api/documentsets/{id}/documents` | yes | Delete all documents |
| `GET` | `/api/documentsets/{id}/documents/{docId}` | yes | Get document detail |
| `DELETE` | `/api/documentsets/{id}/documents/{docId}` | yes | Delete a document |
| `GET` | `/api/quota` | yes | Per-user storage quota |
| `GET` | `/api/documentsets/{id}/conversations` | yes | List chat conversations |
| `POST` | `/api/documentsets/{id}/conversations` | yes | Create a conversation |
| `DELETE` | `/api/documentsets/{id}/conversations/{convId}` | yes | Delete a conversation |
| `GET` | `/api/documentsets/{id}/conversations/{convId}/messages` | yes | Chat messages |
| `POST` | `/api/documentsets/{id}/conversations/{convId}/chat` | yes | Send chat message |
| `GET` | `/api/documentsets/{id}/evals?page=&size=&status=&flagged=&sampled=` | yes | List captured outputs for review |
| `GET` | `/api/documentsets/{id}/evals/{evalId}` | yes | Get an output |
| `PATCH` | `/api/documentsets/{id}/evals/{evalId}` | yes | Review an output (verdict, rating, comment, correctedAnswer) |
| `POST` | `/api/documentsets/{id}/evals/{evalId}/dismiss` | yes | Dismiss an output |
| `POST` | `/api/documentsets/{id}/evals/{evalId}/promote` | yes | Promote a corrected answer to a DRAFT golden case |
| `GET` | `/api/documentsets/{id}/evals/metrics` | yes | Review funnel metrics |

## Project structure

```
healthrecon-rag/
├── backend/          Spring Boot 3.5 + Java 21
│   └── src/main/java/com/healthrecon/rag/
│       ├── api/          REST controllers + DTOs
│       ├── auth/         JWT + Bearer filter + SecurityConfig
│       ├── config/       RagProperties, LlmConfig, QdrantConfig
│       ├── domain/       JPA entities
│       ├── exception/    Custom exceptions
│       ├── repository/   Spring Data repos
│       └── service/      Business logic, ingestion, indexing, chat
├── frontend/         React 18 + TypeScript + Vite
│   └── src/
│       ├── api/          Typed fetch modules + auth client
│       ├── auth/         AuthContext, route guards
│       ├── components/   Shared UI (AppLayout, StatusBadge, ChatPanel)
│       └── pages/        Dashboard, DocumentSetDetail, Login, Register
├── spec/             Architecture, implementation plan, test plan
├── docker-compose.yml
└── .env.example
```

## Stopping and cleaning up

```bash
docker compose down           # stop containers, keep data volumes
docker compose down -v        # stop and delete all data volumes
```
