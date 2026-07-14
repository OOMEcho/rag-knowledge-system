package com.ragknowledge.config.openapi;

import com.ragknowledge.config.security.SecurityProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置，含 API Key 安全方案与全局安全要求
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Configuration
public class OpenApiConfig {

    private static final String API_TITLE = "rag-knowledge-system API";
    private static final String API_VERSION = "0.1.0";
    private static final String API_DESCRIPTION = "Enterprise-grade RAG knowledge system backend APIs.";
    private static final String LICENSE_NAME = "Apache-2.0";
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
    private static final String API_KEY_SCHEME_NAME = "ApiKeyAuth";

    @Bean
    public OpenAPI ragKnowledgeOpenApi(SecurityProperties securityProperties) {
        // OpenAPI 基础信息集中配置，避免散落在 Controller 中导致开源文档口径不一致。
        Info apiInfo = new Info()
                .title(API_TITLE)
                .version(API_VERSION)
                .description(API_DESCRIPTION)
                .license(new License().name(LICENSE_NAME).url(LICENSE_URL));
        // 声明 API Key 安全方案，Swagger UI 的 Authorize 按钮可为调试请求统一附加 Key 请求头。
        SecurityScheme apiKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(securityProperties.getHeaderName());
        // 返回统一 OpenAPI 对象，springdoc 会基于该对象和 Controller 注解生成 Swagger 文档。
        return new OpenAPI()
                .info(apiInfo)
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME_NAME, apiKeyScheme))
                // 全局安全要求让所有接口在文档中标注需要 API Key；认证未开启时服务端不会强制校验。
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME_NAME));
    }
}
