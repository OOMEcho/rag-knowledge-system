package com.ragknowledge.document.entity;

/**
 * 文档入库状态枚举，表达 UPLOADED -> PARSING -> EMBEDDING -> COMPLETED/FAILED 状态机
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    EMBEDDING,
    COMPLETED,
    FAILED,
    DELETED
}
