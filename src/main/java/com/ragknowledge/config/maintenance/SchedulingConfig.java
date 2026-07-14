package com.ragknowledge.config.maintenance;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务调度开关配置
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // 开启 Spring 定时任务调度，当前仅服务于孤儿数据对账任务。
}
