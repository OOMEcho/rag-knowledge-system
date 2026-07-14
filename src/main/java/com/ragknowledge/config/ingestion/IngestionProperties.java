package com.ragknowledge.config.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 文档入库参数配置属性：切分参数、文件类型白名单与工作线程数
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "rag.ingestion")
public class IngestionProperties {

    private int chunkSize = 800;
    private int minChunkSizeChars = 350;
    private int minChunkLengthToEmbed = 5;
    private int maxNumChunks = 10000;
    private boolean keepSeparator = true;
    // 入库线程池并发数默认压到 2，本地 Ollama embedding 是串行瓶颈，更高并发只会互相拖慢。
    private int workerCount = 2;
    // 队列容量限制积压任务数量，超出后拒绝新任务并提示调用方稍后重试。
    private int queueCapacity = 100;
    // 上传扩展名白名单，默认覆盖 Tika 可稳定解析的常见办公文档格式。
    private List<String> allowedFileExtensions = List.of(
            "txt", "md", "markdown", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv", "html", "htm");

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getMinChunkSizeChars() {
        return minChunkSizeChars;
    }

    public void setMinChunkSizeChars(int minChunkSizeChars) {
        this.minChunkSizeChars = minChunkSizeChars;
    }

    public int getMinChunkLengthToEmbed() {
        return minChunkLengthToEmbed;
    }

    public void setMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
    }

    public int getMaxNumChunks() {
        return maxNumChunks;
    }

    public void setMaxNumChunks(int maxNumChunks) {
        this.maxNumChunks = maxNumChunks;
    }

    public boolean isKeepSeparator() {
        return keepSeparator;
    }

    public void setKeepSeparator(boolean keepSeparator) {
        this.keepSeparator = keepSeparator;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public List<String> getAllowedFileExtensions() {
        return allowedFileExtensions;
    }

    public void setAllowedFileExtensions(List<String> allowedFileExtensions) {
        this.allowedFileExtensions = allowedFileExtensions;
    }
}
