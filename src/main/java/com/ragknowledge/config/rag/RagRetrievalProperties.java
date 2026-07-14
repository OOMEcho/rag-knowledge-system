package com.ragknowledge.config.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索参数配置属性：topK、相似度阈值与 RRF 融合参数
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalProperties {

    private int defaultTopK = 5;
    private int maxTopK = 20;
    private double defaultSimilarityThreshold = 0.65;
    // 混合检索默认开启：向量通道 + pg_trgm 关键词通道 RRF 融合；关闭后退化为纯向量检索。
    private boolean hybridEnabled = true;
    // RRF 融合常数，60 是论文推荐值：越大排名差异的影响越平滑，一般无需调整。
    private int rrfK = 60;
    // 每个通道召回 topK 的倍数作为融合候选池，扩大候选集让两个通道充分互补。
    private int channelPoolMultiplier = 2;

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getChannelPoolMultiplier() {
        return channelPoolMultiplier;
    }

    public void setChannelPoolMultiplier(int channelPoolMultiplier) {
        this.channelPoolMultiplier = channelPoolMultiplier;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(int defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public int getMaxTopK() {
        return maxTopK;
    }

    public void setMaxTopK(int maxTopK) {
        this.maxTopK = maxTopK;
    }

    public double getDefaultSimilarityThreshold() {
        return defaultSimilarityThreshold;
    }

    public void setDefaultSimilarityThreshold(double defaultSimilarityThreshold) {
        this.defaultSimilarityThreshold = defaultSimilarityThreshold;
    }
}
