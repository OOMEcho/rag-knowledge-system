package com.ragknowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * RAG 知识库系统应用启动类
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagKnowledgeSystemApplication {

    public static void main(String[] args) {
        // 通过 Spring Boot 统一启动应用上下文，确保自动配置、组件扫描和配置文件绑定按约定生效。
        SpringApplication.run(RagKnowledgeSystemApplication.class, args);
    }
}
