package com.cretas.aims.controller;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 「待确认入库」这一屏不许把内部标识当成人话给仓库工人看。
 *
 * <h2>2026-08-18 prod 实测的原始响应</h2>
 *
 * <pre>
 * GET /api/mobile/F006/warehouse/transit-ledgers
 * {
 *   "productName": "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207",   &lt;- 缺陷1: 塞的是 productTypeId
 *   "toWarehouseName": null,                                  &lt;- 缺陷2: 不告诉仓管收到哪个仓
 *   "outputLines": [{ "productTypeId": "eb0aa47b-...", ... }] &lt;- 缺陷3: 逐行也只有 UUID
 * }
 * </pre>
 *
 * 该产品在 {@code product_types} 里名字是 {@code SOP-20260817-01-黄油鸡-成品800g}
 * （prod 实测: {@code id = eb0aa47b-… / factory_id = F006 / product_category = FINISHED_PRODUCT}）。
 * 这一屏的用户是仓库工人，36 位十六进制对他等于没有信息。
 *
 * <h2>断言为什么要「双向」写</h2>
 *
 * ⚠️ 只断言「不等于那串 UUID」是<b>恒真陷阱</b>：把实现改成返回空串、返回 null、
 * 返回任何一个别的字符串，那条断言照样绿。所以每条都<b>同时</b>钉住
 * 「等于期望的那个品名」——只有它能把「修好了」和「换了个坏法」分开。
 *
 * <h2>阳性对照</h2>
 *
 * {@link #productNameIsTheRealProductName()} 就是阳性对照：它证明正常路径下品名<b>确实</b>
 * 被解析出来了。没有它，下面那些「查不到时给中文说明」的断言可能只是因为解析从来没成功过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("待确认入库屏: 品名/目的仓不许显示内部标识")
class TransitLedgerDisplayIdentityContractTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final String PLAN_ID = "ffc61a6f-f731-4401-95f7-a82891f8ad9d";
    private static final String SETTLEMENT_ID = "ca3a989d-c0ac-47e1-b881-39e1c0117128";
    private static final String PLAN_NUMBER = "PLAN-1786954657305-A356E80A";

    /** prod 上被当成品名显示出去的那串 UUID。 */
    private static final String PRODUCT_TYPE_ID = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    /** 它在 product_types 里真正的名字。 */
    private static final String PRODUCT_NAME = "SOP-20260817-01-黄油鸡-成品800g";
    private static final String BATCH = "PB-PLAN-1786954657305-A356E80A-56231";

    /** F006 的成品仓 (prod: factory_warehouses code=WH-FG / name=成品仓 / type=FINISHED)。 */
    private static final String WAREHOUSE_NAME = "成品仓";

    /** 与 controller 常量逐字对应 —— 改那边必须改这边。 */
    private static final String UNKNOWN_PRODUCT_NAME = "未知产品(产品档案查不到)";
    private static final String NO_FINISHED_WAREHOUSE = "未指定(本厂未配置成品仓)";

    private static final Pattern BARE_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Mock ProductionSettlementRepository productionSettlementRepository;
    @Mock ProductionSettlementOutputLineRepository productionSettlementOutputLineRepository;
    @Mock ProductionPlanService productionPlanService;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock WarehouseResolver warehouseResolver;

    private ProductionWarehouseReceiptMobileController controller;

    @BeforeEach
    void setUp() {
        controller = new ProductionWarehouseReceiptMobileController(
                productionSettlementRepository,
                productionSettlementOutputLineRepository,
                productionPlanService,
                productTypeRepository,
                rawMaterialTypeRepository,
                warehouseResolver);

        when(productionSettlementRepository
                .findByFactoryIdAndPostingStatusAndDeletedAtIsNull(FACTORY, "PENDING_WAREHOUSE_RECEIPT"))
                .thenReturn(List.of(settlement()));
        when(productionPlanService.getProductionPlanById(anyString(), anyString())).thenReturn(plan());
        when(productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        FACTORY, SETTLEMENT_ID))
                .thenReturn(List.of(outputLine(PRODUCT_TYPE_ID, BATCH, "75.0000")));
        // 默认: 产品档案查得到, 成品仓配好了 —— 各测试按需覆盖。
        when(productTypeRepository.findByFactoryIdAndIdIn(eq(FACTORY), any()))
                .thenReturn(List.of(productType(PRODUCT_TYPE_ID, FACTORY, PRODUCT_NAME)));
        when(rawMaterialTypeRepository.findByIdIn(any())).thenReturn(List.of());
        when(warehouseResolver.resolveFinishedGoodsName(FACTORY)).thenReturn(WAREHOUSE_NAME);
    }

    private ProductionWarehouseReceiptMobileDTO firstRow() {
        List<ProductionWarehouseReceiptMobileDTO> rows =
                controller.listTransitLedgers(FACTORY, "PENDING_CONFIRMATION").getData();
        assertThat(rows).as("仪器自检: 至少要返回一行, 否则下面的断言全是空转").hasSize(1);
        return rows.getFirst();
    }

    // ==================== 缺陷 1: 抬头品名 ====================

    @Test
    @DisplayName("🟢 阳性对照: 正常路径下 productName 就是产品档案里那个真名")
    void productNameIsTheRealProductName() {
        assertThat(firstRow().getProductName())
                .as("这条是阳性对照 —— 它绿了才说明解析这条路真的通, 别的断言才有意义")
                .isEqualTo(PRODUCT_NAME);
    }

    @Test
    @DisplayName("🔴 productName 不再是 productTypeId 那串 UUID")
    void productNameIsNotTheProductTypeId() {
        String productName = firstRow().getProductName();

        // ⚠️ 下面两条缺一不可: 只有 isNotEqualTo 时, 返回空串/null 也能通过。
        assertThat(productName).isNotEqualTo(PRODUCT_TYPE_ID);
        assertThat(productName).isEqualTo(PRODUCT_NAME);
        assertThat(productName).doesNotMatch(BARE_UUID.pattern());
    }

    // ==================== 缺陷 3: 逐行品名 ====================

    @Test
    @DisplayName("🔴 outputLines 逐行也带真实品名, 而 productTypeId 仍保留供技术支持定位")
    void outputLineCarriesResolvedProductName() {
        ProductionWarehouseReceiptMobileDTO.OutputLine line = firstRow().getOutputLines().getFirst();

        assertThat(line.getProductName()).isEqualTo(PRODUCT_NAME);
        assertThat(line.getProductName()).doesNotMatch(BARE_UUID.pattern());
        assertThat(line.getProductTypeId())
                .as("原 id 不能丢 —— 确认回传和技术支持定位都要它")
                .isEqualTo(PRODUCT_TYPE_ID);
    }

    // ==================== 缺陷 2: 目的仓 ====================

    @Test
    @DisplayName("🔴 toWarehouseName 告诉仓管收进哪个仓, 不再是 null")
    void toWarehouseNameIsPopulated() {
        String warehouse = firstRow().getToWarehouseName();

        assertThat(warehouse).isNotNull();
        assertThat(warehouse).isEqualTo(WAREHOUSE_NAME);
    }

    @Test
    @DisplayName("工厂没配成品仓 → 说清「未指定」, ⛔ 不是 null (前端会渲染成一个破折号)")
    void missingFinishedWarehouseSaysUnspecifiedRatherThanNull() {
        when(warehouseResolver.resolveFinishedGoodsName(FACTORY)).thenReturn(null);

        assertThat(firstRow().getToWarehouseName())
                .isNotNull()
                .isEqualTo(NO_FINISHED_WAREHOUSE);
    }

    // ==================== 查不到时的诚实兜底 ====================

    @Test
    @DisplayName("🔴 产品档案查不到 → 说明性中文, ⛔ 绝不回落成 UUID / 空串 / null")
    void unresolvableProductSaysSoInsteadOfLeakingTheId() {
        when(productTypeRepository.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of());
        when(rawMaterialTypeRepository.findByIdIn(any())).thenReturn(List.of());

        ProductionWarehouseReceiptMobileDTO row = firstRow();

        assertThat(row.getProductName()).isEqualTo(UNKNOWN_PRODUCT_NAME);
        assertThat(row.getProductName()).isNotEqualTo(PRODUCT_TYPE_ID);
        assertThat(row.getProductName()).doesNotMatch(BARE_UUID.pattern());
        assertThat(row.getOutputLines().getFirst().getProductName()).isEqualTo(UNKNOWN_PRODUCT_NAME);
    }

    @Test
    @DisplayName("副产的 SKU 在 raw_material_types 里 —— 只查 product_types 会让它恒显示「未知产品」")
    void byproductNameFallsBackToRawMaterialTypes() {
        when(productTypeRepository.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of());
        when(rawMaterialTypeRepository.findByIdIn(any()))
                .thenReturn(List.of(rawMaterial(PRODUCT_TYPE_ID, FACTORY, "验收-副产-肥油")));

        assertThat(firstRow().getProductName()).isEqualTo("验收-副产-肥油");
    }

    @Test
    @DisplayName("⛔ 跨厂命中不许顶替: 别的工厂有同 id 的物料, 也当作没查到")
    void crossFactoryRawMaterialIsNotUsed() {
        when(productTypeRepository.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of());
        when(rawMaterialTypeRepository.findByIdIn(any()))
                .thenReturn(List.of(rawMaterial(PRODUCT_TYPE_ID, OTHER_FACTORY, "别厂的肥油")));

        assertThat(firstRow().getProductName())
                .isNotEqualTo("别厂的肥油")
                .isEqualTo(UNKNOWN_PRODUCT_NAME);
    }

    // ==================== 多产出 / 无产出 ====================

    @Test
    @DisplayName("🔴 多产出抬头是中文, 不再是英文的「2 Workflow outputs」")
    void multipleOutputsHeadlineIsChineseAndNamed() {
        String secondId = "11111111-2222-3333-4444-555555555555";
        when(productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        FACTORY, SETTLEMENT_ID))
                .thenReturn(List.of(
                        outputLine(PRODUCT_TYPE_ID, BATCH, "75.0000"),
                        outputLine(secondId, BATCH + "-B", "12.0000")));
        when(productTypeRepository.findByFactoryIdAndIdIn(eq(FACTORY), any()))
                .thenReturn(List.of(
                        productType(PRODUCT_TYPE_ID, FACTORY, PRODUCT_NAME),
                        productType(secondId, FACTORY, "副产-鸡油")));

        String headline = firstRow().getProductName();

        assertThat(headline).isEqualTo(PRODUCT_NAME + " 等 2 项产出");
        assertThat(headline).doesNotContain("Workflow outputs");
        assertThat(headline).doesNotContain(PRODUCT_TYPE_ID);
        assertThat(headline).doesNotContain(secondId);
    }

    @Test
    @DisplayName("没有产出行 → 用计划自己的品名 (非 Workflow 的老计划)")
    void planProductNameUsedWhenThereAreNoOutputLines() {
        when(productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        FACTORY, SETTLEMENT_ID))
                .thenReturn(List.of());

        assertThat(firstRow().getProductName()).isEqualTo(PRODUCT_NAME);
    }

    @Test
    @DisplayName("没有产出行且计划也没品名 → 说明性中文, ⛔ 不是 null")
    void blankPlanProductNameStillNeverNull() {
        when(productionSettlementOutputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        FACTORY, SETTLEMENT_ID))
                .thenReturn(List.of());
        ProductionPlanDTO nameless = plan();
        nameless.setProductName("   ");
        when(productionPlanService.getProductionPlanById(anyString(), anyString())).thenReturn(nameless);

        assertThat(firstRow().getProductName()).isEqualTo(UNKNOWN_PRODUCT_NAME);
    }

    // ==================== 其余面向用户的字段 ====================

    @Test
    @DisplayName("direction/status 保持机器枚举 —— 前端 directionLabel() 负责翻译, 不在这一层改")
    void enumsStayMachineReadableForTheClientToLabel() {
        ProductionWarehouseReceiptMobileDTO row = firstRow();

        assertThat(row.getDirection()).isEqualTo("FINISHED_GOODS_RECEIPT");
        assertThat(row.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(row.getFromLocation())
                .as("来源侧本来就是中文, 目的侧现在也是 —— 一屏两种口径才是问题")
                .isEqualTo("中转/车间");
    }

    // ==================== fixtures ====================

    private ProductionSettlement settlement() {
        ProductionSettlement s = new ProductionSettlement();
        s.setId(SETTLEMENT_ID);
        s.setProductionPlanId(PLAN_ID);
        s.setPlanNumber(PLAN_NUMBER);
        s.setPlannedQuantity(new BigDecimal("80.00"));
        s.setActualFinishedQuantity(new BigDecimal("75.00"));
        s.setQuantityUnit("盒");
        s.setSettledBy(7L);
        return s;
    }

    private ProductionPlanDTO plan() {
        ProductionPlanDTO dto = new ProductionPlanDTO();
        dto.setPlanNumber(PLAN_NUMBER);
        dto.setProductName(PRODUCT_NAME);
        dto.setProductUnit("盒");
        dto.setPlannedQuantity(new BigDecimal("80.00"));
        return dto;
    }

    private ProductionSettlementOutputLine outputLine(String productTypeId, String batch, String qty) {
        ProductionSettlementOutputLine line = new ProductionSettlementOutputLine();
        line.setFactoryId(FACTORY);
        line.setSettlementId(SETTLEMENT_ID);
        line.setProductionPlanId(PLAN_ID);
        line.setProductTypeId(productTypeId);
        line.setReportedBatchNumber(batch);
        line.setReportedQuantity(new BigDecimal(qty));
        line.setQuantityUnit("盒");
        line.setStatus("REPORTED");
        return line;
    }

    private ProductType productType(String id, String factoryId, String name) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setFactoryId(factoryId);
        pt.setName(name);
        return pt;
    }

    private RawMaterialType rawMaterial(String id, String factoryId, String name) {
        RawMaterialType rmt = new RawMaterialType();
        rmt.setId(id);
        rmt.setFactoryId(factoryId);
        rmt.setName(name);
        return rmt;
    }
}
