package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.production.ProductionProgressDashboardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * B8 生产进度数字打屏看板
 *
 * 客户原话 4802-4811s: "可以做一个数字打屏嘛...看到当天所有商品工序生产的一个进度"
 *
 * MVP: 按当天生产计划 + 报工数据聚合,展示大屏进度
 * - Plan 级进度: reportedQuantity / plannedQuantity
 * - Process 级进度: 按 process_category 汇总该 plan 关联的报工
 *   (通过 product_name 匹配,因为 production_reports 无直接 plan_id)
 *
 * <p>B2-fix (2026-07-06): 查询逻辑已抽到 {@link ProductionProgressDashboardService}
 * (AI Tool {@code production_progress_dashboard} 也复用同一份逻辑)。此前 Controller
 * 一直保留着一份重复的旧实现(未同步任何后续修复), 导致 web-admin 打屏看板与 AI Tool
 * 口径实际不一致 —— 现在统一委托 Service, 避免双份实现再次分叉。
 *
 * @author Cretas Team (B8 客户需求)
 * @since 2026-04-08
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/dashboard")
@RequireModule("production_plan")
public class ProductionProgressDashboardController {

    @Autowired
    private ProductionProgressDashboardService dashboardService;

    @GetMapping("/production-progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProductionProgress(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            Map<String, Object> data = dashboardService.getDashboard(factoryId, date);
            return ResponseEntity.ok(ApiResponse.success("查询成功", data));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("[B8 打屏] 查询失败 factoryId={}", factoryId, e);
            throw new BusinessException(500, "查询生产进度失败: " + e.getMessage(), e);
        }
    }
}
