package com.ragknowledge.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * API Key 认证与请求限流配置属性
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "rag.security")
public class SecurityProperties {

    // 认证默认关闭以保证本地开箱即用；prod profile 会强制开启，生产部署必须使用 prod profile。
    private boolean enabled = false;
    // 允许配置多个 API Key，支持为不同调用方分发独立 Key 并单独轮换。
    private List<String> apiKeys = List.of();
    // API Key 请求头名称，与 OpenAPI 安全方案保持一致。
    private String headerName = "X-API-Key";
    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class RateLimit {

        // 限流独立于认证开关，本地默认也开启，宽松阈值不影响开发但能兜底防误用。
        private boolean enabled = true;
        // 普通业务 API 每客户端每分钟请求上限。
        private int requestsPerMinute = 120;
        // RAG 检索问答会触发 embedding 和大模型调用，成本远高于普通 API，单独设置更严格上限。
        private int ragRequestsPerMinute = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public int getRagRequestsPerMinute() {
            return ragRequestsPerMinute;
        }

        public void setRagRequestsPerMinute(int ragRequestsPerMinute) {
            this.ragRequestsPerMinute = ragRequestsPerMinute;
        }
    }
}
