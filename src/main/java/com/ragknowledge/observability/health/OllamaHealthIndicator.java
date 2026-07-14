package com.ragknowledge.observability.health;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

/**
 * Ollama 健康检查，校验服务可达且所需 chat/embedding 模型已安装
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
public class OllamaHealthIndicator implements HealthIndicator {

    private static final String TAGS_API_PATH = "/api/tags";

    private final RestClient restClient;
    private final String baseUrl;
    private final String chatModel;
    private final String embeddingModel;

    public OllamaHealthIndicator(RestClient.Builder restClientBuilder,
                                 @Value("${spring.ai.ollama.base-url}") String baseUrl,
                                 @Value("${spring.ai.ollama.chat.options.model}") String chatModel,
                                 @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel) {
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        // RestClient 复用 Spring Boot 全局 HTTP client timeout 配置，避免健康检查长时间阻塞。
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Health health() {
        try {
            // 调用 /api/tags 只查询本地模型列表，不触发大模型加载，适合作为轻量健康检查。
            JsonNode response = restClient.get().uri(TAGS_API_PATH).retrieve().body(JsonNode.class);
            // 从 Ollama 返回中提取本地模型名称，后续用于校验 chat 和 embedding 模型是否存在。
            Set<String> modelNames = extractModelNames(response);
            // 判断 chat 模型是否存在，缺失时 RAG chat 无法工作。
            boolean chatModelAvailable = modelNames.contains(chatModel);
            // 判断 embedding 模型是否存在，缺失时文档入库和检索无法工作。
            boolean embeddingModelAvailable = modelNames.contains(embeddingModel);

            // 两类模型都存在才认为 Ollama 对本系统是可用状态。
            if (chatModelAvailable && embeddingModelAvailable) {
                return Health.up()
                        .withDetail("baseUrl", baseUrl)
                        .withDetail("chatModel", chatModel)
                        .withDetail("embeddingModel", embeddingModel)
                        .build();
            }

            // Ollama 服务可访问但模型缺失属于对外服务不可用，而不是进程完全 down。
            return Health.outOfService()
                    .withDetail("baseUrl", baseUrl)
                    .withDetail("chatModel", chatModel)
                    .withDetail("chatModelAvailable", chatModelAvailable)
                    .withDetail("embeddingModel", embeddingModel)
                    .withDetail("embeddingModelAvailable", embeddingModelAvailable)
                    .build();
        } catch (RuntimeException exception) {
            // Ollama 不可访问时标记为 down，便于 actuator health 直接暴露基础设施故障。
            return Health.down(exception)
                    .withDetail("baseUrl", baseUrl)
                    .withDetail("chatModel", chatModel)
                    .withDetail("embeddingModel", embeddingModel)
                    .build();
        }
    }

    /**
     * 从 Ollama tags 响应中提取模型名称。
     *
     * @param response Ollama `/api/tags` 响应
     * @return 模型名称集合
     */
    private Set<String> extractModelNames(JsonNode response) {
        // 使用 Set 存储模型名，便于后续 O(1) 判断目标模型是否存在。
        Set<String> modelNames = new HashSet<>();

        // 响应为空或模型字段缺失时返回空集合，由上层判断模型不可用。
        if (response == null || !response.path("models").isArray()) {
            return modelNames;
        }

        // 遍历 models 数组读取 name 字段，Ollama 模型名带 tag，如 qwen3.6:35b。
        for (JsonNode modelNode : response.path("models")) {
            // path 避免字段缺失时抛异常，asText 统一转换成字符串。
            String modelName = modelNode.path("name").asText();

            // 空模型名没有业务意义，跳过避免污染可用模型集合。
            if (!modelName.isBlank()) {
                modelNames.add(modelName);
            }
        }

        return modelNames;
    }
}
