# HealthRecon RAG — Test Plan

## Backend unit tests (`mvnw test`, JUnit 5 + Mockito)

All layers (control at service level; security utilities covered directly):

| Class | Coverage |
|---|---|
| `JwtServiceTest` | token create/parse round-trip, tampered token rejected |
| `AuthServiceTest` | register/login/logout paths, duplicate email, bad credentials, `me` by id |
| `DocumentSetServiceTest` | list/create/get/delete ownership scoping, `recomputeStatus` transitions, delete removes vectors first, delete drops Qdrant collection (mocked `VectorIndexer`) |
| `DocumentUploadServiceTest` | valid uploads, duplicates (name/hash), empty/size/type rejection, per-file results |
| `IngestionJobTest` | pending → extracting → ready; extraction failure → failed (save called on both transitions) |
| `TextExtractionServiceTest` | plain text, binary garbage handled without crash (blank text) |
| `MarkdownStructureExtractorTest` | headings create sections with correct heading chains |
| `HtmlStructureExtractorTest` | h1–h6 + body text structured correctly |
| `AdaptiveStructuralChunkerTest` | heading-chain boundaries, chain truncation at MAX_CHAIN_CHARS, oversized section fallback to fixed chunker |
| `FixedCharChunkerTest` | window size, overlap, max-chunks cap |
| `ChunkingServiceTest` | adaptive vs fixed mode selection, empty content → no chunks |
| `IndexingServiceTest` | chunks → embeddings → upsert → INDEXED; embedding/extraction failure → FAILED + index_error; empty content → INDEXED with no vectors |
| `IndexingJobTest` | indexes NOT_INDEXED/INDEXING docs, skips INDEXED/FAILED, no docs → no-op |
| `ChatServiceTest` | READY gate (ConflictException), context prompt composition, history window, persists bot messages w/ sources JSON |
| `LlmConfigTest` | startup validation: refuses to build ChatModel/EmbeddingModel without `LLM_BASE_URL` or `LLM_API_KEY` (clear missing-variable message), builds fine when configured |
| `QuotaServiceTest` | per-user used/limit computation, remaining bytes |
| `DocumentUploadServiceTest` | quota enforcement: over-limit upload rejected with FAILED + message, fits accepted, batch accumulates used bytes |
| `DocumentSetServiceTest` | metadata update (rename/description), duplicate-name → ConflictException, delete-all documents drops vectors before rows and recomputes status |

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
- **Auth header helper**: `registerAndGetToken()` registers a user and returns the raw JWT
  (`AuthResponse.token`); requests carry `Authorization: Bearer <token>`.
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

**Current status: 80 tests, all green** (73 unit + 7 integration).

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
| `src/components/ChatPanel.test.tsx` | disabled hint; new-chat + empty state; loads first conversation + messages + sources; send reloads transcript; auto-rename from the reply title updates the sidebar row; load error surfaced; delete active conversation falls back to next |
| `src/pages/DocumentSetDetailPage.test.tsx` | two-column layout (chat panel renders in the right column), paginated list + pager next (page requests), delete-all after confirm, single-document delete via icon button, delete set navigates back, not-found state |

**Current status: 45 tests, all green + `npm run build` green.**

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