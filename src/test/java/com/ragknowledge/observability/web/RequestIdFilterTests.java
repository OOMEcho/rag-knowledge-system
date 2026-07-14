package com.ragknowledge.observability.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求追踪过滤器单元测试
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
class RequestIdFilterTests {

    @Test
    void shouldReuseIncomingRequestIdAndClearMdcAfterRequest() throws Exception {
        // 构造过滤器实例，直接测试请求追踪逻辑而不启动 Web 容器。
        RequestIdFilter filter = new RequestIdFilter();
        // 构造带请求 ID 的模拟请求，用于验证跨系统传入 ID 会被复用。
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 设置上游请求 ID，业务日志应沿用这个值便于链路排查。
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "trace-001");
        // 构造模拟响应，用于验证响应头会回传同一个请求 ID。
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 构造过滤链，验证过滤器能继续放行请求。
        MockFilterChain filterChain = new MockFilterChain();

        // 执行过滤器，内部会写响应头、设置 MDC 并继续过滤链。
        filter.doFilter(request, response, filterChain);

        // 响应头必须回传请求 ID，方便调用方保存并反馈给服务端排查。
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("trace-001");
        // 请求结束必须清理 MDC，避免容器线程复用时串入其他请求。
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void shouldGenerateRequestIdWhenIncomingHeaderIsMissing() throws Exception {
        // 构造过滤器实例，直接测试缺失请求头时的生成逻辑。
        RequestIdFilter filter = new RequestIdFilter();
        // 构造无请求 ID 的模拟请求，用于验证系统会自动生成追踪 ID。
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 构造模拟响应，用于读取过滤器生成的请求 ID。
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 构造过滤链，验证生成请求 ID 后仍会继续执行后续链路。
        MockFilterChain filterChain = new MockFilterChain();

        // 执行过滤器，内部会生成 UUID 并写入响应头。
        filter.doFilter(request, response, filterChain);

        // 未传请求 ID 时也必须有响应头，保证每个入口请求都有可追踪标识。
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        // 请求结束必须清理 MDC，避免测试线程或容器线程残留 requestId。
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)).isNull();
    }
}
