# rag-knowledge-system 协作指南

本文件定义项目方向和代理/贡献者的工作规则。
用户专属的编码规则统一放在「用户自定义规则」章节。

## 项目目标

构建企业级的检索增强生成（RAG）知识库系统，而不是玩具级 Demo。

系统提供知识库管理、文档入库、向量索引、检索和 RAG 问答的后端 API，计划在 GitHub 开源。

## 已确认的项目元数据

- 项目目录：`rag-knowledge-system`
- Maven groupId：`com`
- Maven artifactId：`ragknowledge`
- 基础包名：`com.ragknowledge`
- Java 版本：JDK 17
- 许可证：Apache-2.0
- 前端：独立项目 `rag-knowledge-web`（Vue 3，仓库地址 https://github.com/OOMEcho/rag-knowledge-web ），本仓库只做后端

## 已确认的技术栈

- 框架：Spring Boot 3.x
- 构建工具：Maven
- AI 框架：Spring AI 1.1.x 稳定版本线
- 本地大模型运行时：Ollama
- Chat 模型：`qwen3.6:35b`
- Embedding 模型：`qwen3-embedding:0.6b`（输出 1024 维；pgvector HNSW 索引上限 2000 维，禁止换回 4096 维的 8b 版本）
- 数据库：PostgreSQL
- 向量数据库：PostgreSQL + pgvector
- ORM：MyBatis-Plus，不使用 JPA
- API 文档：springdoc-openapi / Swagger UI
- 数据库迁移：业务表结构统一由 Flyway 管理
- 可观测性：Spring Boot Actuator + Micrometer
- 测试：JUnit 5、Spring Boot Test，必要时使用 Testcontainers
- 容器化：本地基础设施使用 Docker Compose

## 初始版本明确不做的事

- 未经明确要求，不在本仓库内实现前端（前端在独立项目中）。
- 模块化单体稳定之前，不引入微服务。
- 第一阶段不做 Kubernetes 部署。
- 第一阶段不做 RBAC、SSO 或多租户计费。
- 不得硬编码本地绝对路径、模型主机名或任何凭证。

## 架构原则

- 优先采用模块化单体，模块边界清晰到未来可拆分。
- 优先实现小而生产化的功能，拒绝 Demo 式捷径。
- RAG 管道必须可观测、可排障。
- 业务元数据与向量记录分开持久化。
- 每个生成的回答都必须能返回引用来源。
- 知识库隔离必须在业务查询和向量检索 metadata filter 两层同时强制执行。
- 配置必须通过 Spring 配置和环境变量外置。
- 公开 API 统一挂在 `/api/v1` 下并版本化。
- 数据库变更必须通过迁移脚本可复现。
- 命名必须使用清晰的领域词汇，无特殊理由禁止 `Manager`、`Util`、`DataHandler` 这类含糊命名。

## 建议的模块布局

使用基础包 `com.ragknowledge`。

```text
com.ragknowledge
  common          # 统一响应、错误码、全局异常
    exception
    response
  config          # 配置类与 @ConfigurationProperties（按领域分子包）
  knowledge       # 知识库域
    controller
    service       # 只放服务接口，impl 子包放实现类
    mapper        # 只放 Mapper 接口
    entity        # 表实体、状态枚举、持久层查询投影
    dto           # API 请求/响应模型
  document        # 文档域（结构同上）
  rag             # RAG 域
    controller
    service
    retriever     # 检索器组件及其返回模型
    dto
  maintenance     # 后台自愈任务（启动恢复、孤儿数据对账）
  infrastructure  # 基础设施适配
    storage
  observability   # 请求追踪、健康检查
  security        # 运行时安全组件（认证过滤器、限流过滤器）
```

分包规则：`entity`/`dto`/`mapper`/`service` 是角色包，只放对应角色的类型（查询投影归 entity，具体 `@Service` 归 `service/impl`）；`retriever`/`storage` 这类组件包可以携带自己的返回模型 record。

## 核心领域模型

围绕以下业务概念展开：

- `knowledge_base`：知识库元数据与生命周期状态
- `knowledge_document`：上传文档元数据、checksum、解析状态与索引状态
- `document_chunk`：解析后的分块、分块元数据、token 估算与引用信息

向量记录通过 Spring AI `PgVectorStore` 存入 pgvector。每条向量文档必须携带足以回溯业务表的元数据：

- `knowledgeBaseId`
- `documentId`
- `documentName`
- `chunkId`
- `chunkIndex`
- `sourceType`

## RAG 管道

目标管道：

```text
上传文档
  -> 校验文件（类型白名单、大小限制）
  -> 计算 checksum 去重
  -> 异步入库：解析内容（Tika）
  -> 切分为 chunk（TokenTextSplitter）
  -> 持久化文档与 chunk 元数据
  -> Ollama `qwen3-embedding:0.6b` 生成 embedding
  -> 向量写入 PostgreSQL pgvector
  -> 接收用户问题（多轮时先由大模型改写追问为独立问题）
  -> 混合检索 topK：向量通道（按 knowledgeBaseId 过滤）+ 关键词通道（pg_trgm），RRF 融合
  -> 构造含上下文与安全指令的 Prompt
  -> 调用 Ollama `qwen3.6:35b`
  -> 返回回答与引用来源（支持 SSE 流式）
```

## RAG 质量规则

- 除非明确设计了跨知识库 API，检索必须始终按 `knowledgeBaseId` 限定范围。
- 回答必须携带引用来源。
- 检索上下文不足时，模型应明确表示无法基于当前知识库回答。
- `topK`、`similarityThreshold` 和 Prompt 模板必须可配置。
- 默认不得在一个回答中混合不相关的知识库。
- chunk 元数据必须足以排查检索质量问题。

## 数据库策略

- 业务表使用 MyBatis-Plus。
- 初始架构使用 PostgreSQL 作为唯一应用数据库。
- embedding 存储与相似度检索使用 pgvector。
- 业务表和扩展优先使用 Flyway 迁移。
- Spring AI 的 pgvector 建表初始化仅在与迁移不冲突时用于本地开发。
- 类生产环境的表结构不得依赖文档化的 Docker Compose 或迁移命令之外的手工建库步骤。

## MyBatis-Plus 规则

- 使用 `mybatis-plus-spring-boot3-starter`。
- `@MapperScan` 配置为 `com.ragknowledge.**.mapper` 或具体 mapper 包。
- 配置 `MybatisPlusInterceptor`。
- 添加 `PaginationInnerInterceptor` 并指定 `DbType.POSTGRE_SQL`。
- 只有实体确实需要并发更新保护时才使用乐观锁。
- 实体类只保留持久化字段，API 请求/响应一律使用 DTO。

## API 策略

- REST API 统一挂在 `/api/v1` 下。
- 公开 Controller 使用 springdoc-openapi 注解。
- Swagger UI 位于 `/swagger-ui.html`，OpenAPI JSON 位于 `/v3/api-docs`。
- 请求校验使用 Bean Validation。
- 业务 API 使用统一响应格式（`ApiResponse`）。
- 校验错误、未找到、冲突和基础设施错误统一由全局异常处理器处理。
- 响应中的 ID 类字段（雪花 ID）必须序列化为 JSON 字符串，防止 JS Number 精度丢失。

当前接口清单：

```text
POST   /api/v1/knowledge-bases
GET    /api/v1/knowledge-bases
GET    /api/v1/knowledge-bases/{id}
PUT    /api/v1/knowledge-bases/{id}
DELETE /api/v1/knowledge-bases/{id}

POST   /api/v1/documents/upload
GET    /api/v1/documents
GET    /api/v1/documents/{id}
POST   /api/v1/documents/{id}/ingest
POST   /api/v1/documents/{id}/retry-ingestion
GET    /api/v1/documents/{id}/chunks
DELETE /api/v1/documents/{id}

POST   /api/v1/rag/search
POST   /api/v1/rag/chat
POST   /api/v1/rag/chat/stream

GET    /actuator/health
GET    /swagger-ui.html
```

## 配置策略

模型名称和基础设施地址必须可配置。

本地默认配置目标：

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen3.6:35b
      embedding:
        options:
          model: qwen3-embedding:0.6b
    vectorstore:
      pgvector:
        dimensions: 1024
        distance-type: COSINE_DISTANCE
        index-type: HNSW
```

默认不开启模型自动拉取，本机已安装所需的 Ollama 模型。

## 开源要求

仓库需按公开 GitHub 项目标准准备。

首次公开发布前必须具备的项目文件：

- `README.md`（中文，作为唯一综合文档：架构、快速开始、API、RAG Pipeline、安全与运维）
- `README.en.md`（英文版，与中文版内容同步）
- `LICENSE`
- `.gitignore`
- `.env.example`
- `docker-compose.yml`
- `SECURITY.md`
- 构建与测试的 GitHub Actions workflow

文档有意收敛在双语 README 中；除非项目所有者再次要求，不得重建独立的 `docs/` 目录、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md` 或 `CHANGELOG.md`。公开 API 或行为变更必须同步到两份 README。

## 版本路线图

### v0.1 基础骨架

- 创建 JDK 17 + Maven 的 Spring Boot 3 项目。
- 引入 Spring AI、Ollama、pgvector、MyBatis-Plus、springdoc-openapi、Actuator、Flyway 和校验依赖。
- 添加 PostgreSQL + pgvector 的 Docker Compose。
- 添加基础配置文件和 `.env.example`。
- 添加全局响应与异常处理。
- 添加 OpenAPI 配置。
- 添加 MyBatis-Plus 配置。

### v0.2 知识库与文档元数据

- 知识库 CRUD API。
- 文档元数据持久化。
- 文档上传 API。
- 基于 checksum 的重复检测。
- 文档状态生命周期。

### v0.3 文档入库与向量化

- 文本、Markdown、PDF 等格式解析。
- chunk 切分策略。
- chunk 元数据持久化。
- 生成 embedding 并写入 pgvector。
- 业务记录与向量记录的删除一致性。

### v0.4 RAG 检索与问答

- 向量检索 API。
- RAG 问答 API。
- 按 `knowledgeBaseId` 过滤检索。
- 构造有据可依的 Prompt 模板。
- 返回回答引用。

### v0.5 企业级加固

- 结构化日志与请求追踪基础。
- 数据库与 Ollama 健康检查。
- 入库失败恢复行为。
- Testcontainers 集成测试。
- RAG 检索质量测试夹具。

### v1.0 开源发布

- 完善 README 文档。
- 确认 Apache-2.0 许可证就位。
- 添加安全策略。
- 添加 CI 构建与测试 workflow。
- 验证全新 clone 后可干净启动。

> 以上路线图已全部完成，并额外完成：异步入库、API Key 认证与限流安全基线、混合检索（RRF）、多轮对话、SSE 流式、CORS 支持与独立 Vue 前端。后续待做事项见项目所有者安排（P4 运维项）。

## 实施顺序

新功能实施时遵循以下顺序，除非用户调整优先级：

1. 项目骨架与构建配置。
2. 基础设施配置：PostgreSQL、pgvector、Ollama、OpenAPI、Actuator。
3. 统一响应与异常处理。
4. MyBatis-Plus 配置与迁移。
5. 知识库 API。
6. 文档上传与元数据 API。
7. 入库管道与向量化。
8. RAG 检索与问答。
9. 测试与开源文档。

## 用户自定义规则

### 沟通规则

- 默认使用中文回答、汇报进展和总结验证结果。
- 只有当用户明确要求其他语言时，才切换到对应语言。

### 执行原则

1. 编码前先思考，不凭空假设。遇到需求边界、业务口径、数据来源、事务边界或实现路径不明确时，必须说明当前假设、可选解释与权衡；若不澄清会影响正确性，先停下来询问用户。
2. 简洁优先，用最少必要代码解决问题。禁止过度设计、额外功能、为一次性逻辑提前抽象；只有在不扩大影响范围、不降低可读性且能明确对应需求时，才允许压缩或重写冗余实现。
3. 精准修改，只改与请求直接相关的内容。不得改动无关代码、注释、格式或命名；不得借机重构未出问题的代码；只清理自己本次改动造成的混乱，每一行修改都应能追溯到用户请求。
4. 目标驱动执行，先把指令转为可验证的成功标准，再按“计划 -> 执行 -> 验证 -> 必要时修正”的循环推进。完成前必须执行受影响范围的测试、编译或检查，并说明验证结果。

### 文档与注释规则

- 所有类、接口、record 和枚举必须有类级 Javadoc：一句话中文职责描述（可带补充说明），加 `@author xuesong.lei` 和 `@since yyyy-MM-dd`（类的创建日期）。Javadoc 位于 import 之下、类注解之上。描述必须体现该类的实际职责，禁止模板套话。
- Service 接口方法必须写 Javadoc，说明方法目的、参数、返回值、可能异常和副作用。
- 实现类方法如果已经实现了带完整 Javadoc 的接口方法，不重复编写方法级 Javadoc。
- 私有方法和 helper 方法必须添加方法级注释。
- Controller 方法不添加方法级注释，依靠路由注解和方法命名表达接口语义。
- 跨模块调用、复杂分支、关键业务规则、魔法值、事务边界、非直观逻辑和非显而易见的执行顺序，必须添加行内注释。
- 注释应解释代码存在的原因、业务语义和维护风险，禁止只复述代码字面含义。
- 方法内部凡是调用其他方法，必须在调用语句附近添加行内注释，说明该调用在做什么以及为什么需要调用。允许注释偏多，但必须服务于理解和维护。
- 条件分支，包括 `if/else`、`switch`、三元表达式和复杂布尔条件，必须添加注释说明判断依据、业务含义和不同分支的处理意图，禁止只让读者从条件表达式反推业务规则。
- 涉及业务口径、事务边界、跨库调用、跨模块调用、查询条件、批量写入、ID 预生成、DTO/VO/Entity 转换、异常处理、默认值兜底或非显而易见的执行顺序时，必须添加注释说明依据、意图和不可随意改动的原因。
- 注释可以偏多，但必须优先解释业务含义、设计取舍、风险边界和不可随意修改的原因，禁止堆砌低价值注释。

### 代码风格规则

- 命名必须语义化，类名、方法名、变量名、DTO 和 Entity 应体现明确业务含义。
- 方法职责必须单一，过长方法或深层嵌套逻辑必须拆分，前提是不扩大需求范围。
- 魔法值必须提取为常量，或通过注释说明来源和依据。
- 代码是写给人看的，可读性优先于炫技式压缩。

### 既有代码修改规则

- 修改既有代码前，必须先阅读相关上下文。
- 修改前必须确认调用链、数据口径、事务边界和既有注释含义。
- 不得只根据局部代码形态重构、改名、删除注释或调整格式。
- 不得在实现请求时顺手修改无关代码。
