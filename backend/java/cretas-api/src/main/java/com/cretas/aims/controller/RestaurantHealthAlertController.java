package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.alerts.restaurant.RestaurantHealthAlertBridgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 餐饮经营体检预警 — 手动 / on-demand 触发接口, 2026-07-11.
 *
 * <p>标准触发路径是 {@code AlertRestaurantHealthCheckScheduler} 每 6 小时定时扫描;
 * 本 endpoint 补充"立即刷新一次"的手动路径 (老板/管理员改完数据想马上看到最新
 * standing alert, 不想等最多 6 小时的下一轮 cron). {@code sweepFactory} 幂等
 * (dedup + auto-resolve), 两条触发路径自由并存不冲突, 反复调用无副作用堆积.
 *
 * @since 2026-07-11
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/alerts/restaurant-health-check")
@Tag(name = "餐饮经营体检预警", description = "反回扣/经营异常 standing alert 手动触发")
@RequiredArgsConstructor
public class RestaurantHealthAlertController {

    private final RestaurantHealthAlertBridgeService bridgeService;

    @PostMapping("/sweep")
    @Operation(summary = "立即刷新经营体检预警",
               description = "拉取最新体检报告, 同步 standing alert (新建/更新/自动解决), 幂等")
    @RequireRole({"factory_super_admin", "permission_admin", "restaurant_owner"})
    public ApiResponse<Map<String, Object>> sweepNow(@PathVariable String factoryId) {
        log.info("POST /alerts/restaurant-health-check/sweep factoryId={}", factoryId);
        Map<String, Object> result = bridgeService.sweepFactory(factoryId);
        if (!Boolean.TRUE.equals(result.get("available"))) {
            return ApiResponse.success("体检报告暂不可用 — " + result.get("reason"), result);
        }
        return ApiResponse.success("经营体检预警已刷新", result);
    }
}
