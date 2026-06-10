package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
}
