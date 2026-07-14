package com.ragknowledge.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.security.ApiKeyAuthenticationFilter;
import com.ragknowledge.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置：API Key 认证过滤链与 CORS 配置源
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SecurityProperties securityProperties;
    private final CorsProperties corsProperties;

    public SecurityConfig(SecurityProperties securityProperties, CorsProperties corsProperties) {
        this.securityProperties = securityProperties;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        // 纯 REST API 不使用 Cookie 会话，关闭 CSRF 并保持无状态，避免容器创建 HttpSession。
        // CORS 无论认证是否开启都需要生效：前后端分离部署时浏览器跨域访问依赖预检响应头。
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // 认证关闭时放行全部请求，保持本地开发和快速体验开箱即用；生产必须通过 prod profile 开启。
        if (!securityProperties.isEnabled()) {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        // 开启认证但未配置任何有效 Key 时快速失败，防止误以为已受保护实际全部请求都会被拒绝。
        ensureApiKeysConfigured();

        http.authorizeHttpRequests(authorize -> authorize
                        // 健康检查端点必须匿名可用，容器编排和负载均衡探针不会携带 API Key。
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Swagger UI 静态资源无法携带自定义请求头，放行页面本身；prod profile 会整体关闭 springdoc。
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 其余全部端点（业务 API 和其他 actuator 端点）都要求 API Key 认证。
                        .anyRequest().authenticated())
                // 认证失败统一返回 ApiResponse 格式的 401，而不是 Spring Security 默认响应。
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                // API Key 过滤器放在标准认证过滤器位置之前，负责把有效 Key 转成认证上下文。
                .addFilterBefore(new ApiKeyAuthenticationFilter(securityProperties), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 构造 CORS 配置源，声明允许跨域访问的前端来源和请求头。
     *
     * @return CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 允许来源从配置读取：本地默认 Vite 开发服务器，生产由部署方显式声明前端域名。
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 请求头需覆盖 JSON 请求、API Key 认证和请求追踪三类场景。
        configuration.setAllowedHeaders(List.of("Content-Type", securityProperties.getHeaderName(), "X-Request-Id"));
        // 暴露 X-Request-Id 让前端能读取服务端生成的追踪 ID 用于问题排查。
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        // 认证走自定义请求头而非 Cookie，不需要凭证模式，保持更小的跨域授权面。
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 校验开启认证时至少配置了一个非空 API Key。
     */
    private void ensureApiKeysConfigured() {
        // 逐个检查配置的 Key，空白 Key 等同于未配置，不能作为有效凭证。
        boolean hasValidKey = securityProperties.getApiKeys().stream().anyMatch(StringUtils::hasText);

        // 缺少有效 Key 时启动失败并给出配置指引，避免服务带着不可用的认证配置上线。
        if (!hasValidKey) {
            throw new IllegalStateException(
                    "rag.security.enabled=true 但未配置任何有效 API Key，请通过 RAG_API_KEYS 环境变量配置至少一个 Key");
        }
    }
}
