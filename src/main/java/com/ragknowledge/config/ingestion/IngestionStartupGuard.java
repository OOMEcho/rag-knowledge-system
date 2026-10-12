package com.ragknowledge.config.ingestion;

import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 入库启动门禁，阻止恢复完成前的新任务与残留数据清理交错。
 *
 * @author xuesong.lei
 * @since 2026-10-12
 */
@Component
public class IngestionStartupGuard {

    // HTTP 线程和启动线程共享状态，volatile 保证恢复完成后对请求立即可见。
    private volatile boolean recoveryCompleted;

    /** 恢复成功后开放入库，启动恢复失败时不得调用。 */
    public void markRecoveryCompleted() {
        recoveryCompleted = true;
    }

    /** 恢复完成前返回可重试的 503，且不得读取或抢占文档状态。 */
    public void ensureReadyForIngestion() {
        // 即使 Web 端口已经监听，也必须等待残留任务清理全部结束。
        if (!recoveryCompleted) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                    "服务正在恢复入库任务，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
