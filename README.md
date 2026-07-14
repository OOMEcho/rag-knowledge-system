<div align="center">

# rag-knowledge-system

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/technologies/downloads/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.8-6db33f.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://github.com/pgvector/pgvector)
[![Ollama](https://img.shields.io/badge/Ollama-qwen3-black.svg)](https://ollama.com/)
[![License](https://img.shields.io/badge/License-Apache--2.0-green.svg)](LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/OOMEcho/rag-knowledge-system)

**中文** | [English](README.en.md)

</div>

## 🌟 项目介绍

rag-knowledge-system 是一个完全本地化运行的 RAG（Retrieval-Augmented Generation）知识库系统，采用前后端分离架构设计。系统覆盖从文档上传、异步入库（解析/切分/向量化）到混合检索、多轮问答、流式输出的完整链路，大模型与向量库全部使用本地组件（Ollama + pgvector），一台 Mac 即可离线运行，不依赖任何云服务。

### ✨ 核心特性

- 📚 **知识库管理**: 知识库 CRUD，活跃名称唯一，删除保护（非空知识库不可删）
- 📄 **异步文档入库**: 上传（SHA-256 去重、类型白名单、大小限制）→ Tika 解析 → TokenTextSplitter 切分 → Ollama embedding → pgvector 写入，状态机全程可轮询
- 🔀 **混合检索**: 向量语义通道（pgvector HNSW）+ 关键词通道（pg_trgm，中英文均有效），Reciprocal Rank Fusion 融合排序
- 💬 **多轮对话**: 无状态 `history` 传递，LLM 自动把追问改写为独立检索问题，改写失败回退原问题
- ⚡ **SSE 流式输出**: 引用先行、回答逐字推送，错误统一转 error 事件
- 🔖 **引用溯源**: 每次回答附带引用来源（文档名/分块序号/融合分数）
- 🛡️ **安全基线**: 可选 API Key 认证、`prod` profile 生产锁定、bucket4j 限流、CORS 白名单
- 🔁 **故障自愈**: 失败重试、服务重启自动恢复中断任务、孤儿向量/文件定时对账清理
- 🔍 **请求追踪**: `X-Request-Id` 全链路日志追踪、Ollama 健康检查
- 🧪 **质量回归**: Testcontainers + 真实 Ollama 的检索质量评估测试，评估用例 JSON 可扩展

## 🔗 相关项目

- 点击跳转[前端仓库](https://github.com/OOMEcho/rag-knowledge-web)（Vue 3 + Element Plus）

## 🏗️ 技术架构

### 后端技术栈

| 技术 | 版本 | 描述 |
|------|------|------|
| Spring Boot | 3.5.13 | 基础框架 |
| Spring AI | 1.1.8 | Ollama chat/embedding + PgVectorStore |
| Spring Security | 6.x | API Key 认证 |
| PostgreSQL + pgvector | 17 / 0.8 | 业务元数据 + 向量存储 |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| Flyway | - | 数据库迁移 |
| bucket4j | 8.10 | 令牌桶限流 |
| Apache Tika | - | 多格式文档解析 |
| springdoc-openapi | - | API 文档 |
| Testcontainers | - | 集成测试 |

### 模型依赖

| 用途 | 默认模型 | 说明 |
|------|----------|------|
| Chat | `qwen3.6:35b` | 回答生成、追问改写 |
| Embedding | `qwen3-embedding:0.6b` | 1024 维；pgvector HNSW 索引上限 2000 维，故不用 4096 维的 8b 版本 |

## 🚀 快速开始

### 环境要求

- ☕ JDK 17+
- 🔨 Maven 3.9+
- 🐳 Docker / OrbStack
- 🦙 Ollama

### 本地开发

1. **克隆项目**

```bash
git clone https://github.com/OOMEcho/rag-knowledge-system.git
cd rag-knowledge-system
```

2. **安装 Ollama 模型**

```bash
ollama pull qwen3.6:35b
ollama pull qwen3-embedding:0.6b
```

3. **初始化数据库**（二选一）

```bash
# 方式一：本机已有带 pgvector 的 PostgreSQL 容器（如 OrbStack 中的 postgres17）
bash scripts/init-local-db.sh
# 容器名/密码可覆盖：PG_CONTAINER=postgres17 RAG_DB_PASSWORD=xxx bash scripts/init-local-db.sh

# 方式二：使用项目自带 Compose
docker compose up -d postgres
```

4. **启动应用**

```bash
mvn spring-boot:run
```

启动时 Flyway 自动建业务表，Spring AI 自动创建 `rag_vector_store` 向量表（1024 维 + HNSW 索引）。

5. **访问应用**

- 健康检查: http://localhost:8080/actuator/health
- Ollama 健康: http://localhost:8080/actuator/health/ollama
- API 文档: http://localhost:8080/swagger-ui.html

6. **启动前端界面**（可选）

```bash
git clone https://github.com/OOMEcho/rag-knowledge-web.git
cd rag-knowledge-web
npm install && npm run dev   # 打开 http://localhost:5173
```

7. **端到端冒烟测试**

```bash
bash scripts/local-smoke-test.sh   # 建库 → 上传 → 入库 → 检索问答 全流程
```

### Docker 部署

```bash
docker compose --profile app up --build
```

macOS 容器内访问宿主机 Ollama 默认走 `http://host.docker.internal:11434`。

## ⚙️ 配置说明

默认配置在 `src/main/resources/application.yml`，全部环境变量见 [.env.example](.env.example)。常用项：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5432/rag_knowledge` | 数据库连接 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 地址 |
| `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBEDDING_MODEL` | `qwen3.6:35b` / `qwen3-embedding:0.6b` | 模型（换 embedding 模型必须同步改 `RAG_VECTOR_DIMENSIONS` 并重建向量表） |
| `RAG_DEFAULT_TOP_K` / `RAG_MAX_TOP_K` | `5` / `20` | 检索条数 |
| `RAG_DEFAULT_SIMILARITY_THRESHOLD` | `0.65` | 向量相似度阈值 |
| `RAG_HYBRID_ENABLED` | `true` | 关闭则退化为纯向量检索 |
| `RAG_INGESTION_*` | 见 .env.example | 切分参数、文件类型白名单、入库工作线程数 |
| `RAG_UPLOAD_MAX_FILE_SIZE` | `50MB` | 上传大小限制 |
| `RAG_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 跨域白名单，前后端分离部署时配置前端域名（逗号分隔） |
| `RAG_SECURITY_ENABLED` / `RAG_API_KEYS` | `false` / 空 | API Key 认证 |
| `RAG_RATE_LIMIT_RPM` / `RAG_RATE_LIMIT_RAG_RPM` | `120` / `30` | 每分钟限流（普通 / RAG 接口） |
| `HTTP_CLIENT_READ_TIMEOUT` | `300s` | Ollama 调用超时（本地大模型生成较慢） |

## 📦 功能模块

### 🎯 核心模块

| 模块 | 功能描述 |
|------|----------|
| **知识库管理** | 知识库增删改查、名称唯一约束、删除保护 |
| **文档管理** | 上传去重、类型白名单、异步入库状态机、失败重试、分块查询 |
| **RAG 检索** | 向量 + 关键词双通道召回、RRF 融合、知识库隔离 |
| **RAG 问答** | 带引用回答、多轮追问改写、SSE 流式输出 |
| **安全防护** | API Key 认证、限流、prod profile 生产锁定、CORS |
| **运维自愈** | 重启恢复中断任务、孤儿向量/文件定时对账 |
| **可观测性** | 请求追踪、Ollama 健康检查 |

### 🔄 RAG Pipeline

1. 上传文档：SHA-256 去重、扩展名白名单、大小限制，原始文件落地本地存储
2. 异步入库：原子状态抢占防并发重入 → Tika 解析 → `TokenTextSplitter` 切分 → chunk 元数据入库 → Ollama embedding → pgvector 写入；失败自动清理中间数据并记录脱敏后的失败原因
3. 检索：向量通道（COSINE 相似度，按 `knowledgeBaseId` 过滤）+ 关键词通道（pg_trgm `word_similarity`）双路召回，RRF（k=60）融合取 topK
4. 多轮时先改写追问为独立问题（改写失败回退原问题）
5. 构造含对话历史与引用上下文的 Prompt，调用 chat 模型生成带引用编号的回答

### 🔒 安全特性

- **API Key 认证**: `RAG_SECURITY_ENABLED=true` + `RAG_API_KEYS=key1,key2`（逗号分隔，支持轮换），请求头 `X-API-Key` 提交；开启但未配置 Key 时启动直接失败；`/actuator/health/**` 始终匿名供探针使用
- **限流**: 内存令牌桶，按 API Key 或来源 IP 计数；RAG 接口触发大模型调用，配额更严；超限返回统一格式 429
- **失败原因脱敏**: 完整异常只进服务日志，API 只返回可展示的业务消息

## 🏛️ 系统架构

### 包结构

```
com.ragknowledge/
├── RagKnowledgeSystemApplication.java   # 应用启动类
├── common/                              # 统一响应、错误码、全局异常处理
├── config/                              # 配置类
│   ├── ingestion/                       # 入库线程池与参数
│   ├── maintenance/                     # 孤儿清理任务配置
│   ├── mybatis/                         # MyBatis-Plus 配置
│   ├── openapi/                         # OpenAPI + API Key 安全方案
│   ├── rag/                             # 检索参数（topK/阈值/RRF）
│   ├── security/                        # Spring Security、CORS、限流属性
│   └── storage/                         # 本地存储配置
├── knowledge/                           # 知识库元数据管理
├── document/                            # 文档上传、解析、切分、异步入库流水线
├── rag/                                 # 混合检索、Prompt 构建、问答与流式接口
│   └── retriever/                       # HybridChunkRetriever（RRF 融合）
├── maintenance/                         # 孤儿向量/文件定时对账清理
├── infrastructure/storage/              # 本地文件存储适配（预留对象存储接口）
├── observability/                       # 请求追踪、Ollama 健康检查
└── security/                            # API Key 过滤器、限流过滤器
```

### 数据库设计

业务表由 Flyway 管理（`src/main/resources/db/migration`），逻辑删除 + 部分唯一索引：

- `knowledge_base` - 知识库表（活跃名称唯一）
- `knowledge_document` - 文档元数据表（知识库内 checksum 唯一去重）
- `document_chunk` - 分块表（关键词通道直接检索 content）
- `rag_vector_store` - 向量表（Spring AI 启动时自动创建，1024 维 + HNSW）

知识库隔离双保险：业务表查询强制过滤 `knowledge_base_id`；向量检索强制 metadata filter `knowledgeBaseId`。

## 📡 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/knowledge-bases` | 创建知识库 |
| GET | `/api/v1/knowledge-bases` | 分页查询知识库 |
| GET/PUT/DELETE | `/api/v1/knowledge-bases/{id}` | 详情 / 更新 / 删除空知识库 |
| POST | `/api/v1/documents/upload?knowledgeBaseId=` | 上传文档 |
| GET | `/api/v1/documents` | 分页查询文档 |
| GET/DELETE | `/api/v1/documents/{id}` | 文档详情（含入库状态）/ 删除 |
| POST | `/api/v1/documents/{id}/ingest` | 触发异步入库，返回 `PARSING`，轮询详情取终态 |
| POST | `/api/v1/documents/{id}/retry-ingestion` | 重试失败入库 |
| GET | `/api/v1/documents/{id}/chunks` | 分页查询分块 |
| POST | `/api/v1/rag/search` | 混合检索，返回引用来源 |
| POST | `/api/v1/rag/chat` | RAG 问答（支持 `history` 多轮） |
| POST | `/api/v1/rag/chat/stream` | SSE 流式问答 |

### 统一响应格式

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "timestamp": "2026-01-01T12:00:00+08:00"
}
```

> 响应中的 ID 类字段（`id`、`knowledgeBaseId`、`documentId`、`chunkId`）序列化为 JSON **字符串**：雪花 ID 超出 JavaScript Number 安全整数范围，字符串形式避免浏览器端精度丢失。

### 调用示例

```bash
# 创建知识库
curl -sS -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H 'Content-Type: application/json' \
  -d '{"name":"企业制度知识库","description":"企业制度和流程文档"}'

# 上传并入库（用返回的 ID 替换）
curl -sS 'http://localhost:8080/api/v1/documents/upload?knowledgeBaseId=<kbId>' \
  -F 'file=@/path/to/policy.md;type=text/markdown'
curl -sS -X POST http://localhost:8080/api/v1/documents/<docId>/ingest
curl -sS http://localhost:8080/api/v1/documents/<docId>    # 轮询到 COMPLETED

# 问答
curl -sS -X POST http://localhost:8080/api/v1/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"<kbId>","question":"员工需要在多久内提交报销？"}'
```

多轮对话通过 `history` 传入历史（按时间升序，最多 20 条），响应中 `rewrittenQuestion` 为服务端实际用于检索的改写问题：

```bash
curl -sS -X POST http://localhost:8080/api/v1/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "knowledgeBaseId": "<kbId>",
    "question": "那超过 5000 元的呢？",
    "history": [
      {"role": "USER", "content": "员工需要在多久内提交报销？"},
      {"role": "ASSISTANT", "content": "员工需要在费用发生后的 30 天内提交报销 [1]。"}
    ]
  }'
```

SSE 流式问答，事件顺序 `rewrittenQuestion`（仅多轮）→ `references` → 若干 `delta` → `done`，失败发 `error` 事件：

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"<kbId>","question":"差旅报销有什么要求？"}'
```

## 📊 API 文档

系统集成 springdoc-openapi，提供完整的接口文档和在线测试（含 API Key Authorize 按钮）。

- **文档地址**: http://localhost:8080/swagger-ui.html （prod profile 下整体关闭）

## 🧪 测试

```bash
# 单元测试（不依赖 Docker）
mvn test

# 含集成测试：Flyway 迁移验证 + 检索质量回归（需 Docker，Ollama 不可用时质量测试自动跳过）
mvn verify -DskipITs=false
```

检索质量回归测试 `RagRetrievalQualityIT` 使用真实 pgvector 容器和本地 Ollama，按 `src/test/resources/rag/fixtures/rag-eval-cases.json` 的评估用例验证混合检索召回，新增用例直接扩展该 JSON 文件。

## 📚 部署

### 生产环境部署

生产必须激活 `prod` profile，它强制执行安全基线：开启 API Key 认证（未配置 Key 启动失败）、关闭 Swagger、健康检查隐藏细节、actuator 仅暴露 `health`。

```bash
mvn clean package -DskipTests
SPRING_PROFILES_ACTIVE=prod RAG_API_KEYS=<强随机Key> java -jar target/ragknowledge-0.1.0-SNAPSHOT.jar
```

### 运维要点

- **入库失败恢复**: 状态 `FAILED` + 脱敏 `failure_reason`，`retry-ingestion` 重试；服务重启自动把中断任务置为 `FAILED` 供重试
- **孤儿数据对账**: 定时任务（默认每天 03:00）清理无主向量和上传文件，`RAG_ORPHAN_CLEANUP_*` 可配
- **数据库迁移**: 表结构变更必须新增 Flyway migration，不修改已发布脚本

## 📄 许可证

本项目基于 [Apache-2.0](LICENSE) 许可证开源。

## ❓ 常见问题

<details>
<summary>如何更换 embedding 模型？</summary>

修改 `OLLAMA_EMBEDDING_MODEL` 的同时必须把 `RAG_VECTOR_DIMENSIONS` 改为新模型的输出维度，并删除旧向量表（`DROP TABLE rag_vector_store`）后重启让 Spring AI 重建。注意 pgvector 的 HNSW 索引最多支持 2000 维。已入库的文档需要重新入库。
</details>

<details>
<summary>如何开启 API Key 认证？</summary>

设置 `RAG_SECURITY_ENABLED=true` 和 `RAG_API_KEYS=<key1>,<key2>`，调用方通过 `X-API-Key` 请求头提交。生产环境直接使用 `SPRING_PROFILES_ACTIVE=prod`（强制开启认证）。
</details>

<details>
<summary>为什么 API 返回的 ID 是字符串？</summary>

系统使用 19 位雪花 ID，超出 JavaScript Number 的安全整数范围（2^53），以数字返回会在浏览器端丢失精度，因此所有 ID 字段统一序列化为字符串。
</details>

<details>
<summary>Ollama 调用超时怎么办？</summary>

本地大模型生成较慢，默认 `HTTP_CLIENT_READ_TIMEOUT=300s`。如果 35b 模型在你的机器上更慢，调大该值；也可以换更小的 chat 模型（`OLLAMA_CHAT_MODEL`）。
</details>

<details>
<summary>如何关闭混合检索只用向量？</summary>

设置 `RAG_HYBRID_ENABLED=false`，检索退化为纯向量通道，引用的 `score` 语义从 RRF 融合分数变为余弦相似度。
</details>

<details>
<summary>前端跨域访问被拦截？</summary>

把前端域名加入 `RAG_CORS_ALLOWED_ORIGINS`（逗号分隔多个）。本地开发默认已放行 `http://localhost:5173`。
</details>

## 📞 支持

如果您在使用过程中遇到问题，可以通过以下方式寻求帮助：

- 🐛 Issue: [提交Issue](https://github.com/OOMEcho/rag-knowledge-system/issues)
- 📖 前端项目: [rag-knowledge-web](https://github.com/OOMEcho/rag-knowledge-web)

---

<div align="center">

**如果这个项目对您有帮助，请给它一个 ⭐ Star！**

Made with ❤️ by [OOMEcho](https://github.com/OOMEcho)

</div>
