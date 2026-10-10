package com.ragknowledge.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.config.rag.RagRetrievalProperties;
import com.ragknowledge.document.entity.DocumentChunkEntity;
import com.ragknowledge.document.mapper.DocumentChunkMapper;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.rag.dto.RagChatMessage;
import com.ragknowledge.rag.dto.RagChatRequest;
import com.ragknowledge.rag.dto.RagChatResponse;
import com.ragknowledge.rag.dto.RagReferenceResponse;
import com.ragknowledge.rag.dto.RagSearchRequest;
import com.ragknowledge.rag.dto.RagSearchResponse;
import com.ragknowledge.rag.retriever.HybridChunkRetriever;
import com.ragknowledge.rag.retriever.RetrievedChunk;
import com.ragknowledge.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 服务实现：混合检索、追问改写、Prompt 构造与回答生成
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class RagServiceImpl implements RagService {

    private static final Logger log = LoggerFactory.getLogger(RagServiceImpl.class);
    private static final boolean NOT_DELETED = false;
    private static final String NO_CONTEXT_ANSWER = "当前知识库中没有检索到足够相关的内容，无法基于已有知识回答该问题。";
    private static final String SYSTEM_PROMPT = "你是企业知识库 RAG 助手。必须只基于提供的上下文回答。"
            + "如果上下文不足以回答问题，直接说明无法基于当前知识库回答。"
            + "回答要准确、简洁，并在关键结论后标注引用编号，例如 [1]、[2]。";
    private static final String REWRITE_SYSTEM_PROMPT = "你是查询改写助手。根据对话历史，把用户的最新问题改写成一个"
            + "独立完整、不依赖上下文就能理解的检索问题。只输出改写后的问题本身，不要输出任何解释或前缀。";
    // 参与 Prompt 和改写的历史消息上限，避免长对话撑大上下文并拖慢本地模型。
    private static final int MAX_PROMPT_HISTORY_MESSAGES = 10;
    // 改写结果长度上限，超长输出说明模型没有按要求只输出问题，回退原始问题更安全。
    private static final int MAX_REWRITTEN_QUESTION_LENGTH = 512;
    private static final String SSE_EVENT_REWRITTEN_QUESTION = "rewrittenQuestion";
    private static final String SSE_EVENT_REFERENCES = "references";
    private static final String SSE_EVENT_DELTA = "delta";
    private static final String SSE_EVENT_DONE = "done";
    private static final String SSE_EVENT_ERROR = "error";

    private final ChatClient chatClient;
    private final HybridChunkRetriever hybridChunkRetriever;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final RagRetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;

    public RagServiceImpl(ChatModel chatModel,
                          HybridChunkRetriever hybridChunkRetriever,
                          KnowledgeBaseMapper knowledgeBaseMapper,
                          DocumentChunkMapper documentChunkMapper,
                          RagRetrievalProperties retrievalProperties,
                          ObjectMapper objectMapper) {
        // ChatClient 由 ChatModel 构建，模型名称和 Ollama 地址继续由 application.yml 管理。
        this.chatClient = ChatClient.builder(chatModel).build();
        this.hybridChunkRetriever = hybridChunkRetriever;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
    }

    // 注意：检索和问答方法不能加 @Transactional。方法内部的 embedding 和大模型调用可能耗时数分钟，
    // 事务会让整个调用期间持有一个 Hikari 连接，几个并发问答就会耗尽连接池；
    // 方法内的数据库操作都是独立单条查询，不需要事务一致性保证。
    @Override
    public RagSearchResponse search(RagSearchRequest request) {
        // 检索前校验知识库存在，避免对不存在的 knowledgeBaseId 执行无意义 embedding 和向量查询。
        ensureKnowledgeBaseExists(request.knowledgeBaseId());
        // topK 统一由服务层兜底和限流，避免调用方传入过大值拖垮向量检索。
        int topK = resolveTopK(request.topK());
        // 相似度阈值统一由服务层兜底，保证所有 RAG 入口使用一致检索口径。
        double similarityThreshold = resolveSimilarityThreshold(request.similarityThreshold());
        // 混合检索并转换为业务引用，chunk 表过滤逻辑保证已删除数据不进入结果。
        List<RagReferenceResponse> references = retrieveReferences(request.knowledgeBaseId(), request.question(), topK, similarityThreshold);

        // 返回实际检索参数，便于调用方排查默认值、阈值和 topK 对结果的影响。
        return new RagSearchResponse(request.knowledgeBaseId(), request.question(), topK, similarityThreshold, references);
    }

    @Override
    public RagChatResponse chat(RagChatRequest request) {
        // 问答与检索共用知识库校验和参数兜底，保证两个入口检索口径一致。
        ensureKnowledgeBaseExists(request.knowledgeBaseId());
        int topK = resolveTopK(request.topK());
        double similarityThreshold = resolveSimilarityThreshold(request.similarityThreshold());
        // 有对话历史时先做查询改写，把依赖上下文的追问改写成独立检索问题。
        String rewrittenQuestion = rewriteQuestionIfNeeded(request.history(), request.question());
        // 检索使用改写后问题（若有），生成回答仍使用用户原始问题，保持答案口吻贴近用户表述。
        String retrievalQuery = rewrittenQuestion != null ? rewrittenQuestion : request.question();
        List<RagReferenceResponse> references = retrieveReferences(request.knowledgeBaseId(), retrievalQuery, topK, similarityThreshold);

        // 没有引用时不调用大模型，避免模型在无上下文情况下自由发挥产生幻觉。
        if (references.isEmpty()) {
            return new RagChatResponse(request.knowledgeBaseId(), request.question(), rewrittenQuestion, NO_CONTEXT_ANSWER, references);
        }

        // 调用大模型前先构造受控 Prompt，确保模型只基于检索上下文回答。
        String answer = callChatModel(request.question(), request.history(), references);
        // 返回模型回答和同一批引用来源，便于调用方展示答案依据。
        return new RagChatResponse(request.knowledgeBaseId(), request.question(), rewrittenQuestion, answer, references);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(RagChatRequest request) {
        // defer 把校验、改写和检索全部纳入流内执行：SSE 响应头一旦写出就无法再返回普通 JSON 错误，
        // 因此所有异常都要转成 error 事件，让客户端在同一协议内处理失败。
        return Flux.defer(() -> {
            ensureKnowledgeBaseExists(request.knowledgeBaseId());
            int topK = resolveTopK(request.topK());
            double similarityThreshold = resolveSimilarityThreshold(request.similarityThreshold());
            // 与同步问答一致：有历史时先改写，检索用改写问题，生成用原始问题。
            String rewrittenQuestion = rewriteQuestionIfNeeded(request.history(), request.question());
            String retrievalQuery = rewrittenQuestion != null ? rewrittenQuestion : request.question();
            List<RagReferenceResponse> references = retrieveReferences(request.knowledgeBaseId(), retrievalQuery, topK, similarityThreshold);

            // 引用事件先于回答发出，客户端可先渲染来源再逐字追加答案。
            Flux<ServerSentEvent<String>> headEvents = buildHeadEvents(rewrittenQuestion, references);

            // 无引用时不调用大模型，直接输出固定无法回答文案并结束流。
            if (references.isEmpty()) {
                return headEvents
                        .concatWith(Flux.just(sseEvent(SSE_EVENT_DELTA, NO_CONTEXT_ANSWER)))
                        .concatWith(Flux.just(sseEvent(SSE_EVENT_DONE, "")));
            }

            // stream() 返回模型增量输出，每个片段包装成 delta 事件推送。
            Flux<ServerSentEvent<String>> deltaEvents = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(toHistoryMessages(request.history()))
                    .user(buildUserPrompt(request.question(), buildReferenceContext(references)))
                    .stream()
                    .content()
                    .map(delta -> sseEvent(SSE_EVENT_DELTA, delta));

            return headEvents
                    .concatWith(deltaEvents)
                    .concatWith(Flux.just(sseEvent(SSE_EVENT_DONE, "")));
        }).onErrorResume(exception -> {
            // 业务异常消息可控可直接下发；其他异常只下发通用文案，细节进服务日志。
            String message = exception instanceof BusinessException ? exception.getMessage() : "问答处理失败，请稍后重试";
            log.error("RAG chat stream failed, knowledgeBaseId={}", request.knowledgeBaseId(), exception);
            return Flux.just(sseEvent(SSE_EVENT_ERROR, message));
        });
    }

    /**
     * 构造 SSE 头部事件：可选的改写问题事件和引用来源事件。
     *
     * @param rewrittenQuestion 改写问题，未改写时为 null
     * @param references        引用来源列表
     * @return 头部事件流
     */
    private Flux<ServerSentEvent<String>> buildHeadEvents(String rewrittenQuestion, List<RagReferenceResponse> references) {
        List<ServerSentEvent<String>> events = new ArrayList<>();

        // 只有实际发生改写时才发出该事件，客户端可展示"已根据上下文理解为..."。
        if (rewrittenQuestion != null) {
            events.add(sseEvent(SSE_EVENT_REWRITTEN_QUESTION, rewrittenQuestion));
        }

        // 引用列表序列化为 JSON 下发，保持与同步接口相同的引用结构。
        events.add(sseEvent(SSE_EVENT_REFERENCES, toJson(references)));
        return Flux.fromIterable(events);
    }

    /**
     * 构造单个 SSE 事件。
     *
     * @param event 事件名
     * @param data  事件数据
     * @return SSE 事件
     */
    private ServerSentEvent<String> sseEvent(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 引用 DTO 均为简单类型，序列化失败属于编程错误，转为运行时异常由流的错误处理兜底。
            throw new IllegalStateException("Failed to serialize SSE payload", exception);
        }
    }

    /**
     * 存在对话历史时通过大模型改写最新问题为独立检索问题。
     *
     * @param history  对话历史
     * @param question 用户最新问题
     * @return 改写后的问题；无历史或改写失败时返回 null 表示不改写
     */
    private String rewriteQuestionIfNeeded(List<RagChatMessage> history, String question) {
        // 无历史的首轮问题本身就是独立问题，跳过改写节省一次模型调用。
        if (CollectionUtils.isEmpty(history)) {
            return null;
        }

        try {
            // 用轻量 Prompt 请求模型输出独立问题，改写失败不应阻断问答主流程。
            String rewritten = chatClient.prompt()
                    .system(REWRITE_SYSTEM_PROMPT)
                    .user(buildRewritePrompt(history, question))
                    .call()
                    .content();

            // 空输出或超长输出说明模型未按约定返回，回退原始问题保证检索仍可执行。
            if (!StringUtils.hasText(rewritten) || rewritten.trim().length() > MAX_REWRITTEN_QUESTION_LENGTH) {
                return null;
            }

            return rewritten.trim();
        } catch (RuntimeException exception) {
            // 改写只是检索质量增强，失败时记录日志并回退原始问题，不影响问答可用性。
            log.warn("Question rewrite failed, fallback to original question", exception);
            return null;
        }
    }

    /**
     * 构造查询改写 Prompt。
     *
     * @param history  对话历史
     * @param question 用户最新问题
     * @return 改写 Prompt
     */
    private String buildRewritePrompt(List<RagChatMessage> history, String question) {
        StringBuilder builder = new StringBuilder("# 对话历史\n");

        // 只取最近若干条历史，避免超长对话拖慢改写调用。
        for (RagChatMessage message : truncateHistory(history)) {
            // 角色以中文标注，帮助模型区分提问和回答。
            builder.append(message.role() == RagChatMessage.Role.USER ? "用户：" : "助手：")
                    .append(message.content())
                    .append('\n');
        }

        builder.append("\n# 用户最新问题\n").append(question);
        return builder.toString();
    }

    /**
     * 将对话历史转换为 Spring AI 消息列表。
     *
     * @param history 对话历史
     * @return Spring AI 消息列表
     */
    private List<Message> toHistoryMessages(List<RagChatMessage> history) {
        // 无历史时返回空列表，ChatClient 会忽略空消息集合。
        if (CollectionUtils.isEmpty(history)) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>();

        // 历史消息按原始顺序进入 Prompt，让模型理解对话脉络后再基于上下文回答。
        for (RagChatMessage message : truncateHistory(history)) {
            // 按角色映射为 Spring AI 的用户/助手消息类型。
            messages.add(message.role() == RagChatMessage.Role.USER
                    ? new UserMessage(message.content())
                    : new AssistantMessage(message.content()));
        }

        return messages;
    }

    /**
     * 截取最近的历史消息。
     *
     * @param history 对话历史
     * @return 最多 MAX_PROMPT_HISTORY_MESSAGES 条的最近历史
     */
    private List<RagChatMessage> truncateHistory(List<RagChatMessage> history) {
        // 历史按时间升序传入，超限时保留最近的消息，早期消息对当前问题影响最小。
        if (history.size() <= MAX_PROMPT_HISTORY_MESSAGES) {
            return history;
        }

        return history.subList(history.size() - MAX_PROMPT_HISTORY_MESSAGES, history.size());
    }

    /**
     * 执行混合检索并转换为引用来源。
     *
     * @param knowledgeBaseId     知识库 ID
     * @param query               检索问题
     * @param topK                返回数量
     * @param similarityThreshold 向量通道相似度阈值
     * @return 引用来源列表
     */
    private List<RagReferenceResponse> retrieveReferences(Long knowledgeBaseId, String query, int topK, double similarityThreshold) {
        // 混合检索器负责向量与关键词双通道召回和 RRF 融合。
        List<RetrievedChunk> retrievedChunks = hybridChunkRetriever.retrieve(knowledgeBaseId, query, topK, similarityThreshold);
        // 根据 chunkId 批量查询活跃 chunk，过滤掉已删除或异常残留数据。
        Map<Long, DocumentChunkEntity> activeChunkMap = loadActiveChunkMap(knowledgeBaseId, retrievedChunks);
        List<RagReferenceResponse> references = new ArrayList<>(retrievedChunks.size());

        // 按融合排序生成引用，保证 [1]、[2] 编号与 Prompt 上下文顺序一致。
        for (RetrievedChunk retrievedChunk : retrievedChunks) {
            DocumentChunkEntity chunk = activeChunkMap.get(retrievedChunk.chunkId());

            // 跳过非活跃 chunk，避免已删除或异常残留数据进入答案上下文。
            if (chunk == null) {
                continue;
            }

            references.add(toReference(retrievedChunk, chunk));
        }

        return references;
    }

    /**
     * 批量加载活跃 chunk 映射。
     *
     * @param knowledgeBaseId 知识库 ID，必须同时约束业务查询与向量 metadata filter
     * @param retrievedChunks 检索结果
     * @return chunkId 到 chunk 实体的映射
     */
    private Map<Long, DocumentChunkEntity> loadActiveChunkMap(Long knowledgeBaseId, List<RetrievedChunk> retrievedChunks) {
        // 空结果无需访问数据库，直接返回空映射。
        if (retrievedChunks.isEmpty()) {
            return Map.of();
        }

        List<Long> chunkIds = retrievedChunks.stream().map(RetrievedChunk::chunkId).toList();
        // 两路候选都回查文档完成状态和知识库归属，半成品向量或异常 metadata 不能绕过业务边界。
        List<DocumentChunkEntity> activeChunks = documentChunkMapper.selectRetrievableChunks(knowledgeBaseId, chunkIds);
        Map<Long, DocumentChunkEntity> activeChunkMap = new HashMap<>();

        // 手动构建 map，避免异常重复数据时 Collectors.toMap 抛异常影响检索结果。
        for (DocumentChunkEntity chunk : activeChunks) {
            activeChunkMap.put(chunk.getId(), chunk);
        }

        return activeChunkMap;
    }

    /**
     * 构造单个引用来源。
     *
     * @param retrievedChunk 检索结果
     * @param chunk          活跃 chunk 实体
     * @return 引用来源 DTO
     */
    private RagReferenceResponse toReference(RetrievedChunk retrievedChunk, DocumentChunkEntity chunk) {
        // 向量通道命中的结果直接读取向量 metadata；关键词通道独有结果回落到 chunk 表的 metadata 快照。
        Map<String, Object> metadata = retrievedChunk.vectorDocument() != null
                ? retrievedChunk.vectorDocument().getMetadata()
                : parseChunkMetadata(chunk);

        // 引用内容使用业务 chunk 表中的 content，保证与业务删除状态一致。
        return new RagReferenceResponse(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getKnowledgeBaseId(),
                metadataAsString(metadata, "documentName"),
                chunk.getChunkIndex(),
                metadataAsString(metadata, "sourceType"),
                retrievedChunk.score(),
                chunk.getContent()
        );
    }

    /**
     * 解析 chunk 表中保存的 metadata 快照。
     *
     * @param chunk chunk 实体
     * @return metadata 映射，解析失败时返回空映射
     */
    private Map<String, Object> parseChunkMetadata(DocumentChunkEntity chunk) {
        // metadataJson 为空的历史数据无法提供展示字段，返回空映射由展示层兜底。
        if (!StringUtils.hasText(chunk.getMetadataJson())) {
            return Map.of();
        }

        try {
            // metadataJson 是入库时的 metadata 快照，与向量 metadata 同源，可直接用于引用展示。
            return objectMapper.readValue(chunk.getMetadataJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            // 快照解析失败只影响 documentName 等展示字段，不应中断检索，返回空映射兜底。
            log.warn("Failed to parse chunk metadata snapshot, chunkId={}", chunk.getId(), exception);
            return Map.of();
        }
    }

    /**
     * 校验知识库存在且未删除。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void ensureKnowledgeBaseExists(Long knowledgeBaseId) {
        // RAG 检索必须绑定真实知识库，避免不存在的 ID 触发无意义向量查询和模型调用。
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, knowledgeBaseId)
                .eq(KnowledgeBaseEntity::getDeleted, NOT_DELETED));

        // 未找到知识库时返回 404，明确提示调用方知识库边界无效。
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识库不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 解析 topK 参数。
     *
     * @param requestedTopK 请求 topK
     * @return 安全 topK
     */
    private int resolveTopK(Integer requestedTopK) {
        // 未传 topK 时使用系统默认值，保持 API 简单同时允许运维统一调优。
        int topK = requestedTopK == null ? retrievalProperties.getDefaultTopK() : requestedTopK;

        // topK 小于 1 没有检索意义，回退默认值避免检索参数异常。
        if (topK < 1) {
            topK = retrievalProperties.getDefaultTopK();
        }

        // topK 超过系统上限时强制收敛，避免一次请求带来过高检索和 Prompt 成本。
        if (topK > retrievalProperties.getMaxTopK()) {
            topK = retrievalProperties.getMaxTopK();
        }

        return topK;
    }

    /**
     * 解析相似度阈值参数。
     *
     * @param requestedThreshold 请求相似度阈值
     * @return 安全相似度阈值
     */
    private double resolveSimilarityThreshold(Double requestedThreshold) {
        // 未传阈值时使用系统默认值，避免每个客户端自行决定检索严格程度。
        double threshold = requestedThreshold == null ? retrievalProperties.getDefaultSimilarityThreshold() : requestedThreshold;

        // 阈值超出 [0,1] 不符合相似度语义，回退默认值保护服务稳定性。
        if (threshold < 0 || threshold > 1) {
            threshold = retrievalProperties.getDefaultSimilarityThreshold();
        }

        return threshold;
    }

    /**
     * 调用大模型生成回答。
     *
     * @param question   用户问题
     * @param history    对话历史
     * @param references 引用来源
     * @return 模型回答
     */
    private String callChatModel(String question, List<RagChatMessage> history, List<RagReferenceResponse> references) {
        // 构造上下文文本，引用编号顺序必须和返回 references 顺序一致。
        String context = buildReferenceContext(references);
        // 历史消息进入 Prompt 让模型理解多轮语境，上下文仍是回答的唯一知识来源。
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(toHistoryMessages(history))
                .user(buildUserPrompt(question, context))
                .call()
                .content();
        // 规范化模型输出，避免空回答直接返回给调用方。
        return normalizeAnswer(answer);
    }

    /**
     * 构造引用上下文。
     *
     * @param references 引用来源
     * @return Prompt 上下文
     */
    private String buildReferenceContext(List<RagReferenceResponse> references) {
        StringBuilder builder = new StringBuilder();

        // 引用编号从 1 开始，方便模型和调用方以 [1]、[2] 形式引用。
        for (int index = 0; index < references.size(); index++) {
            RagReferenceResponse reference = references.get(index);
            // 写入引用头，包含文档名和 chunk 序号，方便模型在回答中定位来源。
            builder.append('[').append(index + 1).append("] ")
                    .append("document=").append(Objects.toString(reference.documentName(), ""))
                    .append(", chunkIndex=").append(reference.chunkIndex())
                    .append('\n');
            // 写入引用正文，这是模型回答的唯一知识来源。
            builder.append(reference.content()).append("\n\n");
        }

        return builder.toString();
    }

    /**
     * 构造用户 Prompt。
     *
     * @param question 用户问题
     * @param context  引用上下文
     * @return 用户 Prompt
     */
    private String buildUserPrompt(String question, String context) {
        // Prompt 明确上下文边界，避免模型把问题和上下文混为一谈。
        return "请基于以下知识库上下文回答问题。\n"
                + "如果上下文中没有答案，请回答：当前知识库中没有足够信息回答该问题。\n\n"
                + "# 知识库上下文\n"
                + context
                + "# 用户问题\n"
                + question;
    }

    /**
     * 规范化模型回答。
     *
     * @param answer 原始模型回答
     * @return 可对外返回的回答
     */
    private String normalizeAnswer(String answer) {
        // 模型可能返回空字符串，空回答需要兜底为无法回答，避免调用方展示空白结果。
        if (!StringUtils.hasText(answer)) {
            return NO_CONTEXT_ANSWER;
        }

        return answer.trim();
    }

    /**
     * 从 metadata 中读取字符串。
     *
     * @param metadata metadata 映射
     * @param key      字段名
     * @return 字符串值，缺失时返回空字符串
     */
    private String metadataAsString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);

        // 空值没有展示意义，返回空字符串保持响应稳定。
        if (value == null) {
            return "";
        }

        return String.valueOf(value);
    }
}
