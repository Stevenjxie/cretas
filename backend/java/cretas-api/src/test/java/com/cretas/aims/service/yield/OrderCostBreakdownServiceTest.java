package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 单元测试 {@link OrderCostBreakdownService} — 覆盖客户 Excel 算错钱的 5 个场景在系统侧的正确行为:
 * 混批异质成本拆分 / 多级递归回溯 / 盒数不翻倍 / 首道原料不双计 / 价格脱敏 / 空态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCostBreakdownServiceTest {

    private static final String F = "DEMO_FACTORY";
    private static final String ORDER = "SO-M67DEMO-001";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private YieldReportService yieldReportService;
    @InjectMocks private OrderCostBreakdownService service;

    private ProductionPlan plan(String id) {
        ProductionPlan p = new ProductionPlan(); p.setId(id); return p;
    }
    private ProductionBatch batch(Long id, String qty) {
        ProductionBatch b = new ProductionBatch(); b.setId(id); b.setQuantity(new BigDecimal(qty)); return b;
    }
    private MaterialConsumption cons(String srcBatchId, String qty, String unitPrice, String totalCost) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F); c.setBatchId(srcBatchId);
        c.setQuantity(new BigDecimal(qty)); c.setUnitPrice(new BigDecimal(unitPrice)); c.setTotalCost(new BigDecimal(totalCost));
        return c;
    }
    private MaterialBatch mb(String id, String name, String srcDocType, String srcDocId) {
        MaterialBatch m = new MaterialBatch(); m.setId(id); m.setBatchNumber(name); m.setQuantityUnit("kg");
        m.setSourceDocType(srcDocType); m.setSourceDocId(srcDocId); return m;
    }
    private BatchYieldDTO batchYield(String labor, BigDecimal... stepMaterials) {
        StepYieldDTO[] steps = new StepYieldDTO[stepMaterials.length];
        for (int i = 0; i < stepMaterials.length; i++) {
            steps[i] = StepYieldDTO.builder().processOrder(i + 1).materialCost(stepMaterials[i]).build();
        }
        return BatchYieldDTO.builder().totalLaborCost(new BigDecimal(labor)).steps(List.of(steps)).build();
    }

    private void stubOneBatch(Long batchId, String qty, BatchYieldDTO y, List<MaterialConsumption> cons) {
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of(plan("PP1")));
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any())).thenReturn(List.of(batch(batchId, qty)));
        when(yieldReportService.getYield(F, batchId)).thenReturn(y);
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(batchId, F)).thenReturn(cons);
    }

    @Test
    @DisplayName("混批异质成本: rawCost=Σ各源; 成本占比≠重量占比 (硬伤⑤)")
    void mixedBatchHeterogeneousCost() {
        // 首道(修油)材料=0, 熟制调料=980, 包装=880; 人工 1334; 盒数 1787
        stubOneBatch(1L, "1787",
                batchYield("1334", BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170"), cons("MB2", "22", "170", "3740")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("MB2", F)).thenReturn(Optional.of(mb("MB2", "焯水0614", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.isHasData()).isTrue();
        assertThat(dto.getBoxCount()).isEqualTo(1787);
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("10910"); // 7170+3740, 非按重量糊平均
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("980");
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("880");
        assertThat(dto.getLaborCost()).isEqualByComparingTo("1334");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("14104");
        assertThat(dto.getPerBoxCost()).isEqualByComparingTo("7.89");
        // 重量占比 78:22, 成本占比 65.7:34.3 → 不相等 (异质单价)
        OrderCostBreakdownDTO.SourceCost a = dto.getSources().get(0);
        assertThat(a.getWeightSharePct()).isEqualByComparingTo("78.0");
        assertThat(a.getCostSharePct()).isEqualByComparingTo("65.7");
        assertThat(a.getWeightSharePct()).isNotEqualByComparingTo(a.getCostSharePct());
    }

    @Test
    @DisplayName("多级递归: 上游半成品由生产批次产出 → 回溯到原料, depth=2")
    void recursiveBackTraceDepth2() {
        stubOneBatch(1L, "100",
                batchYield("0", BigDecimal.ZERO),
                List.of(cons("MB1", "78", "91.92", "7170")));
        // MB1 由生产批次 8971 产出 → 递归
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F))
                .thenReturn(Optional.of(mb("MB1", "焯水0613", "PRODUCTION_BATCH", "8971")));
        // 8971 的消耗 = 原料 RAWA (leaf)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(8971L, F))
                .thenReturn(List.of(cons("RAWA", "100", "71.70", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("RAWA", F))
                .thenReturn(Optional.of(mb("RAWA", "原料A", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("7170"); // 回溯到原料层
        assertThat(dto.getSources().get(0).getDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("首道原料材料不双计: 报告侧首道 material 被排除, 原料只来自 traced consumption")
    void firstStepMaterialNotDoubleCounted() {
        // 报告首道(修油)material=999 (应被忽略), 熟制 980, 包装 880; 原料来自 consumption 1000
        stubOneBatch(1L, "100",
                batchYield("0", new BigDecimal("999"), new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("1000");  // 非 1000+999
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("980");
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("880");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("2860");        // 999 被排除
    }

    @Test
    @DisplayName("价格脱敏: maskPrice=true 时所有金额字段为 null, 仅保留投料量/重量占比")
    void priceMaskNullsCosts() {
        stubOneBatch(1L, "1787",
                batchYield("1334", BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170"), cons("MB2", "22", "170", "3740")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("MB2", F)).thenReturn(Optional.of(mb("MB2", "焯水0614", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, true);

        assertThat(dto.isPriceMasked()).isTrue();
        assertThat(dto.getRawMaterialCost()).isNull();
        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.getPerBoxCost()).isNull();
        assertThat(dto.getSources().get(0).getCost()).isNull();
        assertThat(dto.getSources().get(0).getUnitPrice()).isNull();
        assertThat(dto.getSources().get(0).getCostSharePct()).isNull();
        // 量/重量占比仍保留
        assertThat(dto.getSources().get(0).getQuantity()).isEqualByComparingTo("78");
        assertThat(dto.getSources().get(0).getWeightSharePct()).isEqualByComparingTo("78.0");
    }

    @Test
    @DisplayName("空订单: 无生产计划 → hasData=false, 诚实空")
    void emptyOrderHonestEmpty() {
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of());
        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);
        assertThat(dto.isHasData()).isFalse();
        assertThat(dto.getBoxCount()).isZero();
    }
}
