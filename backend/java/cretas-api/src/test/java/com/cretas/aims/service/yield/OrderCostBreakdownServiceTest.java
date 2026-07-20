package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
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
import java.util.LinkedHashMap;
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
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private YieldReportService yieldReportService;
    @Mock private BomVersionRepository bomVersionRepository;
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

    /** 2 道 (RAW + SEASONING带共享锅) 的 BatchYieldDTO (AUDIT-004)。 */
    private BatchYieldDTO batchYieldAuxPot(String labor, String potNo, BigDecimal potTotal, String method,
                                           BigDecimal stepOutput, BigDecimal seasoningMat) {
        StepYieldDTO raw = StepYieldDTO.builder().processOrder(1).materialCost(BigDecimal.ZERO).costCategory("RAW_MATERIAL").build();
        StepYieldDTO sea = StepYieldDTO.builder().processOrder(2).materialCost(seasoningMat).costCategory("SEASONING")
                .totalOutput(stepOutput).auxPotNo(potNo).auxPotTotalCost(potTotal).auxAllocMethod(method).build();
        return BatchYieldDTO.builder().totalLaborCost(new BigDecimal(labor)).steps(List.of(raw, sea)).build();
    }

    /** 末道带包装明细 (AUDIT-002); 末道 costCategory=PACKAGING。 */
    private BatchYieldDTO batchYieldWithPackaging(String labor, List<Map<String, Object>> pkgLast, BigDecimal... mats) {
        StepYieldDTO[] steps = new StepYieldDTO[mats.length];
        for (int i = 0; i < mats.length; i++) {
            steps[i] = StepYieldDTO.builder().processOrder(i + 1).materialCost(mats[i])
                    .costCategory(i == mats.length - 1 ? "PACKAGING" : null)
                    .packagingDetail(i == mats.length - 1 ? pkgLast : null).build();
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
    @DisplayName("computeByPlan: 按计划取批次归集权威成本 (结单族成品成本传导用) — label=planId")
    void computeByPlan_aggregatesPlanBatches() {
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, "PP1"))
                .thenReturn(List.of(batch(1L, "100")));
        when(yieldReportService.getYield(F, 1L)).thenReturn(batchYield("0", BigDecimal.ZERO));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(1L, F))
                .thenReturn(List.of(cons("MB1", "78", "91.92", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F))
                .thenReturn(Optional.of(mb("MB1", "原料A", null, null)));

        OrderCostBreakdownDTO dto = service.computeByPlan(F, "PP1", false);

        assertThat(dto.isHasData()).isTrue();
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("7170");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("7170");
        assertThat(dto.getOrderId()).isEqualTo("PP1");   // label 填 planId (展示用)
    }

    @Test
    @DisplayName("computeByPlan: 无批次 → hasData=false (诚实空态, 不造 0 行)")
    void computeByPlan_noBatches_empty() {
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, "PP-EMPTY"))
                .thenReturn(List.of());

        OrderCostBreakdownDTO dto = service.computeByPlan(F, "PP-EMPTY", false);

        assertThat(dto.isHasData()).isFalse();
        assertThat(dto.getOrderId()).isEqualTo("PP-EMPTY");
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
    @DisplayName("AUDIT-002 包装明细按名称归集; 各项之和=包装成本; 脱敏时 cost null 保留 name")
    void packagingDetailAggregationAndMask() {
        List<Map<String, Object>> pkg = List.of(
                Map.of("name", "膜", "cost", 300),
                Map.of("name", "气体", "cost", 180),
                Map.of("name", "标签", "cost", 120),
                Map.of("name", "其他", "cost", 280));
        stubOneBatch(1L, "1787",
                batchYieldWithPackaging("0", pkg, BigDecimal.ZERO, new BigDecimal("880")),  // 末道包装 880
                List.of(cons("MB1", "78", "91.92", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "焯水", null, null)));

        OrderCostBreakdownDTO open = service.compute(F, ORDER, false);
        assertThat(open.getPackagingCost()).isEqualByComparingTo("880");
        assertThat(open.getPackagingDetail()).hasSize(4);
        BigDecimal sum = open.getPackagingDetail().stream()
                .map(OrderCostBreakdownDTO.PackagingItem::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("880");                         // 明细之和 = 包装成本
        assertThat(open.getPackagingDetail().get(0).getName()).isEqualTo("膜"); // 归集保序
        assertThat(open.getPackagingDetail().get(0).getCost()).isEqualByComparingTo("300");

        OrderCostBreakdownDTO masked = service.compute(F, ORDER, true);
        assertThat(masked.getPackagingDetail().get(0).getCost()).isNull();      // 成本脱敏
        assertThat(masked.getPackagingDetail().get(0).getName()).isEqualTo("膜"); // 名称保留
    }

    @Test
    @DisplayName("AUDIT-004 辅料按锅平摊: 本批 share=锅总×本批产出÷锅总产出; 脱敏金额null 保留物理量")
    void auxPotAllocationByOutput() {
        // 熟制辅料锅 POT1 总成本¥980; 锅总产出 300kg, 本批 180kg → 本批 share = 980×180/300 = 588
        stubOneBatch(1L, "100",
                batchYieldAuxPot("0", "POT1", new BigDecimal("980"), "BY_OUTPUT", new BigDecimal("180"), new BigDecimal("980")),
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));
        when(productionReportRepository.sumOutputByAuxPotNo(F, "POT1")).thenReturn(new BigDecimal("300"));

        OrderCostBreakdownDTO open = service.compute(F, ORDER, false);
        assertThat(open.getSeasoningCost()).isEqualByComparingTo("588");   // 980×180/300, 非整锅 980
        assertThat(open.getAuxiliaryAllocations()).hasSize(1);
        OrderCostBreakdownDTO.AuxiliaryAllocation a = open.getAuxiliaryAllocations().get(0);
        assertThat(a.getPotNo()).isEqualTo("POT1");
        assertThat(a.getBatchShare()).isEqualByComparingTo("588");
        assertThat(a.getBatchSharePct()).isEqualByComparingTo("60.0");      // 180/300
        assertThat(a.getPotTotalOutput()).isEqualByComparingTo("300");
        assertThat(a.getBatchOutput()).isEqualByComparingTo("180");

        OrderCostBreakdownDTO masked = service.compute(F, ORDER, true);
        OrderCostBreakdownDTO.AuxiliaryAllocation am = masked.getAuxiliaryAllocations().get(0);
        assertThat(am.getBatchShare()).isNull();
        assertThat(am.getPotTotalCost()).isNull();
        assertThat(am.getPotNo()).isEqualTo("POT1");                        // 物理量保留
        assertThat(am.getBatchSharePct()).isEqualByComparingTo("60.0");
    }

    @Test
    @DisplayName("AUDIT-004 锅唯一成员 → 分摊100%=锅总成本 (demo 场景: 数字不变)")
    void auxPotSoleMemberFullCost() {
        stubOneBatch(1L, "1787",
                batchYieldAuxPot("0", "POT-M67", new BigDecimal("980"), "BY_OUTPUT", new BigDecimal("179.8"), new BigDecimal("980")),
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));
        when(productionReportRepository.sumOutputByAuxPotNo(F, "POT-M67")).thenReturn(new BigDecimal("179.8"));

        OrderCostBreakdownDTO open = service.compute(F, ORDER, false);
        assertThat(open.getSeasoningCost()).isEqualByComparingTo("980");    // 100% = 整锅, 数字不变
        assertThat(open.getAuxiliaryAllocations().get(0).getBatchSharePct()).isEqualByComparingTo("100.0");
    }

    @Test
    @DisplayName("单工序批次 (首道即末道) 启发式归 SKIP 非 PACKAGING — 修复与 traced 原料双计")
    void singleStepBatchHeuristicSkipsNotPackaging() {
        // 1 工序, materialCost=500 (null costCategory → 启发式); 原料来自 consumption 1000
        stubOneBatch(1L, "100",
                batchYield("0", new BigDecimal("500")),   // 单工序
                List.of(cons("MB1", "10", "100", "1000")));
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(mb("MB1", "源", null, null)));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.getPackagingCost()).isEqualByComparingTo("0");      // 修复: 不再误把单工序 material 当包装
        assertThat(dto.getSeasoningCost()).isEqualByComparingTo("0");
        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("1000"); // 仅 traced consumption
        assertThat(dto.getTotalCost()).isEqualByComparingTo("1000");       // 500 步材料 SKIP (避免与原料双计)
    }

    /**
     * SP-F ①b: 文员链 修油→焯水→熟制 的 computeByBatch(熟制) 跨链聚合 人工/调料。
     *
     * <p>拓扑 (线性, 1:1 全量消耗 → 分摊比例=1):
     * <pre>
     *   修油批(id=10, labor 104) → WIP-修油(receipt 100) ─consume 100→ 焯水批(id=20, labor 104)
     *   焯水批(id=20)            → WIP-焯水(receipt 100) ─consume 100→ 熟制批(id=30, labor 156, 调料 33)
     *   修油批消耗 RAW 牛大肠 (leaf, ¥7170)
     * </pre>
     * 期望 computeByBatch(熟制): rawMaterialCost=7170 (traced raw),
     * laborCost=156(熟制自身)+104(焯水)+104(修油)=364, seasoningCost=33, totalCost=7170+364+33=7567。
     * (= 熟制 WIP 应有的 unitPrice×qty: 151.35×50≈7567)。
     */
    @Test
    @DisplayName("SP-F ①b: 文员链 computeByBatch(熟制) 聚合上游人工 → labor=364, total=7567")
    void clerkChainAggregatesUpstreamLaborSeasoning() {
        // ── 熟制 finished batch (id=30, 50 盒); computeByBatch 按批次号查 ──
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "CLK-B-XX"))
                .thenReturn(Optional.of(batch(30L, "50")));
        // 熟制自身: 人工 156, 调料 33 (SEASONING 道)
        when(yieldReportService.getYield(F, 30L)).thenReturn(
                batchYieldWithCategory("156", new String[]{"SEASONING"}, new BigDecimal("33")));
        // 熟制消耗 WIP-焯水 100kg (1:1 全量)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(30L, F))
                .thenReturn(List.of(cons("WIP-CS", "100", "0", "0")));

        // WIP-焯水 由焯水批(id=20)产出, receiptQuantity=100
        when(materialBatchRepository.findByIdAndFactoryId("WIP-CS", F))
                .thenReturn(Optional.of(wipMb("WIP-CS", 20L, "100")));
        // 焯水批自身: 人工 104, 无调料
        when(yieldReportService.getYield(F, 20L)).thenReturn(batchYield("104", BigDecimal.ZERO));
        // 焯水消耗 WIP-修油 100kg (1:1)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(20L, F))
                .thenReturn(List.of(cons("WIP-XY", "100", "0", "0")));

        // WIP-修油 由修油批(id=10)产出, receiptQuantity=100
        when(materialBatchRepository.findByIdAndFactoryId("WIP-XY", F))
                .thenReturn(Optional.of(wipMb("WIP-XY", 10L, "100")));
        // 修油批自身: 人工 104, 无调料
        when(yieldReportService.getYield(F, 10L)).thenReturn(batchYield("104", BigDecimal.ZERO));
        // 修油消耗 RAW 牛大肠 100kg = ¥7170 (leaf)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(10L, F))
                .thenReturn(List.of(cons("RAW-NIU", "100", "71.70", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("RAW-NIU", F))
                .thenReturn(Optional.of(mb("RAW-NIU", "牛大肠0613", null, null)));

        OrderCostBreakdownDTO dto = service.computeByBatch(F, "CLK-B-XX", false);

        assertThat(dto.getRawMaterialCost()).as("traced 原料 7170").isEqualByComparingTo("7170");
        assertThat(dto.getLaborCost())
                .as("熟制156 + 焯水104 + 修油104 = 364").isEqualByComparingTo("364");
        assertThat(dto.getSeasoningCost()).as("仅熟制调料 33").isEqualByComparingTo("33");
        assertThat(dto.getTotalCost())
                .as("7170 + 364 + 33 = 7567 (= WIP unitPrice 151.35×50)").isEqualByComparingTo("7567");
    }

    /** wipMb helper: WIP MaterialBatch produced by a production batch (sourceDocType=PRODUCTION_BATCH). */
    private MaterialBatch wipMb(String id, long srcProdBatchId, String receiptQty) {
        MaterialBatch m = new MaterialBatch();
        m.setId(id); m.setFactoryId(F); m.setBatchNumber(id); m.setQuantityUnit("kg");
        m.setSourceDocType("PRODUCTION_BATCH"); m.setSourceDocId(String.valueOf(srcProdBatchId));
        m.setReceiptQuantity(new BigDecimal(receiptQty));
        return m;
    }

    /**
     * SP-F ①b 回归: 部分消耗时上游人工按比例分摊 (镜像 traceCost)。
     * 熟制只消耗 WIP-焯水 50kg (产出 100kg) → 焯水/修油人工各按 50% 分摊。
     * 期望 laborCost = 156(熟制全额) + (104+104)×50% = 156 + 104 = 260。
     */
    @Test
    @DisplayName("SP-F ①b: 部分消耗(50/100) → 上游人工按比例分摊 (laborCost=260)")
    void clerkChainPartialConsumptionApportionsLabor() {
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "CLK-B-YY"))
                .thenReturn(Optional.of(batch(30L, "50")));
        when(yieldReportService.getYield(F, 30L)).thenReturn(batchYield("156", BigDecimal.ZERO));
        // 熟制只消耗 50kg of WIP-焯水 (产出 100) → 比例 0.5
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(30L, F))
                .thenReturn(List.of(cons("WIP-CS2", "50", "0", "0")));
        when(materialBatchRepository.findByIdAndFactoryId("WIP-CS2", F))
                .thenReturn(Optional.of(wipMb("WIP-CS2", 20L, "100")));
        when(yieldReportService.getYield(F, 20L)).thenReturn(batchYield("104", BigDecimal.ZERO));
        // 焯水全量消耗 WIP-修油 100kg (1:1)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(20L, F))
                .thenReturn(List.of(cons("WIP-XY2", "100", "0", "0")));
        when(materialBatchRepository.findByIdAndFactoryId("WIP-XY2", F))
                .thenReturn(Optional.of(wipMb("WIP-XY2", 10L, "100")));
        when(yieldReportService.getYield(F, 10L)).thenReturn(batchYield("104", BigDecimal.ZERO));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(10L, F))
                .thenReturn(List.of(cons("RAW-NIU2", "100", "71.70", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("RAW-NIU2", F))
                .thenReturn(Optional.of(mb("RAW-NIU2", "牛大肠", null, null)));

        OrderCostBreakdownDTO dto = service.computeByBatch(F, "CLK-B-YY", false);

        // 熟制 156 全额 + (焯水104 + 修油104) × 50% = 156 + 104 = 260
        assertThat(dto.getLaborCost())
                .as("熟制156 + 上游(104+104)×0.5 = 260").isEqualByComparingTo("260");
    }

    /**
     * SP-F ①b 回归 (M67 demo seed 拓扑): 上游 WIP 生产批 *无* labor/seasoning 报工 →
     * 跨链聚合加 0 → 成品成本与改前一致 (零回归)。
     *
     * <p>这正是 M67 demo seed 的形状: 熟制(PB-001)消耗 焯水0613(MB-0613, sourceDocType=PRODUCTION_BATCH
     * → 上游 WIP 批 PB-CS0613), 而 PB-CS0613 无任何 production_reports (seed 只给成品批写报工)。
     * 故 aggregateUpstreamLaborSeasoning 递归进 PB-CS0613 取 getYield → 空 → [0,0]。
     * 断言: laborCost 仍为成品批自身的 1334, 不因上游链多计。
     */
    @Test
    @DisplayName("SP-F ①b 回归: 上游WIP无报工 → 成品成本零回归 (M67 demo: labor=1334 不变)")
    void m67SeedTopologyNoRegressionWhenUpstreamHasNoReports() {
        // 成品批 (id=1, 1787盒); 自身 人工1334, 调料980, 包装880
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of(plan("PP1")));
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any()))
                .thenReturn(List.of(batch(1L, "1787")));
        when(yieldReportService.getYield(F, 1L)).thenReturn(
                batchYield("1334", BigDecimal.ZERO, new BigDecimal("980"), new BigDecimal("880")));
        // 消耗 MB-0613 (焯水0613) — sourceDocType=PRODUCTION_BATCH → 上游 WIP 批 8971
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(1L, F))
                .thenReturn(List.of(cons("MB-0613", "78", "91.92", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("MB-0613", F))
                .thenReturn(Optional.of(wipMb("MB-0613", 8971L, "78")));
        // 上游 WIP 批 8971 消耗原料 (leaf) — 但 8971 无 production_reports (getYield 返回 null)
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(8971L, F))
                .thenReturn(List.of(cons("RAWA", "100", "71.70", "7170")));
        when(materialBatchRepository.findByIdAndFactoryId("RAWA", F))
                .thenReturn(Optional.of(mb("RAWA", "原料A", null, null)));
        // 8971 getYield NOT stubbed (LENIENT) → null → batchLaborSeasoning 返 [0,0]

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        // 全部与改前一致: 上游链无报工 → 加 0
        assertThat(dto.getLaborCost()).as("成品自身 1334, 上游链加 0").isEqualByComparingTo("1334");
        assertThat(dto.getSeasoningCost()).as("成品调料 980, 上游链加 0").isEqualByComparingTo("980");
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("880");
        assertThat(dto.getRawMaterialCost()).as("traced 回溯到原料 7170").isEqualByComparingTo("7170");
        assertThat(dto.getTotalCost()).as("1334+980+880+7170 = 10364").isEqualByComparingTo("10364");
        assertThat(dto.getSources().get(0).getDepth()).as("depth=2 (递归回溯)").isEqualTo(2);
    }

    @Test
    @DisplayName("空订单: 无生产计划 → hasData=false, 诚实空")
    void emptyOrderHonestEmpty() {
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of());
        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);
        assertThat(dto.isHasData()).isFalse();
        assertThat(dto.getBoxCount()).isZero();
    }

    @Test
    void pinnedBomPackagingAndLaborAudit_areTraceableForFiveBoxes() {
        stubPinnedF006Audit(pinnedBomSnapshot(true));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.isCostComplete()).isTrue();
        assertThat(dto.getCalculationStatus()).isEqualTo("COMPLETE");
        assertThat(dto.getOrderNumber()).isEqualTo("SO-20260720-0001");
        assertThat(dto.getProductionPlanNumber()).isEqualTo("PLAN-1784523993145-78E6EE57");
        assertThat(dto.getOutputQuantity()).isEqualByComparingTo("5");
        assertThat(dto.getOutputUnit()).isEqualTo("box");
        assertThat(dto.getNetWeightGramsPerUnit()).isEqualByComparingTo("800");
        assertThat(dto.getConvertedOutputKg()).isEqualByComparingTo("4.0000");
        assertThat(dto.getCostDenominatorQuantity()).isEqualByComparingTo("5");
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("4.25");
        assertThat(dto.getPackagingDetails()).hasSize(3);
        assertThat(dto.getPackagingDetails().get(0).getQuantity()).isEqualByComparingTo("5");
        assertThat(dto.getPackagingDetails().get(1).getQuantity()).isEqualByComparingTo("5");
        assertThat(dto.getPackagingDetails().get(2).getQuantity()).isEqualByComparingTo("0.625");
        assertThat(dto.getPackagingDetails()).extracting(OrderCostBreakdownDTO.PackagingDetail::getCollectionStatus)
                .containsOnly("COLLECTED");
        assertThat(dto.getEquipmentCostStatus()).isEqualTo("CONFIRMED_ZERO");
        assertThat(dto.getOtherCostStatus()).isEqualTo("CONFIRMED_ZERO");
        assertThat(dto.getLaborDetails()).hasSize(2);
        assertThat(dto.getLaborDetails()).extracting(OrderCostBreakdownDTO.LaborDetail::getLaborMinutes)
                .containsExactly(120, 100);
        BigDecimal personMinutes = dto.getLaborDetails().stream()
                .map(detail -> BigDecimal.valueOf(detail.getLaborMinutes()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal personHours = dto.getLaborDetails().stream()
                .map(OrderCostBreakdownDTO.LaborDetail::getLaborHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(personMinutes).isEqualByComparingTo("220");
        assertThat(personHours).isEqualByComparingTo("3.6667");
        assertThat(dto.getMissingCostItemCount()).isZero();
    }

    @Test
    void pinnedBomMissingPackagingPrice_marksPartialAndDoesNotPretendZeroOrComplete() {
        stubPinnedF006Audit(pinnedBomSnapshot(false));

        OrderCostBreakdownDTO dto = service.compute(F, ORDER, false);

        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getCalculationStatus()).isEqualTo("PARTIAL");
        assertThat(dto.getPackagingCost()).isEqualByComparingTo("3.00");
        assertThat(dto.getPackagingDetails()).hasSize(3);
        OrderCostBreakdownDTO.PackagingDetail missing = dto.getPackagingDetails().get(2);
        assertThat(missing.getMaterialName()).isEqualTo("外箱");
        assertThat(missing.getCollectionStatus()).isEqualTo("MISSING_PRICE");
        assertThat(missing.getAmount()).isNull();
        assertThat(dto.getMissingCostItems()).contains("PACKAGING_PRICE:PKG-CASE");
        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.getPerBoxCost()).isNull();
        assertThat(dto.getNetTotalCost()).isNull();
    }

    @Test
    void computeByPlan_keepsM07AndM09CostLedgersIsolated() {
        ProductionBatch m07 = batch(707L, "5");
        ProductionBatch m09 = batch(909L, "5");
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, "M07")).thenReturn(List.of(m07));
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, "M09")).thenReturn(List.of(m09));
        when(yieldReportService.getYield(F, 707L)).thenReturn(batchYield("10", BigDecimal.ZERO));
        when(yieldReportService.getYield(F, 909L)).thenReturn(batchYield("20", BigDecimal.ZERO));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(707L, F))
                .thenReturn(List.of(cons("RAW-M07", "1", "100", "100")));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(909L, F))
                .thenReturn(List.of(cons("RAW-M09", "1", "900", "900")));
        when(materialBatchRepository.findByIdAndFactoryId("RAW-M07", F))
                .thenReturn(Optional.of(mb("RAW-M07", "M07原料", null, null)));
        when(materialBatchRepository.findByIdAndFactoryId("RAW-M09", F))
                .thenReturn(Optional.of(mb("RAW-M09", "M09原料", null, null)));

        OrderCostBreakdownDTO m07Cost = service.computeByPlan(F, "M07", false);
        OrderCostBreakdownDTO m09Cost = service.computeByPlan(F, "M09", false);

        assertThat(m07Cost.getRawMaterialCost()).isEqualByComparingTo("100");
        assertThat(m07Cost.getLaborCost()).isEqualByComparingTo("10");
        assertThat(m07Cost.getSources()).extracting(OrderCostBreakdownDTO.SourceCost::getBatchId)
                .containsExactly("RAW-M07");
        assertThat(m09Cost.getRawMaterialCost()).isEqualByComparingTo("900");
        assertThat(m09Cost.getLaborCost()).isEqualByComparingTo("20");
        assertThat(m09Cost.getSources()).extracting(OrderCostBreakdownDTO.SourceCost::getBatchId)
                .containsExactly("RAW-M09");
    }

    private void stubPinnedF006Audit(Map<String, Object> snapshot) {
        ProductionPlan p = plan("PLAN-F006-M11");
        p.setFactoryId(F);
        p.setPlanNumber("PLAN-1784523993145-78E6EE57");
        p.setCustomerOrderNumber("SO-20260720-0001");
        p.setProductTypeId("CPF0060015");
        p.setPlannedUnit("box");
        p.setPlannedNetWeightGrams(new BigDecimal("800"));
        p.setSelectedBomRecipeId("BOM-F006-V1");
        p.setSelectedBomVersion(1);
        p.setSelectedWorkflowId(11L);
        p.setSelectedWorkflowVersion(3);
        when(planRepository.findByFactoryIdAndSourceOrderId(F, ORDER)).thenReturn(List.of(p));
        when(planRepository.findByIdAndFactoryId("PLAN-F006-M11", F)).thenReturn(Optional.of(p));

        ProductionBatch b = batch(9001L, "5");
        b.setFactoryId(F);
        b.setProductionPlanId(p.getId());
        b.setBatchNumber("TRF-FG-20260720-8825");
        b.setProductTypeId("CPF0060015");
        b.setActualQuantity(new BigDecimal("5"));
        b.setUnit("box");
        b.setEquipmentCost(BigDecimal.ZERO);
        b.setOtherCost(BigDecimal.ZERO);
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(eq(F), any())).thenReturn(List.of(b));

        StepYieldDTO first = StepYieldDTO.builder()
                .processOrder(1).processName("第一道")
                .totalInput(new BigDecimal("5")).inputUnit("kg")
                .totalOutput(new BigDecimal("4.5")).outputUnit("kg")
                .materialCost(BigDecimal.ZERO).costCategory("RAW_MATERIAL")
                .laborCost(new BigDecimal("120"))
                .laborSegments(List.of(Map.of("startTime", "08:00", "endTime", "09:00", "headcount", 2)))
                .build();
        StepYieldDTO second = StepYieldDTO.builder()
                .processOrder(2).processName("第二道")
                .totalInput(new BigDecimal("4.5")).inputUnit("kg")
                .totalOutput(new BigDecimal("5")).outputUnit("box")
                .materialCost(BigDecimal.ZERO).costCategory("PACKAGING")
                .laborCost(new BigDecimal("100"))
                .laborSegments(List.of(Map.of("startTime", "09:00", "endTime", "09:50", "headcount", 2)))
                .build();
        when(yieldReportService.getYield(F, 9001L)).thenReturn(BatchYieldDTO.builder()
                .batchId(9001L).batchNumber(b.getBatchNumber())
                .firstStepInput(new BigDecimal("5")).firstStepInputUnit("kg")
                .lastStepOutput(new BigDecimal("5")).lastStepOutputUnit("box")
                .lastStepOutputInFirstUnit(new BigDecimal("4"))
                .totalLaborCost(new BigDecimal("220"))
                .steps(List.of(first, second)).build());
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(9001L, F)).thenReturn(List.of());

        BomVersion version = new BomVersion();
        version.setFactoryId(F);
        version.setBomRecipeId("BOM-F006-V1");
        version.setVersionNumber(1);
        version.setSnapshotJson(snapshot);
        when(bomVersionRepository.findByFactoryIdAndBomRecipeIdOrderByVersionNumberDesc(F, "BOM-F006-V1"))
                .thenReturn(List.of(version));
    }

    private Map<String, Object> pinnedBomSnapshot(boolean allPrices) {
        Map<String, Object> box = packagingItem("PKG-BOX", "成品盒", "1", "box", "0.50");
        Map<String, Object> film = packagingItem("PKG-FILM", "封膜", "1", "slice", "0.10");
        Map<String, Object> outerCase = packagingItem("PKG-CASE", "外箱", "0.125", "case",
                allPrices ? "0.25" : null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("outputQuantityPerUnit", new BigDecimal("800"));
        snapshot.put("outputUnit", "g");
        snapshot.put("items", List.of(box, film, outerCase));
        return snapshot;
    }

    private Map<String, Object> packagingItem(String code, String name, String quantity,
                                               String unit, String itemCost) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialCategory", "PACKAGING");
        item.put("materialTypeId", code);
        item.put("materialName", name);
        item.put("standardQuantity", new BigDecimal(quantity));
        item.put("unit", unit);
        item.put("itemCost", itemCost == null ? null : new BigDecimal(itemCost));
        return item;
    }
}
