package com.ragknowledge.infrastructure.storage;

import com.ragknowledge.common.exception.BusinessException;
import com.ragknowledge.common.response.ErrorCode;
import com.ragknowledge.config.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * 本地文件系统存储实现，含目录穿越防护
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Service
public class LocalDocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorageService.class);
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String DEFAULT_FILE_NAME = "uploaded-file";

    private final Path localRoot;

    public LocalDocumentStorageService(StorageProperties storageProperties) {
        // 本地根目录统一转为绝对规范路径，后续路径校验才能可靠阻止目录穿越。
        this.localRoot = storageProperties.getLocalRoot().toAbsolutePath().normalize();
    }

    @Override
    public StoredDocumentFile storeUploadedFile(Long knowledgeBaseId, Long documentId, MultipartFile file) {
        // 上传文件名会进入存储路径，必须先清洗成安全文件名，避免客户端提交路径片段。
        String safeFileName = resolveSafeFileName(file.getOriginalFilename());
        // storageKey 只使用业务 ID 和安全文件名，保证后续可从元数据稳定定位原始文件。
        String storageKey = buildStorageKey(knowledgeBaseId, documentId, safeFileName);
        // 解析 storageKey 时再次校验路径边界，防止任何非预期路径写出本地存储根目录。
        Path targetPath = resolveStoragePath(storageKey);

        try {
            // 先创建父目录，避免文件复制时因目录不存在失败。
            Files.createDirectories(targetPath.getParent());
            // 创建摘要器用于边复制边计算 checksum，避免为大文件额外读取一次内容。
            MessageDigest messageDigest = createMessageDigest();
            // 通过流式复制保存文件，避免把上传文件完整加载到内存。
            copyFileAndUpdateDigest(file, targetPath, messageDigest);
            // 保存完成后读取真实文件大小，避免只信任客户端上报的 multipart size。
            long storedFileSize = Files.size(targetPath);
            // 摘要计算完成后转为十六进制字符串，作为业务去重和审计字段。
            String checksumSha256 = HexFormat.of().formatHex(messageDigest.digest());
            // 返回存储信息，文档服务会将这些字段写入业务元数据表。
            return new StoredDocumentFile(storageKey, safeFileName, storedFileSize, checksumSha256);
        } catch (IOException exception) {
            // 文件系统异常属于基础设施故障，对外返回 500，避免调用方误认为业务参数错误。
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Resource loadStoredFile(String storageKey) {
        // 解析 storageKey 时必须复用统一路径校验，避免解析流程绕过目录穿越保护。
        Path targetPath = resolveStoragePath(storageKey);

        // 文件不存在说明元数据和本地存储已经不一致，不能继续解析生成错误向量。
        if (!Files.exists(targetPath)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文档原始文件不存在", HttpStatus.NOT_FOUND);
        }

        // 使用 FileSystemResource 交给 Spring AI 解析器读取，避免业务层直接依赖本地 Path。
        return new FileSystemResource(targetPath);
    }

    @Override
    public void deleteStoredFile(String storageKey) {
        // 空 storageKey 说明文件还未成功保存或元数据异常，删除动作无需继续执行。
        if (!StringUtils.hasText(storageKey)) {
            return;
        }

        try {
            // 删除前仍然通过统一解析方法校验路径边界，避免传入非法 key 删除非项目文件。
            Path targetPath = resolveStoragePath(storageKey);
            // 删除失败不抛出异常，避免文件系统清理问题影响数据库主流程。
            Files.deleteIfExists(targetPath);
        } catch (RuntimeException | IOException exception) {
            // 删除失败保留日志，后续可通过运维任务清理孤儿文件。
            log.warn("Failed to delete stored document file, storageKey={}", storageKey, exception);
        }
    }

    /**
     * 构造文档存储键。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param documentId      文档 ID
     * @param safeFileName    安全文件名
     * @return 文档存储键
     */
    private String buildStorageKey(Long knowledgeBaseId, Long documentId, String safeFileName) {
        // 按知识库和文档 ID 分区，后续文档解析、删除和迁移都可以从元数据直接定位文件。
        return knowledgeBaseId + "/" + documentId + "/" + safeFileName;
    }

    /**
     * 将 storageKey 解析成本地文件路径，并校验不能越过存储根目录。
     *
     * @param storageKey 文件存储键
     * @return 本地文件路径
     */
    private Path resolveStoragePath(String storageKey) {
        // normalize 用于消除路径中的 . 或 ..，便于后续 startsWith 做可靠边界校验。
        Path resolvedPath = localRoot.resolve(storageKey).normalize();

        // 如果解析后的路径不在根目录下，说明 storageKey 存在目录穿越风险，必须拒绝。
        if (!resolvedPath.startsWith(localRoot)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "非法文件存储路径", HttpStatus.CONFLICT);
        }

        return resolvedPath;
    }

    /**
     * 清洗上传文件名，避免路径片段进入本地存储路径。
     *
     * @param originalFileName 客户端原始文件名
     * @return 安全文件名
     */
    private String resolveSafeFileName(String originalFileName) {
        // null 文件名统一兜底，避免不同客户端未传 filename 时产生空路径。
        String fileName = Objects.toString(originalFileName, DEFAULT_FILE_NAME);
        // Spring 的 cleanPath 可清理常见路径片段，作为后续文件名提取前的第一道处理。
        String cleanedFileName = StringUtils.cleanPath(fileName).replace('\\', '/');
        // 只保留最后一段路径名，防止浏览器或恶意客户端提交包含目录的文件名。
        String safeFileName = cleanedFileName.substring(cleanedFileName.lastIndexOf('/') + 1);

        // 清洗后为空时使用兜底文件名，保证存储键始终可构造。
        if (!StringUtils.hasText(safeFileName)) {
            return DEFAULT_FILE_NAME;
        }

        return safeFileName;
    }

    /**
     * 创建消息摘要器。
     *
     * @return SHA-256 消息摘要器
     */
    private MessageDigest createMessageDigest() {
        try {
            // SHA-256 是 JDK 标准算法，使用它作为文档去重和审计的稳定 checksum 口径。
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            // 标准 JDK 缺少 SHA-256 属于运行环境严重异常，使用非法状态异常尽早暴露问题。
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    /**
     * 复制上传文件并同步更新消息摘要。
     *
     * @param file          上传文件
     * @param targetPath    目标路径
     * @param messageDigest 消息摘要器
     * @throws IOException 当读取上传流或写入本地文件失败时抛出
     */
    private void copyFileAndUpdateDigest(MultipartFile file, Path targetPath, MessageDigest messageDigest) throws IOException {
        // DigestInputStream 可以在文件复制过程中同步更新摘要，避免单独读流计算 checksum。
        try (InputStream inputStream = new DigestInputStream(file.getInputStream(), messageDigest)) {
            // REPLACE_EXISTING 使同一 documentId 的重试写入具备幂等性，避免半文件残留导致再次上传失败。
            Files.copy(inputStream, targetPath, REPLACE_EXISTING);
        }
    }
}
