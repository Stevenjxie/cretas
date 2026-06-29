package com.cretas.aims.controller;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * BOM 对话式微调 ("对话调偏差") —— 直接调用 {@code bom_adjust} 工具的确定性入口。
 *
 * <p>前端产品 BOM 配置页的对话框: 输 "把冷冻猪舌用量改成120" → preview 展示旧→新 + 整张 BOM 表
 * → 确认 → 落库 + 返回更新后的表。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/adjust")
@Tag(name = "BOM对话微调", description = "对话式改 BOM 原料用量/损耗/单价")
@RequiredArgsConstructor
public class BomAdjustController {

    private final ToolRegistry toolRegistry;
    private final JwtUtil jwtUtil;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/preview")
    @Operation(summary = "预览 BOM 微调 (旧→新 + 整张 BOM 表), 不落库")
    public ApiResponse<Object> preview(@PathVariable String factoryId, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        return invoke(factoryId, body, true, request);
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "执行 BOM 微调")
    public ApiResponse<Object> apply(@PathVariable String factoryId, @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        return invoke(factoryId, body, false, request);
    }

    private ApiResponse<Object> invoke(String factoryId, Map<String, Object> body, boolean preview,
                                       HttpServletRequest request) {
        ToolExecutor executor = toolRegistry.getExecutor("bom_adjust")
                .orElseThrow(() -> new BusinessException(500, "bom_adjust 工具未注册"));
        try {
            ToolCall toolCall = ToolCall.of("bom-adjust-" + System.currentTimeMillis(), "bom_adjust",
                    MAPPER.writeValueAsString(body));
            Map<String, Object> context = new HashMap<>();
            context.put("factoryId", factoryId);
            context.put("userId", extractUserId(request)); // validateContext 必需
            String result = preview ? executor.preview(toolCall, context) : executor.execute(toolCall, context);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(result, Map.class);
            Object inner = parsed.get("data"); // 工具 buildSuccessResult 再包一层, 解包
            return ApiResponse.success(inner instanceof Map ? inner : parsed);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("[BOM-ADJUST] {} 失败: {}", preview ? "preview" : "apply", e.getMessage(), e);
            throw new BusinessException(500, "BOM 微调失败, 请稍后重试");
        }
    }

    private Long extractUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                return jwtUtil.getUserIdFromToken(auth.substring(7));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }
}
