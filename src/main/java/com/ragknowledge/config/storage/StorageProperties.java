package com.ragknowledge.config.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 本地文件存储配置属性
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "rag.storage")
public class StorageProperties {

    private Path localRoot = Path.of("data/uploads");

    public Path getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(Path localRoot) {
        this.localRoot = localRoot;
    }
}
