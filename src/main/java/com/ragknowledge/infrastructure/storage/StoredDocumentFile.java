package com.ragknowledge.infrastructure.storage;

/**
 * 已存储文件信息，携带存储 key 与落盘元数据
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public record StoredDocumentFile(
        String storageKey,
        String fileName,
        long fileSize,
        String checksumSha256
) {
}
