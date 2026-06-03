package com.cretas.aims.service.restock;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.ProductWarehouseDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockRow;
import com.cretas.aims.service.restock.dto.WarehouseRestockBoardDTO;
import com.cretas.aims.service.restock.dto.WarehouseRestockRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 全天备货看板: 订单需求 vs 可用结存 (成品FG + 在产WIP折盒 + 已排产) vs 缺口, 产品级聚合, 只读实时。
 *
 * <p>三层互不相交:
 * <ul>
 *   <li>成品FG — FinishedGoodsBatch AVAILABLE (盒)</li>
 *   <li>在产WIP — SemiFinishedInventory availableQuantity (kg → 折盒)</li>
 *   <li>已排产 — ProductionPlan {PLANNED,PENDING} plannedQuantity (仅未开工, 避免 IN_PROGRESS 重复计算)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RestockBoardService {

    /** 有效需求订单状态 (排除 DRAFT/FINANCE_REJECTED/CANCELLED)。 */
    private static final List<SalesOrderStatus> DEMAND_STATUSES = List.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.PENDING_FINANCE_REVIEW,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED);

    /**
     * 已排产 = 仅未开工计划 (IN_PROGRESS/PAUSED 产出已进 WIP/FG, 再算则重复计算)。
     * PREPARED/CANCELLED/COMPLETED 均排除。
     */
    private static final List<ProductionPlanStatus> SCHEDULED_STATUSES = List.of(
            ProductionPlanStatus.PLANNED,
            ProductionPlanStatus.PENDING);

    private final SalesOrderItemRepository salesOrderItemRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductTypeRepository productTypeRepository;

    @Transactional(readOnly = true)
    public RestockBoardDTO getRestockBoard(String factoryId, LocalDate deliveryDate) {
        List<ProductDemandProjection> demands =
                salesOrderItemRepository.sumDemandByProductForDeliveryDate(
                        factoryId, deliveryDate, DEMAND_STATUSES);

        List<RestockRow> rows = new ArrayList<>();
        for (ProductDemandProjection d : demands) {
            rows.add(buildRow(factoryId, d));
        }

        int shortfallCount = (int) rows.stream().filter(r -> "SHORTFALL".equals(r.getStatus())).count();
        int satisfiedCount = (int) rows.stream().filter(r -> "SATISFIED".equals(r.getStatus())).count();

        return RestockBoardDTO.builder()
                .deliveryDate(deliveryDate)
                .rows(rows)
                .summary(RestockBoardDTO.Summary.builder()
                        .totalProducts(rows.size())
                        .shortfallProducts(shortfallCount)
                        .fullySatisfiedProducts(satisfiedCount)
                        .build())
                .build();
    }

    private RestockRow buildRow(String factoryId, ProductDemandProjection d) {
        String productTypeId = d.getProductTypeId();
        Optional<ProductType> ptOpt = productTypeRepository.findById(productTypeId);
        BigDecimal gramsPerUnit = ptOpt.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield    = ptOpt.map(ProductType::getWipToFgYield).orElse(null);

        String warning = null;

        // F2: 同产品订单行 unit 不一致 → demandQty/total/shortfall null, 不静默累加
        boolean unitInconsistent =
                d.getMinUnit() != null && !Objects.equals(d.getMinUnit(), d.getMaxUnit());

        // P4 防御: 只汇总 unit='盒' 的 FG 行, 防止换算前后混单位加和
        BigDecimal fg = nz(finishedGoodsBatchRepository
                .sumAvailableQuantityByProductTypeAndUnit(factoryId, productTypeId, "盒"));

        // 在产 WIP (kg) → 折盒 (×出率 effYield / gramsPerUnit)
        BigDecimal wipKg   = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, productTypeId));
        BigDecimal effYield = wipYield != null ? wipYield : BigDecimal.ONE;
        BigDecimal wipBox   = RestockUnitConverter.kgToBox(wipKg.multiply(effYield), gramsPerUnit);

        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            // gramsPerUnit 未配置, 无法折盒
            warning = append(warning, "未配置规格(gramsPerUnit), 在产无法折盒");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            // gramsPerUnit 存在但 wipToFgYield 未配置, 按 1:1 估算
            warning = append(warning, "未配置在产出率, 按1:1估算");
        }

        // 已排产 (盒, 仅未开工计划)
        BigDecimal scheduled = nz(productionPlanRepository
                .sumPlannedQuantityByProductAndStatuses(factoryId, productTypeId, SCHEDULED_STATUSES));

        RestockRow.RestockRowBuilder b = RestockRow.builder()
                .productTypeId(productTypeId)
                .productName(d.getProductName())
                .unit("盒")
                .fgAvailableQty(fg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .wipIsEstimated(true);

        if (unitInconsistent) {
            warning = append(warning, "订单行单位不一致, 需人工核对");
            b.demandQty(null)
             .totalAvailableQty(null)
             .shortfallQty(null)
             .status("UNIT_INCONSISTENT");
        } else {
            BigDecimal demand    = nz(d.getDemand());
            BigDecimal total     = fg.add(nz(wipBox)).add(scheduled);
            BigDecimal shortfall = demand.subtract(total).max(BigDecimal.ZERO);
            b.demandQty(demand)
             .totalAvailableQty(total)
             .shortfallQty(shortfall)
             .status(shortfall.compareTo(BigDecimal.ZERO) == 0 ? "SATISFIED" : "SHORTFALL");
        }

        return b.conversionWarning(warning).build();
    }

    // ==================== P3 多仓备货看板 ====================

    /**
     * P3 多仓备货看板: 同交货日按产品 × 目的仓展开需求, 库存三层仍为全厂共享池。
     *
     * <p>向后兼容: 旧订单行 {@code destWarehouseCode = null} 被 COALESCE 归入"未分仓"桶,
     * 与 {@link #getRestockBoard} 的产品级结果等价 (全在"未分仓"桶)。
     *
     * <p>注: 供给侧 (FG/WIP/已排产) 仍为全厂级, 一个产品在所有仓行共享同一库存数字。
     * 这是 P3 建模设计决策 — 六扇门无仓级库存分区, 全厂统一调度。
     *
     * @param factoryId    工厂 ID
     * @param deliveryDate 要求交货日期
     * @return 按产品 × 仓分组的备货看板
     */
    @Transactional(readOnly = true)
    public WarehouseRestockBoardDTO getRestockBoardByWarehouse(String factoryId, LocalDate deliveryDate) {
        List<ProductWarehouseDemandProjection> demands =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        factoryId, deliveryDate, DEMAND_STATUSES);

        List<WarehouseRestockRow> rows = new ArrayList<>();
        for (ProductWarehouseDemandProjection d : demands) {
            rows.add(buildWarehouseRow(factoryId, d));
        }

        int shortfallCount  = (int) rows.stream().filter(r -> "SHORTFALL".equals(r.getStatus())).count();
        int satisfiedCount  = (int) rows.stream().filter(r -> "SATISFIED".equals(r.getStatus())).count();
        long warehouseCount = rows.stream().map(WarehouseRestockRow::getDestWarehouseCode).distinct().count();

        return WarehouseRestockBoardDTO.builder()
                .deliveryDate(deliveryDate)
                .rows(rows)
                .summary(WarehouseRestockBoardDTO.Summary.builder()
                        .totalRows(rows.size())
                        .shortfallRows(shortfallCount)
                        .satisfiedRows(satisfiedCount)
                        .warehouseCount(warehouseCount)
                        .build())
                .build();
    }

    private WarehouseRestockRow buildWarehouseRow(String factoryId, ProductWarehouseDemandProjection d) {
        String productTypeId = d.getProductTypeId();
        Optional<ProductType> ptOpt = productTypeRepository.findById(productTypeId);
        BigDecimal gramsPerUnit = ptOpt.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield    = ptOpt.map(ProductType::getWipToFgYield).orElse(null);

        String warning = null;

        // F2: 单位不一致检测 (同产品+仓组合, 跨行不同 unit)
        boolean unitInconsistent =
                d.getMinUnit() != null && !Objects.equals(d.getMinUnit(), d.getMaxUnit());

        // 供给侧: 全厂共享池 (P4 防御: 只汇总 unit='盒' 的 FG 行)
        BigDecimal fg = nz(finishedGoodsBatchRepository
                .sumAvailableQuantityByProductTypeAndUnit(factoryId, productTypeId, "盒"));

        BigDecimal wipKg    = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, productTypeId));
        BigDecimal effYield = wipYield != null ? wipYield : BigDecimal.ONE;
        BigDecimal wipBox   = RestockUnitConverter.kgToBox(wipKg.multiply(effYield), gramsPerUnit);

        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            warning = append(warning, "未配置规格(gramsPerUnit), 在产无法折盒");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            warning = append(warning, "未配置在产出率, 按1:1估算");
        }

        BigDecimal scheduled = nz(productionPlanRepository
                .sumPlannedQuantityByProductAndStatuses(factoryId, productTypeId, SCHEDULED_STATUSES));

        WarehouseRestockRow.WarehouseRestockRowBuilder b = WarehouseRestockRow.builder()
                .productTypeId(productTypeId)
                .productName(d.getProductName())
                .unit("盒")
                .destWarehouseCode(d.getDestWarehouseCode())
                .destWarehouseName(d.getDestWarehouseName())
                .fgAvailableQty(fg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .wipIsEstimated(true);

        if (unitInconsistent) {
            warning = append(warning, "订单行单位不一致, 需人工核对");
            b.warehouseDemandQty(null)
             .totalAvailableQty(null)
             .shortfallQty(null)
             .status("UNIT_INCONSISTENT");
        } else {
            BigDecimal demand    = nz(d.getDemand());
            BigDecimal total     = fg.add(nz(wipBox)).add(scheduled);
            BigDecimal shortfall = demand.subtract(total).max(BigDecimal.ZERO);
            b.warehouseDemandQty(demand)
             .totalAvailableQty(total)
             .shortfallQty(shortfall)
             .status(shortfall.compareTo(BigDecimal.ZERO) == 0 ? "SATISFIED" : "SHORTFALL");
        }

        return b.conversionWarning(warning).build();
    }

    /** null 安全: 把 null 当 0 处理。 */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** 拼接警告字符串, 用"; "分隔。 */
    private static String append(String existing, String add) {
        return existing == null ? add : existing + "; " + add;
    }
}
