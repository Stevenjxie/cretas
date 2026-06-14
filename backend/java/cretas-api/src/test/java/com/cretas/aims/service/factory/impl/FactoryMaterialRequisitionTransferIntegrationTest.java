package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryMaterialRequisition transfer and material returns")
class FactoryMaterialRequisitionTransferIntegrationTest {

    private static final String FACTORY_ID = "F001";
    private static final String MR_ID = "mr-001";
    private static final String PLAN_ID = "plan-001";
    private static final String WH_LOGISTICS = "wh-logistics";
    private static final String WH_WORKSHOP = "wh-workshop";
    private static final Long OPERATOR = 99L;

    @Mock
    private FactoryMaterialRequisitionRepository repository;
    @Mock
    private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private BomItemRepository bomItemRepository;
    @Mock
    private TransferService transferService;
    @Mock
    private FactoryWarehouseRepository warehouseRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private ProductionMaterialReturnRepository productionMaterialReturnRepository;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    @BeforeEach
    void setup() {
        InternalTransfer stubTransfer = new InternalTransfer();
        stubTransfer.setId("tr-stub-1");
        lenient().when(transferService.createTransfer(any(), any(), any())).thenReturn(stubTransfer);
        lenient().when(materialBatchRepository.findByIdAndFactoryIdForUpdate(eq("batch-1"), eq(FACTORY_ID)))
                .thenReturn(Optional.of(batch("batch-1", "MAT-001", "10.00", "8.00")));
        lenient().when(materialBatchRepository.findByIdAndFactoryIdForUpdate(eq("batch-2"), eq(FACTORY_ID)))
                .thenReturn(Optional.of(batch("batch-2", "MAT-002", "0.50", "0.50")));
        lenient().when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(materialConsumptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionMaterialReturnRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("transferToFactory creates outbound transfer from logistics to workshop")
    void transferToFactory_shouldCreateOutboundTransfer() {
        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), captor.capture(), eq(OPERATOR));
        CreateTransferRequest req = captor.getValue();

        assertEquals(FACTORY_ID, req.getTargetFactoryId());
        assertEquals(WH_LOGISTICS, req.getSourceWarehouseId());
        assertEquals(WH_WORKSHOP, req.getTargetWarehouseId());
        assertEquals(2, req.getItems().size());
        assertEquals("tr-stub-1", mr.getOutboundTransferId());
    }

    @Test
    @DisplayName("close creates reverse transfer, restores used quantity, and writes trace records")
    void close_withReturnedQty_shouldCreateReverseTransferAndRestoreInventory() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        ArgumentCaptor<CreateTransferRequest> transferCaptor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), transferCaptor.capture(), eq(OPERATOR));
        CreateTransferRequest req = transferCaptor.getValue();

        assertEquals(WH_WORKSHOP, req.getSourceWarehouseId());
        assertEquals(WH_LOGISTICS, req.getTargetWarehouseId());
        assertEquals(1, req.getItems().size());
        assertEquals("tr-stub-1", mr.getReturnTransferId());
        assertEquals(new BigDecimal("2.00"), mr.getItems().get(0).getReturnedQty());

        ArgumentCaptor<MaterialBatch> batchCaptor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(batchCaptor.capture());
        assertEquals(new BigDecimal("6.00"), batchCaptor.getValue().getUsedQuantity());

        ArgumentCaptor<MaterialConsumption> consumptionCaptor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(consumptionCaptor.capture());
        MaterialConsumption trace = consumptionCaptor.getValue();
        assertEquals(new BigDecimal("-2.00"), trace.getQuantity());
        assertEquals("MATERIAL_RETURN", trace.getSourceType());
        assertEquals(PLAN_ID, trace.getProductionPlanId());
        assertEquals("batch-1", trace.getBatchId());

        ArgumentCaptor<ProductionMaterialReturn> returnCaptor = ArgumentCaptor.forClass(ProductionMaterialReturn.class);
        verify(productionMaterialReturnRepository).save(returnCaptor.capture());
        ProductionMaterialReturn materialReturn = returnCaptor.getValue();
        assertEquals(ProductionMaterialReturn.ReturnStatus.EXECUTED, materialReturn.getReturnStatus());
        assertEquals(new BigDecimal("2.00"), materialReturn.getReturnQuantity());
        assertEquals("batch-1", materialReturn.getMaterialBatchId());
    }

    @Test
    @DisplayName("close subtracts manual wastage before returning material")
    void close_shouldSubtractManualWastageBeforeReturningMaterial() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "0.75")));

        assertEquals(new BigDecimal("0.75"), mr.getItems().get(0).getWastageQty());
        assertEquals(new BigDecimal("1.25"), mr.getItems().get(0).getReturnedQty());
        verify(materialBatchRepository).save(any(MaterialBatch.class));
        verify(materialConsumptionRepository).save(any(MaterialConsumption.class));
        verify(productionMaterialReturnRepository).save(any(ProductionMaterialReturn.class));
    }

    @Test
    @DisplayName("close rejects negative returns when consumed plus wastage exceeds issued")
    void close_shouldRejectWhenConsumedAndWastageExceedIssued() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("9.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));

        assertThrows(BusinessException.class, () -> service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "1.00"))));

        verify(repository, never()).save(any());
        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("close is fail-closed when material return persistence fails")
    void close_shouldPropagateMaterialReturnPersistenceFailure() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));
        doThrow(new RuntimeException("return ledger write failed"))
                .when(productionMaterialReturnRepository).save(any(ProductionMaterialReturn.class));

        assertThrows(RuntimeException.class, () -> service.close(FACTORY_ID, MR_ID, OPERATOR, List.of()));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("close without returned material does not create return transfer")
    void close_withoutReturnedQty_shouldNotCreateTransfer() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("10.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID))
                .thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
        assertNull(mr.getReturnTransferId());
    }

    private FactoryMaterialRequisition buildMrInPicking() {
        FactoryMaterialRequisition mr = new FactoryMaterialRequisition();
        mr.setId(MR_ID);
        mr.setFactoryId(FACTORY_ID);
        mr.setProductionPlanId(PLAN_ID);
        mr.setRequisitionNo("MR-20260411-0001");
        mr.setStatus(Status.PICKING);
        mr.setSourceWarehouseId(WH_LOGISTICS);
        mr.setTargetWarehouseId(WH_WORKSHOP);

        FactoryMaterialRequisitionItem it1 = new FactoryMaterialRequisitionItem();
        it1.setId("it-1");
        it1.setRequisition(mr);
        it1.setMaterialTypeId("MAT-001");
        it1.setMaterialName("Material A");
        it1.setPickedQty(new BigDecimal("10.00"));
        it1.setUnit("kg");
        it1.setBatchNumbers(List.of(Map.of("batchId", "batch-1", "qty", "10.00")));

        FactoryMaterialRequisitionItem it2 = new FactoryMaterialRequisitionItem();
        it2.setId("it-2");
        it2.setRequisition(mr);
        it2.setMaterialTypeId("MAT-002");
        it2.setMaterialName("Material B");
        it2.setPickedQty(new BigDecimal("0.50"));
        it2.setUnit("kg");
        it2.setBatchNumbers(List.of(Map.of("batchId", "batch-2", "qty", "0.50")));

        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(it1);
        items.add(it2);
        mr.setItems(items);
        return mr;
    }

    private FactoryMaterialRequisition buildMrInIssued(BigDecimal it1Issued, BigDecimal it1Consumed,
                                                       BigDecimal it2Issued, BigDecimal it2Consumed) {
        FactoryMaterialRequisition mr = buildMrInPicking();
        mr.setStatus(Status.ISSUED);
        mr.getItems().get(0).setIssuedQty(it1Issued);
        mr.getItems().get(0).setConsumedQty(it1Consumed);
        mr.getItems().get(1).setIssuedQty(it2Issued);
        mr.getItems().get(1).setConsumedQty(it2Consumed);
        return mr;
    }

    private MaterialBatch batch(String id, String materialTypeId, String receiptQty, String usedQty) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setMaterialTypeId(materialTypeId);
        batch.setBatchNumber(id + "-no");
        batch.setWarehouseId(WH_WORKSHOP);
        batch.setReceiptDate(LocalDate.now());
        batch.setReceiptQuantity(new BigDecimal(receiptQty));
        batch.setUsedQuantity(new BigDecimal(usedQty));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setUnitPrice(new BigDecimal("3.50"));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(OPERATOR);
        return batch;
    }
}
