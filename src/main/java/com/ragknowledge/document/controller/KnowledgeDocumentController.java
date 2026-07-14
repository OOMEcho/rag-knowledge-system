package com.ragknowledge.document.controller;

import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.document.dto.DocumentChunkResponse;
import com.ragknowledge.document.dto.KnowledgeDocumentResponse;
import com.ragknowledge.document.service.DocumentIngestionService;
import com.ragknowledge.document.service.KnowledgeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档管理接口：上传、查询、入库触发、重试与删除
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Validated
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "知识文档管理 API")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final DocumentIngestionService documentIngestionService;

    public KnowledgeDocumentController(KnowledgeDocumentService knowledgeDocumentService,
                                       DocumentIngestionService documentIngestionService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.documentIngestionService = documentIngestionService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档")
    public ApiResponse<KnowledgeDocumentResponse> uploadDocument(@RequestParam @Min(value = 1, message = "知识库 ID 必须大于 0") Long knowledgeBaseId,
                                                                 @RequestPart("file") MultipartFile file) {
        // 调用服务层保存文件和元数据，确保 checksum 去重和知识库归属校验在同一业务流程中完成。
        KnowledgeDocumentResponse response = knowledgeDocumentService.uploadDocument(knowledgeBaseId, file);
        // 上传结果使用统一响应封装，便于调用方跟踪文档生命周期状态。
        return ApiResponse.success(response);
    }

    @GetMapping
    @Operation(summary = "分页查询文档")
    public ApiResponse<PageResponse<KnowledgeDocumentResponse>> listDocuments(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于等于 1") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页大小必须大于等于 1") int pageSize) {
        // 调用服务层查询文档列表，服务层会统一处理 deleted 过滤、状态规范化和分页上限。
        PageResponse<KnowledgeDocumentResponse> response = knowledgeDocumentService.listDocuments(knowledgeBaseId, status, pageNum, pageSize);
        // 列表结果使用统一响应封装，保持 API 协议稳定。
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询文档详情")
    public ApiResponse<KnowledgeDocumentResponse> getDocument(@PathVariable Long id) {
        // 调用服务层读取文档详情，确保已删除文档不会被正常接口访问。
        KnowledgeDocumentResponse response = knowledgeDocumentService.getDocument(id);
        // 详情结果使用统一响应封装，避免直接暴露持久化实体。
        return ApiResponse.success(response);
    }

    @PostMapping("/{id}/ingest")
    @Operation(summary = "触发文档异步入库", description = "抢占成功后返回 PARSING 状态，解析和向量化在后台执行，终态通过文档详情轮询")
    public ApiResponse<KnowledgeDocumentResponse> ingestDocument(@PathVariable Long id) {
        // 触发异步入库，服务层原子抢占状态后把长耗时任务提交到独立线程池。
        KnowledgeDocumentResponse response = documentIngestionService.ingestDocument(id);
        // 返回 PARSING 状态，调用方轮询文档详情获取 COMPLETED/FAILED 终态。
        return ApiResponse.success(response);
    }

    @PostMapping("/{id}/retry-ingestion")
    @Operation(summary = "重试失败文档入库", description = "仅允许 FAILED 文档重试，返回语义与异步入库接口一致")
    public ApiResponse<KnowledgeDocumentResponse> retryDocumentIngestion(@PathVariable Long id) {
        // 调用失败恢复入口，仅允许 FAILED 文档重试，避免误触发已完成文档重新向量化。
        KnowledgeDocumentResponse response = documentIngestionService.retryFailedDocument(id);
        // 返回 PARSING 状态，调用方轮询文档详情获取重试终态。
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}/chunks")
    @Operation(summary = "分页查询文档分块")
    public ApiResponse<PageResponse<DocumentChunkResponse>> listDocumentChunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于等于 1") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页大小必须大于等于 1") int pageSize) {
        // 调用入库服务查询活跃 chunk，确保已删除或重新入库替换的历史 chunk 不对外展示。
        PageResponse<DocumentChunkResponse> response = documentIngestionService.listDocumentChunks(id, pageNum, pageSize);
        // chunk 列表使用统一分页响应，便于调试文档切分和后续引用来源。
        return ApiResponse.success(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        // 调用服务层逻辑删除文档，后续向量和 chunk 删除也会收敛到该业务入口。
        knowledgeDocumentService.deleteDocument(id);
        // 删除接口返回空数据成功响应，保持业务 API 协议统一。
        return ApiResponse.<Void>success(null);
    }
}
