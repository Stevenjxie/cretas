package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 调拨的可搬动量判据 = 货架实物 = 账面可用 − 未小结报工消耗。
 *
 * <p><b>为什么需要这条</b> (2026-08-15 F006 真机)：延迟扣减设计下，报工把料投进工序后
 * 只写 {@code material_consumptions}、暂不动 {@code used_quantity}，等生产小结才逐笔扣。
 * 于是同一个批次同一时刻出现两个数：
 *
 * <ul>
 *   <li>报工页「生产仓可用」= 15 − 10 = <b>5kg</b>（实时算流水，对）</li>
 *   <li>调拨新建页「可用」= receipt − used = 15 − 0 = <b>15kg</b>（账面，错）</li>
 * </ul>
 *
 * 而 FEFO 扣减与预选校验都按账面放行 —— <b>能调走货架上没有的 10kg</b>。
 *
 * <p>2026-07-05 引入 {@code physicalShelf} 时明确只做显示提示、写了「⛔ 不改任何
 * gate/校验」；2026-08-15 经 owner 拍板扩到闸上：<b>提示看得见但拦不住，等于把责任推给操作员</b>。
 *
 * <p><b>两个方向各钉一条</b> —— 只钉「有未结消耗要拦」会让人用「把可用量一律减半」蒙混，
 * 而那会把没有未结消耗的正常调拨也误拦。
 */
@DisplayName("TransferServiceImpl — 调拨判据用货架实物, 不用账面")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferUnsettledConsumptionGateTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository,
                transferItemRepository,
                materialBatchRepository,
                finishedGoodsBatchRepository,
                applicationEventPublisher,
                materialBatchService,
                rawMaterialTypeRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", materialConsumptionRepository);
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ── 拦得住: 有未结消耗时, 账面够而货架不够 ────────────────────────────
    @Test
    @DisplayName("账面 15 / 未结 10 → 调 15 被拦 (真机 F006 的那一笔)")
    void unsettledConsumptionBlocksTransferThatBookWouldAllow() {
        InternalTransferItem item = rawItem(201L, new BigDecimal("15"), null);
        InternalTransfer t = transfer(item);
        MaterialBatch b = batch("TRF-MT-7992", new BigDecimal("15"), BigDecimal.ZERO);

        stubFefo(t, b);
        stubUnsettled("TRF-MT-7992", new BigDecimal("10"));

        assertThatThrownBy(() -> service.shipTransfer("F001", "T_UNS_001", 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getMessage()).contains("原料库存不足");
                    assertThat(be.getActionHint())
                            .as("出路必须指向生产小结 —— 让人去采购是白跑一趟")
                            .contains("生产小结");
                });
        // ⚠️ 这里 usedQuantity 是 5 而不是 0 —— FEFO 循环会先扣掉够得着的那部分, 再因为
        // 剩余量 > 0 抛异常。生产上整个 shipTransfer 在 @Transactional 里, 这次部分扣减
        // 随异常一起回滚; 单测没有事务, 所以看得到这个中间态。
        // 断言写成「必须为 0」是把事务的职责摊到本方法头上, 会得到一条永远红的假要求。
        assertThat(b.getUsedQuantity())
                .as("扣到货架实物为止就停 —— 没有越过 5kg 去动那 10kg 未结的量")
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("账面 15 / 未结 10 → 调 5 放行, 且只扣 5")
    void transferWithinPhysicalShelfStillPasses() {
        InternalTransferItem item = rawItem(202L, new BigDecimal("5"), null);
        InternalTransfer t = transfer(item);
        MaterialBatch b = batch("TRF-MT-7992", new BigDecimal("15"), BigDecimal.ZERO);

        stubFefo(t, b);
        stubUnsettled("TRF-MT-7992", new BigDecimal("10"));

        service.shipTransfer("F001", "T_UNS_001", 99L);

        assertThat(b.getUsedQuantity()).isEqualByComparingTo("5");
    }

    // ── 不误拦: 没有未结消耗时行为完全不变 ──────────────────────────────
    @Test
    @DisplayName("无未结消耗 → 账面 15 调 15 照常放行 (防止把闸改成一律收紧)")
    void withoutUnsettledConsumptionFullBookAmountStillTransfers() {
        InternalTransferItem item = rawItem(203L, new BigDecimal("15"), null);
        InternalTransfer t = transfer(item);
        MaterialBatch b = batch("B_CLEAN", new BigDecimal("15"), BigDecimal.ZERO);

        stubFefo(t, b);
        stubUnsettled("B_CLEAN", null);   // 该批次没有未结消耗行

        service.shipTransfer("F001", "T_UNS_001", 99L);

        assertThat(b.getUsedQuantity()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("消耗仓库(materialConsumptionRepository)未注入 → 退化为账面, 不回归")
    void nullConsumptionRepositoryDegradesToBookAvailability() {
        ReflectionTestUtils.setField(service, "materialConsumptionRepository", null);
        InternalTransferItem item = rawItem(204L, new BigDecimal("15"), null);
        InternalTransfer t = transfer(item);
        MaterialBatch b = batch("B_NOREPO", new BigDecimal("15"), BigDecimal.ZERO);
        stubFefo(t, b);

        service.shipTransfer("F001", "T_UNS_001", 99L);

        assertThat(b.getUsedQuantity()).isEqualByComparingTo("15");
    }

    // ── 阳性对照: 桩真的被调到了 ─────────────────────────────────────────
    @Test
    @DisplayName("阳性对照 —— 未结消耗查询确实被调用, 否则上面几条是恒真的")
    void theUnsettledQueryIsActuallyConsulted() {
        InternalTransferItem item = rawItem(205L, new BigDecimal("1"), null);
        InternalTransfer t = transfer(item);
        MaterialBatch b = batch("B_PROBE", new BigDecimal("15"), BigDecimal.ZERO);
        stubFefo(t, b);
        stubUnsettled("B_PROBE", new BigDecimal("14"));

        service.shipTransfer("F001", "T_UNS_001", 99L);

        // 货架实物 = 15 − 14 = 1, 恰好够 1 —— 若查询没被调用, 账面 15 也能过,
        // 那这条就分辨不出来。所以再验一次边界: 调 2 必须被拦。
        InternalTransferItem over = rawItem(206L, new BigDecimal("2"), null);
        InternalTransfer t2 = transfer(over);
        MaterialBatch b2 = batch("B_PROBE", new BigDecimal("15"), BigDecimal.ZERO);
        stubFefo(t2, b2);
        stubUnsettled("B_PROBE", new BigDecimal("14"));

        assertThatThrownBy(() -> service.shipTransfer("F001", "T_UNS_001", 99L))
                .isInstanceOf(BusinessException.class);
    }

    // ===== helpers =====

    private void stubFefo(InternalTransfer t, MaterialBatch b) {
        when(transferRepository.findByIdAndEitherFactoryId("T_UNS_001", "F001")).thenReturn(Optional.of(t));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F001", "MT_PIG", "WH_WKS"))
                .thenReturn(List.of(b));
    }

    /** null = 该批次无未结消耗行 (真实 SQL 就是不返回这一行)。 */
    private void stubUnsettled(String batchId, BigDecimal qty) {
        when(materialConsumptionRepository.sumUnsettledConsumptionGroupedByBatch(anyString(), anyList()))
                .thenReturn(qty == null ? List.of() : List.<Object[]>of(new Object[]{batchId, qty}));
    }

    private InternalTransfer transfer(InternalTransferItem item) {
        InternalTransfer t = new InternalTransfer();
        t.setId("T_UNS_001");
        t.setTransferNumber("TR-UNS-001");
        t.setSourceFactoryId("F001");
        t.setTargetFactoryId("F002");
        t.setSourceWarehouseId("WH_WKS");
        t.setStatus(TransferStatus.APPROVED);
        t.setTransferType(TransferType.HQ_TO_BRANCH);
        item.setTransferId(t.getId());
        t.getItems().add(item);
        return t;
    }

    private InternalTransferItem rawItem(Long id, BigDecimal qty, String preselected) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(id);
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId("MT_PIG");
        item.setQuantity(qty);
        item.setUnit("kg");
        item.setSourceBatchId(preselected);
        return item;
    }

    private MaterialBatch batch(String id, BigDecimal receipt, BigDecimal used) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setBatchNumber(id);
        b.setFactoryId("F001");
        b.setWarehouseId("WH_WKS");
        b.setMaterialTypeId("MT_PIG");
        b.setQuantityUnit("kg");
        b.setReceiptQuantity(receipt);
        b.setUsedQuantity(used);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setExpireDate(LocalDate.now().plusDays(30));
        return b;
    }
}
