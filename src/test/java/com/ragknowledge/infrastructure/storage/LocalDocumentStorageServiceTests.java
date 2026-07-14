package com.ragknowledge.infrastructure.storage;

import com.ragknowledge.config.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地文件存储服务单元测试
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
class LocalDocumentStorageServiceTests {

    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    private Path tempDir;

    @Test
    void storeUploadedFileShouldPersistFileAndReturnChecksum() {
        // 使用临时目录隔离测试文件，避免单元测试污染项目 data/uploads 运行目录。
        StorageProperties storageProperties = new StorageProperties();
        // 将本地存储根目录指向 JUnit 临时目录，确保测试结束后由框架自动清理。
        storageProperties.setLocalRoot(tempDir);
        // 构造本地存储服务，验证真实文件系统写入和删除逻辑。
        LocalDocumentStorageService storageService = new LocalDocumentStorageService(storageProperties);
        // 使用 MockMultipartFile 模拟上传文件，避免启动 Web 容器即可覆盖 multipart 存储行为。
        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());

        // 保存文件并返回存储元数据，这是文档上传服务后续写入数据库的依据。
        StoredDocumentFile storedFile = storageService.storeUploadedFile(1L, 2L, file);
        // 根据 storageKey 拼出物理路径，验证文件确实落在配置的本地根目录下。
        Path storedPath = tempDir.resolve(storedFile.storageKey()).normalize();

        // 校验文件已保存，避免只测试返回值而忽略真实文件写入。
        assertThat(Files.exists(storedPath)).isTrue();
        // 校验 checksum 与已知 SHA-256 值一致，保证去重口径稳定。
        assertThat(storedFile.checksumSha256()).isEqualTo(HELLO_SHA256);
        // 校验文件大小来自实际落盘文件，避免信任客户端 multipart 元数据。
        assertThat(storedFile.fileSize()).isEqualTo(5L);

        // 通过 storageKey 重新加载文件资源，验证后续文档解析流程不需要知道本地路径拼接细节。
        assertThat(storageService.loadStoredFile(storedFile.storageKey()).exists()).isTrue();

        // 删除刚才保存的文件，验证文档删除和失败回滚时依赖的清理能力。
        storageService.deleteStoredFile(storedFile.storageKey());
        // 校验文件已被删除，避免后续上传失败回滚产生孤儿文件。
        assertThat(Files.exists(storedPath)).isFalse();
    }
}
