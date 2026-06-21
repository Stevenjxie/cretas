package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单级单盒成本拆分 — 单一权威服务 (T1 谱系遍历 + T2 上游成本回溯 + T3 人工归集).
 *
 * <p>原料成本沿 {@link MaterialConsumption} 边回溯: 若上游 {@link MaterialBatch} 由某生产批次产出
 * ({@code source_doc_type='PRODUCTION_BATCH'}), 递归进其消耗 (多级); 否则该消耗即叶子, 成本=其
 * 实测 total_cost (= 该上游链累计成本)。混批按各批实测投料量×各自单价精确归集 (非按重量糊平均)。
 *
 * <p>人工/调料/包装来自该批 {@code ProductionReport} 逐道报工 (复用 {@link YieldReportService#getYield});
 * 首道(原料道)材料不计入 (原料由上游 traced 承载, 避免双计)。
 *
 * <p>价格脱敏: maskPrice 时金额字段全置 null, 仅保留投料量/重量占比 (红线: procurement:price:view)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCostBreakdownService {

    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final YieldReportService yieldReportService;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_DEPTH = 10;

    public OrderCostBreakdownDTO compute(String factoryId, String orderId, boolean maskPrice) {
        List<String> planIds = planRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId).stream()
                .map(ProductionPlan::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (planIds.isEmpty()) {
            return empty(orderId, maskPrice);
        }
        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);
        if (batches.isEmpty()) {
            return empty(orderId, maskPrice);
        }

        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal seasoning = BigDecimal.ZERO;
        BigDecimal packaging = BigDecimal.ZERO;
        BigDecimal raw = BigDecimal.ZERO;
        int boxCount = 0;
        List<SourceCost> sources = new ArrayList<>();

        for (ProductionBatch b : batches) {
            BatchYieldDTO y = yieldReportService.getYield(factoryId, b.getId());
            if (y != null) {
                if (y.getTotalLaborCost() != null) {
                    labor = labor.add(y.getTotalLaborCost());
                }
                List<StepYieldDTO> steps = y.getSteps() == null ? List.of() : y.getSteps();
                for (int i = 0; i < steps.size(); i++) {
                    BigDecimal m = steps.get(i).getMaterialCost();
                    if (m == null) {
                        continue;
                    }
                    if (i == steps.size() - 1) {
                        packaging = packaging.add(m);   // 末道 = 包装材料
                    } else if (i > 0) {
                        seasoning = seasoning.add(m);    // 中间道 = 调料 (首道原料由上游 traced 承载, 不计)
                    }
                }
            }
            if (b.getQuantity() != null) {
                boxCount += b.getQuantity().intValue();
            }
            for (MaterialConsumption c : consumptionRepository.findByProductionBatchIdAndFactoryId(b.getId(), factoryId)) {
                BigDecimal[] leaf = traceCost(factoryId, c, 1);
                BigDecimal cost = leaf[0];
                int depth = leaf[1].intValue();
                raw = raw.add(cost);
                MaterialBatch mb = c.getBatchId() == null ? null
                        : materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
                sources.add(SourceCost.builder()
                        .batchId(c.getBatchId())
                        .batchName(mb != null ? mb.getBatchNumber() : c.getBatchId())
                        .quantity(c.getQuantity())
                        .unit(mb != null ? mb.getQuantityUnit() : "kg")
                        .unitPrice(c.getUnitPrice())
                        .cost(cost)
                        .depth(depth)
                        .build());
            }
        }

        BigDecimal total = labor.add(seasoning).add(packaging).add(raw);
        BigDecimal perBox = boxCount > 0 ? total.divide(BigDecimal.valueOf(boxCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal totalQty = sources.stream().map(s -> nz(s.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (SourceCost s : sources) {
            s.setWeightSharePct(totalQty.signum() > 0
                    ? nz(s.getQuantity()).multiply(HUNDRED).divide(totalQty, 1, RoundingMode.HALF_UP) : null);
            s.setCostSharePct(raw.signum() > 0
                    ? nz(s.getCost()).multiply(HUNDRED).divide(raw, 1, RoundingMode.HALF_UP) : null);
        }

        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .orderId(orderId)
                .boxCount(boxCount)
                .hasData(true)
                .priceMasked(maskPrice)
                .rawMaterialCost(raw)
                .laborCost(labor)
                .seasoningCost(seasoning)
                .packagingCost(packaging)
                .totalCost(total)
                .perBoxCost(perBox)
                .sources(sources)
                .build();
        if (maskPrice) {
            maskCosts(dto);
        }
        return dto;
    }

    /**
     * T1 递归回溯: 返回 [该消耗回溯到的成本, 谱系深度].
     * 上游批次由生产批次产出且有消耗 → 递归取其上游成本和 (回溯到更上游); 否则叶子 = consumption.totalCost。
     */
    private BigDecimal[] traceCost(String factoryId, MaterialConsumption c, int depth) {
        BigDecimal own = nz(c.getTotalCost());
        if (depth >= MAX_DEPTH || c.getBatchId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};   // 叶子
        }
        Long upstreamBatchId = parseLong(mb.getSourceDocId());
        if (upstreamBatchId == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        List<MaterialConsumption> up = consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId);
        if (up.isEmpty()) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        BigDecimal sum = BigDecimal.ZERO;
        int maxChildDepth = depth;
        for (MaterialConsumption u : up) {
            BigDecimal[] r = traceCost(factoryId, u, depth + 1);
            sum = sum.add(r[0]);
            maxChildDepth = Math.max(maxChildDepth, r[1].intValue());
        }
        return new BigDecimal[]{sum.signum() > 0 ? sum : own, BigDecimal.valueOf(maxChildDepth)};
    }

    private void maskCosts(OrderCostBreakdownDTO dto) {
        dto.setRawMaterialCost(null);
        dto.setLaborCost(null);
        dto.setSeasoningCost(null);
        dto.setPackagingCost(null);
        dto.setTotalCost(null);
        dto.setPerBoxCost(null);
        if (dto.getSources() != null) {
            for (SourceCost s : dto.getSources()) {
                s.setUnitPrice(null);
                s.setCost(null);
                s.setCostSharePct(null);
            }
        }
    }

    private OrderCostBreakdownDTO empty(String orderId, boolean maskPrice) {
        return OrderCostBreakdownDTO.builder()
                .orderId(orderId).boxCount(0).hasData(false).priceMasked(maskPrice)
                .sources(List.of()).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
