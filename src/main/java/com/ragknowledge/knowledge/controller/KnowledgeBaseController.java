package com.ragknowledge.knowledge.controller;

import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.common.response.PageResponse;
import com.ragknowledge.knowledge.dto.CreateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.dto.KnowledgeBaseResponse;
import com.ragknowledge.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.ragknowledge.knowledge.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口：增删改查
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@Validated
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Tag(name = "Knowledge Bases", description = "知识库管理 API")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    @Operation(summary = "创建知识库")
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        // 调用服务层创建知识库，确保名称唯一性和默认生命周期字段由业务层统一处理。
        KnowledgeBaseResponse response = knowledgeBaseService.createKnowledgeBase(request);
        // 通过统一响应结构返回结果，保持所有业务 API 的协议一致。
        return ApiResponse.success(response);
    }

    @GetMapping
    @Operation(summary = "分页查询知识库")
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> listKnowledgeBases(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于等于 1") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页大小必须大于等于 1") int pageSize) {
        // 调用服务层执行分页查询，保证分页边界和 deleted 过滤口径集中在业务层。
        PageResponse<KnowledgeBaseResponse> response = knowledgeBaseService.listKnowledgeBases(keyword, pageNum, pageSize);
        // 列表接口同样使用统一响应，便于调用方按固定协议解析分页数据。
        return ApiResponse.success(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询知识库详情")
    public ApiResponse<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable Long id) {
        // 调用服务层读取详情，服务层会统一处理不存在或已删除的知识库。
        KnowledgeBaseResponse response = knowledgeBaseService.getKnowledgeBase(id);
        // 详情数据使用统一响应封装，避免 Controller 直接暴露异常或实体结构。
        return ApiResponse.success(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新知识库")
    public ApiResponse<KnowledgeBaseResponse> updateKnowledgeBase(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        // 调用服务层更新知识库，名称冲突和存在性校验必须在同一事务边界中完成。
        KnowledgeBaseResponse response = knowledgeBaseService.updateKnowledgeBase(id, request);
        // 返回更新后的业务 DTO，避免调用方额外查询最新状态。
        return ApiResponse.success(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识库")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable Long id) {
        // 调用服务层执行逻辑删除，服务层会阻止删除仍有关联文档的知识库。
        knowledgeBaseService.deleteKnowledgeBase(id);
        // 删除接口返回空数据成功响应，保持响应协议稳定。
        return ApiResponse.<Void>success(null);
    }
}
