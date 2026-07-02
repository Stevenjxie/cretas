package com.cretas.aims.service.factory.impl;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferType;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryMaterialRequisition transfer (physical WH-LOG→WH-WKS move) and returns")
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

    /** Simulated persistent batch store so we can assert both-warehouse quantities after a move. */
    private final Map<String, MaterialBatch> batchStore = new HashMap<>();

    @BeforeEach
    void setup() {
        batchStore.clear();
        InternalTransfer stubTransfer = new InternalTransfer();
        stubTransfer.setId("tr-stub-1");
        lenient().when(transferService.createTransfer(any(), any(), any())).thenReturn(stubTransfer);
        lenient().when(materialBatchRepository.findByIdAndFactoryIdForUpdate(any(), eq(FACTORY_ID)))
                .thenAnswer(inv -> Optional.ofNullable(batchStore.get(inv.getArgument(0, String.class))));
        lenient().when(materialBatchRepository.save(any())).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            batchStore.put(b.getId(), b);
            return b;
        });
        lenient().when(materialConsumptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productionMaterialReturnRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== transferToFactory: physical move ====================

    @Test
    @DisplayName("transferToFactory relocates picked stock: WH-LOG down, WH-WKS batch created, valid enum")
    void transferToFactory_movesStockToWorkshop() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "0.00", "3.50"));
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));

        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        // status + issued
        assertEquals(Status.TRANSFERRED, mr.getStatus());
        assertEquals(new BigDecimal("10.00"), mr.getItems().get(0).getIssuedQty());

        // WH-LOG source deducted by moved qty
        assertEquals(new BigDecimal("10.00"), batchStore.get("batch-1").getUsedQuantity());
        assertEquals(new BigDecimal("0.00"), batchStore.get("batch-1").getCurrentQuantity());
        assertEquals(MaterialBatchStatus.DEPLETED, batchStore.get("batch-1").getStatus());
        assertEquals(new BigDecimal("0.50"), batchStore.get("batch-2").getUsedQuantity());

        // A new WH-WKS batch exists for each material, price/unit preserved
        MaterialBatch wks1 = findWorkshopBatch("MAT-001");
        assertNotNull(wks1, "workshop batch for MAT-001 must be created");
        assertEquals(WH_WORKSHOP, wks1.getWarehouseId());
        assertEquals(new BigDecimal("10.00"), wks1.getReceiptQuantity());
        assertEquals(new BigDecimal("10.00"), wks1.getCurrentQuantity());
        assertEquals(new BigDecimal("3.50"), wks1.getUnitPrice());
        assertEquals("kg", wks1.getQuantityUnit());
        MaterialBatch wks2 = findWorkshopBatch("MAT-002");
        assertNotNull(wks2);
        assertEquals(new BigDecimal("0.50"), wks2.getReceiptQuantity());

        // workshopBatchId recorded on the item batch rows (for reversal)
        Object recorded = mr.getItems().get(0).getBatchNumbers().get(0).get("workshopBatchId");
        assertNotNull(recorded);
        assertEquals(wks1.getId(), recorded);

        // audit transfer uses a VALID enum (previously "FACTORY_TO_FACTORY" → 400)
        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), captor.capture(), eq(OPERATOR));
        CreateTransferRequest req = captor.getValue();
        assertEquals(TransferType.WAREHOUSE_TO_WAREHOUSE.name(), req.getTransferType());
        assertEquals(WH_LOGISTICS, req.getSourceWarehouseId());
        assertEquals(WH_WORKSHOP, req.getTargetWarehouseId());
        assertEquals("tr-stub-1", mr.getOutboundTransferId());
        // valueOf must not throw for the persisted type
        assertEquals(TransferType.WAREHOUSE_TO_WAREHOUSE, TransferType.valueOf(req.getTransferType()));
    }

    @Test
    @DisplayName("transferToFactory is idempotent: re-move skips rows already carrying workshopBatchId")
    void transferToFactory_idempotentOnAlreadyMovedRows() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));
        MaterialBatch existingWks = batch("wks-existing", "MAT-001", WH_WORKSHOP, "10.00", "0.00", "3.50");
        batchStore.put("wks-existing", existingWks);

        FactoryMaterialRequisition mr = buildMrInPicking();
        // it-1 already moved (row carries workshopBatchId)
        Map<String, Object> movedRow = new LinkedHashMap<>();
        movedRow.put("batchId", "batch-1");
        movedRow.put("qty", "10.00");
        movedRow.put("workshopBatchId", "wks-existing");
        mr.getItems().get(0).setBatchNumbers(new ArrayList<>(List.of(movedRow)));
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR);

        // batch-1 must NOT be deducted again (still fully used = 10, no double move)
        assertEquals(new BigDecimal("10.00"), batchStore.get("batch-1").getUsedQuantity());
        // it-2 still moved normally
        assertEquals(new BigDecimal("0.50"), batchStore.get("batch-2").getUsedQuantity());
        assertEquals("wks-existing", mr.getItems().get(0).getBatchNumbers().get(0).get("workshopBatchId"));
    }

    @Test
    @DisplayName("transferToFactory blocks when source stock is insufficient (honest, no partial move)")
    void transferToFactory_insufficientStock_blocks() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "9.00", "3.50")); // avail 1 < 10
        batchStore.put("batch-2", batch("batch-2", "MAT-002", WH_LOGISTICS, "5.00", "0.00", "2.00"));
        FactoryMaterialRequisition mr = buildMrInPicking();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transferToFactory(FACTORY_ID, MR_ID, OPERATOR));
        assertTrue(ex.getMessage().contains("不足"));
        verify(repository, never()).save(any());
    }

    // ==================== close: return + workshop drawdown ====================

    @Test
    @DisplayName("close restores WH-LOG and draws down WH-WKS batch, keeping inventory balanced")
    void close_withReturnedQty_balancesBothWarehouses() {
        // Post-transfer state: LOG source fully issued out (used 10), WKS batch received 10, prod consumed 8
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        assertEquals(new BigDecimal("2.00"), mr.getItems().get(0).getReturnedQty());

        // WH-LOG restored by returned (10 - 2 = 8)
        assertEquals(new BigDecimal("8.00"), batchStore.get("batch-1").getUsedQuantity());
        // WH-WKS drawn down by returned (used 8 → 10, current 0) — no phantom stock left
        assertEquals(new BigDecimal("10.00"), batchStore.get("wks-1").getUsedQuantity());
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());

        // return transfer uses valid enum
        ArgumentCaptor<CreateTransferRequest> transferCaptor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), transferCaptor.capture(), eq(OPERATOR));
        assertEquals(TransferType.WAREHOUSE_TO_WAREHOUSE.name(), transferCaptor.getValue().getTransferType());
        assertEquals(WH_WORKSHOP, transferCaptor.getValue().getSourceWarehouseId());
        assertEquals(WH_LOGISTICS, transferCaptor.getValue().getTargetWarehouseId());

        // trace + return records preserved
        ArgumentCaptor<MaterialConsumption> consumptionCaptor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(materialConsumptionRepository).save(consumptionCaptor.capture());
        assertEquals(new BigDecimal("-2.00"), consumptionCaptor.getValue().getQuantity());
        assertEquals("MATERIAL_RETURN", consumptionCaptor.getValue().getSourceType());
        assertEquals("batch-1", consumptionCaptor.getValue().getBatchId());

        ArgumentCaptor<ProductionMaterialReturn> returnCaptor = ArgumentCaptor.forClass(ProductionMaterialReturn.class);
        verify(productionMaterialReturnRepository).save(returnCaptor.capture());
        assertEquals(ProductionMaterialReturn.ReturnStatus.EXECUTED, returnCaptor.getValue().getReturnStatus());
        assertEquals(new BigDecimal("2.00"), returnCaptor.getValue().getReturnQuantity());
    }

    @Test
    @DisplayName("close draws down WH-WKS by returned + wastage so the workshop batch zeroes out")
    void close_wastage_alsoDrawsDownWorkshop() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        // WKS received 10, prod consumed 8 → 2 remaining = returned(1.25) + wastage(0.75)
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));

        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "0.75")));

        assertEquals(new BigDecimal("0.75"), mr.getItems().get(0).getWastageQty());
        assertEquals(new BigDecimal("1.25"), mr.getItems().get(0).getReturnedQty());
        // WKS drawn by returned(1.25) + wastage(0.75) = 2.0 → used 8 → 10, current 0
        assertEquals(new BigDecimal("0.00"), batchStore.get("wks-1").getCurrentQuantity());
        // LOG restored by returned only (10 - 1.25 = 8.75)
        assertEquals(new BigDecimal("8.75"), batchStore.get("batch-1").getUsedQuantity());
    }

    @Test
    @DisplayName("close rejects negative returns when consumed plus wastage exceeds issued")
    void close_shouldRejectWhenConsumedAndWastageExceedIssued() {
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("9.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));

        assertThrows(BusinessException.class, () -> service.close(FACTORY_ID, MR_ID, OPERATOR,
                List.of(Map.of("itemId", "it-1", "wastageQty", "1.00"))));

        verify(repository, never()).save(any());
        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("close is fail-closed when material return persistence fails")
    void close_shouldPropagateMaterialReturnPersistenceFailure() {
        batchStore.put("batch-1", batch("batch-1", "MAT-001", WH_LOGISTICS, "10.00", "10.00", "3.50"));
        batchStore.put("wks-1", batch("wks-1", "MAT-001", WH_WORKSHOP, "10.00", "8.00", "3.50"));
        FactoryMaterialRequisition mr = buildMrInIssued(
                new BigDecimal("10.00"), new BigDecimal("8.00"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", null);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
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
                new BigDecimal("0.50"), new BigDecimal("0.50"), "wks-1", "wks-2");
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(MR_ID, FACTORY_ID)).thenReturn(Optional.of(mr));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(FACTORY_ID, MR_ID, OPERATOR, List.of());

        verify(transferService, never()).createTransfer(any(), any(), any());
        verify(materialBatchRepository, never()).save(any());
        verify(materialConsumptionRepository, never()).save(any());
        verify(productionMaterialReturnRepository, never()).save(any());
        assertNull(mr.getReturnTransferId());
    }

    // ==================== builders ====================

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
        it1.setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-1", "10.00", null))));

        FactoryMaterialRequisitionItem it2 = new FactoryMaterialRequisitionItem();
        it2.setId("it-2");
        it2.setRequisition(mr);
        it2.setMaterialTypeId("MAT-002");
        it2.setMaterialName("Material B");
        it2.setPickedQty(new BigDecimal("0.50"));
        it2.setUnit("kg");
        it2.setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-2", "0.50", null))));

        List<FactoryMaterialRequisitionItem> items = new ArrayList<>();
        items.add(it1);
        items.add(it2);
        mr.setItems(items);
        return mr;
    }

    private FactoryMaterialRequisition buildMrInIssued(BigDecimal it1Issued, BigDecimal it1Consumed,
                                                       BigDecimal it2Issued, BigDecimal it2Consumed,
                                                       String wks1Id, String wks2Id) {
        FactoryMaterialRequisition mr = buildMrInPicking();
        mr.setStatus(Status.ISSUED);
        mr.getItems().get(0).setIssuedQty(it1Issued);
        mr.getItems().get(0).setConsumedQty(it1Consumed);
        mr.getItems().get(0).setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-1", "10.00", wks1Id))));
        mr.getItems().get(1).setIssuedQty(it2Issued);
        mr.getItems().get(1).setConsumedQty(it2Consumed);
        mr.getItems().get(1).setBatchNumbers(new ArrayList<>(List.of(mutableRow("batch-2", "0.50", wks2Id))));
        return mr;
    }

    private Map<String, Object> mutableRow(String batchId, String qty, String workshopBatchId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batchId);
        row.put("qty", qty);
        if (workshopBatchId != null) {
            row.put("workshopBatchId", workshopBatchId);
        }
        return row;
    }

    private MaterialBatch findWorkshopBatch(String materialTypeId) {
        return batchStore.values().stream()
                .filter(b -> WH_WORKSHOP.equals(b.getWarehouseId()))
                .filter(b -> materialTypeId.equals(b.getMaterialTypeId()))
                .filter(b -> !b.getId().startsWith("wks-existing"))
                .findFirst()
                .orElse(null);
    }

    private MaterialBatch batch(String id, String materialTypeId, String warehouseId,
                                String receiptQty, String usedQty, String unitPrice) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY_ID);
        batch.setMaterialTypeId(materialTypeId);
        batch.setBatchNumber(id + "-no");
        batch.setWarehouseId(warehouseId);
        batch.setReceiptDate(LocalDate.now());
        batch.setReceiptQuantity(new BigDecimal(receiptQty));
        batch.setUsedQuantity(new BigDecimal(usedQty));
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setUnitPrice(new BigDecimal(unitPrice));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setCreatedBy(OPERATOR);
        return batch;
    }
}
