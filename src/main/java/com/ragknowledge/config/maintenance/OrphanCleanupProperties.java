package com.ragknowledge.config.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 孤儿数据对账清理任务配置属性
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "rag.maintenance.orphan-cleanup")
public class OrphanCleanupProperties {

    // 对账任务默认开启，只在明确配置 false 时关闭。
    private boolean enabled = true;
    // 文件必须晚于该时长未修改才允许清理，保护"文件已保存、元数据尚未提交"的上传中间态。
    private Duration minFileAge = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getMinFileAge() {
        return minFileAge;
    }

    public void setMinFileAge(Duration minFileAge) {
        this.minFileAge = minFileAge;
    }
}
