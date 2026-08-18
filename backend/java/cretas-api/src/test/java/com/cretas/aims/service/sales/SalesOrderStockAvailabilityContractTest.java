package com.cretas.aims.service.sales;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸 —— 销售下单要明示「这东西发得出去吗」，<b>第一步只提示、不拦截</b>。
 *
 * <h2>判据 (Steve 2026-08-18 拍板)</h2>
 * 判据是<b>「当前有在手库存」</b>而不是「历史上入过库」——「入过库」是不可撤销的历史事实，
 * 一个早就卖完、也不再经营的商品照样满足它，那道闸什么都没守住。用户要的是<b>发得出货</b>。
 *
 * <h2>⛔ 为什么默认不拦 —— prod 实测 (2026-08-18)</h2>
 * <pre>
 * F006        可下单对象 516 (197 product_types + 319 raw_material_types)
 *             有在手成品 1 / 有在手物料 10 / 在途采购 0 / 在产计划 1  ⇒ 约 2.3%
 * LIUSHANMEN  可下单对象 161, material_batches 与 finished_goods_batches 【各 0 行】,
 *             却有 8 张真实销售订单  ⇒ 硬上任何一档 = 这家 100% 卡死
 * </pre>
 * 所以拦截开关默认关闭。形态 E:「宁可窄而可信，不要宽到被人关掉」。
 *
 * <h2>桩的形状</h2>
 * ⚠️ 桩一律用<b>真实实体对象</b>并只填真实上游会填的字段，可用量走实体自己的
 * {@code getAvailableQuantity()} / {@code getCurrentQuantity()} ——
 * ⛔ 不 mock 出一个「真实 SQL 永远不会产出的形状」（本仓形态 B‴ 踩过）。
 * 仓储桩返回的也是 {@code findAvailableBatches* } 已经过滤过的列表，与生产一致。
 */
class SalesOrderStockAvailabilityContractTest {

    private static final String F = "F006";
    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";   // 黄油鸡成品800g
    private static final String MATERIAL = "RMT_1777441647274";
    private static final String NOTHING = "PT_NO_SIGNAL_AT_ALL";

    private FinishedGoodsBatchRepository fgRepo;
    private MaterialBatchRepository mbRepo;
    private PurchaseOrderRepository poRepo;
    private PurchaseOrderItemRepository poiRepo;
    private ProductionPlanRepository planRepo;
    private SalesOrderStockAvailabilityService service;

    @BeforeEach
    void setUp() {
        fgRepo = mock(FinishedGoodsBatchRepository.class);
        mbRepo = mock(MaterialBatchRepository.class);
        poRepo = mock(PurchaseOrderRepository.class);
        poiRepo = mock(PurchaseOrderItemRepository.class);
        planRepo = mock(ProductionPlanRepository.class);

        when(fgRepo.findAvailableBatches(anyString(), anyString())).thenReturn(List.of());
        when(mbRepo.findAvailableBatchesFEFO(anyString(), anyString())).thenReturn(List.of());
        when(poRepo.findByFactoryIdAndStatusIn(anyString(), any())).thenReturn(List.of());
        when(poiRepo.findByPurchaseOrderIdIn(any())).thenReturn(List.of());
        when(planRepo.findByFactoryIdAndProductTypeId(anyString(), anyString())).thenReturn(List.of());

        service = new SalesOrderStockAvailabilityService(fgRepo, mbRepo);
        ReflectionTestUtils.setField(service, "purchaseOrderRepository", poRepo);
        ReflectionTestUtils.setField(service, "purchaseOrderItemRepository", poiRepo);
        ReflectionTestUtils.setField(service, "productionPlanRepository", planRepo);
    }

    private static FinishedGoodsBatch fgBatch(BigDecimal produced, BigDecimal shipped, BigDecimal reserved) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(reserved);
        return b;
    }

    private static MaterialBatch materialBatch(BigDecimal receipt, BigDecimal used, BigDecimal reserved) {
        MaterialBatch b = new MaterialBatch();
        b.setReceiptQuantity(receipt);
        b.setUsedQuantity(used);
        b.setReservedQuantity(reserved);
        return b;
    }

    private List<SalesOrderStockAvailabilityService.Availability> assess(String refId, String unit) {
        return service.assess(F, List.of(
                new SalesOrderStockAvailabilityService.Line(refId, "黄油鸡成品800g", unit)));
    }

    // ───────────────────────── 三档 ─────────────────────────

    @Test
    @DisplayName("🔴 第一档 有在手 → 放行且【不出提示】(2.3% 覆盖率下, 刷屏就是把提示做死)")
    void inStockEmitsNoMessage() {
        when(fgRepo.findAvailableBatches(eq(F), eq(FG)))
                .thenReturn(List.of(fgBatch(new BigDecimal("80"), new BigDecimal("5"), BigDecimal.ZERO)));

        var r = assess(FG, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.IN_STOCK, r.tier());
        assertEquals(0, r.onHandQty().compareTo(new BigDecimal("75")), "在手应为 80-5-0=75");
        assertNull(r.message(), "有货还提示 = 噪音");
    }

    @Test
    @DisplayName("在手也算物料批次 —— 销售行的 id 既可能是成品也可能是物料字典, 两边都要查")
    void materialBatchesAlsoCountAsOnHand() {
        when(mbRepo.findAvailableBatchesFEFO(eq(F), eq(MATERIAL)))
                .thenReturn(List.of(materialBatch(new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("2"))));

        var r = assess(MATERIAL, "kg").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.IN_STOCK, r.tier());
        assertEquals(0, r.onHandQty().compareTo(new BigDecimal("18")), "在手应为 30-10-2=18");
    }

    @Test
    @DisplayName("🔴 第二档 无在手但【有在产计划】→ 放行 + 明示当前 0 与预计到货日")
    void inboundFromProductionPlanIsAnnounced() {
        ProductionPlan plan = new ProductionPlan();
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setPlannedQuantity(new BigDecimal("80"));
        plan.setActualQuantity(BigDecimal.ZERO);
        plan.setExpectedCompletionDate(LocalDate.of(2026, 8, 25));
        when(planRepo.findByFactoryIdAndProductTypeId(eq(F), eq(FG))).thenReturn(List.of(plan));

        var r = assess(FG, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.INBOUND_ONLY, r.tier());
        assertEquals(0, r.inboundQty().compareTo(new BigDecimal("80")));
        assertTrue(r.message().contains("当前在手 0"), r.message());
        assertTrue(r.message().contains("2026-08-25"), "没说预计什么时候到: " + r.message());
        assertTrue(r.message().contains("盒"), "单位没跟着说, 用户看不出 80 是 80 什么: " + r.message());
    }

    @Test
    @DisplayName("🔴 第二档 无在手但【有在途采购】→ 未收量 = 订购 - 已收, 且带预计到货日")
    void inboundFromPurchaseIsAnnounced() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-1");
        po.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        po.setExpectedDeliveryDate(LocalDate.of(2026, 8, 22));
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setPurchaseOrderId("PO-1");
        line.setMaterialTypeId(MATERIAL);
        line.setQuantity(new BigDecimal("100"));
        line.setReceivedQuantity(new BigDecimal("40"));
        when(poRepo.findByFactoryIdAndStatusIn(eq(F), any())).thenReturn(List.of(po));
        when(poiRepo.findByPurchaseOrderIdIn(any())).thenReturn(List.of(line));

        var r = assess(MATERIAL, "kg").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.INBOUND_ONLY, r.tier());
        assertEquals(0, r.inboundQty().compareTo(new BigDecimal("60")), "未收量应为 100-40=60");
        assertTrue(r.message().contains("2026-08-22"), r.message());
    }

    @Test
    @DisplayName("🔴 第三档 两者都没有 → 明示 + 下一步; 措辞只陈述【记录事实】不断言「你发不出货」")
    void noSignalExplainsNextStep() {
        var r = assess(NOTHING, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, r.tier());
        assertTrue(r.message().contains("查不到在手库存记录"), r.message());
        assertTrue(r.message().contains("采购") && r.message().contains("生产计划"),
                "没告诉下一步: " + r.message());
        // 形态 D′: 2.3% 覆盖率下「你发不出货」绝大多数时候是误报, 一次误报就烧掉整个提示的信任
        assertFalse(r.message().contains("发不出"), "措辞越界了: " + r.message());
    }

    // ───────────────────────── 开关 ─────────────────────────

    @Test
    @DisplayName("🔴 默认【不拦截】—— 第三档也只回提示, assess 不抛异常")
    void gateIsOffByDefault() {
        assertFalse(service.isEnforcing(), "开关默认必须是关的");
        var r = assess(NOTHING, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, r.tier());
    }

    @Test
    @DisplayName("🔴 开关能被打开, 且打开后第三档确实变成可拦 —— 证明拦截分支【到得了】, 不是死代码")
    void gateCanBeTurnedOnAndTheBlockingBranchIsReachable() {
        // ⛔ 刻意不写成编译期常量: static final boolean = false 会被 javac 把分支整段消掉,
        //    拦截逻辑就成了永远执行不到的代码, 这条断言也就永远验不了它。
        service.setEnforcingForTest(true);
        assertTrue(service.isEnforcing());
        var r = assess(NOTHING, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, r.tier(),
                "开关打开后, 第三档仍应被判成 NONE —— 由调用方据此拦");

        service.setEnforcingForTest(false);
        assertFalse(service.isEnforcing(), "开关必须能关回去");
    }

    // ───────────────────────── 仪器自己的死活 ─────────────────────────

    @Test
    @DisplayName("🔴 在途仓储未注册时 inboundKnown=false —— 「没量到」不许和「量到了是 0」长成一样")
    void missingRepositoriesAreReportedAsNotMeasured() {
        SalesOrderStockAvailabilityService bare =
                new SalesOrderStockAvailabilityService(fgRepo, mbRepo);   // 三个 optional 仓储都没注入
        var r = bare.assess(F, List.of(
                new SalesOrderStockAvailabilityService.Line(NOTHING, "某商品", "盒"))).get(0);
        assertFalse(r.inboundKnown(), "仪器没起来却报成「确实没有在途」= 把没量到读成了没问题");
        assertTrue(r.message().contains("没量到"), "读数里没说清是没量到: " + r.message());
    }

    @Test
    @DisplayName("阳性对照: 同一个 service 在有信号时读得出非 NONE —— 否则上面的 NONE 可能只是它把一切都判成 NONE")
    void positiveControlServiceCanReturnNonNone() {
        when(fgRepo.findAvailableBatches(eq(F), eq(FG)))
                .thenReturn(List.of(fgBatch(new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO)));
        assertEquals(SalesOrderStockAvailabilityService.Tier.IN_STOCK, assess(FG, "盒").get(0).tier());
        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, assess(NOTHING, "盒").get(0).tier());
    }

    @Test
    @DisplayName("已完成/已取消的计划不算在产 —— 否则「在产」会把历史产量算进未来")
    void completedPlansDoNotCountAsInbound() {
        ProductionPlan done = new ProductionPlan();
        done.setStatus(ProductionPlanStatus.COMPLETED);
        done.setPlannedQuantity(new BigDecimal("999"));
        done.setActualQuantity(BigDecimal.ZERO);
        ProductionPlan cancelled = new ProductionPlan();
        cancelled.setStatus(ProductionPlanStatus.CANCELLED);
        cancelled.setPlannedQuantity(new BigDecimal("999"));
        cancelled.setActualQuantity(BigDecimal.ZERO);
        when(planRepo.findByFactoryIdAndProductTypeId(eq(F), eq(FG)))
                .thenReturn(List.of(done, cancelled));

        var r = assess(FG, "盒").get(0);
        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, r.tier(),
                "COMPLETED/CANCELLED 被算成在产了");
        assertEquals(0, r.inboundQty().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("已产出的部分要从在产里扣掉 —— 计划 80 已产 80 不再是「还会有 80 进来」")
    void producedQuantityIsNettedOffTheInbound() {
        ProductionPlan p = new ProductionPlan();
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setPlannedQuantity(new BigDecimal("80"));
        p.setActualQuantity(new BigDecimal("80"));
        when(planRepo.findByFactoryIdAndProductTypeId(eq(F), eq(FG))).thenReturn(List.of(p));

        assertEquals(SalesOrderStockAvailabilityService.Tier.NONE, assess(FG, "盒").get(0).tier());
    }

    @Test
    @DisplayName("空行/空 id 不炸 —— 守卫不该把没填完的草稿拦死")
    void blankLinesAreSkipped() {
        assertTrue(service.assess(F, List.of()).isEmpty());
        assertTrue(service.assess(F, null).isEmpty());
        assertTrue(service.assess(F, java.util.Collections.singletonList(
                new SalesOrderStockAvailabilityService.Line(null, "x", "盒"))).isEmpty());
    }
}
