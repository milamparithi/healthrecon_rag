# HealthRecon RAG — Test Plan

## Backend unit tests (`mvnw test`, JUnit 5 + Mockito)

All layers (control at service level; security utilities covered directly):

| Class | Coverage |
|---|---|
| `JwtServiceTest` | token create/parse round-trip, tampered token rejected |
| `AuthServiceTest` | register/login/logout paths, duplicate email, bad credentials, `me` by id |
| `DocumentSetServiceTest` | list/create/get/delete ownership scoping, `recomputeStatus` transitions, delete removes vectors first, delete drops Qdrant collection (mocked `VectorIndexer`), delete/delete-all/delete-document invalidate the semantic cache |
| `DocumentUploadServiceTest` | valid uploads, duplicates (name/hash), empty/size/type rejection, per-file results |
| `IngestionJobTest` | pending → extracting → ready; extraction failure → failed (save called on both transitions) |
| `TextExtractionServiceTest` | plain text, binary garbage handled without crash (blank text) |
| `MarkdownStructureExtractorTest` | headings create sections with correct heading chains |
| `HtmlStructureExtractorTest` | h1–h6 + body text structured correctly |
| `AdaptiveStructuralChunkerTest` | heading-chain boundaries, chain truncation at MAX_CHAIN_CHARS, oversized section fallback to fixed chunker |
| `FixedCharChunkerTest` | window size, overlap, max-chunks cap |
| `ChunkingServiceTest` | adaptive vs fixed mode selection, empty content → no chunks |
| `IndexingServiceTest` | chunks → embeddings → upsert → INDEXED; embedding/extraction failure → FAILED + index_error; empty content → INDEXED with no vectors; re-index invalidates the semantic cache |
| `IndexingJobTest` | indexes NOT_INDEXED/INDEXING docs, skips INDEXED/FAILED, no docs → no-op |
| `ChatServiceTest` | READY gate (ConflictException), context prompt composition, history window, persists bot messages w/ sources JSON, auto-rename from the default title + keeping custom titles; guardrail paths (enabled via injected `GuardrailProperties`): grounded cited answers pass and the hardened system prompt is present, harmful input + prompt injection + oversized input are blocked without calling the LLM (refusal persisted, sources empty), answers without citation / hallucinated / rude-tone answers are replaced by the refusal, model refusals pass through, rate limit throws `TooManyRequestsException`, conversation message cap throws before persisting; semantic cache paths: cache hit returns the stored answer + sources without calling the LLM or retrieval search, a miss stores the grounded answer, refused answers are never cached, blocked input never touches the cache, disabled cache never consults the store; eval capture paths: grounded answers and refusals are handed to `EvalCaptureService` after the assistant message is saved; query-rewrite paths: rewrite service disabled → original query used, rewrite enabled → rewritten query + its embedding drive search while the answer prompt keeps the user's wording (embed model called twice), rewriter throws → original query used, cache hit → rewrite skipped |
| `LlmConfigTest` | startup validation: refuses to build ChatModel/EmbeddingModel without `LLM_BASE_URL` or `LLM_API_KEY` (clear missing-variable message), builds fine when configured |
| `QuotaServiceTest` | per-user used/limit computation, remaining bytes |
| `DocumentUploadServiceTest` | quota enforcement: over-limit upload rejected with FAILED + message, fits accepted, batch accumulates used bytes |
| `DocumentSetServiceTest` | metadata update (rename/description), duplicate-name → ConflictException, delete-all documents drops vectors before rows and recomputes status |
| `GoldenCaseServiceTest` | create defaults to DRAFT + serializes expected sources (`ExpectedSource` JSON), create honors explicit status, unknown status → rejected, update edits fields and keeps status when `status` null, cross-user case → 404, delete checks ownership then delegates, sources parsed tolerantly, list uses the owned-set guard |
| `GoldenCaseGeneratorTest` | happy path parses the JSON array into DRAFT cases with expected sources, blank extracted text → `golden_error` (no LLM call), invalid JSON → document marked FAILED, strips ```json fences before parsing, filters blank question/answer pairs |
| `GoldenGenerationJobTest` | disabled → no interaction, processes due `PENDING` docs, retries crashed `GENERATING` docs, skips docs past the retry cap, retries docs under the cap, no due docs → no interaction |
| `ChatGuardrailServiceTest` | clean document question allowed; harmful content and prompt injection blocked with the fixed refusal; blank + oversized messages → `IllegalArgumentException`; disabled → everything allowed; blank answer → refusal; model refusals pass through; missing citation → refusal; hallucinated (low lexical coverage) answer → refusal despite citation; rude + alarmist tones → refusal; grounded + cited answer allowed; answer without sources allowed; `terms()` extraction/lowercasing; LLM judge: judge disabled (`llm-check-enabled=false`) skips it, consistent answer passes, judge verdict `consistent=false` → refusal, no sources → judge skipped, judge model exception → fail-open (answer passes) |
| `FactualConsistencyCheckerTest` | consistent answer → true; inconsistent answer → false; ```json-fenced response parsed; prose-surrounded JSON extracted; chat-model exception → fails open (true, no throw); malformed response → fails open; blank answer or blank context → skipped (true, `chatModel` never called) |
| `ChatRateLimiterTest` | permits within limit, rejects over limit, per-user isolation, allows again after the window elapses, disabled limit (≤0) never rejects |
| `SparseVectorizerTest` | stopword + single-char drop, `tokenSet` stopword filtering, stopword-only text → empty vector, blank/null → empty vector, deterministic across calls, term frequency reflected in values, indices sorted + positive |
| `LexicalRerankerTest` | ranks candidates by lexical overlap with the query, returns up to `topK`, empty query keeps original order, never changes the candidate set |
| `QueryRewriteServiceTest` | `enabled()` reflects configuration; disabled → original query without calling the model; enabled → standalone-rewrite prompt (system instructions + history + question) and trimmed result; blank question skips rewrite; model error → fail-open (original, no throw); blank model response → original; result capped to `maxChars` |
| `EvalCaptureServiceTest` | disabled → no-op; grounded capture stores question/answer/sources/coverage with `origin=CHAT`; refused outputs flag `GUARDRAIL_REFUSAL` only; `LOW_COVERAGE` flag set when guardrails enabled and coverage below the threshold (and not when disabled); `NO_SOURCES` when answered with no sources; `sample-rate=1.0` → always sampled; a flagged output is never sampled |
| `AnswerEvalServiceTest` | get maps to `AnswerEvalResponse`; not found → 404; paginated list (Specification filters on status/sampled/flagged, newest first); invalid status filter rejected; `ACCEPT` review never evicts the cache; `REJECT`/`REWORD` evict the set's semantic cache; `REJECT` without corrected answer rejected; unknown verdict rejected; dismiss clears verdict/rating/comment; promote without corrected answer throws; promote creates a DRAFT golden case from `correctedAnswer` + expected sources and keeps the review verdict; metrics aggregation (counts + average rating) |
| `LangfusePropertiesTest` | default `hostOrDefault`/`environmentOrDefault`, `sampleRatio` clamped to 0–1, `configured()` requires public+secret key, `disabled()` factory yields a defaulted inactive record |
| `LangfuseTraceConfigTest` | enabled + configured keys → helper `isRecording` true; enabled but empty/missing keys → disabled helper (no exporter); disabled → disabled helper; `isEnabled()`/`shouldEmit()` reflect the flag |
| `LangfuseSpanHelperTest` | root span + child spans land in one trace; `TracedSpan` metadata flattened to `langfuse.observation.metadata.*` (string + boolean typed); failed span → `ERROR` status; adjacent-span sequencing; disabled/unsampled helpers emit nothing; context cleared on `close()` |
| `LangfuseChatModelListenerTest` | `onRequest` opens a `generation-chat` span with `gen_ai.*` + `input.value`; `onResponse` adds `output.value`, token usage + `INFO` status; `onError` records exception + `ERROR`; disabled helper skips all; null token usage tolerated |
| `LangfuseEmbeddingModelListenerTest` | `onRequest`/`onResponse` create `generation-embedding` spans with input char count + output cardinality; `onError` → `ERROR`; disabled helper skips; no raw embedding vectors exported |

## Backend integration tests (`@SpringBootTest` + MockMvc + Testcontainers)

- **Testcontainers**: JVM-wide singleton containers — `postgres:16-alpine` and
  `qdrant/qdrant:latest` (via `org.testcontainers.qdrant.QdrantContainer`, gRPC port) —
  registered through `@DynamicPropertySource` in `BaseIntegrationTest`. (A
  `@Container @ServiceConnection` approach was abandoned because the container stopped after
  the first test class, leaving the cached second Spring context with a dead datasource URL.)
- **Background jobs**: both `IngestionJob` and `IndexingJob` have configurable poll +
  initial-delay intervals; tests set `app.ingestion.poll-ms`, `app.ingestion.initial-delay-ms`,
  `app.indexing.poll-ms`, `app.indexing.initial-delay-ms` to 1h so jobs never race in-test.
  Extraction/indexing is triggered explicitly in-test.
- **LLM mocks**: `BaseIntegrationTest` declares `@MockitoBean ChatModel` + `EmbeddingModel`;
  the embedding stub returns a unit vector (dimension 768, matching
  `llm.embedding-dimension=768`) and `embedAll` returns one vector per input segment
  (`thenAnswer`). Qdrant metadata (doc_id/filename/section) is written/read in integration
  tests to prove round-trip.
- **Golden status in integration tests**: `BaseIntegrationTest` sets `app.golden.enabled=false`
  (plus 1h poll/delays) so the scheduled golden job never races in-test; generation is driven
  explicitly via the autowired `GoldenCaseGenerator`. The `chatModel.chat(anyList())` stub
  returns a **single-line JSON string** (concatenated, never a Java text-block continuation)
  with one Q/A pair per document.
- **Auth header helper**: `registerAndGetToken()` registers a user and returns the raw JWT
  (`AuthResponse.token`); requests carry `Authorization: Bearer <token>`.
- **Guard rails in integration tests**: guardrails are **enabled by default** (the shared
  context does not override `app.rag.guardrails.enabled`), so the `chatModel` chat stub must
  return a **grounded + cited** answer (`Take paracetamol 500 mg as the first step [1].`) for
  the happy path to pass the output grounding check. Tests that re-register users per test
  use unique emails (the DB persists across tests in a class). The rate-limit test class
  overrides `app.rag.guardrails.rate-limit-requests=2` (own Spring context) and expects the
  third chat in the window to return HTTP **429** with `error: TooManyRequests`.
- No test `application.yml` — an earlier one shadowed the main config and dropped
  `app.jwt.secret`. Config overrides are injected as properties directly. Run with
  `mvn clean test` to avoid stale copied resources in `target/`.

Tests:
- `AuthIntegrationTest` (5): register→me with token; me without token → 401; login returns
  token; login with bad password → 401; duplicate email → 409; logout → 204 and token still
  valid after (stateless).
- `DocumentSetFlowIntegrationTest` (1): register → create set → upload 3 files → documents
  become READY with expected extracted text length → paginated list (page/size/total
  elements, second page) → rename set via PATCH → duplicate-name 409 → per-user quota
  (used/limit) → delete-all → set EMPTY + documentCount 0 + usedBytes 0 → user B cannot
  access user A's set (404).
- `ChatIntegrationTest` (1): full lifecycle — register 2 users → create set → upload md →
  ingest to READY → index → conversations list empty → create conversation → chat returns
  mocked answer with sources → chat response + first conversation title are renamed from the
  first user message → messages persisted (roles + sources) → user B cannot read/use
  A's conversation (404) → delete conversation → messages gone → delete set cleans up.
- `ConversationTitlerTest` (6): short title, whitespace collapse, trailing punctuation
  stripped, long-message truncation at word boundary with `…`, blank → null, punctuation-only → null.
- `ChatServiceTest` (5): now also covers auto-rename from the default title (reply + entity)
  and keeping custom titles (reply `title` null).
- `GoldenEvalIntegrationTest` (1): full golden lifecycle — register users A/B → create set →
  upload md → `ingestionJob.processPending()` to READY → find doc →
  `indexingService.indexDocument(doc)` → `goldenCaseGenerator.generate(doc)` (stubbed chat JSON)
  → GET golden returns a DRAFT case with the expected source → PATCH promotes to GOLDEN →
  `POST /run` returns the report (hitRate/recall@k/MRR 1.0, case row) → user B cannot access
  A's golden cases (404) → deleting the doc set cascades the cases.
- `ChatGuardrailIntegrationTest` (3): prompt-injection and harmful messages return the fixed
  refusal with empty sources and **never call the LLM** (`verify(chatModel, never())`), and
  the refusal is persisted as an assistant message (sources `[]`); a grounded question still
  answers normally (guards against false positives).
- `ChatRateLimitIntegrationTest` (1): with `rate-limit-requests=2`, two chats succeed and the
  third returns 429 `TooManyRequests` (JSON body).
- `SemanticCacheIntegrationTest` (1): semantic cache enabled in the shared context;
  question-dependent deterministic 768-dim embeddings (`embeddingFor(text)`, hash-based
  normalized vectors → same text cosine ≈1.0, different text ≈0) so first chat stores, a
  repeated question hits the cache (same answer + sources, underlying `chatModel` called once)
  while a different question is a miss (`chatModel` called twice); deleting a document
  invalidates so the earlier question is answered fresh again (`chatModel` called three
  times). Same-text lookup keeps the mocked (grounded, cited) answer valid after invalidation.
- `HybridSearchIntegrationTest` (2): with **identical** unit-vector dense embeddings (dim 768)
  so the cosine leg cannot discriminate, the client-side sparse BM25 leg ranks the chunk whose
  terms match the query first (proves fusion + sparse indexing against real Qdrant); hybrid
  search is scoped to the document set (a second set's colliding chunk never leaks in).
- `EvalIntegrationTest` (2): chat answer is captured (row + metrics 1) → a repeated identical
  question is a cache hit and adds **no** eval row → reviewer `PATCH`es `REJECT` + corrected
  answer → `POST /promote` creates a DRAFT golden case → the cache is evicted so a third
  identical question is answered fresh (2 eval rows) → foreign user gets 404 → metrics reflect
  the funnel (accepted/reworded/rejected, average rating). Second test: a guardrail-refused
  answer is captured and flagged `GUARDRAIL_REFUSAL`, then dismissed.
- `LlmJudgeIntegrationTest` (2): with `app.rag.guardrails.llm-check-enabled=true` in its own
  Spring context (which must repeat the shared `BaseIntegrationTest` properties — a subclass
  `@SpringBootTest` replaces the inherited one — incl. `llm.embedding-dimension=768`), the
  judge refutes a fabricated answer → refusal with empty sources (and the chat model is called
  twice: answer + judge) and a contradiction-free answer passes with its source. The shared
  `chatModel` stub inspects the judge prompt's `SystemMessage` text to distinguish the two
  calls.
- `QueryRewriteIntegrationTest` (2): with `app.rag.query-rewrite.enabled=true` in its own
  Spring context (repeating the shared `BaseIntegrationTest` properties — a subclass
  `@SpringBootTest` replaces the inherited one — incl. `llm.embedding-dimension=768`), a
  rewrite stub steers retrieval to a deliberately disjoint-vocabulary doc (real Qdrant:
  source = escalation doc while the answer prompt keeps the user's original wording, chat
  model called twice: rewrite + answer); a failing rewrite (model throws) falls back to the
  original query and its correct source (fail-open). The stub distinguishes the rewrite call
  by the prompt's `SystemMessage` text.

**Current status: 227 tests, all green** (206 unit + 21 integration).

## Frontend unit tests (`npm run test`, Vitest 2 + jsdom + Testing Library)

Quality gate is `npm run build` (`tsc -b` strict incl. `noUnusedLocals`/`noUnusedParameters` +
vite production build) plus `npm run test`:

| File | Coverage |
|---|---|
| `src/api/client.test.ts` | bearer-token attach (and absence without token), 204 → undefined, `ApiError` from 2xx/non-2xx bodies, fallback message, 401 on protected paths clears token + dispatches `auth:unauthorized`, 401 on login/register does NOT |
| `src/auth/AuthContext.test.tsx` | session restore from stored token, no-token fast path, login/register persist token + user, logout clears + navigates to `/login`, `auth:unauthorized` event logs out + redirects |
| `src/auth/RequireAuth.test.tsx` | loading state while initializing, renders children when authed, redirect to `/login` when not; `RedirectIfAuthed` inverse |
| `src/pages/DashboardPage.test.tsx` | empty state, list rendering (counts + links), create-form flow reloads list, storage quota bar, inline edit (save calls PATCH + reloads, cancel aborts, empty name disables save), surfaces load errors |
| `src/api/chat.test.ts` | conversation list/create (with/without title), message load, chat send, delete — all hit the right URL/method/body via the mocked client |
| `src/api/quota.test.ts` | quota fetch hits `/quota` via the mocked client |
| `src/api/documentsets.test.ts` | `listAllDocuments` pagination: single-page returns content, pages through until `totalPages`, stops after all pages collected, empty set returns `[]` |
| `src/components/ChatPanel.test.tsx` | disabled hint; new-chat + empty state; loads first conversation + messages + sources; send reloads transcript; auto-rename from the reply title updates the sidebar row; load error surfaced; delete active conversation falls back to next; input capped at `maxlength=1000`; character counter shown and send locked beyond the limit |
| `src/pages/DocumentSetDetailPage.test.tsx` | two-column layout (chat panel renders in the right column), paginated list + pager next (page requests), delete-all after confirm, single-document delete via icon button, delete set navigates back, not-found state |
| `src/api/golden.test.ts` | list/create/update/delete/run-hit the golden endpoints via the mocked client |
| `src/pages/GoldenCasesPage.test.tsx` | renders cases with status badges (DRAFT/GOLDEN) + edit/delete; add-case form opens the source-picker checkbox dropdown, selects a READY document and POSTs with `docId` populated; only READY documents are offered as sources; edit saves changes keeping source docIds; sources no longer in the set are preserved when editing; delete confirms then removes; run evaluation renders metrics + warnings + per-case rows; run-card empty state is count-aware (golden case ready hint + Run enabled vs promote hint + Run disabled); not-found state |
| `src/api/evals.test.ts` | list without filters → `/evals`; list with `page/size/status/flagged/sampled` → query string; review `PATCH` body (verdict/rating/comment/correctedAnswer); dismiss `POST`; promote `POST`; metrics `GET` |
| `src/pages/EvalsPage.test.tsx` | renders captured outputs with flag badges (GUARDRAIL_REFUSAL, LOW_COVERAGE), `sampled` chip and the metrics bar; review with verdict + corrected answer calls `reviewEval`; REWORD/REJECT without a corrected answer is blocked client-side (`reviewEval` not called); promote calls `promoteEval` on a reviewed corrected answer; dismiss calls `dismissEval` after confirm |

**Current status: 77 tests, all green + `npm run build` green.**

Tooling: vitest is wired into `vite.config.ts` (`vitest/config`, jsdom + `src/test/setup.ts` with
jest-dom). Dev deps only (`vitest@^2` for Vite 5 compatibility) — no impact on the Docker image.

### Manual E2E run against the compose stack via curl
health UP → documentsets w/o token 401 → register → create set → multipart upload →
poll to READY w/ correct extracted-text length → cross-user 404 → logout(no-op) + token
still valid → SPA serves index.html on deep links. Phase 2 chat smoke: after a set is READY
and indexed, create a conversation and POST chat (mocked LLM or real via `LLM_*`), verify
new messages + sources in `messages`.

## Commands

```bash
# Set JDK 21 first (system JAVA_HOME points at JDK 8):
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
# Backend tests (Docker Desktop must be running for Testcontainers):
cd backend ; .\mvnw.cmd clean test
# Frontend tests + typecheck + build:
cd frontend ; npm run test ; npm run build
# Full stack:
docker compose up -d --build ; docker compose ps   # all healthy
```