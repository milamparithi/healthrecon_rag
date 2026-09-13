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
           QdrantVectorIndexer, ConversationService, ChatService
service/extraction/  StructuralDocument, MarkdownStructureExtractor, HtmlStructureExtractor
service/chunking/    FixedCharChunker, AdaptiveStructuralChunker
domain/    JPA entities + enums (DocumentSetStatus, DocumentStatus, IndexStatus)
repository/ Spring Data repositories + projection
config/    RagProperties, QdrantProperties, LlmProperties, QuotaProperties, LlmConfig (ChatModel/EmbeddingModel beans)
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
  upsert into the Qdrant collection named `[document-set-uuid]`. Deterministic string ids
  (`UUID.nameUUIDFromBytes(docId:index)`) allow idempotent re-indexing (delete-before-add).
  Final states `INDEXED` or `FAILED` (`index_error`). Deleting a document removes its
  vectors then the row; deleting a set drops the whole collection.
- Chat flow (`ChatService`, non-streaming): requires set `READY` (else `409`) →
  embed user question → top-k search → prepend retrieved snippets to the system prompt →
  `ChatModel.chat(history window + user message)` → persist user + assistant messages
  (sources serialized as JSON) in `conversation`/`chat_message`. Conversations are scoped to
  the owner user; cross-user access → 404.
- Provider-agnostic LLM: `LlmConfig` builds `ChatModel`/`EmbeddingModel` from
  `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_CHAT_MODEL`, `LLM_EMBEDDING_MODEL`,
  `LLM_EMBEDDING_DIMENSION`. Both `LLM_BASE_URL` and `LLM_API_KEY` are required — the
  application refuses to start when either is missing (no local fallback) so the missing
  configuration is surfaced as a clear startup error. The chat service is
  `ChatLanguageModel`-interface-driven — swap the provider via env, no code change.

### Frontend layering
```
api/      fetch wrapper + typed modules (auth, documentsets, quota, chat) + shared types
auth/     AuthContext (token mgmt, /me, login/register/logout) + route guards (RequireAuth)
components/ AppLayout, StatusBadge, AuthShell, ChatPanel
pages/     Login, Register, Dashboard, DocumentSetDetail
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
  `QDRANT_API_KEY`, `LLM_*`, `RAG_*` chunking/retrieval knobs, `MAX_UPLOAD_BYTES_PER_USER`. Frontend proxies `/api/` to
  `backend:8080`; SPA deep links fall through to `index.html`.
- Schema changes are only applied via new Flyway migrations (V2..V5). Never edit an applied
  migration; migrations are immutable once deployed.