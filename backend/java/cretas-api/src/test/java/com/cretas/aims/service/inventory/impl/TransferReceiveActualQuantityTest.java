package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-3 调拨签收 actualQuantity 持久化 — 单元测试。
 *
 * <p>receiveTransfer 支持可选 itemActualQuantities 参数:
 * - 非 null map: 对应 item 写入 receivedQuantity = map.get(itemId)
 * - null map / item 不在 map 中: 回退为 item.quantity (向后兼容)
 * - 禁止 N+1: item 在 transfer.getItems() 中一次性处理
 */
@DisplayName("TransferServiceImpl BUG-3 — 签收 actualQuantity")
@ExtendWith(MockitoExtension.class)
class TransferReceiveActualQuantityTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

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
    }

    private InternalTransfer buildShippedTransfer(Long itemId, BigDecimal shippedQty) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(itemId);
        item.setItemType(TransferItemType.RAW_MATERIAL);
        item.setMaterialTypeId("MT-001");
        item.setQuantity(shippedQty);
        item.setUnit("kg");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TRF-001");
        transfer.setTransferNumber("TR-001");
        transfer.setSourceFactoryId("F001");
        transfer.setTargetFactoryId("F006");
        transfer.setStatus(TransferStatus.SHIPPED);
        transfer.setTransferType(TransferType.HQ_TO_BRANCH);
        transfer.getItems().add(item);

        return transfer;
    }

    @Test
    @DisplayName("传入 actualQuantities 覆盖 item.receivedQuantity")
    void receiveWithActualQuantity_writesReceivedQuantity() {
        InternalTransfer transfer = buildShippedTransfer(10L, new BigDecimal("100"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        Map<Long, BigDecimal> actuals = Map.of(10L, new BigDecimal("87.5"));
        service.receiveTransfer("F006", "TRF-001", 1L, actuals);

        // item.receivedQuantity 应被设置为 87.5
        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity())
                .as("actualQuantity 传入时应写入 receivedQuantity")
                .isEqualByComparingTo(new BigDecimal("87.5"));
    }

    @Test
    @DisplayName("actualQuantities=null 时回退为 item.quantity (向后兼容)")
    void receiveWithNullActualQuantities_fallsBackToShippedQuantity() {
        InternalTransfer transfer = buildShippedTransfer(20L, new BigDecimal("50"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        service.receiveTransfer("F006", "TRF-001", 1L, null);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity())
                .as("null map 时 receivedQuantity 应回退为 shipped quantity")
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    @DisplayName("item 不在 actualQuantities map 中时回退为 item.quantity")
    void receiveWithMissingItemInMap_fallsBackToShippedQuantity() {
        InternalTransfer transfer = buildShippedTransfer(30L, new BigDecimal("60"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // map 不包含 itemId=30L
        Map<Long, BigDecimal> actuals = Map.of(999L, new BigDecimal("99"));
        service.receiveTransfer("F006", "TRF-001", 1L, actuals);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity())
                .as("item 不在 map 中时 receivedQuantity 应回退为 shipped quantity")
                .isEqualByComparingTo(new BigDecimal("60"));
    }

    @Test
    @DisplayName("旧接口 receiveTransfer(factoryId, transferId, userId) 仍正常 (向后兼容)")
    void oldSignature_stillWorksAsBeforeWithFallback() {
        InternalTransfer transfer = buildShippedTransfer(40L, new BigDecimal("200"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // 调用旧接口 3 参数版本
        service.receiveTransfer("F006", "TRF-001", 1L);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity())
                .as("旧接口应回退为 shipped quantity")
                .isEqualByComparingTo(new BigDecimal("200"));
    }

    // ─── 🔒 库存完整性修复 (2026-07-06): 超收上限守卫 ──────────────────────
    //
    // 复现 bug: 发货 5kg, itemActualQuantities 传 500 (100 倍) → 旧代码无校验,
    // 直接写入 receivedQuantity=500, confirmTransfer 阶段据此在目标仓凭空建 500kg
    // 批次, 源仓只按发货量 5kg 扣减 → 净空造 495kg。
    //
    // 默认容忍率 2% (transferReceiveOverToleranceRate 字段内联默认值, 见
    // TransferServiceImpl 字段注释) — 调拨是内部搬运, 物理上目标不该收到比发运更多
    // 的实物, 容忍只是为了兼容两端过磅的仪器误差, 不是业务性多收开口子。

    @Test
    @DisplayName("🔒 实收远超发运量 (100倍, 复现 bug 数值 5→500) → 409 拒绝签收, 不写入 receivedQuantity")
    void receiveWithGrossOverReceipt_throws409AndDoesNotPersist() {
        InternalTransfer transfer = buildShippedTransfer(50L, new BigDecimal("5"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));

        Map<Long, BigDecimal> actuals = Map.of(50L, new BigDecimal("500"));

        assertThatThrownBy(() -> service.receiveTransfer("F006", "TRF-001", 1L, actuals))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实收量超出发运量上限")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));

        // 校验发生在任何 DB mutation 之前: 不应写 receivedQuantity, 不应 save
        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity()).isNull();
        verify(transferItemRepository, never()).saveAll(any());
        verify(transferRepository, never()).save(any(InternalTransfer.class));
    }

    @Test
    @DisplayName("🔒 实收略超容忍率 (5% > 默认2%) → 409 拒绝签收")
    void receiveWithOverReceiptBeyondTolerance_throws409() {
        InternalTransfer transfer = buildShippedTransfer(51L, new BigDecimal("100"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));

        // 100 × 1.05 = 105, 超过默认 2% 容忍上限 (102)
        Map<Long, BigDecimal> actuals = Map.of(51L, new BigDecimal("105"));

        assertThatThrownBy(() -> service.receiveTransfer("F006", "TRF-001", 1L, actuals))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实收量超出发运量上限");
    }

    @Test
    @DisplayName("实收 = 发运量恰好上限内 (102, 默认2%容忍边界) → 允许签收")
    void receiveWithinToleranceBoundary_allowed() {
        InternalTransfer transfer = buildShippedTransfer(52L, new BigDecimal("100"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        // 恰好等于 100 × (1+0.02) = 102 — 边界值应被允许 (compareTo > 0 才拒绝)
        Map<Long, BigDecimal> actuals = Map.of(52L, new BigDecimal("102"));

        service.receiveTransfer("F006", "TRF-001", 1L, actuals);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("102"));
    }

    @Test
    @DisplayName("实收 < 发运量 (少收) → 不受超收守卫影响, 正常签收")
    void receiveUnderReceipt_notAffectedByOverGuard() {
        InternalTransfer transfer = buildShippedTransfer(53L, new BigDecimal("100"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        Map<Long, BigDecimal> actuals = Map.of(53L, new BigDecimal("80"));

        service.receiveTransfer("F006", "TRF-001", 1L, actuals);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("80"));
    }

    @Test
    @DisplayName("Ops 可调容忍率: 放宽到 50% 后, 之前会拒绝的 5% 超收变为允许")
    void receiveWithWidenedTolerance_allowsPreviouslyRejectedOverReceipt() {
        ReflectionTestUtils.setField(service, "transferReceiveOverToleranceRate", new BigDecimal("0.50"));

        InternalTransfer transfer = buildShippedTransfer(54L, new BigDecimal("100"));

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        Map<Long, BigDecimal> actuals = Map.of(54L, new BigDecimal("105"));

        service.receiveTransfer("F006", "TRF-001", 1L, actuals);

        InternalTransferItem item = transfer.getItems().get(0);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("105"));
    }

    @Test
    @DisplayName("多行 items: 一行超收一行正常 → 整单 409 拒绝 (fail-fast, 事务性)")
    void receiveWithOneItemOverReceiptAmongMany_throws409ForWholeTransfer() {
        InternalTransferItem itemOk = new InternalTransferItem();
        itemOk.setId(60L);
        itemOk.setItemType(TransferItemType.RAW_MATERIAL);
        itemOk.setMaterialTypeId("MT-OK");
        itemOk.setQuantity(new BigDecimal("50"));
        itemOk.setUnit("kg");

        InternalTransferItem itemOver = new InternalTransferItem();
        itemOver.setId(61L);
        itemOver.setItemType(TransferItemType.RAW_MATERIAL);
        itemOver.setMaterialTypeId("MT-OVER");
        itemOver.setItemName("超收料");
        itemOver.setQuantity(new BigDecimal("10"));
        itemOver.setUnit("kg");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TRF-001");
        transfer.setTransferNumber("TR-001");
        transfer.setSourceFactoryId("F001");
        transfer.setTargetFactoryId("F006");
        transfer.setStatus(TransferStatus.SHIPPED);
        transfer.setTransferType(TransferType.HQ_TO_BRANCH);
        transfer.getItems().add(itemOk);
        transfer.getItems().add(itemOver);

        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));

        Map<Long, BigDecimal> actuals = Map.of(60L, new BigDecimal("50"), 61L, new BigDecimal("1000"));

        assertThatThrownBy(() -> service.receiveTransfer("F006", "TRF-001", 1L, actuals))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超收料");

        verify(transferItemRepository, never()).saveAll(any());
    }
}
