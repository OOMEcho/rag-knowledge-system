package com.ragknowledge.integration;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import com.ragknowledge.document.entity.DocumentStatus;
import com.ragknowledge.document.service.DocumentIngestionService;
import com.ragknowledge.document.service.KnowledgeDocumentService;
import com.ragknowledge.knowledge.entity.KnowledgeBaseEntity;
import com.ragknowledge.knowledge.mapper.KnowledgeBaseMapper;
import com.ragknowledge.rag.dto.RagReferenceResponse;
import com.ragknowledge.rag.dto.RagSearchRequest;
import com.ragknowledge.rag.dto.RagSearchResponse;
import com.ragknowledge.rag.service.RagService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 检索质量回归测试：使用真实 pgvector 容器和本地 Ollama embedding，
 * 按 rag-eval-cases.json 中的评估用例验证混合检索能召回预期关键词所在 chunk。
 * 依赖本地 Ollama，CI 无法运行，仅在本地通过 -DskipITs=false 执行；Ollama 不可用时自动跳过。
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Testcontainers
@SpringBootTest
class RagRetrievalQualityIT {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");
    private static final String OLLAMA_BASE_URL = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    // 评估检索参数：低阈值宽召回，回归目标是"预期内容必须出现在 topK 引用中"。
    private static final int EVAL_TOP_K = 3;
    private static final double EVAL_SIMILARITY_THRESHOLD = 0.2;
    private static final Duration INGESTION_TIMEOUT = Duration.ofSeconds(120);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("rag_knowledge_test")
            .withUsername("rag_user")
            .withPassword("rag_password");

    // 上传文件写入临时目录，避免测试污染项目 data/uploads。
    private static Path tempStorageRoot;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;
    @Autowired
    private DocumentIngestionService documentIngestionService;
    @Autowired
    private RagService ragService;
    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        // 数据源指向 Testcontainers 容器，Flyway 迁移和 Spring AI 向量表都在隔离环境创建。
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        tempStorageRoot = Files.createTempDirectory("rag-eval-uploads");
        registry.add("rag.storage.local-root", () -> tempStorageRoot.toString());
    }

    @BeforeAll
    static void checkOllamaAvailable() {
        // Ollama 是外部依赖，不可用时跳过而不是失败，保证 mvn verify 在无模型环境也能通过。
        assumeTrue(isOllamaReachable(), "本地 Ollama 不可用，跳过检索质量评估");
    }

    @Test
    void hybridRetrievalShouldRecallExpectedKeywordsForAllEvalCases() throws Exception {
        // 构造评估知识库并完成 fixture 文档入库，得到可检索的真实向量和 chunk 数据。
        Long knowledgeBaseId = createEvalKnowledgeBase();
        ingestFixtureDocument(knowledgeBaseId);

        // 加载评估用例，每个用例包含问题和必须被召回的关键词。
        List<EvalCase> evalCases = loadEvalCases();
        assertThat(evalCases).isNotEmpty();

        for (EvalCase evalCase : evalCases) {
            // 通过标准检索入口执行混合检索，保证评估口径与线上 API 一致。
            RagSearchResponse response = ragService.search(new RagSearchRequest(
                    knowledgeBaseId, evalCase.question(), EVAL_TOP_K, EVAL_SIMILARITY_THRESHOLD));
            // 拼接全部引用内容，关键词出现在任一引用中即视为召回成功。
            String referencedContent = response.references().stream()
                    .map(RagReferenceResponse::content)
                    .reduce("", (left, right) -> left + "\n" + right);

            for (String keyword : evalCase.expectedKeywords()) {
                assertThat(referencedContent)
                        .as("用例 [%s] 的关键词 [%s] 应出现在 top%s 引用中", evalCase.name(), keyword, EVAL_TOP_K)
                        .contains(keyword);
            }
        }
    }

    /**
     * 创建评估专用知识库。
     *
     * @return 知识库 ID
     */
    private Long createEvalKnowledgeBase() {
        // 直接通过 mapper 构造知识库，评估关注检索质量，不需要覆盖知识库 API 流程。
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(IdWorker.getId());
        knowledgeBase.setName("eval-" + System.currentTimeMillis());
        knowledgeBase.setDescription("retrieval quality eval");
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setDocumentCount(0);
        knowledgeBase.setDeleted(false);
        knowledgeBase.setCreatedAt(LocalDateTime.now());
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase.getId();
    }

    /**
     * 上传并入库 fixture 文档，轮询等待异步入库完成。
     *
     * @param knowledgeBaseId 知识库 ID
     */
    private void ingestFixtureDocument(Long knowledgeBaseId) throws Exception {
        ClassPathResource fixture = new ClassPathResource("rag/fixtures/reimbursement-policy.md");
        // 走标准上传入口，保证 checksum、存储和元数据流程与线上一致。
        MockMultipartFile file = new MockMultipartFile(
                "file", "reimbursement-policy.md", "text/markdown", fixture.getInputStream());
        KnowledgeDocumentResponse document = knowledgeDocumentService.uploadDocument(knowledgeBaseId, file);
        // 触发异步入库后轮询终态，embedding 由本地 Ollama 真实生成。
        documentIngestionService.ingestDocument(document.id());

        long deadline = System.currentTimeMillis() + INGESTION_TIMEOUT.toMillis();
        String status = document.status();

        // 轮询文档状态直到终态或超时，入库失败时直接断言失败并展示原因。
        while (System.currentTimeMillis() < deadline) {
            KnowledgeDocumentResponse latest = knowledgeDocumentService.getDocument(document.id());
            status = latest.status();

            if (DocumentStatus.COMPLETED.name().equals(status)) {
                return;
            }

            if (DocumentStatus.FAILED.name().equals(status)) {
                throw new AssertionError("fixture 文档入库失败: " + latest.failureReason());
            }

            Thread.sleep(1000);
        }

        throw new AssertionError("fixture 文档入库超时，最后状态: " + status);
    }

    /**
     * 加载评估用例。
     *
     * @return 评估用例列表
     */
    private List<EvalCase> loadEvalCases() throws IOException {
        ClassPathResource resource = new ClassPathResource("rag/fixtures/rag-eval-cases.json");
        return objectMapper.readValue(resource.getInputStream(), new TypeReference<List<EvalCase>>() {
        });
    }

    /**
     * 探测本地 Ollama 是否可用。
     *
     * @return 可访问时返回 true
     */
    private static boolean isOllamaReachable() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    /**
     * 评估用例结构，与 rag-eval-cases.json 字段对应。
     */
    private record EvalCase(String name, String knowledgeBase, String question, List<String> expectedKeywords) {
    }
}
