package com.ragknowledge.document.entity;

/**
 * 关键词通道检索结果：chunk ID 和 trigram 词相似度分数。
 * 仅作为 mapper 查询结果载体，不对外暴露。
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public class ChunkKeywordScore {

    private Long id;
    private Double score;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
