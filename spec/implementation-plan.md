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