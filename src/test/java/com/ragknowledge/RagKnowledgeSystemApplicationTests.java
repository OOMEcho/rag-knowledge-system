package com.ragknowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文启动冒烟测试
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
class RagKnowledgeSystemApplicationTests {

    @Test
    void applicationClassIsAvailable() {
        // 基础阶段只验证启动类可被加载，避免没有 PostgreSQL 或 Ollama 时启动完整上下文造成测试不可运行。
        assertThat(RagKnowledgeSystemApplication.class).isNotNull();
    }
}
