# HealthRecon RAG — Implementation Plan

Legend: ✅ done · 🔄 in progress · ⬜ planned

## Phase 0 — Foundations ✅
- Monorepo layout (`backend/`, `frontend/`, `spec/`), AGENTS.md, git repo, `.env.example`.
- `docker-compose.yml` with postgres:16, qdrant:v1.12.4, backend, frontend; healthchecks and
  restart policies. V1 Flyway migration creates base tables.
- Backend skeleton: Spring Boot 3.5 on Java 21, Spring Security, Data JPA, Flyway, actuator,
  LangChain4j starter. Frontend skeleton: Vite + React + TS strict build + light theme.
- `Dockerfile`s for backend (multi-stage Maven → JRE, curl for healthcheck) and frontend
  (node build → nginx).

## Phase 1 — Auth + document sets ✅
- Auth: register, login, logout, me; stateless JWT bearer tokens; BCrypt password hashing;
  duplicate-email → 409. Flyway V2 migration (`app_user`, `user_credentials`).
- Document sets: CRUD + multi-file upload + text extraction pipeline (Tika) + document set
  status recomputation. Flyway V3 migration (`document_set`, `stored_document`).
- Upload: duplicate detection (filename + SHA-256), per-file `UploadResult`
  (UPLOADED / DUPLICATE / FAILED), size/content-type limits.
- Scheduled `IngestionJob` with configurable poll interval
  (`app.ingestion.poll-ms`, default 5s, tests override to 1h).
- Per-user isolation: all document-set queries scoped by authenticated user id; cross-user
  access → 404.
- Frontend: route guards (`/login` `/register` behind `RedirectIfAuthed`; all other routes
  behind `RequireAuth`), dashboard (set list/create/delete, 3s polling while uploading),
  set detail (multi-file upload, doc list w/ status badges, expand → extracted text,
  delete doc/set), `AuthProvider` + named-route 401 redirect.
- Token storage: `localStorage` (key `hr_token`), cleared on logout and on any 401.
- Tests: backend `mvnw test` green (see Test Plan); frontend `npm run test` (22 tests:
  api client, AuthContext, RequireAuth guards, DashboardPage) + `npm run build` green.

## Phase 2 — RAG chat ✅
- Indexing pipeline: structure-aware chunking (adaptive heading-chain or fixed-character) +
  embeddings (LangChain4j `EmbeddingModel`) upserted into a Qdrant collection per document
  set (`[set-uuid]`), deterministic chunk ids, metadata round-trip, collection dropped on
  document-set delete. `IndexingJob` scans `READY` docs with `IndexStatus` `NOT_INDEXED`/
  `INDEXING`; states `NOT_INDEXED → INDEXING → INDEXED` (or `FAILED` + `index_error`).
- Chat endpoint over a document set (`ChatService`): top-k vector retrieval (context snippet +
  source), LLM completion via `LLM_*` OpenAI-compatible `ChatModel`, non-streaming; gated on
  set status `READY` (else `409`).
- Persisted conversations: `conversation` + `chat_message` tables (V5 migration), per-user
  ownership scoping, sources stored as JSON per assistant message; list/create/messages/delete
  controllers. Decision: **non-streaming** single-turn calls, conversations persisted.
- Structure extraction: markdown/html parsed structurally (headings + body sections); other
  formats fall back to Tika plain text as a single section.
- Config: `RAG_TOP_K`, `RAG_MAX_HISTORY_MESSAGES`, `RAG_SYSTEM_PROMPT`, `RAG_CHUNKING_MODE`
  (adaptive|fixed), `RAG_CHUNK_SIZE`, `RAG_CHUNK_OVERLAP`, `RAG_MAX_CHUNKS_PER_DOC`,
  `QDRANT_HOST`/`QDRANT_GRPC_PORT`/`QDRANT_API_KEY`, `LLM_*`. Migrations V4 (index columns)
  and V5 (chat tables).
- Frontend: `src/api/chat.ts` (conversation/message/chat endpoints), `ChatPanel.tsx`
  (conversation sidebar + message history + sources + send form), slotted into the document
  set detail page and enabled once `set.status === 'READY'`; chat enabled while set READY,
  disabled otherwise. Latest frontend/last-chosen decisions on UX kept (non-streaming).
- Tests: backend `mvnw test` 61 green (7 integration incl. `ChatIntegrationTest` covering the
  full lifecycle + cross-user isolation); frontend `npm run test` 34 green + `npm run build`.

## Phase 3 — Polishing ✅ (partially)
Chat remains non-streaming by decision (see Phase 2). Completed items:
- ✅ Pagination for document lists: `PageResponse` DTO (`content`, `page`, `size`,
  `totalElements`, `totalPages`) over `GET /api/documentsets/{id}/documents?page=&size=`
  (page ≥ 0, size clamped 1–100); frontend pager on the detail page + per-set page loading.
- ✅ Delete-all documents: `DELETE /api/documentsets/{id}/documents` drops the Qdrant
  collection, deletes rows, recomputes set status → `EMPTY`; frontend confirm dialog.
- ✅ Rename set / edit metadata: `PATCH /api/documentsets/{id}` (`UpdateDocumentSetRequest`,
  name required ≤ 200 chars, duplicate name → 409); frontend inline edit form.
- ✅ Per-user file quotas: `app.quota.max-upload-bytes-per-user` (`MAX_UPLOAD_BYTES_PER_USER`,
  default 100 MB); enforced per file on upload (duplicates exempt), counted across all of a
  user's sets; `GET /api/quota` → `{ usedBytes, limitBytes }`, quota bar on the dashboard.
- ✅ Detail page relayout + dashboard editing: the document-set detail page now uses a
  two-column layout (documents + upload + delete-set on the left, chat panel — given wider
  space — on the right; stacks to a single column on mobile). Name/description editing was
  moved out of the detail page into the dashboard as inline per-row editing (PATCH on save).
- ✅ UI de-clutter + auto-titled conversations: container widened to 1200px, row-level
  deletes became small icon buttons with aria-labels, the pager hides on a single page,
  and the first chat exchange auto-renames the conversation from its first user message
  (`ConversationTitler`; custom titles untouched; new title returned in `ChatResponse.title`
  and updated in the sidebar in place). Buttons were normalized to compact padding with
  focus rings, and the light theme got a professional-minimalist pass (refined slate/blue
  palette, hairline card shadows, soft-blue chat bubbles, unified 0.12s transitions).
- Test counts updated: backend `mvnw test` **80 green** (7 integration) — quota service,
  upload quota-rejection, set-metadata update + delete-all, paginated list, LLM config
  startup validation, conversation auto-titling (helper + chat service); frontend
  `npm run test` **45 green** + `npm run build` (dashboard quota bar + inline edit,
  detail page two-column layout, pagination, delete-all, auto-title sidebar update,
  quota api module).
- LLM config hardened: `LLM_BASE_URL`/`LLM_API_KEY` are required (no local/Ollama
  fallback); when either is missing the backend refuses to start with a clear
  startup error naming the missing variable. `.env.example`/compose/README updated.
- ⬜ e2e coverage automation; CI pipeline; hardened prod configuration (env secrets).
- ⬜ Streaming chat responses.

## Phase 4 — Runtime golden dataset & retrieval evaluation ✅
- Async golden generation: `GoldenGenerationJob` (gated by `app.golden.enabled`,
  `app.golden.poll-ms`/`initial-delay-ms`) generates LLM Q&A cases per indexed document.
  Doc `golden_status` `PENDING → GENERATING → DONE | FAILED` with `golden_error` and a
  retry cap (`app.golden.max-retries` = 3, crashed `GENERATING` rows retried); prompt
  requests `app.golden.questions-per-doc` (3) pairs as a strict JSON array, text truncated to
  `app.golden.max-chars-per-doc` (12000), ```` ```json ```` fences stripped, blank pairs
  filtered. Flyway `V6__golden.sql` (`golden_*` columns on `document`, `golden_case` table
  FK → document_set/users, `source_doc_id` ON DELETE SET NULL, indices).
- DRAFT → GOLDEN workflow: generated cases are `DRAFT` with `expected_sources` stored as
  JSON `{filename, docId, section:null}`; users review/edit/promote in-app; only GOLDEN
  counts toward evaluation. Not-found/ownership semantics: case updates/deletes check the
  owned-case guard, list/create the owned-set guard, cross-user access → 404, set deletion
  cascades.
- `GoldenEvaluationService` + `POST /api/documentsets/{docSetId}/golden/run`:
  question embedding → top-k Qdrant search → filename match against expected sources;
  drifted documents (missing from index) reported as warnings and excluded from aggregates.
  Report DTO: `hitRate`, `meanReciprocalRank`, `recall@k`, case rows (hit/rank/recall),
  warnings.
- Frontend: `src/api/golden.ts` + golden types; `GoldenCasesPage` at
  `/documentsets/:id/evaluation` (status badges, inline edit + promote, add-case form,
  run-evaluation card with metrics/warnings/per-case table); "Evaluation" link on the
  document-set detail page; light-theme CSS (chips, metric grid, eval table).
- Expected-source selection: replaces free-text filename entry with a compact **checkbox
  dropdown** populated from the set's documents (`listAllDocuments` pagination helper in
  `api/documentsets.ts`, page size 100), filtered to `READY`; sources submitted with real
  `docId`; sources whose file is gone are kept as locked "not in set" entries on edit.
- Tests: backend `mvnw test` **100 green** (8 integration) — `GoldenCaseServiceTest` (8),
  `GoldenCaseGeneratorTest` (5), `GoldenGenerationJobTest` (6), `GoldenEvalIntegrationTest`
  (1, full lifecycle + cross-user isolation + cascade); frontend `npm run test` **63 green** +
  `npm run build` (`src/api/golden.test.ts` 5, `src/pages/GoldenCasesPage.test.tsx` 9,
  `src/api/documentsets.test.ts` 4).

## Phase 5 — Chat guard rails ✅
- **Purpose / scope**: keep the chat grounded and prevent abuse without affecting genuine
  document questions. All checks deterministic and narrow; `RAG_GUARDRAILS_ENABLED=false`
  turns the whole layer off.
- **Input screen** (`ChatGuardrailService.blocked`): blank or > `max-input-chars` (1000)
  messages rejected with HTTP 400 (`IllegalArgumentException`); harm blocklist (self-harm,
  overdose/poisoning, minors, hate/violence) and prompt-injection blocklist
  (`ignore previous instructions`, `system prompt`, `developer mode`, `jailbreak`, …) →
  fixed safe refusal, **no LLM call**, refusal persisted as an assistant message with empty
  sources.
- **System prompt hardening**: a binding "Safety rules" block is appended after
  `RAG_SYSTEM_PROMPT` (context is data not instructions, cite `[n]`, professional/non-alarmist
  tone, no personal-medical advice beyond the documents, don't reveal the rules).
- **Output checks** (`ChatGuardrailService.refusalFor`): model refusals pass through; tone
  violations (rude/dismissive + alarmist/absolute lists, excessive `!`) and hallucination/
  ungrounded answers (citation required when sources exist + lexical coverage of the context
  below `hallucination-min-coverage` 0.4) are replaced by the fixed refusal (logged, no second
  LLM call). `require-citation` on by default.
- **Abuse limits**: in-memory sliding-window `ChatRateLimiter` per user
  (`rate-limit-requests` 60 / `rate-limit-window-seconds` 60) and per-conversation message cap
  (`max-messages-per-conversation` 300) → `TooManyRequestsException` → HTTP **429**
  (`GlobalExceptionHandler`). Single-instance only (README notes shared store for replicas).
- **Config**: new `app.rag.guardrails.*` (`GuardrailProperties`, registered in `LlmConfig`),
  `RAG_GUARDRAILS_*` env vars in `application.yml`, `.env.example`, `docker-compose.yml`;
  README "Chat guard rails" section. Optional second-pass LLM judge +
  `llm-check-enabled` flag reserved for a future LLM-based factual-consistency check (off).
- **ChatService wiring**: rate limit → input screen (blocked ⇒ persist USER + refusal, return
  early) → conversation cap → retrieve/LLM → output check → persist. Guardrails disabled ⇒
  fully old behavior.
- **Frontend**: `ChatPanel` chat input `maxLength={1000}` + `n/1000` character counter (over-cap
  turns red), Send disabled beyond the cap; backend 429/400/refusal messages surface in the
  existing alert via `ApiError.body.message`.
- **Tests**: backend `mvnw test` **135 green** (12 integration) —
  `ChatGuardrailServiceTest` (21), `ChatRateLimiterTest` (5), `ChatServiceTest` extended to 15,
  `ChatGuardrailIntegrationTest` (3), `ChatRateLimitIntegrationTest` (1, 429); the shared
  integration context keeps guardrails enabled so the happy-path chat stub is grounded + cited.
  Frontend `npm run test` **67 green** + `npm run build` (ChatPanel cap/counter tests;
  run-card empty state is count-aware — golden-ready hint + Run enabled vs promote hint +
  Run disabled).

## Phase 6 — Semantic response cache ✅
- **Purpose / scope**: serve repeated/similar questions instantly from a Qdrant-backed
  answer cache instead of re-running retrieval + generation. Transparent to the client.
- **Config**: `CacheProperties` under `app.rag.cache.*` (`enabled`, `similarity-threshold`
  0.92, `ttl-seconds` 604800, `collection-name` `semantic-cache`), registered in `LlmConfig`;
  `RAG_CACHE_*` env vars in `application.yml`, `.env.example`, `docker-compose.yml`; README
  "Semantic cache" section.
- **Store** (`QdrantSemanticCache`, `service/semanticcache/*`): shared collection, cosine
  distance, one point per (set, question) keyed by `UUID.nameUUIDFromBytes(docSetId + ":"
  + question)`; payload `doc_set_id` scoping; answer + `sources_json` + `created_at` in
  segment metadata. Best-effort everywhere (a cache failure degrades to a normal request).
- **Lookup/store in ChatService**: after embedding the user question, `lookup` (top-1 + set
  filter, score ≥ threshold, not TTL-expired) returns a cached answer + sources → persist
  assistant message, auto-title, return. On a miss the normal RAG path runs, and the grounded
  (non-refused) answer is later `store`d. Refusals/blocked inputs are never cached.
- **Invalidation & eviction**: `DocumentSetService` (delete / delete-all-documents /
  delete-document) and `IndexingService` (after a document is re-indexed) clear the whole
  set's cache. Hourly `@Scheduled evictExpired` removes entries older than the TTL.
- **Tests**: backend `mvnw test` **141 green** (13 integration) — `ChatServiceTest` extended
  to 20 (cache hit returns without LLM/search, miss stores, refused/blocked/disabled never
  touch the cache), new `SemanticCacheIntegrationTest` (1: repeat question → cached answer +
  single underlying `ChatModel` call; new question → miss; document delete invalidates). No
  frontend changes (cache is opaque).

## Phase 7 — Hybrid retrieval & optional reranking ✅
- **Purpose / scope**: replace dense-only top-k retrieval with dense + client-side BM25
  sparse search (RRF-fused) and an optional, config-gated reranker. Document types and user
  phrasing are unpredictable, so exact identifiers (drug names, codes, uncommon terms) stay
  findable via the lexical leg even when the embedding misses them.
- **Config**: `SearchProperties` under `app.rag.search.*` (`hybrid-enabled` true, `candidates`
  30, `fusion` `rrf`, `rerank.enabled` false, `rerank.mode` none), nested in `RagProperties`
  after `cache` and registered in `LlmConfig`. Env pass-through in `application.yml`, `.env.example`,
  `docker-compose.yml` (`RAG_HYBRID_ENABLED`/`RAG_SEARCH_CANDIDATES`/`RAG_SEARCH_FUSION`/
  `RAG_RERANK_ENABLED`/`RAG_RERANK_MODE`) and README "Hybrid retrieval" section.
- **Sparse vectorizer** (`SparseVectorizer`, `service/search/`): deterministic client-side
  tokenizer (lowercase → split → stopword/min-length filters → term frequencies → stable
  FNV-1a hash indices). The same tokenizer feeds indexing and querying; a query that
  tokenizes to nothing falls back to dense-only.
- **Indexer rewrite** (`QdrantVectorIndexer`, raw gRPC): named vectors `dense`
  (cosine, `LLM_EMBEDDING_DIMENSION`) + `bm25` (sparse, `modifier: idf`); chunk text moved to
  `text` payload key; idempotent deterministic ids with delete-before-add kept.
  `VectorIndexer.search` becomes `search(docSetId, queryText, queryEmbedding, topK)`;
  `ChatService` (message text) and `GoldenEvaluationService` (question text) pass the raw query.
- **Query**: two prefetches (`nearest(dense)` + `nearest(sparseIndex, sparseValue)` each
  `limit=candidates`) fused via `QueryFactory.fusion(rrf | dbsf)`, then the reranker slices to
  `topK`. `hybrid-enabled=false` → plain dense top-k. Search errors degrade to an empty result
  set (logged).
- **Reranking**: `Reranker` interface + `RerankerService` (the only `Reranker` bean; selects
  by config). `NoOpReranker` keeps fused order; `LexicalReranker` re-orders by in-process term
  overlap (deterministic, never changes the candidate set, degrades to fused order on failure).
  Extension point reserved for future HTTP/ONNX rerankers.
- **Tests**: backend `mvnw test` **154 green** (139 unit + 15 integration) — new
  `SparseVectorizerTest` (7: stopword/single-char drop, tokenSet, empty inputs, determinism,
  term frequency, sorted positive indices), `LexicalRerankerTest` (4: overlap ranking, topK
  slicing, empty-query order, candidate-set preservation), `HybridSearchIntegrationTest` (2:
  with all-dense identical unit vectors the sparse leg ranks the lexically matching chunk
  first; search scoped to the document set). Existing `ChatServiceTest`/chunker tests updated
  for the new `search` signature and the extra `RagProperties.search` argument. No frontend
  changes (retrieval is server-side).

## Phase 8 — Output evaluation & human-in-the-loop review ✅ (8a) / ✅ (8b)
Chat thumbs-up/down feedback is intentionally **not** implemented (user decision) — it
belongs to a future observability layer. Instead:
- **8a — Capture & review queue (done)**: every fresh chat answer (cache miss, including
  guardrail refusals) is captured by `EvalCaptureService` (best-effort, gated by
  `EVAL_ENABLED`). Auto-flags `GUARDRAIL_REFUSAL` / `LOW_COVERAGE` / `NO_SOURCES`; un-flagged
  outputs are queued with probability `EVAL_SAMPLE_RATE` (0.1). Coverage comes from new
  `ChatGuardrailService.coverageFor(answer, context)`; sources stored as JSON.
- **Review API**: `EvalController` under `/api/documentsets/{id}/evals` — paginated list with
  `status`/`sampled`/`flagged` filters (Specification), review (`PATCH`),
  dismiss (`POST /dismiss`), promote (`POST /promote` → DRAFT golden case from the corrected
  answer), metrics (`GET /metrics`). Flyway `V7__eval.sql` (`answer_eval` table, review
  status/verdict/rating/comment/corrected answer, partial indexes on sampled/flagged).
- **Lifecycle rules**: `REWORD`/`REJECT` require a corrected answer and evict the document
  set's semantic cache; `ACCEPT` never evicts; promote does not touch the review verdict.
- **Frontend**: `EvalsPage` at `/documentsets/:id/reviews` (linked from the set detail as
  *Reviews*) — metrics bar, status/flagged/sampled filters, paginated queue with flag badges
  + sampled chip, inline review form, dismiss (confirm) and promote actions.
- **8b — LLM factual-consistency judge (done)**: an optional second pass over the Grounding
  guard. `FactualConsistencyChecker` (a `@Service` in `service/guardrails/`, reuses the
  existing `ChatModel` bean) asks the model to verify the answer is consistent with the
  retrieved context and returns `{"consistent": bool, "reason"}`. It runs inside
  `ChatGuardrailService.refusalFor` only when the answer passes tone/citation/coverage,
  `sources` is non-empty, and `RAG_GUARDRAILS_LLM_CHECK_ENABLED` (`llm-check-enabled`) is
  true; a `consistent=false` verdict replaces the answer with the safe refusal. **Fail-open**:
  blank inputs, model errors, and unparseable responses never block (they log a warning and
  let the answer through) so a flaky LLM cannot break real questions. The gate reuses the
  guardrails config (`app.rag.guardrails.llm-check-enabled`, default off for cost/latency).
- **Tests**: backend **191 green** (172 unit + 19 integration) — 8a added
  `EvalCaptureServiceTest` (8), `AnswerEvalServiceTest` (12), `ChatServiceTest` extended
  (capture wiring), `EvalIntegrationTest` (2: capture→review→promote→cache-eviction full loop
  + foreign-user 404 + metrics; refusal captured & flagged + dismiss). 8b added
  `FactualConsistencyCheckerTest` (8), `ChatGuardrailServiceTest` extended to 21, and
  `LlmJudgeIntegrationTest` (2: judge refuses an inconsistent answer; a consistent one passes
  with its sources). Frontend `npm run test` **77 green** + `npm run build` (`api/evals.test.ts`
  6, `pages/EvalsPage.test.tsx` 4).

### Phase 9 — Query rewriting (done)
- **Design**: optional LLM query rewriting so follow-up questions and conversational shorthand
  are findable. On a cache miss, `QueryRewriteService` (`service/search/`, reusing the existing
  `ChatModel` bean) reformulates the user's question into **one** standalone search query,
  incorporating conversation history. `RAG_QUERY_REWRITE_ENABLED` (default false) turns it on;
  results are capped at `RAG_QUERY_REWRITE_MAX_CHARS` (default 200). Single-query
  reformulation only — no multi-query expansion (keeps cost/latency low).
- **Wiring**: in `ChatService`, after input screening and only when the cache missed — the
  rewritten query and its embedding drive dense + sparse retrieval (`vectorIndexer.search`),
  while the **original message** is kept for the answer prompt, conversation title,
  evaluation capture, and the semantic-cache key. **Fail-open at two layers**: the service
  itself returns the original on blank input/model error/blank/near-verbatim result, and the
  call site also catches a throwing rewriter (defensive) so a broken rewriter can never break
  chat.
- **Config**: `RagProperties.queryRewrite` (`app.rag.query-rewrite.*`, nested between `search`
  and `chunking`); env passthrough `RAG_QUERY_REWRITE_ENABLED` / `MAX_CHARS` / `SYSTEM_PROMPT`
  added to `application.yml`, `.env.example`, `docker-compose.yml`.
- **Tests**: backend now **205 green** (184 unit + 21 integration) — `QueryRewriteServiceTest`
  (8: enabled reflection, disabled → no model call, standalone-rewrite prompt composition,
  blank input skips, model error fail-open, blank response fail-open, cap, result trimmed),
  `ChatServiceTest` extended to 24 (disabled uses original; rewritten query drives search while
  the prompt keeps the original; rewriter failure falls back; rewrite skipped on cache hit),
  chunker tests updated for the `RagProperties` ctor change, and `QueryRewriteIntegrationTest`
  (2: rewritten query steers retrieval to the right source while the answer prompt keeps the
  user's wording; a failed rewrite falls back to the original query and correct source).
  Frontend unchanged (**77 green** + `npm run build`).

## Phase 10 — Langfuse observability ✅
- **Purpose / scope**: emit a full RAG trace per chat turn to Langfuse Cloud via OpenTelemetry
  OTLP/HTTP (no official SDK — own listeners + span helper for exact attribute control).
  **Fail-open, default-off**, `.env`/compose-only config.
- **Transport**: exporter posts to `{LANGFUSE_HOST}/api/public/otel` (OTLP/HTTP JSON,
  Langfuse has no gRPC endpoint) with HTTP Basic `base64(pk:sk)` +
  `x-langfuse-ingestion-version: 4`; `SdkTracerProvider` + `BatchSpanProcessor` built in
  `LangfuseTraceConfig` only when enabled **and** both keys are set; shutdown-hook flush.
- **Span model**: `ChatService.chat` creates a root `chat.request` span; `doChat` opens named
  sub-spans (`guardrails.input`, `semantic-cache.lookup`, `query-rewrite`, `retriever`,
  `semantic-cache.store`, `guardrails.output`, `eval.capture`). `LangfuseChatModelListener` /
  `LangfuseEmbeddingModelListener` (injected into the model beans via `ObjectProvider`) create
  `generation-chat` / `generation-embedding` child spans for every model call.
- **Attributes**: `gen_ai.*` model/provider/token usage, `input.value`/`output.value`, and
  Langfuse conventions (`langfuse.observation.type`, `langfuse.session.id`/`user.id`,
  `langfuse.trace.name`); eval verdicts/coverage/citation/refused as
  `langfuse.observation.metadata.eval.*` on the root span. Metadata flattened for indexing.
- **Sampling**: `LANGFUSE_SAMPLE_RATIO` (clamped 0–1, default 1.0); `LangfuseTracingContext`
  ThreadLocal carries the sample decision so listeners/sub-spans agree and no orphan spans
  leak when unsampled.
- **Config**: `LangfuseProperties` (`app.langfuse.*`), env passthrough
  `LANGFUSE_ENABLED`/`LANGFUSE_HOST`/`LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY`/
  `LANGFUSE_RELEASE`/`LANGFUSE_ENVIRONMENT`/`LANGFUSE_SAMPLE_RATIO` in `application.yml`,
  `.env.example`, `docker-compose.yml`. Standalone runs use OS env vars.
- **Fail-open**: every span helper null-checks and catches `Throwable`; tracing never throws
  into the request path; with `enabled=false` or unset keys `LangfuseSpanHelper` is a no-op
  and `ChatServiceTest`/`LlmConfigTest` constructors pass `LangfuseSpanHelper.disabled()`.
- **Tests**: `LangfusePropertiesTest` (5: clamp, defaults, configured, missing keys, disabled
  factory), `LangfuseTraceConfigTest` (4: enabled+configured → recording, enabled w/o keys →
  disabled, disabled → disabled, missing-keys no-op), `LangfuseSpanHelperTest` (6: parent/child
  spans in one trace, disabled/unsampled no-ops, fail + metadata, null-attribute/field
  filtering, metadata type preservation),
  `LangfuseChatModelListenerTest` (4), `LangfuseEmbeddingModelListenerTest` (3) — all
  `InMemorySpanExporter`-based. Backend now **227 green** (206 unit + 21 integration);
  the integration classes are unchanged (new tests are unit-only and need no Docker).