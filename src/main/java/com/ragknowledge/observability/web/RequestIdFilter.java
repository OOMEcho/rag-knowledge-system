package com.ragknowledge.observability.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪过滤器，生成或透传 X-Request-Id 并写入日志 MDC
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 优先复用调用方传入的请求 ID，便于跨系统链路排查同一次请求。
        String requestId = resolveRequestId(request);
        // 将请求 ID 写入响应头，方便调用方在失败时把同一个 ID 提供给服务端排查。
        response.setHeader(REQUEST_ID_HEADER, requestId);
        // 将请求 ID 放入 MDC，日志 pattern 会自动输出该字段。
        MDC.put(MDC_REQUEST_ID_KEY, requestId);

        try {
            // 继续执行后续过滤器和 Controller，确保业务日志都能读取到 MDC 中的 requestId。
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束必须清理 MDC，避免容器线程复用时串入其他请求的 requestId。
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    /**
     * 解析请求 ID。调用方未传入有效请求 ID 时生成新的 UUID。
     *
     * @param request HTTP 请求
     * @return 请求 ID
     */
    private String resolveRequestId(HttpServletRequest request) {
        // 从标准请求头读取请求 ID，避免不同模块各自定义追踪字段。
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        // 请求头存在有效文本时直接复用，保证跨服务调用链路 ID 不被覆盖。
        if (StringUtils.hasText(requestId)) {
            return requestId.trim();
        }

        // 没有上游请求 ID 时生成 UUID，确保每个入口请求都有可追踪标识。
        return UUID.randomUUID().toString();
    }
}
