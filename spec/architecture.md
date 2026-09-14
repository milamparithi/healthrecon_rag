# HealthRecon RAG — Architecture

## Overview

A dockerized, mobile-responsive Single Page Application (SPA) that lets users create
document sets, upload files, automatically extract text, and query those sets via a
RAG chat. Light theme, minimal UI.

## Components

| Component  | Tech                                                                  | Notes |
|------------|-----------------------------------------------------------------------|-------|
| Frontend   | React 18 + TypeScript, Vite, react-router-dom v6                      | Served by nginx:1.27, which also reverse-proxies `/api/` to the backend |
| Backend    | Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, Flyway, LangChain4j | Stateless REST API under `/api` |
| Database   | PostgreSQL 16 (Docker)                                                | Flyway-managed schema |
| Vector DB  | Qdrant (Docker)                                                       | One collection per document set (gRPC, 6334) |
| ML/LLM     | LangChain4j `EmbeddingModel` + `ChatModel` (OpenAI-compatible)        | Models configured via env (`LLM_*`); provider-agnostic |
| Observability | Langfuse Cloud (OTLP/HTTP via OpenTelemetry SDK)                    | Full RAG trace capture; off by default, env-gated |

## Key decisions

### Authentication: stateless JWT bearer tokens
- `POST /api/auth/register` and `POST /api/auth/login` return `AuthResponse { token, user }`.
- The JWT is a signed (HMAC-SHA384, `app.jwt.secret`) token carrying `sub` (user id), `role`,
  `iss`, `iat`, `exp` (24h). Passwords are stored as BCrypt hashes.
- The frontend stores the token in `localStorage` (`hr_token`) and sends
  `Authorization: Bearer <token>` on every API call (except login/register/health).
- Logout is a client-side no-op endpoint (`POST /api/auth/logout`, permit-all) — the client
  simply discards the token; the JWT remains valid until expiry (stateless).
- CSRF is not required (no cookies; tokens via header). CORS is disabled — the app is
  same-origin behind nginx.
- Security rules: `register`, `login`, `logout`, `health`, actuator health/info are
  permit-all; everything else requires authentication. Unauthenticated requests get a JSON
  `401 { "error": "Unauthorized" }` via a custom `AuthenticationEntryPoint`.

### Backend layering
```
api/       REST controllers + DTO records + GlobalExceptionHandler
security/  JwtService, BearerAuthFilter, SecurityConfig, CurrentUserSupport
service/   AuthService, DocumentSetService, DocumentUploadService, QuotaService, IngestionJob, TextExtractionService,
           StructureExtractionService, ChunkingService, IndexingService, IndexingJob, VectorIndexer,
           QdrantVectorIndexer, ConversationService, ChatService,
           GoldenCaseService, GoldenCaseGenerator, GoldenGenerationJob, GoldenEvaluationService,
           EvalCaptureService, AnswerEvalService
service/extraction/  StructuralDocument, MarkdownStructureExtractor, HtmlStructureExtractor
service/chunking/    FixedCharChunker, AdaptiveStructuralChunker
service/search/      SparseVectorizer, Reranker (NoOpReranker, LexicalReranker), RerankerService, QueryRewriteService
service/guardrails/ ChatGuardrailService, ChatRateLimiter, FactualConsistencyChecker
service/semanticcache/ SemanticCache, QdrantSemanticCache, CachedAnswer
service/eval/        EvalCaptureService, AnswerEvalService
service/observability/ LangfuseAttributes, LangfuseTracingContext, TracedSpan, LangfuseSpanHelper,
            LangfuseChatModelListener, LangfuseEmbeddingModelListener, LangfuseTraceConfig
domain/    JPA entities + enums (DocumentSetStatus, DocumentStatus, IndexStatus, GoldenStatus, GoldenCase, AnswerEval)
repository/ Spring Data repositories + projection
config/    RagProperties, QdrantProperties, LlmProperties, QuotaProperties, GoldenProperties, SearchProperties, EvalProperties, LangfuseProperties, LlmConfig (ChatModel/EmbeddingModel beans), LangfuseTraceConfig
exception/ custom exceptions (auth/, not-found, conflict)
```

### Ingestion pipeline (Phase 1)
- Upload stores files as BLOB chunks in Postgres (`stored_document`).
- Duplicate detection by filename + SHA-256 content hash within a document set.
- A scheduled `IngestionJob` (configurable `app.ingestion.poll-ms` + `initial-delay-ms`) scans
  `PENDING` documents, extracts text via Apache Tika (`TextExtractionService`),
  and flips status `UPLOADED → PENDING → EXTRACTING → READY` (or `FAILED` + error message).
- Document set status is `recomputeStatus`-derived from its documents
  (EMPTY / UPLOADING / READY / FAILED).
- Document list is served paginated: `GET /api/documentsets/{id}/documents?page=&size=`
  returns `PageResponse{ content[], page, size, totalElements, totalPages }` (clamped to
  size 1–100).
- Metadata updates via `PATCH /api/documentsets/{id}` (rename/description, duplicate name →
  409); documents can be bulk-deleted via `DELETE /api/documentsets/{id}/documents` which
  drops the Qdrant collection first, deletes rows, then recomputes the set to `EMPTY`.
- Per-user upload quota: `app.quota.max-upload-bytes-per-user` (env
  `MAX_UPLOAD_BYTES_PER_USER`, default 100 MB) enforced per file during upload after the
  duplicate check (duplicates are exempt); `GET /api/quota` returns the live
  `{ usedBytes, limitBytes }` snapshot.

### Indexing & RAG pipeline (Phase 2)
- Structure extraction: for `.md`/`.html`, headers + body are parsed into a structural
  document (sections with heading chains); other formats fall back to Tika plain text as a
  single section.
- Chunking: `adaptive` (heading-chain-aware, falls back to fixed on oversized sections)
  or `fixed` (character window + overlap); configured via `RAG_CHUNKING_MODE` etc.
- `IndexingJob` (`app.indexing.poll-ms` + `initial-delay-ms`) indexes every `READY` doc with
  `IndexStatus` in (`NOT_INDEXED`, `INDEXING`): chunks → embeddings (`EmbeddingModel`) →
  upsert into the Qdrant collection named `[document-set-uuid]`. Each point carries two named
  vectors — `dense` (the model embedding, cosine) and `bm25` (a client-side sparse bag-of-terms
  vector with `modifier: idf` applied server-side) — plus string payload keys
  `doc_id`/`filename`/`section`/`text`. Deterministic string ids
  (`UUID.nameUUIDFromBytes(docId:index)`) allow idempotent re-indexing (delete-before-add).
  Final states `INDEXED` or `FAILED` (`index_error`). Deleting a document removes its
  vectors then the row; deleting a set drops the whole collection.
- Chat flow (`ChatService`, non-streaming): requires set `READY` (else `409`) →
  rate-limit check (per-user sliding window → 429) → input screen (harmful/
  injection/blank/over-long → fixed refusal, no LLM call) → conversation message cap
  (429) → embed user question → serve from the **semantic cache** when a similar
  question was already answered (stored answer + sources, no LLM/retrieval cost) →
  on miss, hybrid top-k search (dense + sparse prefetches fused with RRF,
   optional reranker) → prepend retrieved snippets to the system prompt →
  `ChatModel.chat(history window + user message)` → output grounding/tone check
  (refusal substituted when ungrounded or tone-violating) → persist user + assistant messages
  (sources serialized as JSON) in `conversation`/`chat_message`; a grounded answer on a miss
  is then stored in the semantic cache. Conversations are scoped to
  the owner user; cross-user access → 404.
- Provider-agnostic LLM: `LlmConfig` builds `ChatModel`/`EmbeddingModel` from
  `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_CHAT_MODEL`, `LLM_EMBEDDING_MODEL`,
  `LLM_EMBEDDING_DIMENSION`. Both `LLM_BASE_URL` and `LLM_API_KEY` are required — the
  application refuses to start when either is missing (no local fallback) so the missing
  configuration is surfaced as a clear startup error. The chat service is
  `ChatLanguageModel`-interface-driven — swap the provider via env, no code change.

### Chat guard rails (Phase 5)
- **Design**: deterministic, narrow, config-gated (`app.rag.guardrails.enabled`,
  `RAG_GUARDRAILS_ENABLED`); additive to the operator `RAG_SYSTEM_PROMPT` (a binding
  "Safety rules" block is appended). Genuine document questions are never blocked; violations
  always yield the same fixed safe refusal persisted as an assistant message (empty sources).
- **Input screening** (`ChatGuardrailService.blocked`): blank or `max-input-chars` (1000)
  → HTTP 400; harm and prompt-injection phrase lists → refusal served **before** any LLM
  call/database write. Screened *after* ownership + rate limit.
- **Output checks** (`ChatGuardrailService.refusalFor`): passes model refusals through; tone
  violations and hallucination/ungrounded answers are replaced with the refusal (warn-logged,
  no second LLM call). Grounding = citation present (`[n]`) when sources exist
  (`require-citation` true) AND lexical coverage of the joined snippets with a
  `hallucination-min-coverage` (0.4) threshold (≥4-char terms, stopword-filtered).
- **Abuse limits**: `ChatRateLimiter` (in-memory sliding window per user,
  `rate-limit-requests` 60 / `rate-limit-window-seconds` 60) and
  `max-messages-per-conversation` (300) → `TooManyRequestsException` → `GlobalExceptionHandler`
  maps it to HTTP **429**. Limiter is per-process (an operator note in the README: replicas
  need a shared store).
- **Config & wiring**: `GuardrailProperties` under `app.rag.guardrails.*` (registered in
  `LlmConfig`), lists in `application.yml`, env passthrough for the scalar knobs
  (`RAG_GUARDRAILS_*`). `RAG_GUARDRAILS_LLM_CHECK_ENABLED`
  (`app.rag.guardrails.llm-check-enabled`, off) gates the optional second-pass
  `FactualConsistencyChecker` judge (Phase 8b).
- **Frontend**: `ChatPanel` input `maxLength=1000` + `n/1000` counter (turns red over cap),
  Send disabled beyond cap; backend 400/429/refusal messages surface through the existing
  `ApiError.body.message` alert.

### Semantic response cache (Phase 6)
- **Design**: transparent, Qdrant-backed answer cache. A single shared collection
  (`app.rag.cache.collection-name`, default `semantic-cache`, cosine distance, dimension
  `LLM_EMBEDDING_DIMENSION`) holds one point per stored (set, question). Points are scoped by
  a `doc_set_id` payload key, keyed by a deterministic id
  (`UUID.nameUUIDFromBytes(docSetId + ":" + question)`), so re-storing the same question
  overwrites. Cached payloads carry the answer, serialized `sources_json`, and `created_at`
  (epoch millis) in the segment metadata.
- **Lookup** (`SemanticCache.lookup`): embed the user question once, search with
  `maxResults(1)` + the `doc_set_id` filter, and reuse the best match only when its cosine
  score ≥ `app.rag.cache.similarity-threshold` (0.92) and it is not TTL-expired. On hit the
  assistant message is persisted with the cached answer + sources (conversation is
  auto-titled as usual) and no `EmbeddingModel` retrieval search, guardrail LLM pass, or
  `ChatModel` call happens.
- **Store**: on a miss, after the grounded answer passes the guardrails, the question
  embedding + answer + sources are stored. Answers that are served as fixed refusals,
  blocked inputs, or failed answer checks are **never** cached. `store` is skipped entirely
  when the cache is disabled or when an answer has no sources.
- **Invalidation** (whole-set, since any document change can invalidate prior answers):
  `DocumentSetService.delete`/`deleteAllDocuments`/`deleteDocument` and
  `IndexingService.indexDocument` (after a re-index) clear the entire `doc_set_id` scope.
- **Eviction**: an hourly `@Scheduled` pass (`evictExpired`, gated by `enabled`/`ttl > 0`)
  deletes entries older than `app.rag.cache.ttl-seconds` (604800 = 7 days); lookups also skip
  expired entries. There is no entry-count bound or LRU — TTL + whole-set invalidation bound
  growth.
- **Reliability**: `QdrantSemanticCache` is best-effort — every operation catches and
  degrades to a normal (uncached) request; cache failures never fail or alter the chat. The
  Qdrant client is built lazily from `QdrantProperties`; the cache collection is created once
  per JVM.
- **Config & wiring**: `CacheProperties` under `app.rag.cache.*` (registered in `LlmConfig`),
  env passthrough `RAG_CACHE_ENABLED` / `RAG_CACHE_SIMILARITY_THRESHOLD` /
  `RAG_CACHE_TTL_SECONDS` (collection name is internal). The cache is a `SemanticCache`
  dependency of `ChatService`, `DocumentSetService`, and `IndexingService`.
- **Opaque to the client**: cached and fresh answers share the same `ChatResponse` shape
  (`message`, `sources`, `title`); no `cached` flag or UI surface.

### Hybrid retrieval & optional reranking (Phase 7)
- **Design**: replaces the former dense-only top-k search with a hybrid
  dense+sparse query, because document types and user phrasing are unpredictable and exact
  identifiers (drug names, codes, uncommon terms) must stay findable even when the embedding
  misses them. No external sparse encoder — the vectorizer is client-side.
- **Sparse side** (`SparseVectorizer`, `service/search/`): lowercase → split on non-alphanumeric
  → drop a fixed English stopword set + tokens < 2 chars → count term frequencies → hash each
  term to a stable non-negative index (FNV-1a). The same class tokenizes documents (for
  indexing) and queries (for retrieval), so the sparse space is tautologically aligned. A query
  that tokenizes to nothing (stopwords only) skips the sparse leg (dense-only).
- **Indexing**: each point stores `dense` (embedding, cosine, size `LLM_EMBEDDING_DIMENSION`)
  and `bm25` (sparse, `modifier: idf`). Chunk text moves to the payload key `text` (was
  langchain4j segment metadata), so results carry the snippet without an extra fetch.
- **Query** (`QdrantVectorIndexer.search(docSetId, queryText, queryEmbedding, topK)`, gRPC
  `QueryPoints`): two prefetches — `QueryFactory.nearest(dense)` and
  `QueryFactory.nearest(sparseIndex, sparseValue)` — each limited to `app.rag.search.candidates`
  (default 30), fused with `QueryFactory.fusion(rrf | dbsf)` into `candidates`, then the active
  reranker slices to the final `topK`. `RAG_HYBRID_ENABLED=false` restores plain dense top-k.
- **Reranking (optional, off by default)**: `Reranker` interface (extension point for external
  HTTP or ONNX cross-encoders). `RAG_RERANK_ENABLED=false` → `NoOpReranker` (fused order,
  sliced to `topK`). `RAG_RERANK_MODE=lexical` → `LexicalReranker`, an in-process deterministic
  re-order by term overlap with the query (never changes the candidate set; failures degrade to
  the fused order). `RerankerService` picks the active implementation from config; it is the
  only `Reranker` bean.
- **Config & wiring**: `SearchProperties` under `app.rag.search.*` (registered in `LlmConfig`,
  nested inside `RagProperties` after `cache`); env passthrough `RAG_HYBRID_ENABLED` /
  `RAG_SEARCH_CANDIDATES` / `RAG_SEARCH_FUSION` / `RAG_RERANK_ENABLED` / `RAG_RERANK_MODE`.
- **Query rewriting (optional, Phase 9)**: on a cache miss and after input screening,
  `QueryRewriteService` (in `service/search/`, reuses the `ChatModel` bean) reformulates the
  user's question into a single standalone search query (capped at `RAG_QUERY_REWRITE_MAX_CHARS`,
  default 200) when `app.rag.query-rewrite.enabled` (`RAG_QUERY_REWRITE_ENABLED`, default false)
  is set. The rewritten query + its embedding replace the message only for retrieval; the
  original text is kept for the answer prompt, title, eval capture, and the semantic-cache key.
- **Shared path**: `ChatService` (raw `message` text + embedding, rewritable via
  `QueryRewriteService`) and `GoldenEvaluationService` (question text + embedding) both call
  `vectorIndexer.search(...)`; golden evaluation therefore measures the same retrieval used in
  production chat. Golden answers skip rewriting (their questions are already standalone).

### Golden dataset & retrieval evaluation
- **Async generation**: after a document is indexed (`INDEXED`), a scheduled
  `GoldenGenerationJob` (`app.golden.poll-ms` + `initial-delay-ms`, gate `app.golden.enabled`)
  generates golden Q&A cases per document with the LLM. Document `golden_status` transitions
  `PENDING → GENERATING → DONE | FAILED` (`golden_error`, `golden_attempts` capped at
  `app.golden.max-retries` = 3; overdue `GENERATING` rows are retried). Generation truncates
  extracted text to `app.golden.max-chars-per-doc` (12000), requests `app.golden.questions-per-doc`
  (3) Q/A pairs as a strict JSON array, strips ```` ```json ```` fences, filters blank
  question/answer pairs. Each case stores `expected_sources` as JSON `ExpectedSource[]`
  `{filename, docId, section}` (section null for generated cases); failures mark the document
  `FAILED` but never block indexing/chat.
- **Workflow**: generated cases are scoped to `doc_set_id` + `owner_id` and created as
  `status = DRAFT`. Users review/edit (PATCH) and promote a case `DRAFT → GOLDEN`; only
  GOLDEN cases count toward evaluation. Manual CRUD is supported and cases cascade-delete
  with their document set.
- **Evaluation** (`POST /api/documentsets/{id}/golden/run`, deterministic, retrieval-only):
  for each GOLDEN case, embed the question (stubbed unit vector in tests) → hybrid top-k Qdrant
  search (same `vectorIndexer.search` path as chat) → match expected sources against retrieved **filenames**. Documents that have drifted
  (moved out of the index) are reported as warnings and **excluded** from aggregate metrics.
  Report: `hitRate` (% cases with ≥1 hit in the top-k), `meanReciprocalRank`, `recall@k`
  (% expected sources retrieved, averaged), per-case rows (question → expected/retrieved
  filenames, hit, rank, recall) plus warnings.
- **API**: `GET/POST /api/documentsets/{docSetId}/golden`, `PATCH/DELETE
  /api/documentsets/{docSetId}/golden/{caseId}`, `POST .../golden/run`. All endpoints go
  through the owned-set guard (list/create), owned-case check (update/delete) and per-user
  ownership (cross-user → 404).
- **Frontend**: `GoldenCasesPage` under `/documentsets/:id/evaluation` (linked from the
  document-set detail head) — review list with DRAFT/GOLDEN badges, inline edit + promote,
  add-case form, and a run-evaluation card with a metric grid, drift warnings and a per-case
  results table. The Run button is disabled until at least one case is GOLDEN; the pre-report
  empty state is count-aware ("No report yet." + promote hint when no GOLDEN case exists, or a
  ready-to-run hint listing the number of golden cases). Expected sources are picked from a **checkbox dropdown of READY documents**
  (`listAllDocuments` in `api/documentsets.ts` pages through the documents endpoint up to
  100/page; the page filters to `READY`, so only retrievable files are selectable) — sources
  are sent with the real `docId`. Editing a case pre-checks its current sources; sources
  whose file is no longer in the set are shown as locked "not in set" entries and preserved
  on save rather than dropped.
- **Schema**: Flyway `V6__golden.sql` adds `golden_status/golden_error/golden_attempts` to
  `document` (+ index) and the `golden_case` table (FK → document_set/users, `source_doc_id`
  FK with ON DELETE SET NULL).

### Output evaluation & human review (Phase 8a)
- **Design**: an observability loop over live chat. Every **fresh** answer (a miss — not a
  semantic-cache hit) is captured; cache hits are intentionally not sampled, and guardrail
  refusals (never cached anyway) are captured. Capturing is best-effort and gated by
  `app.eval.enabled` (`EVAL_ENABLED`, default true).
- **Capture** (`EvalCaptureService`, wired after the assistant message is saved in
  `ChatService`): computes flags — `GUARDRAIL_REFUSAL` (refused output), `LOW_COVERAGE`
  (answered but coverage < `hallucination-min-coverage`, only when guardrails are enabled),
  `NO_SOURCES` (answered, no sources). Refused outputs flag only `GUARDRAIL_REFUSAL`.
  Coverage is always stored via `ChatGuardrailService.coverageFor(answer, context)`.
  Un-flagged outputs are queued with probability `app.eval.sample-rate`
  (`EVAL_SAMPLE_RATE`, default 0.1, clamped to 0–1). Sources are serialized to JSON
  (`SourceResponse`-shaped objects; `"[]"` on failure). Refusals and cache hits are never
  cached regardless of flags.
- **Domain**: `answer_eval` (Flyway `V7__eval.sql`, `schema_version='phase-4-eval'`) stores
  question/answer/sources/coverage/flags/origin (`CHAT`), review state
  `review_status` (`PENDING|REVIEWED|DISMISSED`) and verdict
  (`ACCEPT|REWORD|REJECT`) + rating/comment/corrected answer/reviewed-at. Partial indexes
  on `sampled` and flagged outputs keep the queue lookup fast.
- **Review rules** (`AnswerEval.review`): verdict must be one of the three; rating 1–5;
  `REWORD`/`REJECT` require a non-blank `correctedAnswer`; `reviewedAt` is stamped.
  `dismiss()` drops verdict/rating/comment/corrected answer and sets `DISMISSED`.
- **Closed loop**: a valid `REJECT`/`REWORD` review evicts the document set's semantic
  cache (cached answers to the same/like questions must not survive a known-bad answer).
  *Promote* maps the eval sources (`SourceResponse[]`) to `ExpectedSource[]`, pins the
  source docId from the first source, and calls `GoldenCaseService.saveGenerated` to create
  a **DRAFT** golden case from the corrected answer (no verdict/status override).
- **API**: `EvalController` under `/api/documentsets/{docSetId}/evals` (all behind the
  owned-set guard): `GET ?page=&size=&status=&sampled=&flagged=` (Spring `Specification`
  → `PageResponse`, sorted newest first), `GET /{id}`, `PATCH /{id}` (@Valid `ReviewRequest`
  — `{verdict, rating, comment, correctedAnswer}`), `POST /{id}/dismiss` (204),
  `POST /{id}/promote` → `GoldenCaseResponse`, `GET /metrics` → funnel counts +
  `averageRating`.
- **Frontend**: `EvalsPage` under `/documentsets/:id/reviews` (linked from the set detail
  head as *Reviews*). Overview metric bar (captured/flagged/sampled/pending/reviewed/avg
  rating), status + flagged/sampled filters (reset to page 0), paginated queue with flag
  badges (GUARDRAIL_REFUSAL → danger, NO_SOURCES → warning, others → neutral) and a
  `sampled` chip, inline review form (verdict/rating/comment; corrected-answer shown and
  required for REWORD/REJECT), Dismiss (confirm) and Promote (requires corrected answer)
  actions, 404 handled as an alert.

### LLM factual-consistency judge (Phase 8b)
- **Design**: an optional second-pass LLM check that extends the lexical Grounding guard.
  `FactualConsistencyChecker` (a `@Service` in `service/guardrails/`, reusing the existing
  `ChatModel` bean so no new model config) asks the LLM to judge whether the answer is
  factually consistent with the retrieved context. The prompt demands strict JSON
  (`{"consistent": bool, "reason": "..."}`); parsing tolerates ```json fences and
  surrounding prose.
- **Wiring**: inside `ChatGuardrailService.refusalFor`, after an answer passes input/tone/
  citation/coverage, when `properties.llmCheckEnabled()` is true **and** `sources` is
  non-empty, the judge runs. `consistent=false` → warn log + the fixed safe refusal;
  `consistent=true` → normal path. Judge votes do not replace the answer text.
- **Fail-open**: blank answer/context, chat-model errors, and unparseable responses all log a
  warning and let the answer through (never a hard failure). This keeps a flaky LLM from
  breaking genuine questions; the flag is off by default because of extra cost + latency.
- **Test seams**: unit tests mock `ChatModel.chat` directly; the integration context
  (`LlmJudgeIntegrationTest`) repeats the shared `BaseIntegrationTest` properties (incl.
  `llm.embedding-dimension=768`) because a subclass `@SpringBootTest` fully replaces the
  inherited one, then sets `app.rag.guardrails.llm-check-enabled=true`. The shared
  `chatModel` stub distinguishes judge vs main call via the prompt's `SystemMessage` text.

### Query rewriting (Phase 9)
- **Design**: an optional, fail-open LLM step that makes follow-up questions findable.
  `QueryRewriteService` (a `@Service` in `service/search/`, reusing the `ChatModel` bean)
  rewrites the user's question into a single standalone search query, incorporating context
  from the conversation history. Prompt = `SystemMessage` (configurable via
  `app.rag.query-rewrite.system-prompt`) + history + the question; the plain-text result is
  trimmed and capped at `app.rag.query-rewrite.max-chars` (default 200). Single-query
  reformulation only — no multi-query expansion.
- **Wiring** (`ChatService.chat`): runs only when `queryRewriteService.enabled()` AND the
  semantic cache missed AND input screening passed (blocked inputs never trigger it). The
  rewritten query and its embedding feed `vectorIndexer.search(...)`; the **original message**
  still drives the answer prompt, conversation title, eval capture, and the cache key.
- **Fail-open**: the service returns the original query for blank input (incl. blank trimmed
  result) or on any chat-model error (warn log). The call site additionally catches a throwing
  rewriter defensively, so a broken rewriter implementation can never break chat. A
  near-verbatim rewrite is treated as "no change" and skipped.
- **Config**: `QueryRewriteProperties`-shaped record nested in `RagProperties` between
  `search` and `chunking` (`app.rag.query-rewrite.*`); env passthrough
  `RAG_QUERY_REWRITE_ENABLED` / `RAG_QUERY_REWRITE_MAX_CHARS` / `RAG_QUERY_REWRITE_SYSTEM_PROMPT`
  in `application.yml`, `.env.example`, and `docker-compose.yml`.
- **Test seams**: unit tests mock `ChatModel.chat` (verify SystemMessage + history + question,
  and fail-open behavior); `ChatServiceTest` mocks `QueryRewriteService` to prove the
  search/prompt split, cache-hit skip, and defensive fallback. `QueryRewriteIntegrationTest`
  repeats the shared `BaseIntegrationTest` properties (incl. `llm.embedding-dimension=768`)
  and enables `app.rag.query-rewrite.enabled=true`, distinguishing the rewrite vs answer call
  by the prompt's `SystemMessage` text.

### Observability: LLM tracing with Langfuse (Phase 10)
- **Design**: full RAG trace per chat turn shipped to Langfuse Cloud over OpenTelemetry
  OTLP/HTTP. No official LangChain4j/Langfuse SDK — the backend implements its own listeners
  and a span helper so the exact attribute set is controlled. **Fail-open and off by
  default**: tracing never throws into the request path, and with `enabled=false` (or missing
  keys) `LangfuseSpanHelper` becomes a no-op so there is zero runtime overhead.
- **Ingestion transport**: the exporter posts to `{LANGFUSE_HOST}/api/public/otel/v1/traces`
  (OTLP/HTTP JSON; Langfuse's OTLP receiver is mounted at `/api/public/otel` with the proto
  signal path `/v1/traces` appended — posting to the bare `/api/public/otel` returns 404,
  and gRPC is not accepted) with HTTP Basic auth
  `base64(LANGFUSE_PUBLIC_KEY:LANGFUSE_SECRET_KEY)` plus header
  `x-langfuse-ingestion-version: 4`. `SdkTracerProvider` + `BatchSpanProcessor` +
  `OtlpHttpSpanExporter` are built in `LangfuseTraceConfig` only when
  `langfuse.enabled` is true **and** both keys are present; a JVM shutdown hook forces a
  flush.
- **Span model** (`ChatService.chat` → `doChat`): a root span `chat` created per turn,
  then named sub-spans for each stage — `guardrails.input`, `semantic-cache.lookup`,
  `semantic-cache.store`, `query-rewrite`, `retriever`, `guardrails.output`, `eval.capture`.
  The `ChatModel` and `EmbeddingModel` beans get the `@Component`
  `LangfuseChatModelListener` / `LangfuseEmbeddingModelListener` injected (via
  `ObjectProvider`) so every model call inside those stages appears as `generation-chat` /
  `generation-embedding` child spans.
- **Attributes**: `gen_ai.*` (provider, model, prompt/response, input/output tokens,
  usage), `input.value`/`output.value`, and Langfuse conventions — `langfuse.observation.type`,
  `langfuse.session.id`/`user.id`, `langfuse.trace.name`, and evaluation results as
  `langfuse.observation.metadata.eval.*` (grounded/verdict/coverage/refused) on the root
  span. Metadata is flattened (`Map<String,?>` → `eval.<key>`) so it is indexed in Langfuse.
- **Sampling**: per-request `sampleRatio` (`LANGFUSE_SAMPLE_RATIO`, clamped 0–1, default 1.0)
  decides whether the whole trace is emitted; `LangfuseTracingContext` (ThreadLocal) carries
  the decision so listeners and sub-spans can agree and no orphan spans are exported when
  unsampled.
- **Safe wiring**: all span helpers null-check and catch `Throwable`; span state travels via
  the context's `attributes()` map because listener callbacks fire on the same thread as the
  LLM call. Span/service failure marks the trace status `ERROR` and rethrows nothing.
- **Config**: `LangfuseProperties` under `app.langfuse.*`, env passthrough
  `LANGFUSE_ENABLED` / `LANGFUSE_HOST` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` /
  `LANGFUSE_RELEASE` / `LANGFUSE_ENVIRONMENT` / `LANGFUSE_SAMPLE_RATIO`. Standalone (non-Docker)
  runs use OS environment variables — there is no `.env` parsing in the app.

### Frontend layering
```
api/      fetch wrapper + typed modules (auth, documentsets, quota, chat, golden, evals) + shared types
auth/     AuthContext (token mgmt, /me, login/register/logout) + route guards (RequireAuth)
components/ AppLayout, StatusBadge, AuthShell, ChatPanel
pages/     Login, Register, Dashboard, DocumentSetDetail, GoldenCases, Evals
```
- `apiFetch` wrapper in `src/api/client.ts` attaches the bearer token, and on any 401
  (except login/register) clears the token and dispatches a `auth:unauthorized` window event;
  `AuthProvider` listens and redirects to `/login`.
- Dashboard polls every 3s while any set is non-terminal and shows a storage quota bar;
  the detail page polls while documents are in a non-terminal status and paginates the
  document list (Prev/Next); name/description editing lives on the dashboard as inline
  per-row editing (PATCH), delete-all uses a confirm dialog.
- The content container is `max-width: 1200px`. The document-set detail page is a
  two-column layout (left: upload + paginated document list + delete-set; right: chat
  panel, which gets the wider share) that collapses to a single column on mobile.
  Row-level destructive actions use small icon (trash) buttons with accessible
  `aria-label`s; whole-set actions ("Delete all", "Delete document set") stay labeled text
  buttons. The document pager hides when there is a single page.
- Chat UX: `ChatPanel` in the document-set detail page shows conversation list + message
  history (assistant messages list their sources); it is enabled only when the set is `READY`.
  Conversations persist server-side; a new conversation is created on demand. The first
  exchange auto-renames the conversation from its first user message (`ConversationTitler`:
  whitespace-collapsed, truncated at ~45 chars on a word boundary, trailing punctuation
  stripped, `…` when truncated). Custom titles supplied at creation are never overwritten;
  the derived title is returned in `ChatResponse.title` so the sidebar updates in place.
- Button sizing: base `.btn` uses compact padding (`0.4rem 0.75rem`, `0.92rem`) with a
  visible `:focus-visible` ring; icon buttons are 1.75rem/1.5rem hit areas. Light-theme
  palette is refined slate/blue (`#f4f6f9` bg, `#e3e8ee` borders), cards use a hairline
  shadow and 0.875rem radius, chat user bubbles are soft-blue, and interactive elements
  share a subtle 0.12s transition.

## Deployment
- `docker-compose.yml`: postgres, qdrant, backend (Java 21, curl healthcheck on
  `/actuator/health`), frontend (nginx serving the built SPA; wget healthcheck on `/healthz`).
- Backend env: `SPRING_DATASOURCE_*`, `JWT_SECRET`, `QDRANT_HOST`/`QDRANT_GRPC_PORT`/
  `QDRANT_API_KEY`, `LLM_*`, `RAG_*` chunking/retrieval knobs, `RAG_GUARDRAILS_*`,
  `RAG_CACHE_*`, `RAG_HYBRID_ENABLED`/`RAG_SEARCH_*`/`RAG_RERANK_*`,
  `RAG_QUERY_REWRITE_*`,
  `LANGFUSE_ENABLED`/`LANGFUSE_HOST`/`LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY`/
  `LANGFUSE_RELEASE`/`LANGFUSE_ENVIRONMENT`/`LANGFUSE_SAMPLE_RATIO`,
  `MAX_UPLOAD_BYTES_PER_USER`. Frontend proxies `/api/` to
  `backend:8080`; SPA deep links fall through to `index.html`.
- Schema changes are only applied via new Flyway migrations (V2..V7). Never edit an applied
  migration; migrations are immutable once deployed.