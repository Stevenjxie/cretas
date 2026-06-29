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
 * AI 智能建产品 (飞轮衔接) —— 直接调用 {@code product_create} 工具, <b>绕过 NL 意图识别</b>。
 *
 * <p>"说一句话建产品"经 NL 识别会被 MATERIAL_BATCH_CREATE / PRODUCT_UPDATE 等既有意图抢占,
 * 故提供确定性的专用入口: 前端「AI 智能建产品」对话框直接 POST 产品名 → preview 展示飞轮计划
 * (最相似产品 + 继承的工序链 + BOM/调料建议) → 确认 → create 落库 + 继承工序链。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai-product-create")
@Tag(name = "AI智能建产品", description = "飞轮衔接建产品, 绕过 NL 意图识别的确定性入口")
@RequiredArgsConstructor
public class AiProductCreateController {

    private final ToolRegistry toolRegistry;
    private final JwtUtil jwtUtil;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/preview")
    @Operation(summary = "预览飞轮建产品计划(最相似产品 + 继承工序链 + BOM/调料建议), 只读不落库")
    public ApiResponse<Object> preview(@PathVariable String factoryId, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        return invoke(factoryId, body, true, request);
    }

    @RequirePermission({"production:read_write", "system:read_write"})
    @PostMapping
    @Operation(summary = "执行: 建产品 + 飞轮自动继承相似产品工序链(大框架)")
    public ApiResponse<Object> create(@PathVariable String factoryId, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        return invoke(factoryId, body, false, request);
    }

    private ApiResponse<Object> invoke(String factoryId, Map<String, Object> body, boolean preview,
                                       HttpServletRequest request) {
        ToolExecutor executor = toolRegistry.getExecutor("product_create")
                .orElseThrow(() -> new BusinessException(500, "product_create 工具未注册"));
        try {
            ToolCall toolCall = ToolCall.of(
                    "ai-create-" + System.currentTimeMillis(),
                    "product_create",
                    MAPPER.writeValueAsString(body));
            Map<String, Object> context = new HashMap<>();
            context.put("factoryId", factoryId);
            // AbstractTool.validateContext 要求 context 同时含 factoryId + userId
            context.put("userId", extractUserId(request));
            String result = preview
                    ? executor.preview(toolCall, context)
                    : executor.execute(toolCall, context);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(result, Map.class);
            return ApiResponse.success(parsed);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("[AI-PRODUCT-CREATE] {} 失败: {}", preview ? "preview" : "create", e.getMessage(), e);
            throw new BusinessException(500, "AI 建产品失败, 请稍后重试");
        }
    }

    /** 从 Authorization 头解析 userId (validateContext 必需; 解析失败返 null 不阻断只读 preview)。 */
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
