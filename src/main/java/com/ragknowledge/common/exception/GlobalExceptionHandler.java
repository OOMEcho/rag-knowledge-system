package com.ragknowledge.common.exception;

import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.common.response.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器，把校验、业务、上传超限等各类异常统一转为 ApiResponse 格式
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        // Bean Validation 的字段错误需要提取成稳定文案，避免直接暴露框架异常结构给 API 调用方。
        String message = resolveBindValidationMessage(exception);
        // 参数校验失败属于客户端请求问题，统一返回 400 便于调用方按输入错误处理。
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        // 路径变量或请求参数校验失败时没有 BindingResult，需要从约束违规集合中提取第一条可读错误。
        String message = resolveConstraintViolationMessage(exception);
        // 参数校验失败属于客户端请求问题，统一返回 400 便于调用方按输入错误处理。
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException exception) {
        // 请求体 JSON 格式错误或枚举值非法属于客户端问题，必须返回 400 而不是落入兜底 500。
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求体格式不正确");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        // 访问不存在的路径属于客户端问题，必须返回 404 而不是落入兜底 500，避免误报服务端故障。
        return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "请求路径不存在");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        // 上传超限属于客户端请求问题，必须返回 413 而不是落入兜底 500，避免误导调用方排查服务端故障。
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.VALIDATION_FAILED, "上传文件大小超过限制");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        // 业务异常允许携带更精确的 HTTP 状态，避免所有业务错误都被错误归类为 500。
        HttpStatus status = exception.status();
        // 业务异常消息可能由调用方传入空值，需要统一兜底到错误码默认文案。
        String message = resolveBusinessMessage(exception);
        // 通过统一响应结构输出业务错误，保持前后端错误协议稳定。
        return buildErrorResponse(status, exception.errorCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        // 未预期异常必须记录日志，避免对外隐藏内部错误后无法定位生产问题。
        log.error("Unhandled application exception", exception);
        // 系统未知错误不暴露内部细节，避免泄露堆栈、SQL 或基础设施信息。
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    /**
     * 提取请求体字段校验错误的对外消息。
     *
     * @param exception Spring MVC 请求体校验异常
     * @return 可对外展示的校验错误消息
     */
    private String resolveBindValidationMessage(MethodArgumentNotValidException exception) {
        // 默认文案作为兜底，避免校验框架返回空消息时客户端拿到不可读响应。
        String message = ErrorCode.VALIDATION_FAILED.defaultMessage();

        // 只有存在字段错误时才读取第一条错误，避免空集合访问导致新的异常覆盖原始校验错误。
        if (exception.getBindingResult().hasFieldErrors()) {
            // 现阶段统一返回第一条字段错误，避免一次响应过大；后续可扩展为字段错误列表。
            String fieldMessage = exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();

            // 校验消息存在有效文本时才覆盖兜底文案，保证响应消息始终可读。
            if (fieldMessage != null && !fieldMessage.isBlank()) {
                message = fieldMessage;
            }
        }

        return message;
    }

    /**
     * 提取路径变量或请求参数校验错误的对外消息。
     *
     * @param exception 参数约束校验异常
     * @return 可对外展示的校验错误消息
     */
    private String resolveConstraintViolationMessage(ConstraintViolationException exception) {
        // 默认文案作为兜底，避免约束集合为空或消息为空时返回空白错误。
        String message = ErrorCode.VALIDATION_FAILED.defaultMessage();

        // 遍历违规集合是为了兼容路径变量、查询参数等不同校验来源的异常结构。
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            // 只取第一条有效错误，保持当前响应结构简单；批量错误列表可在后续 API 规范中单独设计。
            String violationMessage = violation.getMessage();

            // 校验消息存在有效文本时立即返回，避免继续遍历造成无意义开销和不稳定排序依赖。
            if (violationMessage != null && !violationMessage.isBlank()) {
                message = violationMessage;
                break;
            }
        }

        return message;
    }

    /**
     * 解析业务异常的对外消息。
     *
     * @param exception 业务异常
     * @return 可对外展示的业务错误消息
     */
    private String resolveBusinessMessage(BusinessException exception) {
        // 业务异常消息由调用方提供，但必须防御空值，避免 API 返回空白错误。
        String message = exception.getMessage();

        // 空业务消息回退到错误码默认文案，保证客户端和日志都能看到稳定含义。
        if (message == null || message.isBlank()) {
            message = exception.errorCode().defaultMessage();
        }

        return message;
    }

    /**
     * 构造统一错误响应实体。
     *
     * @param status    HTTP 状态码
     * @param errorCode 业务错误码
     * @param message   对外错误消息
     * @return 带统一响应体的 HTTP 响应
     */
    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(HttpStatus status, ErrorCode errorCode, String message) {
        // HTTP 状态表达协议层结果，业务错误码表达应用层语义，两者同时返回便于客户端精确处理。
        return ResponseEntity.status(status).body(ApiResponse.failure(errorCode, message));
    }
}
