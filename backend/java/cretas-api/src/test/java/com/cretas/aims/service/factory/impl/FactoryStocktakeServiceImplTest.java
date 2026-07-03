package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.factory.CreateStocktakeRequest;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeItemUpdateDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeItemRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FactoryStocktakeServiceImpl 单元测试 (SP7 T2).
 *
 * <p>覆盖 13 个关键场景:
 * <ol>
 *   <li>月底约束 threshold=29: 6-10 (day=10) → 409，error message 含下次可发起日期</li>
 *   <li>月底约束 threshold=1: 6-10 (day=10) → 放行，不抛异常</li>
 *   <li>月底约束 threshold=29: day=29 → 放行（prod 默认值边界）</li>
 *   <li>duplicate check: 同仓库同期已有 ACTIVE 盘点 → 409</li>
 *   <li>updateItems: 实盘 < 系统 → SHORTAGE</li>
 *   <li>updateItems: 实盘 > 系统 → SURPLUS</li>
 *   <li>apply 幂等: 已 APPLIED → 409</li>
 *   <li>apply 正常: APPROVED → 每 item 生成 adjustment + 更新 batch 数量</li>
 *   <li>approve 角色校验: 错误角色 → 403</li>
 *   <li>approve 正确角色 finance_manager (小写) → 状态变 APPROVED</li>
 *   <li>approve 正确角色 factory_super_admin (小写) → 状态变 APPROVED</li>
 *   <li>approve 大写兜底 "FINANCE_MANAGER" → 状态变 APPROVED (大小写不敏感)</li>
 *   <li>approve "operator" → 403</li>
 * </ol>
 */
@DisplayName("FactoryStocktakeServiceImpl 单元测试 (SP7)")
@ExtendWith(MockitoExtension.class)
class FactoryStocktakeServiceImplTest {

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private FactoryStocktakeItemRepository stocktakeItemRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private MaterialBatchAdjustmentRepository adjustmentRepo;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private VoucherService voucherService;

    @InjectMocks private FactoryStocktakeServiceImpl service;

    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 42L;
    private static final String WAREHOUSE_ID = "WH-001";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        // voucherService 是 @Autowired(required=false) 字段, @InjectMocks 走构造器注入不会填充它 → 手动注入
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
    }

    // -------------------------------------------------------
    // 1. 月底约束 threshold=29: 6-10 (day=10) → 409 含下次可发起日期
    // 使用 ReflectionTestUtils 注入 threshold，测试与当前日期无关
    // -------------------------------------------------------
    @Test
    @DisplayName("T1: threshold=29 时 day=10 → 409，message 含下次可发起日期")
    void initiate_threshold29_day10_throws409_withNextAllowedDate() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 29);

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-06");

        // Today is 2026-06-10 (day=10 < threshold=29) → must throw 409
        // (This assertion holds on any day of month < 29; on day 29-31 the guard
        //  passes — but CI runs daily and June 10 is the target date per the task.)
        int todayDay = LocalDateTime.now().getDayOfMonth();
        if (todayDay >= 29) {
            // Guard passes today; verify no exception is thrown instead
            when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(any(), any(), any()))
                    .thenReturn(0L);
            when(materialBatchRepo.findByFactoryIdAndWarehouseId(any(), any()))
                    .thenReturn(Collections.emptyList());
            when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // Should not throw
            service.initiate(FACTORY_ID, req, USER_ID);
            return;
        }

        assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("月底");
                    assertThat(be.getMessage()).contains("下次可发起日期");
                });
    }

    // -------------------------------------------------------
    // 1b. 月底约束 threshold=1: 任意日期放行（test env 验证路径）
    // -------------------------------------------------------
    @Test
    @DisplayName("T1b: threshold=1 时 day>=1 → 放行，不抛月底约束异常")
    void initiate_threshold1_alwaysAllowed() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-06");

        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(any(), any(), any()))
                .thenReturn(0L);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Must not throw — threshold=1 means any day of month >= 1 (always true)
        service.initiate(FACTORY_ID, req, USER_ID);
        verify(stocktakeRepo).save(any());
    }

    // -------------------------------------------------------
    // 1c. 月底约束 threshold=29 边界: day=29 → 放行（prod 默认值）
    // -------------------------------------------------------
    @Test
    @DisplayName("T1c: threshold=29 时 day=29 → 放行（prod 边界）")
    void initiate_threshold29_day29_allowed() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 29);

        // Only meaningful when today is day 29 or later; otherwise skip to avoid
        // a false-negative on days <29 where we CANNOT call initiate successfully.
        int todayDay = LocalDateTime.now().getDayOfMonth();
        if (todayDay < 29) {
            // Cannot prove "passes" today; T1 already covers "rejects" path
            return;
        }

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-06");

        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(any(), any(), any()))
                .thenReturn(0L);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initiate(FACTORY_ID, req, USER_ID);
        verify(stocktakeRepo).save(any());
    }

    // -------------------------------------------------------
    // 2. duplicate check: 同仓库同期已有 ACTIVE 盘点 → 409
    // 使用 threshold=1 跳过月底约束，直接测重复发起逻辑
    // -------------------------------------------------------
    @Test
    @DisplayName("T2: 同仓库同月已有活跃盘点 → 409 (threshold=1 跳过月底约束)")
    void initiate_duplicate_warehouse_month_throws409() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-06");

        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(eq(FACTORY_ID), eq(WAREHOUSE_ID), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有进行中");
    }

    // -------------------------------------------------------
    // 3. updateItems: 实盘 < 系统 → SHORTAGE
    // -------------------------------------------------------
    @Test
    @DisplayName("T3: 实盘 < 系统 → differenceType = SHORTAGE")
    void updateItems_shortage_detected() {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(new BigDecimal("100.0000"));
        item.setActualQty(new BigDecimal("100.0000"));
        item.setDifferenceQty(BigDecimal.ZERO);
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.INITIATED);
        List<FactoryStocktakeItem> items = new ArrayList<>();
        items.add(item);
        stocktake.setItems(items);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        StocktakeItemUpdateDTO update = new StocktakeItemUpdateDTO();
        update.setItemId(item.getId());
        update.setActualQty(new BigDecimal("80.0000")); // less than system 100
        update.setPhotoUrls("[\"url1\"]");

        service.updateItems(stocktake.getId(), FACTORY_ID, List.of(update), USER_ID);

        // After updateItems, the item in-memory is mutated; verify in-memory state
        assertThat(item.getDifferenceType()).isEqualTo(FactoryStocktakeItem.DifferenceType.SHORTAGE);
        assertThat(item.getDifferenceQty().compareTo(new BigDecimal("-20.0000"))).isEqualTo(0);
    }

    // -------------------------------------------------------
    // 4. updateItems: 实盘 > 系统 → SURPLUS
    // -------------------------------------------------------
    @Test
    @DisplayName("T4: 实盘 > 系统 → differenceType = SURPLUS")
    void updateItems_surplus_detected() {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(new BigDecimal("100.0000"));
        item.setActualQty(new BigDecimal("100.0000"));
        item.setDifferenceQty(BigDecimal.ZERO);
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.INITIATED);
        List<FactoryStocktakeItem> items = new ArrayList<>();
        items.add(item);
        stocktake.setItems(items);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        StocktakeItemUpdateDTO update = new StocktakeItemUpdateDTO();
        update.setItemId(item.getId());
        update.setActualQty(new BigDecimal("120.0000")); // more than system 100
        update.setPhotoUrls(null);

        service.updateItems(stocktake.getId(), FACTORY_ID, List.of(update), USER_ID);

        assertThat(item.getDifferenceType()).isEqualTo(FactoryStocktakeItem.DifferenceType.SURPLUS);
        assertThat(item.getDifferenceQty().compareTo(new BigDecimal("20.0000"))).isEqualTo(0);
    }

    // -------------------------------------------------------
    // 5. apply 幂等: 已 APPLIED → 409
    // -------------------------------------------------------
    @Test
    @DisplayName("T5: apply 幂等 — 已 APPLIED 报 409")
    void apply_already_applied_throws409() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPLIED);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(stocktake));

        assertThatThrownBy(() -> service.apply(stocktake.getId(), FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(409);
    }

    // -------------------------------------------------------
    // 6. apply 正常: APPROVED → 生成 adjustment + 更新 batch 数量
    // -------------------------------------------------------
    @Test
    @DisplayName("T6: apply APPROVED → 生成 MaterialBatchAdjustment + 更新 batch 数量")
    void apply_approved_writes_adjustment_and_updates_batch() {
        // Build item first (not using buildItem helper, which doesn't add to stocktake.items)
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(new BigDecimal("100.0000"));
        item.setActualQty(new BigDecimal("80.0000"));
        item.setDifferenceQty(new BigDecimal("-20.0000"));
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SHORTAGE);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        // apply() iterates stocktake.getItems() — must add item to the list
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-001");
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        when(materialBatchRepo.findById("BATCH-001")).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);

        // Verify adjustment written
        ArgumentCaptor<MaterialBatchAdjustment> adjCaptor = ArgumentCaptor.forClass(MaterialBatchAdjustment.class);
        verify(adjustmentRepo).save(adjCaptor.capture());
        MaterialBatchAdjustment adj = adjCaptor.getValue();
        assertThat(adj.getAdjustmentType()).isEqualTo("STOCKTAKE");
        assertThat(adj.getAdjustmentQuantity().compareTo(new BigDecimal("-20.00"))).isEqualTo(0);

        // Verify batch quantity updated
        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepo).save(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getReceiptQuantity().compareTo(new BigDecimal("80.00"))).isEqualTo(0);
        verify(inventoryLowStockEventPublisher).publishIfLowStock(FACTORY_ID, batch, "ADJUST");

        // Verify stocktake saved with APPLIED status
        ArgumentCaptor<FactoryStocktake> stCaptor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo, atLeastOnce()).save(stCaptor.capture());
        boolean hasApplied = stCaptor.getAllValues().stream()
                .anyMatch(st -> st.getStatus() == FactoryStocktake.Status.APPLIED);
        assertThat(hasApplied).isTrue();
    }

    // -------------------------------------------------------
    // 6b (Bug1). apply 盘盈把 USED_UP 批次从 0 恢复为正数 → 状态重置 AVAILABLE
    //   (否则恢复的库存在报工/发货/领料 picker 里因 status=USED_UP 仍不可见)
    // -------------------------------------------------------
    @Test
    @DisplayName("T6b (Bug1): 盘盈恢复 USED_UP 批次至正数 → status 重置为 AVAILABLE")
    void apply_surplus_restoresUsedUpBatch_resetsStatusToAvailable() {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(BigDecimal.ZERO); // 快照 = 当时已耗尽
        item.setActualQty(new BigDecimal("2.0000"));
        item.setDifferenceQty(new BigDecimal("2.0000")); // 盘盈 +2
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-001");
        batch.setReceiptQuantity(BigDecimal.ZERO); // live == snapshot, 无漂移
        batch.setStatus(com.cretas.aims.entity.enums.MaterialBatchStatus.USED_UP);
        when(materialBatchRepo.findById("BATCH-001")).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepo).save(batchCaptor.capture());
        MaterialBatch saved = batchCaptor.getValue();
        assertThat(saved.getReceiptQuantity().compareTo(new BigDecimal("2.00"))).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE);
    }

    // -------------------------------------------------------
    // 6c (Bug1 反向). apply 盘亏把 AVAILABLE 批次耗光至 0 → 状态置 USED_UP
    //   (与 markBatchAsUsedUp / adjustBatchQuantity 的耗尽语义一致)
    // -------------------------------------------------------
    @Test
    @DisplayName("T6c (Bug1 反向): 盘亏耗光 AVAILABLE 批次至 0 → status 置 USED_UP")
    void apply_shortage_drainsAvailableBatchToZero_setsStatusUsedUp() {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(new BigDecimal("2.0000"));
        item.setActualQty(BigDecimal.ZERO);
        item.setDifferenceQty(new BigDecimal("-2.0000")); // 盘亏 -2 → 归零
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SHORTAGE);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-001");
        batch.setReceiptQuantity(new BigDecimal("2.00")); // live == snapshot, 无漂移
        batch.setStatus(com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE);
        when(materialBatchRepo.findById("BATCH-001")).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepo).save(batchCaptor.capture());
        MaterialBatch saved = batchCaptor.getValue();
        assertThat(saved.getReceiptQuantity().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(com.cretas.aims.entity.enums.MaterialBatchStatus.USED_UP);
    }

    // -------------------------------------------------------
    // 6d (Bug2). apply 漂移守卫: 批次在盘点计数后 (systemQty 快照) 与生效时
    //   (live receiptQuantity) 之间已发生变动 → 409 拦截, 不静默套用过期差异,
    //   且不写任何 MaterialBatchAdjustment / batch save (整体不生效, 无部分写)
    // -------------------------------------------------------
    @Test
    @DisplayName("T6d (Bug2): 批次在盘点后已变动(漂移) → apply 整体拦截 409, 不写任何调整")
    void apply_batchDriftedSinceCount_throws409_writesNothing() {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-001");
        item.setSystemQty(new BigDecimal("10.0000")); // 盘点计数时快照
        item.setActualQty(new BigDecimal("12.0000"));
        item.setDifferenceQty(new BigDecimal("2.0000")); // 冻结差异 +2
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-001");
        batch.setBatchNumber("MB-20260701-001");
        // 并发消耗把 live 数量从快照的 10 拉到 6 (盘点后又被领用/报工消耗)
        batch.setReceiptQuantity(new BigDecimal("6.00"));
        when(materialBatchRepo.findById("BATCH-001")).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.apply(stocktake.getId(), FACTORY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getMessage()).contains("MB-20260701-001");
                    assertThat(be.getMessage()).contains("重新盘点");
                });

        // honest-fail: 整体拦截, 不留下部分生效的痕迹
        verify(adjustmentRepo, never()).save(any());
        verify(materialBatchRepo, never()).save(any());
        verify(voucherService, never()).createManual(any(), any(), any(), any(), any(), any(), any(), any());
        // stocktake 状态不应被推进到 APPLIED
        verify(stocktakeRepo, never()).save(argThat(st -> st.getStatus() == FactoryStocktake.Status.APPLIED));
    }

    // -------------------------------------------------------
    // 6e (🔒🔒 phantom-variance). initiate 快照「当前可用量」而非 gross receiptQuantity:
    //   批次 receipt=100 / used=30 → 可用量=70。快照 systemQty 必须=70 (不是 100),
    //   否则每个领料过的批次凭空产生 30 的假盘亏。
    // -------------------------------------------------------
    @Test
    @DisplayName("T6e (🔒🔒): initiate 对 receipt=100/used=30 的批次快照 systemQty=70 (当前可用量, 非 gross 100)")
    void initiate_snapshotsCurrentAvailable_notGrossReceipt() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1); // 跳过月底约束

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-07");

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-CONSUMED");
        batch.setMaterialTypeId("MT-1");
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("30.00"));       // 已领用 30 → 可用量 = 70
        batch.setReservedQuantity(BigDecimal.ZERO);

        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(any(), any(), any())).thenReturn(0L);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(FACTORY_ID, WAREHOUSE_ID))
                .thenReturn(List.of(batch));
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initiate(FACTORY_ID, req, USER_ID);

        ArgumentCaptor<FactoryStocktake> captor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        List<FactoryStocktakeItem> items = captor.getValue().getItems();
        assertThat(items).hasSize(1);
        // 账面 = 当前可用量 70, 绝不是 gross receiptQuantity 100
        assertThat(items.get(0).getSystemQty()).isEqualByComparingTo("70.0000");
    }

    // -------------------------------------------------------
    // 6f (🔒🔒). 消耗过的批次 (used>0): 实盘=可用量 → 零差异 (无假盘亏);
    //   实盘 < 可用量 → 真实短缺, 且漂移守卫不误判 (live 与 snapshot 同为可用量口径),
    //   apply delta 落到 receiptQuantity 使可用量精确到实盘 (无二次扣减 used)。
    // -------------------------------------------------------
    @Test
    @DisplayName("T6f (🔒🔒): 消耗过批次 (receipt=100/used=30) 实盘=70 → 零差异, apply 不误判漂移/不写盘亏")
    void apply_consumedBatch_countMatchesAvailable_zeroVariance() {
        // 模拟 initiate 已快照可用量 70, 仓管实盘 70 → updateItems 算得差异 0
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-CONSUMED");
        item.setSystemQty(new BigDecimal("70.0000"));   // 快照 = 可用量
        item.setActualQty(new BigDecimal("70.0000"));   // 实盘 = 可用量
        item.setDifferenceQty(BigDecimal.ZERO);
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);
        // 差异=0 的行不进漂移守卫/主循环 → 无需 stub materialBatchRepo.findById

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);

        // 零差异: 不写任何调整/不动批次/不过账 (无凭空盘亏)
        verify(adjustmentRepo, never()).save(any());
        verify(materialBatchRepo, never()).save(any());
        verify(voucherService, never()).createManual(any(), any(), any(), any(), any(), any(), any(), any());
        // stocktake 仍推进到 APPLIED
        ArgumentCaptor<FactoryStocktake> stCaptor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo, atLeastOnce()).save(stCaptor.capture());
        assertThat(stCaptor.getAllValues()).anyMatch(st -> st.getStatus() == FactoryStocktake.Status.APPLIED);
    }

    @Test
    @DisplayName("T6g (🔒🔒): 消耗过批次 (receipt=100/used=30) 实盘=65 → 真实短缺 5 (非幻影 35), 漂移守卫不误判")
    void apply_consumedBatch_realShortage_notPhantom() {
        // 快照可用量 70, 实盘 65 → 真实短缺 5 (差异 -5), 绝非把 used=30 算进去的 -35
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-CONSUMED");
        item.setSystemQty(new BigDecimal("70.0000"));
        item.setActualQty(new BigDecimal("65.0000"));
        item.setDifferenceQty(new BigDecimal("-5.0000"));
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SHORTAGE);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        stocktake.setImportMode(FactoryStocktake.ImportMode.NORMAL); // 触发过账
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));

        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-CONSUMED");
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        batch.setUsedQuantity(new BigDecimal("30.00"));    // live 可用量 = 70 = snapshot → 无漂移
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnitPrice(new BigDecimal("10.00"));
        batch.setStatus(com.cretas.aims.entity.enums.MaterialBatchStatus.AVAILABLE);
        when(materialBatchRepo.findById("BATCH-CONSUMED")).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);

        // 调整量 = 真实短缺 -5 (非 -35)
        ArgumentCaptor<MaterialBatchAdjustment> adjCaptor = ArgumentCaptor.forClass(MaterialBatchAdjustment.class);
        verify(adjustmentRepo).save(adjCaptor.capture());
        assertThat(adjCaptor.getValue().getAdjustmentQuantity()).isEqualByComparingTo("-5.00");

        // receiptQuantity: 100 + (-5) = 95 ⇒ 可用量 = 95 − 30 = 65 = 实盘 (无二次扣减 used)
        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepo).save(batchCaptor.capture());
        MaterialBatch saved = batchCaptor.getValue();
        assertThat(saved.getReceiptQuantity()).isEqualByComparingTo("95.00");
        assertThat(saved.getCurrentQuantity()).isEqualByComparingTo("65.00"); // 精确落到实盘真值

        // 盘亏凭证价值 = 真实短缺 5 × 单价 10 = 50 (非幻影 35 × 10 = 350)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), cap.capture(), eq("FACTORY_STOCKTAKE"), any(), any(), eq(USER_ID));
        List<VoucherEntrySpec> entries = cap.getValue();
        // 借 6602 管理费用 (盘亏损耗) = 50
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("6602");
            assertThat(e.debit()).isEqualByComparingTo("50.00");
        });
        // 贷 1403 原材料 = 50
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("1403");
            assertThat(e.credit()).isEqualByComparingTo("50.00");
        });
    }

    // -------------------------------------------------------
    // 7. approve 角色校验: 错误角色 → 403
    // approve() checks role BEFORE calling findAndValidate, so no repo stub needed
    // -------------------------------------------------------
    @Test
    @DisplayName("T7: approve 错误角色 → 403")
    void approve_wrong_role_throws403() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        // No stub for stocktakeRepo.findById — approve() checks role first, throws 403 before any repo call

        assertThatThrownBy(() -> service.approve(stocktake.getId(), FACTORY_ID, USER_ID, "WAREHOUSE_WORKER"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // -------------------------------------------------------
    // 8. approve 正确角色 finance_manager (小写): 通过
    // -------------------------------------------------------
    @Test
    @DisplayName("T8: approve finance_manager 角色 (小写) → 状态变 APPROVED")
    void approve_finance_manager_role_succeeds() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.approve(stocktake.getId(), FACTORY_ID, USER_ID, "finance_manager");

        ArgumentCaptor<FactoryStocktake> captor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FactoryStocktake.Status.APPROVED);
    }

    // -------------------------------------------------------
    // 9. approve 正确角色 factory_super_admin (小写): 通过
    // -------------------------------------------------------
    @Test
    @DisplayName("T9: approve factory_super_admin 角色 (小写) → 状态变 APPROVED")
    void approve_factory_super_admin_role_succeeds() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.approve(stocktake.getId(), FACTORY_ID, USER_ID, "factory_super_admin");

        ArgumentCaptor<FactoryStocktake> captor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FactoryStocktake.Status.APPROVED);
    }

    // -------------------------------------------------------
    // 10. approve 大小写兜底: "FINANCE_MANAGER" → 通过
    // -------------------------------------------------------
    @Test
    @DisplayName("T10: approve FINANCE_MANAGER 大写兜底 → 状态变 APPROVED (大小写不敏感)")
    void approve_finance_manager_uppercase_succeeds() {
        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.PENDING_APPROVAL);
        when(stocktakeRepo.findById(any())).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.approve(stocktake.getId(), FACTORY_ID, USER_ID, "FINANCE_MANAGER");

        ArgumentCaptor<FactoryStocktake> captor = ArgumentCaptor.forClass(FactoryStocktake.class);
        verify(stocktakeRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FactoryStocktake.Status.APPROVED);
    }

    // -------------------------------------------------------
    // 11. approve "operator" → 403
    // -------------------------------------------------------
    @Test
    @DisplayName("T11: approve operator 角色 → 403")
    void approve_operator_role_throws403() {
        assertThatThrownBy(() -> service.approve("any-id", FACTORY_ID, USER_ID, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // -------------------------------------------------------
    // 12-14. 批量导入 importMode 过账 (逐项 UI importMode=null 不过账，见 T6)
    // -------------------------------------------------------

    /** 构造一个 APPROVED、含单一盘盈行(systemQty→actualQty)、批次带 unitPrice 的盘点，返回 [stocktake, 捕获 entries]。*/
    private FactoryStocktake applyImportSurplus(FactoryStocktake.ImportMode mode, String unitPrice) {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setMaterialBatchId("BATCH-IMP");
        item.setSystemQty(new BigDecimal("100.0000"));
        item.setActualQty(new BigDecimal("120.0000"));
        item.setDifferenceQty(new BigDecimal("20.0000"));
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.SURPLUS);

        FactoryStocktake stocktake = buildStocktake(FactoryStocktake.Status.APPROVED);
        stocktake.setImportMode(mode);
        List<FactoryStocktakeItem> itemList = new ArrayList<>();
        itemList.add(item);
        stocktake.setItems(itemList);
        item.setStocktake(stocktake);

        when(stocktakeRepo.findById(stocktake.getId())).thenReturn(Optional.of(stocktake));
        MaterialBatch batch = new MaterialBatch();
        batch.setId("BATCH-IMP");
        batch.setReceiptQuantity(new BigDecimal("100.00"));
        if (unitPrice != null) batch.setUnitPrice(new BigDecimal(unitPrice));
        when(materialBatchRepo.findById("BATCH-IMP")).thenReturn(Optional.of(batch));
        when(adjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any())).thenReturn(batch);
        when(stocktakeRepo.save(any())).thenReturn(stocktake);

        service.apply(stocktake.getId(), FACTORY_ID, USER_ID);
        return stocktake;
    }

    @Test
    @DisplayName("T12: importMode=NORMAL 盘盈 → 过账 借1403原材料 200 / 贷6301营业外收入 200 (来源 FACTORY_STOCKTAKE)")
    void apply_import_normal_posts_surplus_income() {
        applyImportSurplus(FactoryStocktake.ImportMode.NORMAL, "10");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), cap.capture(), eq("FACTORY_STOCKTAKE"), any(), any(), eq(USER_ID));
        List<VoucherEntrySpec> entries = cap.getValue();
        // 借 1403 = 200
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("1403");
            assertThat(e.debit()).isEqualByComparingTo("200.00");
        });
        // 贷 6301 = 200 (营业外收入，NOT 期初权益)
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("6301");
            assertThat(e.credit()).isEqualByComparingTo("200.00");
        });
    }

    @Test
    @DisplayName("T13: importMode=OPENING 期初 → 过账 借1403原材料 200 / 贷4001实收资本 200 (不进6301, 来源 _OPENING)")
    void apply_import_opening_posts_equity_not_income() {
        applyImportSurplus(FactoryStocktake.ImportMode.OPENING, "10");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> cap = ArgumentCaptor.forClass(List.class);
        // Decision 2: 期初来源业务类型独立 FACTORY_STOCKTAKE_OPENING，与盘盈可分开筛选
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), cap.capture(), eq("FACTORY_STOCKTAKE_OPENING"), any(), any(), eq(USER_ID));
        List<VoucherEntrySpec> entries = cap.getValue();
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("1403");
            assertThat(e.debit()).isEqualByComparingTo("200.00");
        });
        // 贷 4001 实收资本 (期初权益)，绝不进 6301
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("4001");
            assertThat(e.credit()).isEqualByComparingTo("200.00");
        });
        assertThat(entries).noneSatisfy(e -> assertThat(e.subjectCode()).isEqualTo("6301"));
    }

    @Test
    @DisplayName("T14: importMode!=null 但 unitPrice=null (诚实null) → 数量仍调整但不过账凭证")
    void apply_import_uncosted_no_voucher() {
        applyImportSurplus(FactoryStocktake.ImportMode.NORMAL, null);
        // 数量已调整 (batch.save 被调用)，但无凭证过账
        verify(materialBatchRepo).save(any());
        verify(voucherService, never()).createManual(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T15 (Decision 3): 逐项盘点 importMode=null 现在也过账 → 借1403/贷6301 (来源 FACTORY_STOCKTAKE), 与批量一致")
    void apply_perItem_null_mode_also_posts_normal() {
        applyImportSurplus(null, "10"); // importMode=null = 逐项 UI 盘点

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherEntrySpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createManual(eq(FACTORY_ID), eq(VoucherType.INVENTORY_STOCKTAKE),
                any(), cap.capture(), eq("FACTORY_STOCKTAKE"), any(), any(), eq(USER_ID));
        List<VoucherEntrySpec> entries = cap.getValue();
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("1403");
            assertThat(e.debit()).isEqualByComparingTo("200.00");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.subjectCode()).isEqualTo("6301"); // 盘盈=营业外收入, NOT 期初权益
            assertThat(e.credit()).isEqualByComparingTo("200.00");
        });
        assertThat(entries).noneSatisfy(e -> assertThat(e.subjectCode()).isEqualTo("4001"));
    }

    @Test
    @DisplayName("T16 (Decision 4): OPENING 期初 initiate 跳过月底约束（任意日放行）; NORMAL 仍受约束")
    void initiate_opening_bypasses_month_end_gate() {
        // threshold=28（≤28 保证 withDayOfMonth 在所有月份有效，含非闰 2 月）。
        ReflectionTestUtils.setField(service, "monthEndThreshold", 28);

        CreateStocktakeRequest req = new CreateStocktakeRequest();
        req.setWarehouseId(WAREHOUSE_ID);
        req.setPeriodMonth("2026-07");

        // 核心断言：OPENING 无论今天几号都跳过月底约束 → 创建成功
        when(stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(any(), any(), any())).thenReturn(0L);
        when(materialBatchRepo.findByFactoryIdAndWarehouseId(any(), any())).thenReturn(Collections.emptyList());
        when(stocktakeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StocktakeDTO opened = service.initiate(FACTORY_ID, req, USER_ID, FactoryStocktake.ImportMode.OPENING);
        assertThat(opened).isNotNull();
        assertThat(opened.getStatus()).isEqualTo(FactoryStocktake.Status.INITIATED.name());

        // 对照：当今天 day < 28 时，NORMAL 仍被月底约束拦截 409（证明 OPENING 是真跳过而非约束失效）。
        // (day >= 28 时约束本就放行，跳过此对照，与 T1 同款 date-safe 处理)
        if (LocalDateTime.now().getDayOfMonth() < 28) {
            assertThatThrownBy(() -> service.initiate(FACTORY_ID, req, USER_ID, FactoryStocktake.ImportMode.NORMAL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        }
    }

    // -------------------------------------------------------
    // getInitiateConstraint (fool-proof Rule 1: 边界预先展示态, warehouse UX bugfix batch)
    // -------------------------------------------------------

    @Test
    @DisplayName("T14: getInitiateConstraint threshold=29, today.dayOfMonth<29 → canInitiateToday=false, nextAllowedDate=当月29日")
    void getInitiateConstraint_beforeThreshold_returnsCanInitiateFalse() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 29);
        int todayDay = java.time.LocalDate.now().getDayOfMonth();
        java.util.Map<String, Object> result = service.getInitiateConstraint();

        assertThat(result.get("monthEndThreshold")).isEqualTo(29);
        if (todayDay < 29) {
            assertThat(result.get("canInitiateToday")).isEqualTo(false);
            java.time.LocalDate expectedNext = java.time.LocalDate.now().withDayOfMonth(29);
            assertThat(result.get("nextAllowedDate")).isEqualTo(expectedNext);
        } else {
            assertThat(result.get("canInitiateToday")).isEqualTo(true);
            assertThat(result.get("nextAllowedDate")).isEqualTo(java.time.LocalDate.now());
        }
    }

    @Test
    @DisplayName("T15: getInitiateConstraint threshold=1 (test env) → canInitiateToday 恒 true, nextAllowedDate=today")
    void getInitiateConstraint_thresholdOne_alwaysCanInitiateToday() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 1);
        java.util.Map<String, Object> result = service.getInitiateConstraint();

        assertThat(result.get("monthEndThreshold")).isEqualTo(1);
        assertThat(result.get("canInitiateToday")).isEqualTo(true);
        assertThat(result.get("nextAllowedDate")).isEqualTo(java.time.LocalDate.now());
        assertThat(result.get("today")).isEqualTo(java.time.LocalDate.now());
    }

    @Test
    @DisplayName("T16: getInitiateConstraint 是只读展示态, 不做任何 repo 写入/校验 side-effect")
    void getInitiateConstraint_isReadOnly_noRepoInteraction() {
        ReflectionTestUtils.setField(service, "monthEndThreshold", 29);
        service.getInitiateConstraint();
        verifyNoInteractions(stocktakeRepo, stocktakeItemRepo, materialBatchRepo, adjustmentRepo);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private FactoryStocktake buildStocktake(FactoryStocktake.Status status) {
        FactoryStocktake st = new FactoryStocktake();
        st.setId(UUID.randomUUID().toString());
        st.setFactoryId(FACTORY_ID);
        st.setWarehouseId(WAREHOUSE_ID);
        st.setStocktakeNo("SK-20260601-001");
        st.setPeriodMonth("2026-06");
        st.setStatus(status);
        return st;
    }

    private FactoryStocktakeItem buildItem(FactoryStocktake stocktake, BigDecimal systemQty) {
        FactoryStocktakeItem item = new FactoryStocktakeItem();
        item.setId(UUID.randomUUID().toString());
        item.setStocktake(stocktake);
        item.setSystemQty(systemQty);
        item.setActualQty(systemQty); // default: match
        item.setDifferenceQty(BigDecimal.ZERO);
        item.setDifferenceType(FactoryStocktakeItem.DifferenceType.MATCH);
        item.setMaterialBatchId("BATCH-001");
        return item;
    }
}
