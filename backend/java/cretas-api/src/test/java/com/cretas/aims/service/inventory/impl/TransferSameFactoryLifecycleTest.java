package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferSameFactoryLifecycleTest {

    private static final String FACTORY = "F006";
    private static final String TRANSFER_ID = "trf-same-factory";

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
                transferRepository, transferItemRepository, materialBatchRepository,
                finishedGoodsBatchRepository, applicationEventPublisher, materialBatchService,
                rawMaterialTypeRepository);
    }

    @Test
    void approvedSameFactoryTransfer_confirmsDirectlyWithoutShipmentAndReceiptSteps() {
        InternalTransfer transfer = transfer(TransferStatus.APPROVED);
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);

        InternalTransfer result = service.confirmTransfer(FACTORY, TRANSFER_ID, 1309L);

        assertEquals(TransferStatus.CONFIRMED, result.getStatus());
        assertNotNull(result.getShippedAt());
        assertNotNull(result.getReceivedAt());
        assertNotNull(result.getConfirmedAt());
    }

    @Test
    void sameFactoryTransfer_rejectsLegacyShipmentAndReceiptEndpoints() {
        InternalTransfer transfer = transfer(TransferStatus.APPROVED);
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY))
                .thenReturn(Optional.of(transfer));

        BusinessException ship = assertThrows(BusinessException.class,
                () -> service.shipTransfer(FACTORY, TRANSFER_ID, 1309L));
        assertEquals("TRANSFER_INTRA_FACTORY_CONFIRM_REQUIRED", ship.getErrorCode());

        BusinessException receive = assertThrows(BusinessException.class,
                () -> service.receiveTransfer(FACTORY, TRANSFER_ID, 1309L));
        assertEquals("TRANSFER_INTRA_FACTORY_CONFIRM_REQUIRED", receive.getErrorCode());
    }

    private InternalTransfer transfer(TransferStatus status) {
        InternalTransfer transfer = new InternalTransfer();
        transfer.setId(TRANSFER_ID);
        transfer.setTransferNumber("TRF-TEST-001");
        transfer.setSourceFactoryId(FACTORY);
        transfer.setTargetFactoryId(FACTORY);
        transfer.setSourceWarehouseId("WH-RAW");
        transfer.setTargetWarehouseId("WH-WKS");
        transfer.setTransferType(TransferType.WAREHOUSE_TO_WAREHOUSE);
        transfer.setStatus(status);
        transfer.setTotalAmount(BigDecimal.ZERO);
        transfer.setItems(new ArrayList<>());
        return transfer;
    }
}
