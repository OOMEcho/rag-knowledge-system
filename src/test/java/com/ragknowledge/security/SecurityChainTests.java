package com.ragknowledge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.config.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证真实安全过滤链与限流过滤器的组合行为，覆盖认证与配额传递。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
class SecurityChainTests {
    @Test
    void authenticatedRequestsShouldShareKeyQuotaAndRejectInvalidKeys() {
        // 最小 Web 上下文装配真实过滤链，不依赖数据库或 Ollama。
        contextRunner().withPropertyValues("rag.security.enabled=true", "rag.security.api-keys=caller-one,caller-two",
                "rag.security.rate-limit.rag-requests-per-minute=2").run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class), context.getBean(RateLimitFilter.class)).build();
            mvc.perform(post("/api/v1/rag/probe").header("X-API-Key", "invalid")).andExpect(status().isUnauthorized());
            // 已认证 Key 在安全上下文中传递给限流器，同一主体第三次请求应被拒绝。
            mvc.perform(post("/api/v1/rag/probe").header("X-API-Key", "caller-one")).andExpect(status().isOk());
            mvc.perform(post("/api/v1/rag/probe").header("X-API-Key", "caller-one")).andExpect(status().isOk());
            mvc.perform(post("/api/v1/rag/probe").header("X-API-Key", "caller-one")).andExpect(status().isTooManyRequests());
            mvc.perform(post("/api/v1/rag/probe").header("X-API-Key", "caller-two")).andExpect(status().isOk());
        });
    }

    @Test
    void prodContextShouldFailWhenAuthenticationIsDisabled() {
        // 通过实际 bean 初始化验证生产保护确实阻止上下文启动。
        contextRunner().withPropertyValues("spring.profiles.active=prod", "rag.security.enabled=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("prod 环境禁止关闭 API Key 认证");
        });
    }

    /** 创建仅包含安全和测试接口的 Web 上下文。 */
    private WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(SecurityConfig.class, RateLimitFilter.class, WebConfiguration.class, ProbeController.class);
    }

    /**
     * 提供测试 Web MVC 和安全配置绑定，不加载完整应用基础设施。
     *
     * @author xuesong.lei
     * @since 2026-10-10
     */
    @Configuration
    @EnableWebMvc
    @EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
    static class WebConfiguration {
    }

    /**
     * 为过滤链回归提供无副作用的业务端点。
     *
     * @author xuesong.lei
     * @since 2026-10-10
     */
    @RestController
    static class ProbeController {
        @PostMapping("/api/v1/rag/probe")
        String probe() {
            return "ok";
        }
    }
}
