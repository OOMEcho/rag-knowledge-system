package com.ragknowledge.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 跨域白名单配置属性，前后端分离部署时声明允许的前端来源
 *
 * @author xuesong.lei
 * @since 2026-07-14
 */
@ConfigurationProperties(prefix = "rag.cors")
public class CorsProperties {

    // 前后端分离部署时前端页面域名与后端不同源，浏览器要求后端显式声明允许的来源。
    // 默认只放行本地 Vite 开发服务器；生产部署通过 RAG_CORS_ALLOWED_ORIGINS 配置前端实际域名。
    private List<String> allowedOrigins = List.of("http://localhost:5173");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
