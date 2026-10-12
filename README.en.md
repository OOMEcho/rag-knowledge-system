<div align="center">

# rag-knowledge-system

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/technologies/downloads/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.8-6db33f.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://github.com/pgvector/pgvector)
[![Ollama](https://img.shields.io/badge/Ollama-qwen3-black.svg)](https://ollama.com/)
[![License](https://img.shields.io/badge/License-Apache--2.0-green.svg)](LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/OOMEcho/rag-knowledge-system)

[中文](README.md) | **English**

</div>

## 🌟 Introduction

rag-knowledge-system is a fully self-hosted RAG (Retrieval-Augmented Generation) knowledge base system with a split frontend/backend architecture. It covers the complete pipeline from document upload and asynchronous ingestion (parse/chunk/embed) to hybrid retrieval, multi-turn Q&A, and streaming output. All LLM and vector components are local (Ollama + pgvector) — it runs fully offline on a single Mac with no cloud dependencies.

### ✨ Features

- 📚 **Knowledge base management**: CRUD with unique active names and delete protection (non-empty knowledge bases cannot be deleted)
- 📄 **Async document ingestion**: upload (SHA-256 dedup, extension whitelist, size limit) → Tika parsing → TokenTextSplitter chunking → Ollama embedding → pgvector write, with a fully pollable status machine
- 🔀 **Hybrid retrieval**: vector channel (pgvector HNSW) + keyword channel (pg_trgm, works for Chinese and English), fused with Reciprocal Rank Fusion
- 💬 **Multi-turn chat**: stateless `history` passing; the LLM rewrites follow-ups into standalone retrieval questions, falling back to the original on failure
- ⚡ **SSE streaming**: references arrive first, answer tokens stream in; all errors become `error` events
- 🔖 **Cited references**: every answer carries its sources (document name / chunk index / fusion score)
- 🛡️ **Security baseline**: optional API key auth, `prod` profile lockdown, bucket4j rate limiting, CORS whitelist
- 🔁 **Self-healing**: failed-ingestion retry, automatic recovery of interrupted tasks after restarts, scheduled orphan vector/file reconciliation
- 🔍 **Request tracing**: `X-Request-Id` end-to-end logging, Ollama health indicator
- 🧪 **Quality regression**: retrieval-quality tests with Testcontainers + real Ollama, driven by an extensible JSON case file

## 🔗 Related Projects

- [Frontend repository](https://github.com/OOMEcho/rag-knowledge-web) (Vue 3 + Element Plus)

## 🏗️ Tech Stack

### Backend

| Technology | Version | Purpose |
|------|------|------|
| Spring Boot | 3.5.13 | Base framework |
| Spring AI | 1.1.8 | Ollama chat/embedding + PgVectorStore |
| Spring Security | 6.x | API key authentication |
| PostgreSQL + pgvector | 17 / 0.8 | Business metadata + vector storage |
| MyBatis-Plus | 3.5.16 | ORM |
| Flyway | - | Database migrations |
| bucket4j | 8.10 | Token-bucket rate limiting |
| Apache Tika | - | Multi-format document parsing |
| springdoc-openapi | - | API documentation |
| Testcontainers | - | Integration testing |

### Models

| Role | Default model | Notes |
|------|----------|------|
| Chat | `qwen3.6:35b` | Answer generation, follow-up rewriting |
| Embedding | `qwen3-embedding:0.6b` | 1024 dims; pgvector HNSW caps at 2000 dims, hence not the 4096-dim 8b variant |

## 🚀 Quick Start

### Prerequisites

- ☕ JDK 17+
- 🔨 Maven 3.9+
- 🐳 Docker / OrbStack
- 🦙 Ollama

### Local Development

1. **Clone the project**

```bash
git clone https://github.com/OOMEcho/rag-knowledge-system.git
cd rag-knowledge-system
```

2. **Install Ollama models**

```bash
ollama pull qwen3.6:35b
ollama pull qwen3-embedding:0.6b
```

3. **Initialize the database** (choose one)

```bash
# Option A: use an existing pgvector-enabled PostgreSQL container (e.g. postgres17 in OrbStack)
bash scripts/init-local-db.sh
# Overridable: PG_CONTAINER=postgres17 RAG_DB_PASSWORD=xxx bash scripts/init-local-db.sh

# Option B: use the bundled Compose file
docker compose up -d postgres
```

4. **Run the application**

```bash
mvn spring-boot:run
```

On startup Flyway creates the business tables and Spring AI creates the `rag_vector_store` table (1024 dims + HNSW index).

5. **Access**

- Health check: http://localhost:8080/actuator/health
- Ollama health: http://localhost:8080/actuator/health/ollama
- API docs: http://localhost:8080/swagger-ui.html

6. **Run the web UI** (optional)

```bash
git clone https://github.com/OOMEcho/rag-knowledge-web.git
cd rag-knowledge-web
npm install && npm run dev   # open http://localhost:5173
```

7. **End-to-end smoke test**

```bash
bash scripts/local-smoke-test.sh   # KB → upload → ingest → search/chat
```

### Docker Deployment

```bash
docker compose --profile app up --build
```

Compose forwards authentication, CORS, rate-limit and retrieval settings. For production, set `SPRING_PROFILES_ACTIVE=prod`, `RAG_SECURITY_ENABLED=true` and `RAG_API_KEYS`. Disabling authentication with the production profile fails startup.

On macOS, containers reach the host Ollama via `http://host.docker.internal:11434`.

## ⚙️ Configuration

Defaults live in `src/main/resources/application.yml`; all environment variables are listed in [.env.example](.env.example). Common ones:

| Environment variable | Default | Description |
|----------|--------|------|
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5432/rag_knowledge` | Database connection |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBEDDING_MODEL` | `qwen3.6:35b` / `qwen3-embedding:0.6b` | Models (changing the embedding model requires updating `RAG_VECTOR_DIMENSIONS` and rebuilding the vector table) |
| `RAG_DEFAULT_TOP_K` / `RAG_MAX_TOP_K` | `5` / `20` | Retrieval size |
| `RAG_DEFAULT_SIMILARITY_THRESHOLD` | `0.65` | Vector similarity threshold |
| `RAG_HYBRID_ENABLED` | `true` | Set `false` for pure vector retrieval |
| `RAG_INGESTION_*` | see .env.example | Chunking params, extension whitelist, worker count |
| `RAG_UPLOAD_MAX_FILE_SIZE` | `50MB` | Upload size limit |
| `RAG_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS whitelist for split deployment (comma-separated) |
| `RAG_SECURITY_ENABLED` / `RAG_API_KEYS` | `false` / empty | API key authentication |
| `RAG_RATE_LIMIT_RPM` / `RAG_RATE_LIMIT_RAG_RPM` | `120` / `30` | Per-minute rate limits (general / RAG endpoints) |
| `HTTP_CLIENT_READ_TIMEOUT` | `300s` | Ollama call timeout (local LLM generation is slow) |

## 📦 Modules

### 🎯 Core Modules

| Module | Description |
|------|----------|
| **Knowledge bases** | CRUD, unique-name constraint, delete protection |
| **Documents** | Dedup upload, extension whitelist, async ingestion status machine, retry, chunk queries |
| **RAG retrieval** | Dual-channel recall (vector + keyword), RRF fusion, per-KB isolation |
| **RAG chat** | Cited answers, follow-up rewriting, SSE streaming |
| **Security** | API key auth, rate limiting, prod profile lockdown, CORS |
| **Self-healing ops** | Restart recovery, scheduled orphan reconciliation |
| **Observability** | Request tracing, Ollama health indicator |

### 🔄 RAG Pipeline

1. Upload: SHA-256 dedup, extension whitelist, size limit; the raw file is persisted to local storage
2. Async ingestion: atomic status claim prevents concurrent re-ingestion → Tika parsing → `TokenTextSplitter` chunking → chunk metadata persisted → Ollama embedding → pgvector write; failures clean up intermediate data and record a sanitized reason
3. Retrieval: vector channel (cosine similarity, filtered by `knowledgeBaseId`) + keyword channel (pg_trgm `word_similarity`), fused with RRF (k=60) to select topK
4. For multi-turn requests, the follow-up is first rewritten into a standalone question (falling back to the original on failure)
5. A prompt containing conversation history and cited context is sent to the chat model, which answers with reference markers

### 🔒 Security

- **API key auth**: `RAG_SECURITY_ENABLED=true` + `RAG_API_KEYS=key1,key2` (comma-separated, rotatable), submitted via the `X-API-Key` header; enabling auth without keys fails fast at startup; `/actuator/health/**` stays public for probes
- **Rate limiting**: in-memory token buckets keyed by the authenticated key digest or unauthenticated client IP; arbitrary key headers cannot reset quotas. RAG endpoints get a stricter quota. The cache holds at most 10000 buckets, expires entries after 10 idle minutes, and rejects new clients when full. Quota or capacity exhaustion returns 429. Limits apply per application instance
- **Sanitized failure reasons**: full exceptions go to server logs only; the API returns displayable business messages

## 🏛️ Architecture

### Package Structure

```
com.ragknowledge/
├── RagKnowledgeSystemApplication.java   # Application entry point
├── common/                              # Unified responses, error codes, exception handling
├── config/                              # Configuration
│   ├── ingestion/                       # Ingestion worker pool & parameters
│   ├── maintenance/                     # Orphan-cleanup job config
│   ├── mybatis/                         # MyBatis-Plus config
│   ├── openapi/                         # OpenAPI + API key security scheme
│   ├── rag/                             # Retrieval params (topK/threshold/RRF)
│   ├── security/                        # Spring Security, CORS, rate-limit properties
│   └── storage/                         # Local storage config
├── knowledge/                           # Knowledge base metadata management
├── document/                            # Upload, parsing, chunking, async ingestion pipeline
├── rag/                                 # Hybrid retrieval, prompting, chat & streaming endpoints
│   └── retriever/                       # HybridChunkRetriever (RRF fusion)
├── maintenance/                         # Scheduled orphan vector/file reconciliation
├── infrastructure/storage/              # Local file storage adapter (object storage interface reserved)
├── observability/                       # Request tracing, Ollama health indicator
└── security/                            # API key filter, rate-limit filter
```

### Database Design

Both retrieval channels only expose chunks from non-deleted knowledge bases and non-deleted documents in `COMPLETED` status. Vector candidates are revalidated against business records before being used as references. Validate the bounded candidate pool before selecting the final topK, so invalid chunks do not consume reference slots. Fewer results are allowed when the pool is exhausted; queries are not expanded indefinitely.

Business tables are managed by Flyway (`src/main/resources/db/migration`) with soft deletes and partial unique indexes:

- `knowledge_base` - knowledge bases (unique active name)
- `knowledge_document` - document metadata (per-KB checksum dedup)
- `document_chunk` - chunks (keyword channel searches `content` directly)
- `rag_vector_store` - vectors (auto-created by Spring AI, 1024 dims + HNSW)

Knowledge base isolation is enforced twice: business queries always filter by `knowledge_base_id`, and vector search always applies the `knowledgeBaseId` metadata filter.

## 📡 API

| Method | Path | Description |
|------|------|------|
| POST | `/api/v1/knowledge-bases` | Create a knowledge base |
| GET | `/api/v1/knowledge-bases` | List knowledge bases (paged) |
| GET/PUT/DELETE | `/api/v1/knowledge-bases/{id}` | Detail / update / delete (empty only) |
| POST | `/api/v1/documents/upload?knowledgeBaseId=` | Upload a document |
| GET | `/api/v1/documents` | List documents (paged) |
| GET/DELETE | `/api/v1/documents/{id}` | Document detail (incl. status) / delete |
| POST | `/api/v1/documents/{id}/ingest` | Trigger async ingestion; returns `PARSING`, poll detail for the terminal status |
| POST | `/api/v1/documents/{id}/retry-ingestion` | Retry a failed ingestion |
| GET | `/api/v1/documents/{id}/chunks` | List chunks (paged) |
| POST | `/api/v1/rag/search` | Hybrid retrieval with cited references |
| POST | `/api/v1/rag/chat` | RAG chat (multi-turn via `history`) |
| POST | `/api/v1/rag/chat/stream` | SSE streaming chat |

### Unified Response Format

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "timestamp": "2026-01-01T12:00:00+08:00"
}
```

> ID fields (`id`, `knowledgeBaseId`, `documentId`, `chunkId`) are serialized as JSON **strings**: snowflake IDs exceed JavaScript's safe integer range, and the string form prevents precision loss in browsers.

### Examples

```bash
# Create a knowledge base
curl -sS -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"Company Policies","description":"Company policy documents"}'

# Upload and ingest (replace the IDs)
curl -sS 'http://localhost:8080/api/v1/documents/upload?knowledgeBaseId=<kbId>' \
  -F 'file=@/path/to/policy.md;type=text/markdown'
curl -sS -X POST http://localhost:8080/api/v1/documents/<docId>/ingest
curl -sS http://localhost:8080/api/v1/documents/<docId>    # poll until COMPLETED

# Chat
curl -sS -X POST http://localhost:8080/api/v1/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"<kbId>","question":"How soon must employees submit reimbursements?"}'
```

Questions are limited to 4096 characters. Multi-turn: pass `history` in chronological order (max 20 non-null entries, up to 8192 characters each); `rewrittenQuestion` in the response shows the rewritten retrieval question:

```bash
curl -sS -X POST http://localhost:8080/api/v1/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "knowledgeBaseId": "<kbId>",
    "question": "What about amounts over 5000?",
    "history": [
      {"role": "USER", "content": "How soon must employees submit reimbursements?"},
      {"role": "ASSISTANT", "content": "Employees must submit within 30 days of the expense [1]."}
    ]
  }'
```

SSE streaming — event order is `rewrittenQuestion` (multi-turn only) → `references` → repeated `delta` → `done`; failures emit an `error` event:

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"<kbId>","question":"What are the travel expense rules?"}'
```

## 📊 API Documentation

springdoc-openapi provides full interactive docs (with an API-key Authorize button).

- **Docs**: http://localhost:8080/swagger-ui.html (disabled under the prod profile)

## 🧪 Testing

```bash
# Unit tests (no Docker required)
mvn test

# Database migrations and business concurrency only (Docker required, no Ollama)
mvn verify -DskipITs=false -Dit.test=PostgresMigrationIT,BusinessConsistencyIT

# With integration tests: migrations + business concurrency + retrieval quality regression
# (requires Docker; the quality test skips automatically when Ollama is unavailable)
mvn verify -DskipITs=false
```

`BusinessConsistencyIT` uses an isolated pgvector container to check duplicate-delete counters, upload/delete coordination, editable-field boundaries and retrieval visibility. It skips when Docker is unavailable. The GitHub Actions workflow is configured to run unit tests and database integration tests without Ollama. Original files are only removed after the deletion transaction commits.

`RagRetrievalQualityIT` uses an isolated pgvector container, temporary uploads and real Ollama models. The finance knowledge base contains seven policies, including four distractors, alongside separate IT and empty knowledge bases, so retrieving every document in a tiny corpus does not produce a misleading pass. Its 25 fictional company-policy cases cover paraphrases, amount boundaries, similar policies, unanswerable questions, knowledge-base isolation and follow-up rewriting. It uses application-default retrieval parameters and checks source recall separately from answer conclusions. Per-case results are written to `target/rag-evaluation-report.json`, including execution/pass counts, failure stages, sources and latency. Missing Docker or Ollama is explicitly skipped and does not count as a quality pass; missing models or failed real requests are errors.

On memory-constrained machines, explicitly configure the chat context, for example: `SPRING_AI_OLLAMA_CHAT_OPTIONS_NUM_CTX=8192 mvn verify -DskipITs=false -Dit.test=RagRetrievalQualityIT`. This does not change the model or retrieval parameters, but limits long-conversation capacity. The report records `chatContextWindow` (0 means the model default); small-window results are not evidence of default large-window performance.

Automated assertions check basic conclusions, citation numbers and refusal signals; they do not prove that every sentence is supported. Human review of answers and sources remains necessary. Extend `src/test/resources/rag/fixtures/rag-eval-cases.json` with accurate expected sources and approval rules, without weakening criteria to obtain a pass.

## 📚 Deployment

### Production

Production must activate the `prod` profile, which enforces the security baseline: API key auth required (startup fails without keys or if an environment override disables authentication), Swagger disabled, health details hidden, actuator reduced to `health`.

```bash
mvn clean package -DskipTests
SPRING_PROFILES_ACTIVE=prod RAG_SECURITY_ENABLED=true RAG_API_KEYS=<strong-random-key> java -jar target/ragknowledge-0.1.0-SNAPSHOT.jar
```

### Deployment Boundary

Only one backend instance may use the same business database and upload directory. Overlapping restarts and multiple replicas sharing ingestion tasks are unsupported; ensure the old process has exited before starting another. Recovery runs before readiness is published. Until recovery completes, `ingest` and `retry-ingestion` return HTTP 503 (`BUSINESS_ERROR`, ingestion recovery in progress) without claiming a task. Interrupted `PARSING/EMBEDDING` documents are cleaned and marked `FAILED`; retries become available after recovery succeeds. Database recovery errors fail startup. This guard is not a distributed lock and does not coordinate multiple instances.

### Operations Notes

- **Ingestion failure recovery**: status `FAILED` with a sanitized `failure_reason`; retry via `retry-ingestion`; interrupted ingestions are auto-marked `FAILED` after restarts
- **Orphan reconciliation**: a scheduled job (03:00 daily by default) removes orphaned vectors and upload files; configurable via `RAG_ORPHAN_CLEANUP_*`
- **Migrations**: schema changes must be new Flyway migration files — never edit released scripts

## 📄 License

Licensed under [Apache-2.0](LICENSE).

## ❓ FAQ

<details>
<summary>How do I switch the embedding model?</summary>

Change `OLLAMA_EMBEDDING_MODEL` together with `RAG_VECTOR_DIMENSIONS` (the new model's output dimension), drop the old vector table (`DROP TABLE rag_vector_store`), and restart so Spring AI recreates it. Note the pgvector HNSW index caps at 2000 dimensions. Previously ingested documents must be re-ingested.
</details>

<details>
<summary>How do I enable API key authentication?</summary>

Set `RAG_SECURITY_ENABLED=true` and `RAG_API_KEYS=<key1>,<key2>`; clients send the key via the `X-API-Key` header. In production use `SPRING_PROFILES_ACTIVE=prod` with `RAG_SECURITY_ENABLED=true`; explicitly disabling authentication fails startup.
</details>

<details>
<summary>Why are IDs returned as strings?</summary>

The system uses 19-digit snowflake IDs, which exceed JavaScript's safe integer range (2^53). Returning them as numbers would lose precision in browsers, so all ID fields are serialized as strings.
</details>

<details>
<summary>Ollama calls time out — what now?</summary>

Local LLM generation is slow; the default is `HTTP_CLIENT_READ_TIMEOUT=300s`. Increase it if the 35b model is slower on your machine, or switch to a smaller chat model via `OLLAMA_CHAT_MODEL`.
</details>

<details>
<summary>How do I disable hybrid retrieval?</summary>

Set `RAG_HYBRID_ENABLED=false` to fall back to pure vector retrieval; the reference `score` then means cosine similarity instead of the RRF fusion score.
</details>

<details>
<summary>Frontend requests blocked by CORS?</summary>

Add the frontend origin(s) to `RAG_CORS_ALLOWED_ORIGINS` (comma-separated). `http://localhost:5173` is allowed by default for local development.
</details>

## 📞 Support

If you run into problems:

- 🐛 Issue: [open an issue](https://github.com/OOMEcho/rag-knowledge-system/issues)
- 📖 Frontend project: [rag-knowledge-web](https://github.com/OOMEcho/rag-knowledge-web)

---

<div align="center">

**If this project helps you, please give it a ⭐ Star!**

Made with ❤️ by [OOMEcho](https://github.com/OOMEcho)

</div>
