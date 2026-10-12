package com.ragknowledge.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import com.ragknowledge.document.entity.DocumentStatus;
import com.ragknowledge.document.service.DocumentIngestionService;
import com.ragknowledge.document.service.KnowledgeDocumentService;
import com.ragknowledge.knowledge.dto.CreateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.service.KnowledgeBaseService;
import com.ragknowledge.rag.dto.*;
import com.ragknowledge.rag.service.RagService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 使用真实 pgvector 和 Ollama 评估召回、拒答、多轮改写及知识库隔离，输出逐题结果。
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.security.enabled=false", "rag.security.rate-limit.enabled=false",
        "rag.maintenance.orphan-cleanup.enabled=false",
        "rag.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5187"
})
class RagRetrievalQualityIT {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalQualityIT.class);
    private static final String OLLAMA_BASE_URL = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    // 使用应用默认检索参数，基线不能靠降低阈值掩盖线上配置的质量问题。
    private static final Duration INGESTION_TIMEOUT = Duration.ofMinutes(3);
    private static final Path REPORT_PATH = Path.of("target", "rag-evaluation-report.json");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("rag_knowledge_test").withUsername("rag_user").withPassword("rag_password");

    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private KnowledgeDocumentService knowledgeDocumentService;
    @Autowired private DocumentIngestionService ingestionService;
    @Autowired private RagService ragService;
    @Autowired private ObjectMapper objectMapper;
    @LocalServerPort private int serverPort;
    @Value("${spring.ai.ollama.chat.options.num-ctx:0}") private int chatContextWindow;

    private final Map<String, Long> knowledgeBases = new LinkedHashMap<>();
    private final List<EvalResult> results = new ArrayList<>();
    private List<EvalCase> cases;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws IOException {
        // 数据库与上传文件都隔离，绝不连接用户本地业务库或写入现有上传目录。
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.ai.ollama.base-url", () -> OLLAMA_BASE_URL);
        Path root = Files.createTempDirectory("rag-eval-uploads");
        registry.add("rag.storage.local-root", () -> root.toString());
    }

    // 保持 JUnit 默认实例生命周期，静态容器先启动再创建 Spring 测试上下文。
    @BeforeEach
    void prepareEvaluation() throws Exception {
        // 外部模型服务不可用时明确跳过，不能把未执行评估统计为通过。
        assumeTrue(isOllamaReachable(), "Ollama 不可用，跳过真实模型评估");
        cases = objectMapper.readValue(new ClassPathResource("rag/fixtures/rag-eval-cases.json").getInputStream(),
                new TypeReference<List<EvalCase>>() {});
        // 用例必须唯一且覆盖两个知识库和空库，防止评估意外退化为两个简单问题。
        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases.stream().map(EvalCase::name).distinct().count()).isEqualTo(cases.size());
        for (String name : List.of("finance", "it", "empty")) {
            knowledgeBases.put(name, knowledgeBaseService.createKnowledgeBase(
                    new CreateKnowledgeBaseRequest("eval-" + name, "虚构资料质量评估")).id());
        }
        for (String file : List.of("reimbursement-policy.md", "travel-policy.md", "procurement-policy.md",
                "leave-policy.md", "asset-policy.md", "training-policy.md", "archive-policy.md")) {
            ingestFixture(knowledgeBases.get("finance"), file);
        }
        ingestFixture(knowledgeBases.get("it"), "it-access-policy.md");
        // 临时测试服务地址只用于本轮真实浏览器联调，进程退出后端口与测试容器均回收。
        // 联调描述中的雪花 ID 也必须使用字符串，避免浏览器读取时丢失精度。
        Map<String, String> runtimeIds = new LinkedHashMap<>();
        knowledgeBases.forEach((name, id) -> runtimeIds.put(name, id.toString()));
        objectMapper.writeValue(Path.of("target", "rag-evaluation-runtime.json").toFile(),
                Map.of("baseUrl", "http://127.0.0.1:" + serverPort, "knowledgeBases", runtimeIds));
    }

    @TestFactory
    Stream<DynamicTest> evaluateCasesIndividually() {
        // 逐题执行并独立失败，某一道题失败不会阻止其他题产生诊断结果。
        return cases.stream().map(evalCase -> DynamicTest.dynamicTest(
                evalCase.stage() + ":" + evalCase.name(), () -> evaluateCase(evalCase)));
    }

    /** 分别检查召回与回答，失败保留阶段、来源和耗时供定位，绝不自动修改阈值。 */
    private void evaluateCase(EvalCase evalCase) throws Exception {
        long started = System.nanoTime();
        List<RagReferenceResponse> references = List.of();
        String rewrittenQuestion = null;
        String answer = null;
        String failureStage = "RETRIEVAL";
        String failure = null;
        int expectedSourceRank = 0;
        try {
            Long baseId = Objects.requireNonNull(knowledgeBases.get(evalCase.knowledgeBase()));
            // 首先独立评估检索；多轮追问依赖改写，交给问答流程得到真实检索结果。
            if (evalCase.history() == null || evalCase.history().isEmpty()) {
                references = ragService.search(new RagSearchRequest(baseId, evalCase.question(), null, null)).references();
                checkIsolation(baseId, references);
                if (!evalCase.unanswerable()) {
                    checkExpectedSource(evalCase, references);
                }
            }
            // ANSWER 用例才调用聊天模型，召回通过不能被误报为生成质量通过。
            if ("ANSWER".equals(evalCase.stage())) {
                failureStage = "ANSWER";
                var response = ragService.chat(new RagChatRequest(baseId, evalCase.question(), evalCase.history(), null, null));
                references = response.references();
                rewrittenQuestion = response.rewrittenQuestion();
                answer = response.answer();
                checkIsolation(baseId, references);
                if (evalCase.unanswerable()) {
                    // 无答案允许召回相近片段，但回答必须明确指出资料未提供所问事实。
                    assertThat(response.answer()).as("无答案用例应明确拒答")
                            .containsPattern("无法|没有|未提供|未规定|未说明|未明确|未列出|未包含|不包含|未涉及|不足|未找到|未提及");
                } else {
                    // 多轮改写与来源召回分别标记，便于区分理解追问失败和检索失败。
                    if (evalCase.history() != null && !evalCase.history().isEmpty()) {
                        failureStage = "REWRITE";
                        assertThat(rewrittenQuestion).as("追问应被改写为独立问题").isNotBlank();
                    }
                    failureStage = "RETRIEVAL";
                    checkExpectedSource(evalCase, references);
                    failureStage = "ANSWER";
                    for (String keyword : evalCase.expectedKeywords()) {
                        assertThat(response.answer()).as("回答关键结论").contains(keyword);
                    }
                    // 存在引用列表还不够，回答正文也必须关联有效编号。
                    assertThat(response.answer()).as("正文应引用实际存在的来源")
                            .containsPattern("\\[[1-" + references.size() + "]\\]");
                }
            }
            failureStage = null;
        } catch (Exception | AssertionError exception) {
            // 缩短错误文本避免报告膨胀，完整断言与堆栈仍保留在 failsafe 报告中。
            failure = Objects.toString(exception.getMessage(), exception.getClass().getSimpleName());
            failure = failure.substring(0, Math.min(failure.length(), 1500));
            throw exception;
        } finally {
            // 记录预期来源首次出现位置；仅用于排序诊断，不能把此数值当作答案置信度。
            for (int index = 0; index < references.size(); index++) {
                if (Objects.equals(evalCase.expectedDocument(), references.get(index).documentName())) {
                    expectedSourceRank = index + 1;
                    break;
                }
            }
            results.add(new EvalResult(evalCase.name(), evalCase.stage(), evalCase.question(), answer,
                    failure == null, failureStage, failure,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), rewrittenQuestion, expectedSourceRank,
                    references.stream().map(RagReferenceResponse::documentName).distinct().toList()));
            // 逐题进度只输出测试名与状态，真实模型较慢时仍能确认执行位置。
            log.info("RAG evaluation case={}, stage={}, passed={}, failureStage={}",
                    evalCase.name(), evalCase.stage(), failure == null, failureStage);
        }
    }

    /** 检查所有引用的业务归属，跨库资料不得用于补足候选数量。 */
    private void checkIsolation(Long baseId, List<RagReferenceResponse> references) {
        assertThat(references).allSatisfy(reference -> assertThat(reference.knowledgeBaseId()).isEqualTo(baseId));
    }

    /** 预期关键词必须来自预期文档，不能把相似制度中偶然出现的词当作正确召回。 */
    private void checkExpectedSource(EvalCase evalCase, List<RagReferenceResponse> references) {
        String sourceText = references.stream()
                .filter(reference -> Objects.equals(reference.documentName(), evalCase.expectedDocument()))
                .map(RagReferenceResponse::content).reduce("", (left, right) -> left + "\n" + right);
        for (String keyword : evalCase.expectedKeywords()) {
            assertThat(sourceText).as("预期来源 %s 的关键词 %s", evalCase.expectedDocument(), keyword).contains(keyword);
        }
    }

    @AfterEach
    void writeEvaluationReport() throws IOException {
        // 即使部分动态测试失败，也生成全部已执行用例的结果，避免只看到第一处失败。
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(REPORT_PATH.toFile(),
                Map.of("caseCount", cases == null ? 0 : cases.size(), "executed", results.size(),
                        "passed", results.stream().filter(EvalResult::passed).count(),
                        "chatContextWindow", chatContextWindow, "results", results));
    }

    /** 走真实上传与异步入库入口，等待终态后再开始检索。 */
    private void ingestFixture(Long baseId, String fileName) throws Exception {
        var resource = new ClassPathResource("rag/fixtures/" + fileName);
        try (var input = resource.getInputStream()) {
            var file = new MockMultipartFile("file", fileName, "text/markdown", input);
            KnowledgeDocumentResponse document = knowledgeDocumentService.uploadDocument(baseId, file);
            ingestionService.ingestDocument(document.id());
            long deadline = System.nanoTime() + INGESTION_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var current = knowledgeDocumentService.getDocument(document.id());
                if (DocumentStatus.COMPLETED.name().equals(current.status())) {
                    return;
                }
                if (DocumentStatus.FAILED.name().equals(current.status())) {
                    throw new AssertionError("fixture 入库失败: " + current.failureReason());
                }
                Thread.sleep(200);
            }
            throw new AssertionError("fixture 入库超时: " + fileName);
        }
    }

    /** 探测配置对应的 Ollama 服务，而非无条件访问另一个固定地址。 */
    private static boolean isOllamaReachable() {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.newBuilder(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 定义每题的评估阶段、预期来源、关键结论及多轮历史。
     *
     * @author xuesong.lei
     * @since 2026-10-12
     */
    private record EvalCase(String name, String stage, String knowledgeBase, String question,
                            String expectedDocument, List<String> expectedKeywords, boolean unanswerable,
                            List<RagChatMessage> history) {}

    /**
     * 保存逐题结果及失败阶段，不把模拟验证或跳过用例算作真实通过。
     *
     * @author xuesong.lei
     * @since 2026-10-12
     */
    private record EvalResult(String name, String stage, String question, String answer,
                              boolean passed, String failureStage, String failure,
                              long elapsedMillis, String rewrittenQuestion, int expectedSourceRank,
                              List<String> sourceDocuments) {}
}
