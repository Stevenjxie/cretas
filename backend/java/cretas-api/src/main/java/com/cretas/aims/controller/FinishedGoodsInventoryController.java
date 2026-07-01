package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ①c 成品作投料来源 — 逐道录入 FG 投料下拉数据源 (07-01 客户: 选批次看到库里所有成品和半成品)。
 *
 * <p>与 {@link SemiFinishedInventoryController} (半成品) 平行: 返回该工厂可投料的成品批次
 * (AVAILABLE 且可用量 &gt; 0), 供逐道 feed picker 的「成品库存」下拉分组。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finished-goods")
@Tag(name = "成品投料库存", description = "①c 逐道录入可投料成品批次 (成品作投料来源)")
@RequiredArgsConstructor
public class FinishedGoodsInventoryController {

    private final FinishedGoodsFeedService finishedGoodsFeedService;

    /**
     * 工厂级可投料成品库存 (AVAILABLE 且可用量 &gt; 0), 供逐道 FG 投料下拉。
     *
     * <p><b>产品族过滤 (可选, 07-01 客户会议防呆)</b>: {@code productTypeId} 传当前计划产品 →
     * 后端解析成产品族仅返回同族成品 (猪蹄计划不显牛肉)。省略 → 全量。成品是终态, 无阶段过滤。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/inventory")
    @Operation(summary = "工厂级可投料成品库存 (含品名/生产日期/成本; 可选产品族过滤)")
    public ApiResponse<List<FinishedGoodsStockItem>> listInventory(
            @PathVariable String factoryId,
            @RequestParam(required = false) String productTypeId) {
        List<FinishedGoodsStockItem> rows = finishedGoodsFeedService.listAvailableForFeed(factoryId, productTypeId);
        log.debug("[FG-feed] listInventory factoryId={} productTypeId={} → {} rows",
                factoryId, productTypeId, rows.size());
        return ApiResponse.success(rows);
    }
}
