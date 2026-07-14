package com.ragknowledge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.config.security.SecurityProperties;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求限流过滤器，基于 bucket4j 内存令牌桶按客户端限制业务 API 频率
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
@ConditionalOnProperty(prefix = "rag.security.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String API_PATH_PREFIX = "/api/";
    private static final String RAG_PATH_PREFIX = "/api/v1/rag/";

    // 桶按客户端标识缓存在内存中，单实例部署有效；多实例部署需替换为 Redis 等分布式实现。
    private final Map<String, Bucket> generalBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> ragBuckets = new ConcurrentHashMap<>();

    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(SecurityProperties securityProperties, ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestPath = request.getRequestURI();

        // 只对业务 API 限流，actuator 和 Swagger 等辅助端点不消耗配额。
        // CORS 预检 OPTIONS 请求由浏览器自动发起且不进入业务处理，同样不消耗配额。
        if (!requestPath.startsWith(API_PATH_PREFIX) || HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 客户端标识优先使用 API Key（可精确到调用方），未认证场景退化为来源 IP。
        String clientKey = resolveClientKey(request);
        // RAG 端点触发 embedding 和大模型调用，成本高，使用独立且更严格的配额。
        Bucket bucket = requestPath.startsWith(RAG_PATH_PREFIX)
                ? ragBuckets.computeIfAbsent(clientKey, key -> newBucket(securityProperties.getRateLimit().getRagRequestsPerMinute()))
                : generalBuckets.computeIfAbsent(clientKey, key -> newBucket(securityProperties.getRateLimit().getRequestsPerMinute()));

        // 令牌不足说明该客户端超过配额，直接返回 429，不再进入业务处理。
        if (!bucket.tryConsume(1)) {
            writeRateLimitedResponse(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 解析限流客户端标识。
     *
     * @param request HTTP 请求
     * @return 客户端标识
     */
    private String resolveClientKey(HttpServletRequest request) {
        // 携带 API Key 时按 Key 限流，同一调用方无论来源 IP 如何变化共享同一配额。
        String apiKey = request.getHeader(securityProperties.getHeaderName());

        if (StringUtils.hasText(apiKey)) {
            return "key:" + apiKey;
        }

        // 未携带 Key 时按来源 IP 限流；直连场景下 remoteAddr 可信，经代理部署时需配置 forward-headers。
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * 创建每分钟固定配额的令牌桶。
     *
     * @param requestsPerMinute 每分钟请求数上限
     * @return 令牌桶
     */
    private Bucket newBucket(int requestsPerMinute) {
        // 贪婪补充策略把配额均匀摊到整个分钟内，避免每分钟开始时的突发流量打满下游模型服务。
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute).refillGreedy(requestsPerMinute, Duration.ofMinutes(1)))
                .build();
    }

    /**
     * 输出统一格式的限流响应。
     *
     * @param response HTTP 响应
     */
    private void writeRateLimitedResponse(HttpServletResponse response) throws IOException {
        // 限流发生在过滤器层，全局异常处理器无法接管，必须手工输出统一错误协议。
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.failure(ErrorCode.RATE_LIMITED, "请求频率超过限制，请稍后再试")));
    }
}
