package com.ragknowledge.security;

import com.ragknowledge.config.security.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * API Key 认证过滤器，把请求头中的有效 Key 转为 Spring Security 认证上下文
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    // 主体用已验证 Key 的摘要区分调用方，限流不能信任原始请求头，也不应在缓存中保留凭证。
    private static final String API_KEY_PRINCIPAL = "api-key-client";
    private static final String API_KEY_AUTHORITY = "ROLE_API_CLIENT";

    private final SecurityProperties securityProperties;

    public ApiKeyAuthenticationFilter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 从配置的请求头读取调用方提交的 API Key。
        String candidateKey = request.getHeader(securityProperties.getHeaderName());

        // Key 匹配成功才写入认证上下文；不匹配时保持匿名，由授权规则统一拒绝并走 401 入口。
        if (isValidApiKey(candidateKey)) {
            // 使用已认证构造器（带权限列表）生成 authenticated=true 的凭证，供后续授权规则放行。
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    resolveClientPrincipal(candidateKey), null, List.of(new SimpleGrantedAuthority(API_KEY_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 无论认证是否成功都继续过滤链，公开端点（如健康检查）不应被该过滤器拦截。
        filterChain.doFilter(request, response);
    }

    /**
     * 为已验证的 Key 生成稳定的非明文调用方标识。
     *
     * @param candidateKey 已通过验证的 API Key
     * @return 用于限流的认证主体
     */
    private String resolveClientPrincipal(String candidateKey) {
        try {
            // SHA-256 为 JDK 标准算法，摘要用于区分配额而不是替代 API Key 验证。
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(candidateKey.getBytes(StandardCharsets.UTF_8));
            return API_KEY_PRINCIPAL + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // 标准算法不可用属于运行环境错误，不能退回缓存原始凭证。
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    /**
     * 校验候选 API Key 是否在配置的有效 Key 列表中。
     *
     * @param candidateKey 请求头中的候选 Key
     * @return 匹配任一有效 Key 时返回 true
     */
    private boolean isValidApiKey(String candidateKey) {
        // 请求未携带 Key 时直接判定未认证，避免空字符串参与比较。
        if (!StringUtils.hasText(candidateKey)) {
            return false;
        }

        byte[] candidateBytes = candidateKey.getBytes(StandardCharsets.UTF_8);

        // 逐个 Key 使用常量时间比较，防止通过响应耗时差异逐字符猜测 Key 内容。
        for (String configuredKey : securityProperties.getApiKeys()) {
            if (MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8), candidateBytes)) {
                return true;
            }
        }

        return false;
    }
}
