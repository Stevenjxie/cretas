package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.config.CostSettingsDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.config.FactoryCostSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 工厂成本设置 —— 工时单价配置入口 (修复报工 warning 指向的 dead-end).
 *
 * <p>GET 取当前工时单价 (null=未配,前端显示按默认 ¥26);
 * PUT upsert 工时单价。配置后 {@code resolveLaborRate} 读到该值,人工成本按真实单价计入。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/config/cost-settings")
@Tag(name = "工厂成本设置", description = "工时单价等工厂级成本参数配置")
@RequiredArgsConstructor
public class FactoryCostSettingsController {

    private final FactoryCostSettingsService service;

    @GetMapping
    @Operation(summary = "取工厂工时单价 (null=未配置,按默认 ¥26/工时)")
    public ApiResponse<CostSettingsDTO> get(@PathVariable String factoryId) {
        return ApiResponse.success(new CostSettingsDTO(service.getLaborHourlyRate(factoryId)));
    }

    @RequirePermission({"production:read_write", "finance:read_write"})
    @PutMapping
    @Operation(summary = "配置工厂工时单价 (¥/工时, 必须 > 0)")
    public ApiResponse<CostSettingsDTO> put(@PathVariable String factoryId,
                                            @RequestBody CostSettingsDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求体不可为空");
        }
        // >0 校验在 Service 层 (抛 400)
        return ApiResponse.success(
                new CostSettingsDTO(service.upsertLaborHourlyRate(factoryId, request.getLaborHourlyRate())));
    }
}
