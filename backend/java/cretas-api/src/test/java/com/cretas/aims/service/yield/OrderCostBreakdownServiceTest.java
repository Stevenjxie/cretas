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
import java.util.Map;
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

    /** 首道带副产物的 BatchYieldDTO (副产物挂在首道, 模拟修油削肥油)。 */
    private BatchYieldDTO batchYieldWithByproduct(String labor, List<Map<String, Object>> bp, BigDecimal... stepMaterials) {
        StepYieldDTO[] steps = new StepYieldDTO[stepMaterials.length];
        for (int i = 0; i < stepMaterials.length; i++) {
            steps[i] = StepYieldDTO.builder().processOrder(i + 1).materialCost(stepMaterials[i])
                    .byproducts(i == 0 ? bp : null).build();
        }
        return BatchYieldDTO.builder().totalLaborCost(new BigDecimal(labor)).steps(List.of(steps)).build();
    }

    /** 带显式 costCategory 的 BatchYieldDTO (CALC-003); cats[i] 对应第 i 道材料类别。 */
    private BatchYieldDTO batchYieldWithCategory(String labor, String[] cats, BigDecimal... mats) {
        StepYieldDTO[] steps = new StepYieldDTO[mats.length];
        for (int i = 0; i < mats.length; i++) {
            steps[i] = StepYieldDTO.builder().processOrder(i + 1).materialCost(mats[i]).costCategory(cats[i]).build();
        }
        return BatchYieldDTO.builder().totalLaborCost(new BigDecimal(labor)).steps(List.of(steps)).build();
    }

    /** 通用步骤构造: 首道可挂副产物/料头, 末道可挂留样 (AUDIT-001+006 组合场景)。 */
    private BatchYieldDTO buildBatch(String labor, List<Map<String, Object>> bpFirst, Integer sampleLast, BigDecimal wasteFirst, BigDecimal... mats) {
        StepYieldDTO[] steps = new StepYieldDTO[mats.length];
        for (int i = 0; i < mats.length; i++) {
            steps[i] = StepYieldDTO.builder().processOrder(i + 1).materialCost(mats[i])
                    .byproducts(i == 0 ? bpFirst : null)
                    .sampleRetainQuantity(i == mats.length - 1 ? sampleLast : null)
                    .wasteQuantity(i == 0 ? wasteFirst : null).build();
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
    @DisplayName("副产回收: 肥油 20kg×¥8 冲减成本 → 净成本/单盒净成本 (AUDIT-001)")
    void byproductCreditReducesNetCost() {
        // 总成本 14104 (人工1334+调料980+包装880+原料10910); 副产 肥油 20kg@¥8=¥160
        stubOneBatch(1L, "1787",
                batchYieldWithByproduct("1334",
                        List.of(Map.of("name", "肥油", "quantity", 20, "unit", "kg", "unitPrice", 8)),
                        BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170"), cons("MB2", "22", "170", "3740")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("MB2", F)).thenReturn(Optional.of(mb("MB2", "焯水0614", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getTotalCost()).isEqualByComparingTo("14104");        // 毛成本不变
        assertThat(dto.getByproductCredit()).isEqualByComparingTo("160");    // 20×8
        assertThat(dto.getNetTotalCost()).isEqualByComparingTo("13944");     // 14104−160
        assertThat(dto.getNetPerBoxCost()).isEqualByComparingTo("7.80");     // 13944/1787
        assertThat(dto.getByproducts()).hasSize(1);
        OrderCostBreakdownDTO.ByproductLine bp = dto.getByproducts().get(0);
        assertThat(bp.getName()).isEqualTo("肥油");
        assertThat(bp.getQuantity()).isEqualByComparingTo("20");
        assertThat(bp.getUnit()).isEqualTo("kg");
        assertThat(bp.getValue()).isEqualByComparingTo("160");
        assertThat(bp.getUnitPrice()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("副产物无单价: 只录数量 → value/credit 为 0, 不臆造价值 (诚实)")
    void byproductWithoutPriceNoCredit() {
        stubOneBatch(1L, "100",
                batchYieldWithByproduct("0",
                        List.of(Map.of("name", "牛骨", "quantity", 5, "unit", "kg")),  // 无 unitPrice
                        new BigDecimal("100")),
                List.of());

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getByproductCredit()).isEqualByComparingTo("0");
        assertThat(dto.getNetTotalCost()).isEqualByComparingTo(dto.getTotalCost());  // 无冲减
        assertThat(dto.getByproducts()).hasSize(1);
        assertThat(dto.getByproducts().get(0).getValue()).isNull();  // 不臆造
        assertThat(dto.getByproducts().get(0).getQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("价格脱敏: 副产物 value/单价 置 null, 保留名称/数量/单位 (物理量)")
    void byproductMaskedWhenNoPermission() {
        stubOneBatch(1L, "1787",
                batchYieldWithByproduct("1334",
                        List.of(Map.of("name", "肥油", "quantity", 20, "unit", "kg", "unitPrice", 8)),
                        BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, true);

        assertThat(dto.getByproductCredit()).isNull();
        assertThat(dto.getNetTotalCost()).isNull();
        assertThat(dto.getNetPerBoxCost()).isNull();
        OrderCostBreakdownDTO.ByproductLine bp = dto.getByproducts().get(0);
        assertThat(bp.getValue()).isNull();
        assertThat(bp.getUnitPrice()).isNull();
        assertThat(bp.getName()).isEqualTo("肥油");           // 物理量保留
        assertThat(bp.getQuantity()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("留样扣减: 5盒留样不可售 → 可售单盒成本=净成本÷可售盒数; 料头仅展示 (AUDIT-006)")
    void sampleRetainSellablePerBox() {
        // 总14104(无副产→net=14104); 盒数1787; 留样5→可售1782; 料头8.5kg 仅展示不扣成本
        stubOneBatch(1L, "1787",
                buildBatch("1334", null, 5, new BigDecimal("8.5"), BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170"), cons("MB2", "22", "170", "3740")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("MB2", F)).thenReturn(Optional.of(mb("MB2", "焯水0614", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getSampleRetainCount()).isEqualTo(5);
        assertThat(dto.getSellableBoxCount()).isEqualTo(1782);          // 1787−5
        assertThat(dto.getWasteQuantity()).isEqualByComparingTo("8.5"); // 仅展示
        assertThat(dto.getPerBoxCost()).isEqualByComparingTo("7.89");   // 毛 14104/1787 不变
        assertThat(dto.getSellablePerBoxCost()).isEqualByComparingTo("7.91"); // 14104/1782
    }

    @Test
    @DisplayName("副产+留样组合 (seed 场景): 净成本÷可售盒数; 脱敏时 sellable 为 null (AUDIT-001+006)")
    void byproductPlusSampleCombined() {
        // 副产 肥油160 → net=13944; 留样5 → 可售1782; sellable=13944/1782
        stubOneBatch(1L, "1787",
                buildBatch("1334", List.of(Map.of("name", "肥油", "quantity", 20, "unit", "kg", "unitPrice", 8)),
                        5, new BigDecimal("8.5"), BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "78", "91.92", "7170"), cons("MB2", "22", "170", "3740")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水0613", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("MB2", F)).thenReturn(Optional.of(mb("MB2", "焯水0614", null, null)));

        OrderCostBreakdownDTO open = service.compute(F, ORDER, false);
        assertThat(open.getNetTotalCost()).isEqualByComparingTo("13944");      // 14104−160
        assertThat(open.getSellableBoxCount()).isEqualTo(1782);
        assertThat(open.getSellablePerBoxCost()).isEqualByComparingTo("7.82"); // 13944/1782

        OrderCostBreakdownDTO masked = service.compute(F, ORDER, true);
        assertThat(masked.getSellablePerBoxCost()).isNull();
        assertThat(masked.getSampleRetainCount()).isEqualTo(5);               // 物理量保留
        assertThat(masked.getSellableBoxCount()).isEqualTo(1782);
    }

    @Test
    @DisplayName("CALC-003 显式 costCategory 分类不依赖工序顺序 (包装不在末道也正确归类)")
    void explicitCostCategoryOrderIndependent() {
        // 工序乱序: 包装(880)在中间道, 调料(980)在末道。
        // 旧启发式会错(末道→包装=980, 中间→调料=880); 显式类别纠正为 包装880/调料980。
        stubOneBatch(1L, "100",
                batchYieldWithCategory("0",
                        new String[]{"RAW_MATERIAL", "PACKAGING", "SEASONING"},
                        BigDecimal.ZERO, new BigDecimal("880"), new BigDecimal("980")),
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getPackagingCost()).isEqualByComparingTo("880");   // 按类别(中间道), 非按末道
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("980");   // 按类别(末道), 非启发式末道→包装
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("1000"); // RAW_MATERIAL 道不计, 原料来自 traced consumption
        assertThat(dto.getTotalCost()).isEqualByComparingTo("2860");       // 1000+880+980
    }

    @Test
    @DisplayName("CALC-003 未知/缺失 costCategory → 回退 step-index 启发式 (向后兼容)")
    void unknownCategoryFallsBackToHeuristic() {
        // cats: [null, "BOGUS", null] → 全部回退启发式: i0 skip, i1(中)→调料980, i2(末)→包装880
        stubOneBatch(1L, "100",
                batchYieldWithCategory("0",
                        new String[]{null, "BOGUS", null},
                        BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")),
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("980");   // 启发式中间道
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("880");   // 启发式末道
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("1000");
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
