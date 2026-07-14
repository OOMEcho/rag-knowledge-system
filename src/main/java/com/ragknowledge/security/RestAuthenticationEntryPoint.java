package com.ragknowledge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragknowledge.common.response.ApiResponse;
import com.ragknowledge.common.response.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证失败统一入口，输出 ApiResponse 格式的 401 响应
 *
 * @author xuesong.lei
 * @since 2026-07-03
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 认证失败发生在 Security 过滤器层，全局异常处理器无法接管，必须在这里手工输出统一错误协议。
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 复用统一 ApiResponse 结构，保证客户端对 401 与业务错误使用同一套解析逻辑。
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.failure(ErrorCode.UNAUTHORIZED, "缺少或无效的 API Key")));
    }
}
