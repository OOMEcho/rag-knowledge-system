package com.ragknowledge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.config.security.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证限流身份可信性、缓存容量与空闲回收，防止变换请求头重置配额。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
class RateLimitFilterTests {

    @AfterEach
    void clearAuthentication() {
        // 线程中的认证上下文不能泄露到后续测试。
        SecurityContextHolder.clearContext();
    }

    @Test
    void unverifiedHeadersShouldShareIpQuota() throws Exception {
        var filter = new RateLimitFilter(new SecurityProperties(), new ObjectMapper().findAndRegisterModules());
        // 同一个 IP 随意变换 Key，仍只能使用一个 RAG 配额。
        for (int index = 0; index < 40; index++) {
            assertThat(request(filter, "127.0.0.1", "unverified-" + index))
                    .isEqualTo(index < 30 ? 200 : 429);
        }
    }

    @Test
    void verifiedKeysShouldHaveIndependentQuotasWithoutExposingKeys() throws Exception {
        var properties = new SecurityProperties();
        properties.setEnabled(true);
        properties.setApiKeys(List.of("caller-one", "caller-two"));
        var filter = new RateLimitFilter(properties, new ObjectMapper().findAndRegisterModules());
        var authenticationFilter = new ApiKeyAuthenticationFilter(properties);
        // 真实认证过滤器生成摘要主体，同一个 Key 换 IP 仍然共享配额。
        for (int index = 0; index < 31; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/rag/chat");
            request.addHeader("X-API-Key", "caller-one");
            request.setRemoteAddr("client-" + index);
            var response = new MockHttpServletResponse();
            authenticationFilter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, (a, b) -> {}));
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).doesNotContain("caller-one");
            assertThat(response.getStatus()).isEqualTo(index < 30 ? 200 : 429);
            SecurityContextHolder.clearContext();
        }
        // 第二个合法调用方获得独立配额。
        var request = new MockHttpServletRequest("POST", "/api/v1/rag/chat");
        request.addHeader("X-API-Key", "caller-two");
        var response = new MockHttpServletResponse();
        authenticationFilter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, (a, b) -> {}));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void fullCacheShouldRejectNewClientsAndRecoverAfterIdleTimeout() throws Exception {
        var ticker = new AtomicLong();
        var filter = new RateLimitFilter(new SecurityProperties(), new ObjectMapper().findAndRegisterModules(), ticker::get);
        // 达到容量后拒绝新客户端，不能驱逐活跃桶导致原有客户端配额被重置。
        for (int index = 0; index < 10000; index++) {
            assertThat(request(filter, "client-" + index, null)).isEqualTo(200);
        }
        assertThat(request(filter, "new-client", null)).isEqualTo(429);
        // 模拟空闲十分钟，不用真实等待即可验证过期桶释放容量。
        ticker.addAndGet(Duration.ofMinutes(10).toNanos());
        assertThat(request(filter, "new-client", null)).isEqualTo(200);
    }

    /** 发送一次 RAG 请求并返回状态码。 */
    private int request(RateLimitFilter filter, String ip, String key) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/rag/chat");
        request.setRemoteAddr(ip);
        // 无 Key 代表普通未认证客户端；有 Key 时验证未认证头不能影响身份。
        if (key != null) {
            request.addHeader("X-API-Key", key);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response.getStatus();
    }
}
