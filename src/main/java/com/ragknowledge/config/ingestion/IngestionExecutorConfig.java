package com.ragknowledge.config.ingestion;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
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

    /**
     * 应用默认异步线程池。项目自定义了 ingestionTaskExecutor 后，
     * Spring Boot 的 TaskExecutionAutoConfiguration 会整体退避，导致 MVC 异步请求
     * （SSE 流式问答）回落到不适合生产的 SimpleAsyncTaskExecutor 并打出警告，
     * 因此需要显式重建 applicationTaskExecutor，沿用 spring.task.execution.* 配置。
     *
     * @param builder Spring Boot 提供的线程池构建器（已绑定 spring.task.execution 配置）
     * @return 应用默认异步线程池
     */
    @Bean
    public AsyncTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
