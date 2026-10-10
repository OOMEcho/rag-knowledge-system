package com.ragknowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.config.rag.RagRetrievalProperties;
import com.ragknowledge.config.security.CorsProperties;
import com.ragknowledge.config.security.SecurityConfig;
import com.ragknowledge.config.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证检索环境变量与生产认证规则，防止配置优先级绕过安全基线。
 *
 * @author xuesong.lei
 * @since 2026-10-10
 */
class ConfigurationRegressionTests {

    @Test
    void retrievalEnvironmentOverridesShouldWorkInBothProfiles() {
        // 使用真实配置加载器，覆盖默认和生产 profile 的环境变量映射。
        for (String profile : new String[]{"default", "prod"}) {
            try (var context = configurationContext(profile)) {
                // 按应用实际前缀绑定，不能仅验证 YAML 文本存在。
                var properties = Binder.get(context.getEnvironment()).bind("rag.retrieval", RagRetrievalProperties.class).get();
                assertThat(properties.getDefaultTopK()).isEqualTo(9);
                assertThat(properties.isHybridEnabled()).isFalse();
            }
        }
    }

    @Test
    void prodShouldRejectEnvironmentOverrideDisablingAuthentication() {
        // 高优先级环境变量确实能覆盖 YAML，但安全配置必须拒绝启动。
        try (var context = configurationContext("prod")) {
            var properties = Binder.get(context.getEnvironment()).bind("rag.security", SecurityProperties.class).get();
            assertThat(properties.isEnabled()).isFalse();
            var configuration = new SecurityConfig(properties, new CorsProperties());
            assertThatThrownBy(() -> configuration.securityFilterChain(null, new ObjectMapper(), context.getEnvironment()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod 环境禁止关闭");
        }
    }

    @Test
    void enabledAuthenticationShouldRejectMissingKeys() {
        // 开启认证却没有配置凭证时，在构建过滤链前就应失败。
        try (var context = configurationContext("default")) {
            var properties = new SecurityProperties();
            properties.setEnabled(true);
            var configuration = new SecurityConfig(properties, new CorsProperties());
            assertThatThrownBy(() -> configuration.securityFilterChain(null, new ObjectMapper(), context.getEnvironment()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("未配置任何有效 API Key");
        }
    }

    /** 创建使用真实 application.yml 和模拟系统环境变量的配置上下文。 */
    private GenericApplicationContext configurationContext(String profile) {
        var context = new GenericApplicationContext();
        // SystemEnvironmentPropertySource 保留系统变量的松散命名行为，与普通 MapPropertySource 不同。
        context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-environment", Map.of(
                "SPRING_PROFILES_ACTIVE", profile,
                "RAG_DEFAULT_TOP_K", "9",
                "RAG_HYBRID_ENABLED", "false",
                "RAG_SECURITY_ENABLED", "false")));
        new ConfigDataApplicationContextInitializer().initialize(context);
        return context;
    }
}
