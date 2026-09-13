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

# Per-user storage quota (bytes, default 100 MB)
MAX_UPLOAD_BYTES_PER_USER=104857600
```

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
.\mvnw.cmd clean test          # 69 tests, ~150s first run
```

### Frontend

```bash
cd frontend
npm run test                    # 42 tests
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
