package com.cretas.aims.service.factory;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闸 —— 领料越仓被拦住时，必须告诉仓管<b>下一步该做什么</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测, MR20260818-0004)</h2>
 *
 * F006 的领料单 MR20260818-0004 (来源仓=原料仓 WH-RAW, 目标仓=生产仓 WH-WKS) 在「出库转运」返回:
 *
 * <pre>
 * HTTP=409 errorCode=PRODUCTION_REQUISITION_BATCH_WAREHOUSE_MISMATCH
 * message   = 领料调拨失败: 批次不属于领料单来源仓库
 * actionHint= 请重新确认领料，由系统按来源仓先进先出选择批次
 * </pre>
 *
 * <b>拒绝是对的</b> —— 挑中的批次确实不在来源仓。<b>缺陷是那句行动指引无效</b>:
 * 「封膜」「成品盒」在原料仓 <b>0 件</b>, 全部库存在生产仓 (封膜 20 卷 / 成品盒 1100 盒),
 * 「重新确认领料」多少次, 来源仓 FEFO 都挑不出批次 —— 用户只会反复重试直到放弃。
 * 判别实验已坐实归因: 把这两行拣货量置 0 后转运立刻成功。
 *
 * <p>本仓判据八「凡是拦住人的地方，都要告诉下一步该做什么」在这里是破的。
 *
 * <h2>这道闸守的三件事</h2>
 * <ol>
 *   <li><b>逐个列出</b>越仓的物料 —— 实测是两个同时越仓, 只报第一个等于让人修一轮撞一轮;</li>
 *   <li>说清<b>来源仓有多少 / 货实际在哪个仓有多少</b>, 用户才知道是去调拨还是去改单;</li>
 *   <li>查不出在手量时<b>如实说查不出来</b> —— ⛔ 不许拿 0 顶替「不知道」,
 *       那会把人支去下一张采购单 (本仓 A¹⁰: 兜底默认值把「我不知道」翻译成「是 0」)。</li>
 * </ol>
 *
 * <h2>⚠️ 这道闸<b>不</b>守什么</h2>
 * 不守「该不该拒绝」。拒绝行为一个字没改, errorCode 仍是
 * {@code PRODUCTION_REQUISITION_BATCH_WAREHOUSE_MISMATCH}, HTTP 仍是 409。
 * 阳性对照 ({@link #positiveControl_allBatchesInSourceWarehouse_transferSucceeds}) 钉住这一点:
 * 批次都在来源仓时照常转运成功, 否则下面那些「拦住了」的断言可能恒真。
 */
class RequisitionCrossWarehouseHintContractTest {

    private static final String F = "F006";
    private static final String MR_ID = "a6aae686-689a-41b6-9278-04d550898b13";
    private static final String PLAN_ID = "plan-butter-chicken";
    /** 原料仓 WH-RAW —— 领料单来源仓 */
    private static final String WH_RAW = "6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e";
    /** 生产仓 WH-WKS —— 领料单目标仓, prod 实测那两样货实际躺在这里 */
    private static final String WH_WKS = "bbede96c-025a-4f96-9d8c-672410b5ed00";
    /** 物流仓 WH-LOG —— 用来验「货在第三个仓」那一支 hint */
    private static final String WH_LOG = "78339e2d-d34c-4b38-b4f9-977fd4a631c2";

    private static final String MT_FILM = "RMT_b7a809d8-6cfd-4e3f-8d99-1868723a5380";  // 封膜
    private static final String MT_BOX = "RMT_c4d198ef-6770-4951-ac19-f8620ccfc8bb";   // 成品盒
    private static final String MT_RAW_A = "RMT_41e1a2d4-ae36-4ad3-9d0f-2b943816a2aa"; // 原料A

    private FactoryMaterialRequisitionRepository repository;
    private ProductionPlanRepository planRepository;
    private FactoryWarehouseRepository warehouseRepository;
    private MaterialBatchRepository batchRepository;
    private FactoryMaterialRequisitionServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(FactoryMaterialRequisitionRepository.class);
        planRepository = mock(ProductionPlanRepository.class);
        warehouseRepository = mock(FactoryWarehouseRepository.class);
        batchRepository = mock(MaterialBatchRepository.class);
        service = new FactoryMaterialRequisitionServiceImpl(
                repository,
                mock(FactoryMaterialRequisitionItemRepository.class),
                planRepository,
                mock(BomRecipeItemRepository.class),
                warehouseRepository,
                batchRepository,
                mock(MaterialConsumptionRepository.class),
                mock(ProductionMaterialReturnRepository.class));

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(F);
        when(planRepository.findByIdAndFactoryId(PLAN_ID, F)).thenReturn(Optional.of(plan));

        stubWarehouse(WH_RAW, "WH-RAW", "原料仓");
        stubWarehouse(WH_WKS, "WH-WKS", "生产仓");
        stubWarehouse(WH_LOG, "WH-LOG", "物流仓");
    }

    private void stubWarehouse(String id, String code, String name) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setFactoryId(F);
        w.setCode(code);
        w.setName(name);
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(w));
    }

    // ---------------------------------------------------------------- fixtures

    private FactoryMaterialRequisitionItem item(String id, String materialTypeId, String name,
                                                String unit, String qty, String batchId) {
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setId(id);
        it.setMaterialTypeId(materialTypeId);
        it.setMaterialName(name);
        it.setUnit(unit);
        it.setRequiredQty(new BigDecimal(qty));
        it.setPickedQty(new BigDecimal(qty));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batchId);
        row.put("qty", qty);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        it.setBatchNumbers(rows);
        return it;
    }

    private void stubBatch(String batchId, String materialTypeId, String warehouseId, String onHand) {
        MaterialBatch b = new MaterialBatch();
        b.setId(batchId);
        b.setFactoryId(F);
        b.setBatchNumber("MT-20260817-" + batchId);
        b.setMaterialTypeId(materialTypeId);
        b.setWarehouseId(warehouseId);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptQuantity(new BigDecimal(onHand));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        when(batchRepository.findByIdAndFactoryIdForUpdate(batchId, F)).thenReturn(Optional.of(b));
    }

    /** 各仓在手量读数 —— 与 prod 实测一致: 该物料在 warehouseId 有 qty, 别的仓没有可领用余量。 */
    private void stubOnHand(String materialTypeId, String warehouseId, String qty) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{warehouseId, new BigDecimal(qty)});
        when(batchRepository.sumAvailableGroupedByWarehouse(F, materialTypeId)).thenReturn(rows);
    }

    private FactoryMaterialRequisition requisition(FactoryMaterialRequisitionItem... items) {
        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId(MR_ID);
        mr.setFactoryId(F);
        mr.setRequisitionNo("MR20260818-0004");
        mr.setProductionPlanId(PLAN_ID);
        mr.setSourceWarehouseId(WH_RAW);
        mr.setTargetWarehouseId(WH_WKS);
        mr.setStatus(Status.PICKING);
        mr.setItems(new ArrayList<>(List.of(items)));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, F)).thenReturn(Optional.of(mr));
        when(repository.save(any(FactoryMaterialRequisition.class))).thenAnswer(inv -> inv.getArgument(0));
        return mr;
    }

    /** prod 实测那一单: 封膜 / 成品盒的批次在生产仓, 原料A 的批次老老实实在原料仓。 */
    private FactoryMaterialRequisition prodScenario() {
        FactoryMaterialRequisition mr = requisition(
                item("it-film", MT_FILM, "SOP-20260817-01-黄油鸡-封膜", "卷", "4", "b-film"),
                item("it-box", MT_BOX, "SOP-20260817-01-黄油鸡-成品盒", "盒", "80", "b-box"),
                item("it-rawa", MT_RAW_A, "SOP-20260817-01-黄油鸡-原料A", "kg", "20", "b-rawa"));
        stubBatch("b-film", MT_FILM, WH_WKS, "20");
        stubBatch("b-box", MT_BOX, WH_WKS, "1100");
        stubBatch("b-rawa", MT_RAW_A, WH_RAW, "180");
        stubOnHand(MT_FILM, WH_WKS, "20");
        stubOnHand(MT_BOX, WH_WKS, "1100");
        stubOnHand(MT_RAW_A, WH_RAW, "180");
        return mr;
    }

    private BusinessException transferExpectingBlock() {
        return assertThrows(BusinessException.class,
                () -> service.transferToFactory(F, MR_ID, 1L));
    }

    // ---------------------------------------------------------------- 阳性对照

    /**
     * 🟢 阳性对照 —— 批次都在来源仓时照常转运成功。
     *
     * <p>没有它, 下面每一条「拦住了并且说了什么」的断言都可能是恒真式:
     * 一个无论如何都抛 409 的实现同样能让它们全绿。
     */
    @Test
    @DisplayName("阳性对照: 批次都在来源仓 → 转运成功, 库存真的搬了")
    void positiveControl_allBatchesInSourceWarehouse_transferSucceeds() {
        requisition(item("it-rawa", MT_RAW_A, "原料A", "kg", "20", "b-rawa"));
        stubBatch("b-rawa", MT_RAW_A, WH_RAW, "180");

        FactoryMaterialRequisition saved = service.transferToFactory(F, MR_ID, 1L);

        assertNotNull(saved, "阳性对照没跑通, 下面的阴性断言全部无效");
        assertEquals(Status.TRANSFERRED, saved.getStatus());
        // 「必发生」的读数: 源批次划出 + 生产仓建批次, 至少两次 save。
        verify(batchRepository, atLeastOnce()).save(any(MaterialBatch.class));
    }

    // ---------------------------------------------------------------- 主判据

    @Test
    @DisplayName("越仓的物料要逐个列出 —— 实测是 2 个同时越仓, 只报第一个等于让人修一轮撞一轮")
    void blocksAndListsEveryOffendingMaterial() {
        prodScenario();

        BusinessException e = transferExpectingBlock();

        assertEquals(409, e.getCode());
        assertEquals("PRODUCTION_REQUISITION_BATCH_WAREHOUSE_MISMATCH", e.getErrorCode());
        assertTrue(e.getMessage().contains("封膜"), "没列出封膜: " + e.getMessage());
        assertTrue(e.getMessage().contains("成品盒"), "只报了第一个物料, 成品盒漏了: " + e.getMessage());
        // 阴性对照: 老老实实在来源仓的那一行不该被算成越仓
        assertFalse(e.getMessage().contains("原料A"),
                "把没越仓的物料也列进去了 (误报比漏报更快烧掉信任): " + e.getMessage());
    }

    @Test
    @DisplayName("要说清: 来源仓有多少 / 货实际在哪个仓有多少")
    void messageCarriesSourceOnHandAndWhereTheStockActuallyIs() {
        prodScenario();

        String msg = transferExpectingBlock().getMessage();

        assertTrue(msg.contains("原料仓(WH-RAW)"), "没说是哪个来源仓: " + msg);
        assertTrue(msg.contains("生产仓(WH-WKS)"), "没说货实际在哪个仓: " + msg);
        assertTrue(msg.contains("可领用 0卷"), "没说来源仓封膜有多少: " + msg);
        assertTrue(msg.contains("可领用 0盒"), "没说来源仓成品盒有多少: " + msg);
        assertTrue(msg.contains("20卷"), "没说生产仓封膜有多少: " + msg);
        assertTrue(msg.contains("1100盒"), "没说生产仓成品盒有多少: " + msg);
    }

    @Test
    @DisplayName("行动指引必须可执行, 而且那句无效指引不许回来")
    void actionHintIsExecutableAndTheDeadEndAdviceIsGone() {
        prodScenario();

        String hint = transferExpectingBlock().getActionHint();

        assertNotNull(hint, "BLOCKING 错误必须带 actionHint");
        // ⛔ 阴性对照: 这正是 prod 上那句无效指引, 重新确认多少次都挑不到批次
        assertFalse(hint.contains("请重新确认领料，由系统按来源仓先进先出选择批次"),
                "那句无效指引又回来了: " + hint);
        // 实测有效的下一步: 把这两行拣货量置 0 后转运立刻成功
        assertTrue(hint.contains("拣货数量改成 0"), "没告诉用户那个实测有效的下一步: " + hint);
        assertTrue(hint.contains("生产仓(WH-WKS)"), "没点名货已经在哪个仓: " + hint);
        assertTrue(hint.contains("重试"), "没说清「直接重试没用」, 用户还是会反复重试: " + hint);
    }

    @Test
    @DisplayName("货在第三个仓 (既不是来源仓也不是目标仓) → 指引改成先调拨过来")
    void stockInAThirdWarehouse_hintSaysTransferItIn() {
        requisition(item("it-film", MT_FILM, "封膜", "卷", "4", "b-film"));
        stubBatch("b-film", MT_FILM, WH_LOG, "20");
        stubOnHand(MT_FILM, WH_LOG, "20");

        BusinessException e = transferExpectingBlock();

        assertTrue(e.getMessage().contains("物流仓(WH-LOG) 20卷"), "没说货在物流仓: " + e.getMessage());
        assertTrue(e.getActionHint().contains("库存调拨"),
                "货在第三个仓时应指向调拨, 而不是「改成 0」: " + e.getActionHint());
        assertFalse(e.getActionHint().contains("拣货数量改成 0"),
                "货不在目标仓, 置 0 解决不了问题: " + e.getActionHint());
    }

    // ---------------------------------------------------------------- 诚实性

    /**
     * ⛔ 查不出在手量时不许编一个 0 出来。
     *
     * <p>本仓 A¹⁰: 兜底默认值会把「我不知道」翻译成「是 0」, 而这两件事对用户完全不同 ——
     * 「来源仓 0」指向「去调拨」, 「查不出来」指向「先人工核对」。说错方向比不说更糟。
     */
    @Test
    @DisplayName("在手量查不出来 → 如实说查不出来, ⛔ 不许拿 0 顶替")
    void whenOnHandLookupFails_saysSoInsteadOfClaimingZero() {
        requisition(item("it-film", MT_FILM, "封膜", "卷", "4", "b-film"));
        stubBatch("b-film", MT_FILM, WH_WKS, "20");
        when(batchRepository.sumAvailableGroupedByWarehouse(eq(F), anyString()))
                .thenThrow(new IllegalStateException("库存查询挂了"));

        BusinessException e = transferExpectingBlock();

        assertTrue(e.getMessage().contains("查不出来"), "没如实说查不出来: " + e.getMessage());
        assertFalse(e.getMessage().contains("可领用 0"),
                "把「不知道」写成了「是 0」: " + e.getMessage());
        assertTrue(e.getActionHint().contains("人工核对"),
                "查不出来时应指向人工核对: " + e.getActionHint());
    }

    // ---------------------------------------------------------------- 顺序

    /**
     * 拒绝必须发生在<b>动库存之前</b>。
     *
     * <p>原实现在迁移主循环里就地抛 —— 排在越仓行前面的那些行已经划出并建好了生产仓批次,
     * 只靠 {@code @Transactional} 回滚兜底。前置整体校验之后, 抛出时一行都还没写。
     * 这条同时是「逐个列出」能成立的前提: 不前置就只报得出第一个。
     */
    @Test
    @DisplayName("拒绝时一行库存都还没动 (前置校验跑在迁移之前)")
    void rejectsBeforeTouchingAnyStock() {
        prodScenario();

        transferExpectingBlock();

        verify(batchRepository, never()).save(any());
    }
}
