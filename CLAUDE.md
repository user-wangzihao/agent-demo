# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Agent Showcase** is a Spring Boot 3.2.5 (Java 17) intelligent knowledge management system. Administrators upload feature documentation; the system vectorizes it and stores embeddings in Milvus. Users chat with an AI agent (via Server-Sent Events) that retrieves relevant knowledge from the vector store and answers using Alibaba DashScope (Qwen) models. The agent supports LLM function calling (5 built-in tools), image understanding, and async video learning.

## Build & Run

```bash
# Build
mvn clean package

# Run via Maven
mvn spring-boot:run

# Run packaged JAR
java -jar target/agent-demo-0.0.1-SNAPSHOT.jar
```

The server starts on **port 9999**. API docs available at `http://localhost:9999/swagger-ui.html`.

## Testing

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=LearnVideoTest
```

Note: the test source root is non-standard: `src/test/main/java/` (not `src/test/java/`).

## Architecture

### Layered Structure
Standard Spring Boot layered architecture under `com.wzh`:
- `controller/` → REST endpoints
- `service/` → business logic; core services (`AgentService`, `DashScopeService`, `KnowledgeToolService`, `TicketToolService`) are concrete `@Service` classes directly; domain services (`FaqDocumentService`, `FeatureDocumentService`, `VideoDocumentService`) use interface + `impl/` implementation
- `mapper/` → MyBatis-Plus DAO layer (logical delete via `deleted` field)
- `entity/` + `entity/dto/` → domain models and DTOs
- `config/` → Spring beans and interceptors
- `common/` → shared utilities (`Result` response wrapper, `UserContext` thread-local, `ToolCallResult`)

### Core Services

| Service | Responsibility |
|---|---|
| `AgentService` | Orchestrates document learning pipeline, chat (SSE), tool-call dispatch, regeneration, feedback, export |
| `DashScopeService` | LLM calls: embeddings (`text-embedding-v3`), chat (`qwen-plus`), vision (`qwen-vl-max`), video (`qwen3.5-plus`) |
| `MilvusService` | Vector DB CRUD and semantic search on collection `feature_document_vectors` |
| `ImageUnderstandingService` | Analyzes images in docs and user messages via vision model |
| `KnowledgeExtractService` | Extracts structured knowledge (cause-effect, prerequisites, error solutions) from documents |
| `VideoLearnService` | Async video processing; status: 0=未学习, 1=学习中, 2=已学习, 3=失败 |
| `MinioService` | Object storage for files, images, and videos |
| `KnowledgeToolService` | Agent-callable tools: `listDocumentStatus`, `triggerKnowledgeLearning`, `analyzeUsageStats` (admin-only) |
| `TicketToolService` | Agent-callable tools: `submitTicket`, `queryTicketStatus` — calls external ticket system at `ticket-system.base-url` |
| `FaqDocumentService` | CRUD for standalone FAQ documents |

### Data Flow: Document Learning
1. Admin uploads feature document (JSON with `featureIntro`, `featureDetails`, `operationGuide`, `faq` fields)
2. `AgentService.learnDocument()` → images analyzed via `ImageUnderstandingService` → structured knowledge extracted via `KnowledgeExtractService`
3. Content is chunked and embedded via `DashScopeService` (1024-dim vectors)
4. Chunks stored in Milvus with fields: `chunk_id`, `doc_id`, `chunk_type`, `feature_name`, `content`, `vector`

### Data Flow: Chat with Function Calling
1. User sends message to `POST /api/agent/chat/stream` (SSE response)
2. Query embedded → semantic search in Milvus → top-k chunks retrieved
3. Retrieved context + conversation history + tool schemas (`TicketToolService.TOOLS_SCHEMA` — 5 tools) sent to `qwen-plus`
4. If model returns a tool call, `AgentService` dispatches to the appropriate tool service and feeds the result back for a second LLM pass
5. Final answer streamed back to client via SSE

### Authentication
`AuthInterceptor` validates a Base64 token (`userId:username:role`) on all requests except `/api/auth/login`. Admin role required for `/api/document/**`, `/api/file/**`, `/api/agent/learn/**`.

### Key External Services
- **MySQL** – user accounts, documents, sessions, messages, video metadata
- **Redis** – session/token caching
- **Milvus** – vector storage for semantic search
- **MinIO** – object storage (bucket: `agent-demo`)
- **DashScope** – Alibaba LLM API (Qwen family models)
- **Ticket System** – external HTTP service at `ticket-system.base-url` (default `http://localhost:8081`), authenticated via `X-Api-Key` header; must be running separately for ticket tools to work

## Key API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/login` | Authenticate, returns Base64 token |
| POST | `/api/agent/chat/stream` | SSE chat stream |
| POST | `/api/agent/learn/{docId}` | Trigger document vectorization (admin) |
| POST | `/api/agent/learn/video/{featureId}` | Trigger async video learning (admin) |
| POST | `/api/agent/feedback` | Submit message feedback |
| GET | `/api/agent/export/{sessionId}` | Export session as Markdown |
| POST | `/api/agent/chat/regenerate/{messageId}` | Regenerate a specific assistant message |
| POST | `/api/file/upload` | Upload file to MinIO (admin) |
| POST | `/api/video/upload` | Upload video (admin) |
| GET/POST | `/api/faq/**` | FAQ document CRUD |
| GET/POST | `/api/session/**` | Chat session management |

API docs (Knife4j) available at `http://localhost:9999/doc.html` in addition to `/swagger-ui.html`.
