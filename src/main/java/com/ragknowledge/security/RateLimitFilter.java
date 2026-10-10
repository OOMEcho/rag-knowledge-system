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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

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

    private static final int MAX_CACHED_BUCKETS = 10000;
    // 空闲十分钟已远超一分钟的补充周期，过期重建不会额外放宽配额。
    private static final long BUCKET_IDLE_TIMEOUT_NANOS = Duration.ofMinutes(10).toNanos();
    // 按访问顺序仅扫描最旧条目；容量耗尽时拒绝新标识，不能驱逐有效桶使攻击者重置配额。
    private final Map<String, ClientBucket> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private final LongSupplier ticker;

    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public RateLimitFilter(SecurityProperties securityProperties, ObjectMapper objectMapper) {
        // 单调时间避免系统时钟调整影响桶的空闲过期判断。
        this(securityProperties, objectMapper, System::nanoTime);
    }

    /**
     * 使用可控单调时钟构造过滤器，便于验证空闲过期而无需真实等待。
     *
     * @param securityProperties 限流与认证配置
     * @param objectMapper 错误响应序列化器
     * @param ticker 单调纳秒时钟
     */
    RateLimitFilter(SecurityProperties securityProperties, ObjectMapper objectMapper, LongSupplier ticker) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        this.ticker = ticker;
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
        Bucket bucket = resolveBucket(clientKey, requestPath.startsWith(RAG_PATH_PREFIX));

        // 配额耗尽或缓存容量已满时返回 429，防止模型负载或客户端标识耗尽内存。
        if (bucket == null || !bucket.tryConsume(1)) {
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
        // 仅信任安全过滤链验证后的主体，未认证请求随意变换 Key 不能获得新配额。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (securityProperties.isEnabled() && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "client:" + authentication.getName();
        }

        // 未认证请求始终按来源 IP 限流；经代理部署时需由可信代理配置 forward-headers。
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * 获取客户端桶并回收空闲条目；满容量时拒绝新增桶以保持内存与配额边界。
     *
     * @param clientKey 可信调用方或来源 IP
     * @param ragRequest 是否使用 RAG 配额
     * @return 令牌桶；缓存满且客户端未登记时返回 null
     */
    private synchronized Bucket resolveBucket(String clientKey, boolean ragRequest) {
        // 访问顺序与最后访问时间一致，只需清理开头的过期条目，避免每个请求遍历全量缓存。
        long now = ticker.getAsLong();
        var iterator = buckets.values().iterator();
        while (iterator.hasNext()) {
            ClientBucket entry = iterator.next();
            // 后续条目更新得更晚，首个未过期条目意味着本轮清理已经完成。
            if (now - entry.lastAccessNanos() < BUCKET_IDLE_TIMEOUT_NANOS) {
                break;
            }
            iterator.remove();
        }
        // 两种接口拥有独立配额，但共同受缓存容量限制。
        String cacheKey = (ragRequest ? "rag:" : "general:") + clientKey;
        ClientBucket entry = buckets.get(cacheKey);
        if (entry == null) {
            // 不驱逐仍有效的桶，否则大量新客户端可迫使旧客户端配额重置。
            if (buckets.size() >= MAX_CACHED_BUCKETS) {
                return null;
            }
            int requestsPerMinute = ragRequest ? securityProperties.getRateLimit().getRagRequestsPerMinute()
                    : securityProperties.getRateLimit().getRequestsPerMinute();
            // 新客户端按所属接口配置创建桶；缓存中只保存摘要主体或 IP。
            entry = new ClientBucket(newBucket(requestsPerMinute), now);
        }
        // 每次访问都更新空闲时间，活跃客户端不能通过耗尽配额后等待清理短路限流。
        buckets.put(cacheKey, new ClientBucket(entry.bucket(), now));
        return entry.bucket();
    }

    /**
     * 客户端配额及最近访问时间，用于有界缓存的空闲回收。
     *
     * @author xuesong.lei
     * @since 2026-10-10
     */
    private record ClientBucket(Bucket bucket, long lastAccessNanos) {
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
