package com.cretas.aims.service.restock;

import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.ProductWarehouseDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonDayCell;
import com.cretas.aims.service.restock.dto.RestockHorizonProductRow;
import com.cretas.aims.service.restock.dto.RestockRow;
import com.cretas.aims.service.restock.dto.WarehouseRestockBoardDTO;
import com.cretas.aims.service.restock.dto.WarehouseRestockRow;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RestockBoardService {

    private static final String BOX_UNIT = "\u76d2";

    private static final List<SalesOrderStatus> DEMAND_STATUSES = List.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.PENDING_FINANCE_REVIEW,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED);

    private static final List<ProductionPlanStatus> SCHEDULED_STATUSES = List.of(
            ProductionPlanStatus.PLANNED,
            ProductionPlanStatus.PENDING);

    private final SalesOrderItemRepository salesOrderItemRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ConversionRepository conversionRepository;
    private final MaterialBatchRepository materialBatchRepository;

    @Transactional(readOnly = true)
    public RestockBoardDTO getRestockBoard(String factoryId, LocalDate deliveryDate) {
        List<ProductDemandProjection> demands = salesOrderItemRepository.sumDemandByProductForDeliveryDate(
                factoryId, deliveryDate, DEMAND_STATUSES);

        List<RestockRow> rows = new ArrayList<>();
        for (ProductDemandProjection demand : demands) {
            rows.add(buildRow(factoryId, demand));
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

    @Transactional(readOnly = true)
    public RestockHorizonDTO getRestockHorizon(String factoryId, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);
        if (daysBetween > 31) {
            throw new IllegalArgumentException("restock horizon supports at most 32 days");
        }

        List<LocalDate> dates = startDate.datesUntil(endDate.plusDays(1)).toList();
        Map<String, ProductHorizonDemand> products = new LinkedHashMap<>();

        for (LocalDate date : dates) {
            List<ProductDemandProjection> demands = salesOrderItemRepository.sumDemandByProductForDeliveryDate(
                    factoryId, date, DEMAND_STATUSES);
            for (ProductDemandProjection demand : demands) {
                ProductHorizonDemand product = products.computeIfAbsent(
                        demand.getProductTypeId(),
                        id -> new ProductHorizonDemand(id, demand.getProductName()));
                product.addDemand(date, nz(demand.getDemand()));
                if (demand.getMinUnit() != null && !Objects.equals(demand.getMinUnit(), demand.getMaxUnit())) {
                    product.warning = append(product.warning, "订单单位不一致, 需人工核对");
                    product.unitInconsistent = true;
                }
            }
        }

        List<RestockHorizonProductRow> rows = products.values().stream()
                .map(product -> buildHorizonRow(factoryId, dates, product))
                .toList();

        int shortfallCount = (int) rows.stream()
                .filter(row -> nz(row.getEndingShortfallQty()).compareTo(BigDecimal.ZERO) > 0)
                .count();

        return RestockHorizonDTO.builder()
                .startDate(startDate)
                .endDate(endDate)
                .dates(dates)
                .rows(rows)
                .summary(RestockHorizonDTO.Summary.builder()
                        .totalProducts(rows.size())
                        .shortfallProducts(shortfallCount)
                        .fullyCoveredProducts(rows.size() - shortfallCount)
                        .days(dates.size())
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public WarehouseRestockBoardDTO getRestockBoardByWarehouse(String factoryId, LocalDate deliveryDate) {
        List<ProductWarehouseDemandProjection> demands =
                salesOrderItemRepository.sumDemandByProductAndWarehouseForDeliveryDate(
                        factoryId, deliveryDate, DEMAND_STATUSES);

        List<WarehouseRestockRow> rows = new ArrayList<>();
        for (ProductWarehouseDemandProjection demand : demands) {
            rows.add(buildWarehouseRow(factoryId, demand));
        }

        int shortfallCount = (int) rows.stream().filter(r -> "SHORTFALL".equals(r.getStatus())).count();
        int satisfiedCount = (int) rows.stream().filter(r -> "SATISFIED".equals(r.getStatus())).count();
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

    private RestockHorizonProductRow buildHorizonRow(
            String factoryId,
            List<LocalDate> dates,
            ProductHorizonDemand demand) {
        Optional<ProductType> productType = productTypeRepository.findById(demand.productTypeId);
        BigDecimal gramsPerUnit = productType.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield = productType.map(ProductType::getWipToFgYield).orElse(null);
        BigDecimal effectiveWipYield = wipYield != null ? wipYield : BigDecimal.ONE;

        BigDecimal fg = finishedGoods(factoryId, demand.productTypeId);
        BigDecimal fgShippable = finishedGoodsShippable(factoryId, demand.productTypeId);
        BigDecimal wipKg = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, demand.productTypeId));
        BigDecimal wipBox = RestockUnitConverter.kgToBox(wipKg.multiply(effectiveWipYield), gramsPerUnit);
        BigDecimal scheduled = scheduled(factoryId, demand.productTypeId);
        ConversionSnapshot conversion = resolveConversion(factoryId, demand.productTypeId, effectiveWipYield);

        String warning = demand.warning;
        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            warning = append(warning, "未配置规格克重, 半成品无法折成品");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            warning = append(warning, "未配置半成品到成品出成率, 半成品按 1:1 估算");
        }
        warning = append(warning, conversion.warning);

        BigDecimal startingCover = fg.add(nz(wipBox)).add(scheduled);
        BigDecimal remaining = startingCover;
        BigDecimal endingShortfall = BigDecimal.ZERO;
        List<RestockHorizonDayCell> cells = new ArrayList<>();

        for (LocalDate date : dates) {
            if (demand.unitInconsistent) {
                cells.add(RestockHorizonDayCell.builder()
                        .deliveryDate(date)
                        .demandQty(null)
                        .availableBeforeDemandQty(remaining)
                        .availableAfterDemandQty(remaining)
                        .shortfallQty(null)
                        .warning("订单单位不一致, 无法安全分配每日需求")
                        .status("UNIT_INCONSISTENT")
                        .build());
                continue;
            }
            BigDecimal dayDemand = nz(demand.demandByDate.get(date));
            BigDecimal before = remaining;
            BigDecimal shortfall = dayDemand.subtract(before).max(BigDecimal.ZERO);
            BigDecimal after = before.subtract(dayDemand).max(BigDecimal.ZERO);
            endingShortfall = endingShortfall.add(shortfall);
            remaining = after;
            cells.add(RestockHorizonDayCell.builder()
                    .deliveryDate(date)
                    .demandQty(dayDemand)
                    .availableBeforeDemandQty(before)
                    .availableAfterDemandQty(after)
                    .shortfallQty(shortfall)
                    .status(shortfall.compareTo(BigDecimal.ZERO) > 0 ? "SHORTFALL" : "COVERED")
                    .build());
        }

        return RestockHorizonProductRow.builder()
                .productTypeId(demand.productTypeId)
                .productName(demand.productName)
                .unit(BOX_UNIT)
                .totalDemandQty(demand.totalDemand())
                .fgAvailableQty(fg)
                .fgShippableQty(fgShippable)
                .wipAvailableQty(wipKg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .rawAvailableQty(conversion.rawAvailableQty)
                .rawUnit(conversion.rawUnit)
                .rawMaterialName(conversion.rawMaterialName)
                .rawEstimatedFgQty(conversion.rawEstimatedFgQty)
                .rawToWipYield(conversion.rawToWipYield)
                .wipToFgYield(effectiveWipYield)
                .rawToFgYield(conversion.rawToFgYield)
                .startingCoverQty(startingCover)
                .endingAvailableQty(remaining)
                .endingShortfallQty(endingShortfall)
                .conversionWarning(warning)
                .days(cells)
                .build();
    }

    private RestockRow buildRow(String factoryId, ProductDemandProjection demand) {
        String productTypeId = demand.getProductTypeId();
        Optional<ProductType> productType = productTypeRepository.findById(productTypeId);
        BigDecimal gramsPerUnit = productType.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield = productType.map(ProductType::getWipToFgYield).orElse(null);
        BigDecimal effectiveWipYield = wipYield != null ? wipYield : BigDecimal.ONE;

        String warning = null;
        boolean unitInconsistent =
                demand.getMinUnit() != null && !Objects.equals(demand.getMinUnit(), demand.getMaxUnit());
        BigDecimal fg = finishedGoods(factoryId, productTypeId);
        BigDecimal wipKg = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, productTypeId));
        BigDecimal wipBox = RestockUnitConverter.kgToBox(wipKg.multiply(effectiveWipYield), gramsPerUnit);
        BigDecimal scheduled = scheduled(factoryId, productTypeId);

        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            warning = append(warning, "未配置规格克重, 半成品无法折盒");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            warning = append(warning, "未配置半成品到成品出成率, 半成品按 1:1 估算");
        }

        RestockRow.RestockRowBuilder builder = RestockRow.builder()
                .productTypeId(productTypeId)
                .productName(demand.getProductName())
                .unit(BOX_UNIT)
                .fgAvailableQty(fg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .wipIsEstimated(true);

        if (unitInconsistent) {
            warning = append(warning, "订单单位不一致, 需人工核对");
            builder.demandQty(null)
                    .totalAvailableQty(null)
                    .shortfallQty(null)
                    .status("UNIT_INCONSISTENT");
        } else {
            BigDecimal demandQty = nz(demand.getDemand());
            BigDecimal total = fg.add(nz(wipBox)).add(scheduled);
            BigDecimal shortfall = demandQty.subtract(total).max(BigDecimal.ZERO);
            builder.demandQty(demandQty)
                    .totalAvailableQty(total)
                    .shortfallQty(shortfall)
                    .status(shortfall.compareTo(BigDecimal.ZERO) == 0 ? "SATISFIED" : "SHORTFALL");
        }

        return builder.conversionWarning(warning).build();
    }

    private WarehouseRestockRow buildWarehouseRow(String factoryId, ProductWarehouseDemandProjection demand) {
        String productTypeId = demand.getProductTypeId();
        Optional<ProductType> productType = productTypeRepository.findById(productTypeId);
        BigDecimal gramsPerUnit = productType.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield = productType.map(ProductType::getWipToFgYield).orElse(null);
        BigDecimal effectiveWipYield = wipYield != null ? wipYield : BigDecimal.ONE;

        String warning = null;
        boolean unitInconsistent =
                demand.getMinUnit() != null && !Objects.equals(demand.getMinUnit(), demand.getMaxUnit());
        BigDecimal fg = finishedGoods(factoryId, productTypeId);
        BigDecimal wipKg = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, productTypeId));
        BigDecimal wipBox = RestockUnitConverter.kgToBox(wipKg.multiply(effectiveWipYield), gramsPerUnit);
        BigDecimal scheduled = scheduled(factoryId, productTypeId);

        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            warning = append(warning, "未配置规格克重, 半成品无法折盒");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            warning = append(warning, "未配置半成品到成品出成率, 半成品按 1:1 估算");
        }

        WarehouseRestockRow.WarehouseRestockRowBuilder builder = WarehouseRestockRow.builder()
                .productTypeId(productTypeId)
                .productName(demand.getProductName())
                .unit(BOX_UNIT)
                .destWarehouseCode(demand.getDestWarehouseCode())
                .destWarehouseName(demand.getDestWarehouseName())
                .fgAvailableQty(fg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .wipIsEstimated(true);

        if (unitInconsistent) {
            warning = append(warning, "订单单位不一致, 需人工核对");
            builder.warehouseDemandQty(null)
                    .totalAvailableQty(null)
                    .shortfallQty(null)
                    .status("UNIT_INCONSISTENT");
        } else {
            BigDecimal demandQty = nz(demand.getDemand());
            BigDecimal total = fg.add(nz(wipBox)).add(scheduled);
            BigDecimal shortfall = demandQty.subtract(total).max(BigDecimal.ZERO);
            builder.warehouseDemandQty(demandQty)
                    .totalAvailableQty(total)
                    .shortfallQty(shortfall)
                    .status(shortfall.compareTo(BigDecimal.ZERO) == 0 ? "SATISFIED" : "SHORTFALL");
        }

        return builder.conversionWarning(warning).build();
    }

    private ConversionSnapshot resolveConversion(String factoryId, String productTypeId, BigDecimal wipToFgYield) {
        List<MaterialProductConversion> activeConversions = conversionRepository
                .findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .stream()
                .filter(conversion -> Boolean.TRUE.equals(conversion.getIsActive()))
                .toList();

        if (activeConversions.isEmpty()) {
            return ConversionSnapshot.builder()
                    .warning("未配置原料到成品转换率, 原料无法估算可产成品")
                    .build();
        }

        if (activeConversions.size() > 1) {
            return ConversionSnapshot.builder()
                    .warning("存在多个启用的原料转换率, 需配置主原料或瓶颈原料后再估算")
                    .build();
        }

        MaterialProductConversion conversion = activeConversions.get(0);
        BigDecimal rawAvailable = nz(materialBatchRepository.sumAvailableQuantityByMaterialType(
                factoryId, conversion.getMaterialTypeId()));
        BigDecimal rawToFg = conversion.getConversionRate();
        BigDecimal rawEstimatedFg = rawToFg != null ? rawAvailable.multiply(rawToFg) : null;
        BigDecimal rawToWip = null;
        if (rawToFg != null && wipToFgYield != null && wipToFgYield.compareTo(BigDecimal.ZERO) > 0) {
            rawToWip = rawToFg.divide(wipToFgYield, 4, RoundingMode.HALF_UP);
        }

        RawMaterialType materialType = conversion.getMaterialType();
        return ConversionSnapshot.builder()
                .rawAvailableQty(rawAvailable)
                .rawUnit(materialType != null ? materialType.getUnit() : null)
                .rawMaterialName(materialType != null ? materialType.getName() : null)
                .rawEstimatedFgQty(rawEstimatedFg)
                .rawToFgYield(rawToFg)
                .rawToWipYield(rawToWip)
                .warning(rawToFg == null ? "原料到成品转换率为空, 原料无法估算可产成品" : null)
                .build();
    }

    /**
     * SP10 §RD-1: 备货看板可售成品库存（盒），排除研发/中试库 (WH-RD)。
     * 试制批次产出进研发库，不计入生产计划决策依据。
     */
    private BigDecimal finishedGoods(String factoryId, String productTypeId) {
        return nz(finishedGoodsBatchRepository.sumSaleableQuantityByProductTypeAndUnitExcludeRd(
                factoryId, productTypeId, BOX_UNIT, WarehouseCodes.WH_RD));
    }

    /**
     * WH-LOG 物流仓可发量（盒）。仅统计物流仓，车间仓（WH-WKS）成品不计入。
     * 不替代 {@link #finishedGoods} 用于覆盖率计算——两者独立并存（T134 spec）。
     */
    private BigDecimal finishedGoodsShippable(String factoryId, String productTypeId) {
        return nz(finishedGoodsBatchRepository.sumShippableQuantityByProductTypeAndWarehouseCodeAndUnit(
                factoryId, productTypeId, WarehouseCodes.WH_LOG, BOX_UNIT));
    }

    private BigDecimal scheduled(String factoryId, String productTypeId) {
        return nz(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(
                factoryId, productTypeId, SCHEDULED_STATUSES));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String append(String existing, String add) {
        if (add == null || add.isBlank()) {
            return existing;
        }
        return existing == null ? add : existing + "; " + add;
    }

    private static class ProductHorizonDemand {
        private final String productTypeId;
        private final String productName;
        private final Map<LocalDate, BigDecimal> demandByDate = new LinkedHashMap<>();
        private boolean unitInconsistent;
        private String warning;

        private ProductHorizonDemand(String productTypeId, String productName) {
            this.productTypeId = productTypeId;
            this.productName = productName;
        }

        private void addDemand(LocalDate date, BigDecimal quantity) {
            demandByDate.merge(date, quantity, BigDecimal::add);
        }

        private BigDecimal totalDemand() {
            return demandByDate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @Builder
    private static class ConversionSnapshot {
        private BigDecimal rawAvailableQty;
        private String rawUnit;
        private String rawMaterialName;
        private BigDecimal rawEstimatedFgQty;
        private BigDecimal rawToWipYield;
        private BigDecimal rawToFgYield;
        private String warning;
    }
}
