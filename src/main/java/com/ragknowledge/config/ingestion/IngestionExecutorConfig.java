package com.ragknowledge.config.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 文档入库专用线程池配置，隔离耗时的解析与向量化任务
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Configuration
public class IngestionExecutorConfig {

    /**
     * 文档入库专用线程池。入库管道包含 Tika 解析和 Ollama embedding，
     * 单个任务可能运行数分钟，必须与 HTTP 工作线程隔离。
     *
     * @param ingestionProperties 入库配置
     * @return 入库任务线程池
     */
    @Bean
    public ThreadPoolTaskExecutor ingestionTaskExecutor(IngestionProperties ingestionProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // core 与 max 相同，线程数固定可预测，本地 Ollama embedding 吞吐是真正瓶颈。
        executor.setCorePoolSize(ingestionProperties.getWorkerCount());
        executor.setMaxPoolSize(ingestionProperties.getWorkerCount());
        // 队列容量限制积压任务数，超出时抛出 TaskRejectedException，由提交方回滚文档状态。
        executor.setQueueCapacity(ingestionProperties.getQueueCapacity());
        executor.setThreadNamePrefix("ingestion-");
        // 应用关闭时不等待长耗时任务完成，中断残留的 PARSING/EMBEDDING 状态由启动恢复流程标记失败。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
