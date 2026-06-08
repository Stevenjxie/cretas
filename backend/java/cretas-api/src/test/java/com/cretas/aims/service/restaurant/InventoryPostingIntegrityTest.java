package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.restaurant.MaterialRequisitionRepository;
import com.cretas.aims.repository.restaurant.StocktakingRecordRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.restaurant.impl.RestaurantInventoryPostingServiceImpl;
import com.cretas.aims.service.restaurant.impl.StocktakingRecordServiceImpl;
import com.cretas.aims.service.uom.MaterialUomConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TDD for #547 QA bugs:
 *   GAP-04-DEDUCT — 领料/损耗/盘点审批扣减库存
 *   GAP-04-C-SYS  — 盘点创建时快照账面库存
 *   GAP-04-E-LIE  — 扣库后成本归因诚实
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("#547 库存过账完整性修复")
class InventoryPostingIntegrityTest {

    // ───────────────────────── shared mocks ─────────────────────────────────
    @Mock SupplierDeliveryNoteRepository noteRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock PurchaseService purchaseService;
    @Mock WarehouseResolver warehouseResolver;
    @Mock MaterialBatchService materialBatchService;
    @Mock MaterialRequisitionRepository materialRequisitionRepository;
    @Mock WastageRecordRepository wastageRecordRepository;
    @Mock StocktakingRecordRepository stocktakingRecordRepository;
    @Mock MaterialUomConverter materialUomConverter;

    RestaurantInventoryPostingServiceImpl postingService;

    private static final String FACTORY = "RES_3101_009";
    private static final String MAT_ID  = "RMT-PORK";
    private static final Long   USER    = 42L;

    @BeforeEach
    void setUp() {
        postingService = new RestaurantInventoryPostingServiceImpl(
                noteRepository,
                supplierRepository,
                rawMaterialTypeRepository,
                purchaseService,
                warehouseResolver,
                materialBatchService,
                materialRequisitionRepository,
                wastageRecordRepository,
                stocktakingRecordRepository,
                materialUomConverter);

        // Default: rawMaterial belongs to factory
        RawMaterialType mat = new RawMaterialType();
        mat.setId(MAT_ID);
        mat.setFactoryId(FACTORY);
        mat.setName("猪肉");
        lenient().when(rawMaterialTypeRepository.findById(MAT_ID)).thenReturn(Optional.of(mat));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-DEDUCT: 领料审批 → useBatchQuantity 被调用 → 批次扣减
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-DEDUCT — 领料审批扣减库存")
    class MaterialRequisitionDeductionTests {

        @Test
        @DisplayName("领料审批后 useBatchQuantity 被正确调用")
        void approveRequisition_callsUseBatchQuantity() {
            MaterialBatchDTO batch = batchDto("BATCH-A", new BigDecimal("5.0"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("2.0")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(FACTORY, "BATCH-A", new BigDecimal("2.0"));

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.0"), null);

            String detail = postingService.postMaterialRequisitionIssue(FACTORY, req, USER);

            // useBatchQuantity must have been called — this is the actual deduction
            verify(materialBatchService).useBatchQuantity(FACTORY, "BATCH-A", new BigDecimal("2.0"));
            assertNotNull(req.getInventoryPostedAt(),
                    "inventoryPostedAt must be set so the idempotency guard can fire on re-call");
            assertEquals(USER, req.getInventoryPostedBy());
            assertNotNull(detail);
            assertTrue(detail.contains("BATCH-A"));
        }

        @Test
        @DisplayName("领料审批幂等：二次调用不重复扣减")
        void approveRequisition_idempotent_noDoubleDeduction() {
            MaterialBatchDTO batch = batchDto("BATCH-A", new BigDecimal("5.0"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("2.0")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(anyString(), anyString(), any());

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.0"), null);

            postingService.postMaterialRequisitionIssue(FACTORY, req, USER);
            // Second call with same requisition (inventoryPostedAt already set)
            postingService.postMaterialRequisitionIssue(FACTORY, req, USER);

            // useBatchQuantity called exactly once — the idempotency guard blocks re-entry
            verify(materialBatchService, times(1))
                    .useBatchQuantity(FACTORY, "BATCH-A", new BigDecimal("2.0"));
        }

        @Test
        @DisplayName("库存不足时领料审批抛 409 INSUFFICIENT_INVENTORY")
        void approveRequisition_insufficientInventory_throws409() {
            MaterialBatchDTO batch = batchDto("BATCH-A", new BigDecimal("1.0")); // only 1 kg
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("3.0")))
                    .thenReturn(List.of(batch));

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("3.0"), null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> postingService.postMaterialRequisitionIssue(FACTORY, req, USER));
            assertEquals(409, ex.getCode());
            assertEquals("INSUFFICIENT_INVENTORY", ex.getErrorCode());
            verify(materialBatchService, never()).useBatchQuantity(any(), any(), any());
        }

        @Test
        @DisplayName("指定批次领料 — 优先使用指定批次")
        void approveRequisition_withPreferredBatch_usesSpecifiedBatch() {
            MaterialBatchDTO batch = batchDto("BATCH-PREFERRED", new BigDecimal("10.0"));
            when(materialBatchService.getMaterialBatchById(FACTORY, "BATCH-PREFERRED"))
                    .thenReturn(batch);
            doNothing().when(materialBatchService)
                    .useBatchQuantity(FACTORY, "BATCH-PREFERRED", new BigDecimal("2.5"));

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.5"), "BATCH-PREFERRED");

            postingService.postMaterialRequisitionIssue(FACTORY, req, USER);

            verify(materialBatchService).useBatchQuantity(FACTORY, "BATCH-PREFERRED", new BigDecimal("2.5"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-DEDUCT: 损耗审批
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-DEDUCT — 损耗审批扣减库存")
    class WastageDeductionTests {

        @Test
        @DisplayName("损耗审批后批次扣减被执行")
        void approveWastage_callsUseBatchQuantity() {
            MaterialBatchDTO batch = batchDto("BATCH-W", new BigDecimal("20.0"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("0.5")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(FACTORY, "BATCH-W", new BigDecimal("0.5"));

            WastageRecord record = wastage(MAT_ID, new BigDecimal("0.5"), null);

            postingService.postWastageDeduction(FACTORY, record, USER);

            verify(materialBatchService).useBatchQuantity(FACTORY, "BATCH-W", new BigDecimal("0.5"));
            assertNotNull(record.getInventoryPostedAt());
        }

        @Test
        @DisplayName("损耗幂等：二次调用不重复扣减")
        void approveWastage_idempotent_noDoubleDeduction() {
            MaterialBatchDTO batch = batchDto("BATCH-W", new BigDecimal("20.0"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("0.5")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(anyString(), anyString(), any());

            WastageRecord record = wastage(MAT_ID, new BigDecimal("0.5"), null);

            postingService.postWastageDeduction(FACTORY, record, USER);
            postingService.postWastageDeduction(FACTORY, record, USER);

            verify(materialBatchService, times(1))
                    .useBatchQuantity(FACTORY, "BATCH-W", new BigDecimal("0.5"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-DEDUCT: 盘点完成 → 盘亏扣减 / 盘盈调增
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-DEDUCT — 盘点完成调整库存")
    class StocktakingAdjustmentTests {

        @Test
        @DisplayName("盘亏 (实盘 < 账面) → useBatchQuantity 扣减差额")
        void completeStocktaking_shortage_deductsDifference() {
            MaterialBatchDTO batch = batchDto("BATCH-S", new BigDecimal("5.0")); // system=5
            when(materialBatchService.getMaterialBatchesByType(FACTORY, MAT_ID))
                    .thenReturn(List.of(batch));
            // actual=3, system=5, shortage=2
            when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MAT_ID), any()))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(eq(FACTORY), eq("BATCH-S"), any());

            StocktakingRecord record = stocktaking(MAT_ID, new BigDecimal("3.0"), null);

            postingService.postStocktakingAdjustment(FACTORY, record, USER);

            // system qty is auto-populated from batch totals
            assertEquals(new BigDecimal("5.0"), record.getSystemQuantity());
            // difference = actual(3) - system(5) = -2 → shortage
            assertNotNull(record.getInventoryPostedAt());
            verify(materialBatchService).useBatchQuantity(eq(FACTORY), eq("BATCH-S"),
                    eq(new BigDecimal("2.0"))); // shortage = 2
        }

        @Test
        @DisplayName("盘平 (实盘 = 账面) → 不调批次")
        void completeStocktaking_match_noAdjustment() {
            MaterialBatchDTO batch = batchDto("BATCH-S", new BigDecimal("5.0"));
            when(materialBatchService.getMaterialBatchesByType(FACTORY, MAT_ID))
                    .thenReturn(List.of(batch));

            StocktakingRecord record = stocktaking(MAT_ID, new BigDecimal("5.0"), null);

            postingService.postStocktakingAdjustment(FACTORY, record, USER);

            assertNotNull(record.getInventoryPostedAt());
            verify(materialBatchService, never()).useBatchQuantity(any(), any(), any());
            verify(materialBatchService, never()).adjustBatchQuantity(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("盘点幂等：二次调用不重复调整")
        void completeStocktaking_idempotent_noDoubleAdjustment() {
            MaterialBatchDTO batch = batchDto("BATCH-S", new BigDecimal("5.0"));
            when(materialBatchService.getMaterialBatchesByType(FACTORY, MAT_ID))
                    .thenReturn(List.of(batch));
            when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MAT_ID), any()))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(anyString(), anyString(), any());

            StocktakingRecord record = stocktaking(MAT_ID, new BigDecimal("3.0"), null);

            postingService.postStocktakingAdjustment(FACTORY, record, USER);
            postingService.postStocktakingAdjustment(FACTORY, record, USER); // second call

            verify(materialBatchService, times(1))
                    .useBatchQuantity(any(), any(), any());
        }

        @Test
        @DisplayName("账面库存预设 → 不覆盖已有 systemQuantity")
        void completeStocktaking_withPresetSystemQuantity_noOverride() {
            // systemQuantity already set at creation time (GAP-04-C-SYS fix)
            StocktakingRecord record = stocktaking(MAT_ID, new BigDecimal("4.0"),
                    new BigDecimal("5.0") /* systemQty set at creation */);

            // With system=5 and actual=4, difference=-1
            MaterialBatchDTO batch = batchDto("BATCH-S", new BigDecimal("5.0"));
            when(materialBatchService.getFIFOBatches(eq(FACTORY), eq(MAT_ID), any()))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(anyString(), anyString(), any());

            postingService.postStocktakingAdjustment(FACTORY, record, USER);

            // systemQuantity must remain the creation-time snapshot (5), not be re-fetched
            assertEquals(new BigDecimal("5.0"), record.getSystemQuantity());
            // getMaterialBatchesByType must NOT have been called (systemQty already present)
            verify(materialBatchService, never()).getMaterialBatchesByType(any(), any());
            verify(materialBatchService).useBatchQuantity(eq(FACTORY), eq("BATCH-S"),
                    eq(new BigDecimal("1.0"))); // shortage = 1
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-C-SYS: 盘点创建时快照 systemQuantity
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-C-SYS — 盘点创建时快照账面库存")
    class StocktakingSystemQuantitySnapshotTests {

        @Test
        @DisplayName("盘点单创建时 systemQuantity 等于各批次可用量之和")
        void createStocktaking_snapshotsSystemQuantityFromBatches() {
            MaterialBatchDTO b1 = batchDto("B1", new BigDecimal("3.0"));
            MaterialBatchDTO b2 = batchDto("B2", new BigDecimal("7.5"));
            when(materialBatchService.getMaterialBatchesByType(FACTORY, MAT_ID))
                    .thenReturn(List.of(b1, b2));

            when(stocktakingRecordRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                    FACTORY, MAT_ID, StocktakingRecord.Status.IN_PROGRESS)).thenReturn(false);
            when(stocktakingRecordRepository.countByFactoryIdAndDate(eq(FACTORY), any()))
                    .thenReturn(0L);
            when(stocktakingRecordRepository.save(any(StocktakingRecord.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            StocktakingRecordServiceImpl stocktakingService = new StocktakingRecordServiceImpl(
                    stocktakingRecordRepository, postingService, materialBatchService);

            StocktakingRecord input = new StocktakingRecord();
            input.setFactoryId(FACTORY);
            input.setRawMaterialTypeId(MAT_ID);
            input.setStocktakingDate(LocalDate.now());

            StocktakingRecord saved = stocktakingService.createRecord(FACTORY, input, USER);

            // 3.0 + 7.5 = 10.5
            assertNotNull(saved.getSystemQuantity(), "systemQuantity must be snapshotted at creation");
            assertEquals(0, saved.getSystemQuantity().compareTo(new BigDecimal("10.5")));
        }

        @Test
        @DisplayName("无库存批次时 systemQuantity = 0 (非 null)")
        void createStocktaking_noBatches_systemQuantityIsZero() {
            when(materialBatchService.getMaterialBatchesByType(FACTORY, MAT_ID))
                    .thenReturn(List.of());

            when(stocktakingRecordRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                    FACTORY, MAT_ID, StocktakingRecord.Status.IN_PROGRESS)).thenReturn(false);
            when(stocktakingRecordRepository.countByFactoryIdAndDate(eq(FACTORY), any()))
                    .thenReturn(0L);
            when(stocktakingRecordRepository.save(any(StocktakingRecord.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            StocktakingRecordServiceImpl stocktakingService = new StocktakingRecordServiceImpl(
                    stocktakingRecordRepository, postingService, materialBatchService);

            StocktakingRecord input = new StocktakingRecord();
            input.setFactoryId(FACTORY);
            input.setRawMaterialTypeId(MAT_ID);
            input.setStocktakingDate(LocalDate.now());

            StocktakingRecord saved = stocktakingService.createRecord(FACTORY, input, USER);

            assertNotNull(saved.getSystemQuantity());
            assertEquals(0, saved.getSystemQuantity().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("已有进行中盘点 → 创建失败 409")
        void createStocktaking_duplicateInProgress_throws409() {
            when(stocktakingRecordRepository.existsByFactoryIdAndRawMaterialTypeIdAndStatus(
                    FACTORY, MAT_ID, StocktakingRecord.Status.IN_PROGRESS)).thenReturn(true);

            StocktakingRecordServiceImpl stocktakingService = new StocktakingRecordServiceImpl(
                    stocktakingRecordRepository, postingService, materialBatchService);

            StocktakingRecord input = new StocktakingRecord();
            input.setFactoryId(FACTORY);
            input.setRawMaterialTypeId(MAT_ID);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> stocktakingService.createRecord(FACTORY, input, USER));
            assertEquals(409, ex.getCode());
            verify(materialBatchService, never()).getMaterialBatchesByType(any(), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-E-LIE: 成本归因诚实 (扣库后 actualCost / estimatedCost 有值)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-E-LIE — 成本归因诚实")
    class CostAttributionHonestyTests {

        @Test
        @DisplayName("领料过账后 actualCost 基于真实扣库单价计算")
        void approveRequisition_actualCostReflectsRealDeduction() {
            // 2 kg @ 15/kg → actual cost = 30
            MaterialBatchDTO batch = batchDto("BATCH-C", new BigDecimal("5.0"));
            batch.setUnitPrice(new BigDecimal("15.00"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("2.0")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(FACTORY, "BATCH-C", new BigDecimal("2.0"));

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.0"), null);

            postingService.postMaterialRequisitionIssue(FACTORY, req, USER);

            assertNotNull(req.getActualCost(), "actualCost must not be null after posting");
            assertEquals(0, req.getActualCost().compareTo(new BigDecimal("30.00")),
                    "actualCost = 2kg × ¥15 = ¥30");
        }

        @Test
        @DisplayName("损耗过账后 estimatedCost 基于真实扣库单价计算")
        void approveWastage_estimatedCostReflectsRealDeduction() {
            // 0.5 kg @ 12/kg → estimated cost = 6
            MaterialBatchDTO batch = batchDto("BATCH-W", new BigDecimal("20.0"));
            batch.setUnitPrice(new BigDecimal("12.00"));
            when(materialBatchService.getFIFOBatches(FACTORY, MAT_ID, new BigDecimal("0.5")))
                    .thenReturn(List.of(batch));
            doNothing().when(materialBatchService)
                    .useBatchQuantity(FACTORY, "BATCH-W", new BigDecimal("0.5"));

            WastageRecord record = wastage(MAT_ID, new BigDecimal("0.5"), null);

            postingService.postWastageDeduction(FACTORY, record, USER);

            assertNotNull(record.getEstimatedCost(), "estimatedCost must not be null after posting");
            assertEquals(0, record.getEstimatedCost().compareTo(new BigDecimal("6.00")),
                    "estimatedCost = 0.5kg × ¥12 = ¥6");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GAP-04-DEDUCT-MUTATION: 真实状态变更断言 (不只验调用, 验 usedQuantity 变了)
    //
    // WHY EXISTING TESTS ARE INSUFFICIENT:
    //   All tests above use `verify(materialBatchService).useBatchQuantity(...)`
    //   which only asserts the mock method was *called* on a Mockito double.
    //   If consumeMaterial were changed to skip the call, or if useBatchQuantity
    //   were changed to not mutate usedQuantity, the existing tests would still pass
    //   because the mocks answer void unconditionally.
    //
    // WHAT THESE NEW TESTS DO:
    //   They wire a StatefulMaterialBatchService — a concrete stub whose
    //   useBatchQuantity() actually mutates an in-memory balance map.
    //   After postMaterialRequisitionIssue runs, the test asserts the balance
    //   CHANGED (3.0 remaining from 5.0 after a 2.0 deduction).  If consumeMaterial
    //   is broken / the call is skipped, the balance stays 5.0 and the assertion fails.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GAP-04-DEDUCT-MUTATION — 实际状态变更 (批次余量确实减少)")
    class BatchQuantityMutationTests {

        /**
         * Stateful stub for MaterialBatchService.
         *
         * useBatchQuantity() mutates an in-memory balance map so tests can assert
         * the balance actually changed — not just that the method was routed.
         *
         * Only the methods called by RestaurantInventoryPostingServiceImpl have real
         * logic; all others return null / empty / zero (they are never invoked by the
         * posting service, so their answers are irrelevant for these tests).
         */
        private class StatefulMaterialBatchService implements MaterialBatchService {
            private final Map<String, BigDecimal> balances = new HashMap<>();

            void seed(String batchId, BigDecimal qty) {
                balances.put(batchId, qty);
            }

            BigDecimal remaining(String batchId) {
                return balances.getOrDefault(batchId, BigDecimal.ZERO);
            }

            // ── Methods actually called by RestaurantInventoryPostingServiceImpl ──

            @Override
            public void useBatchQuantity(String factoryId, String batchId, BigDecimal quantity) {
                BigDecimal current = balances.getOrDefault(batchId, BigDecimal.ZERO);
                if (current.compareTo(quantity) < 0) {
                    throw new com.cretas.aims.exception.BusinessException(
                            "批次可用数量不足: " + batchId);
                }
                balances.put(batchId, current.subtract(quantity));
            }

            @Override
            public com.cretas.aims.dto.material.MaterialBatchDTO getMaterialBatchById(
                    String factoryId, String batchId) {
                return batchDtoForFake(batchId, balances.getOrDefault(batchId, BigDecimal.ZERO));
            }

            @Override
            public List<com.cretas.aims.dto.material.MaterialBatchDTO> getFIFOBatches(
                    String factoryId, String materialTypeId, BigDecimal required) {
                List<com.cretas.aims.dto.material.MaterialBatchDTO> list = new ArrayList<>();
                BigDecimal acc = BigDecimal.ZERO;
                for (Map.Entry<String, BigDecimal> e : balances.entrySet()) {
                    if (acc.compareTo(required) >= 0) break;
                    list.add(batchDtoForFake(e.getKey(), e.getValue()));
                    acc = acc.add(e.getValue());
                }
                return list;
            }

            @Override
            public List<com.cretas.aims.dto.material.MaterialBatchDTO> getMaterialBatchesByType(
                    String factoryId, String materialTypeId) {
                return getFIFOBatches(factoryId, materialTypeId, BigDecimal.valueOf(Long.MAX_VALUE));
            }

            @Override
            public com.cretas.aims.dto.material.MaterialBatchDTO adjustBatchQuantity(
                    String factoryId, String batchId, BigDecimal newQty, String reason, Long userId) {
                balances.put(batchId, newQty);
                return batchDtoForFake(batchId, newQty);
            }

            // ── Unused interface methods (not called by the posting service) ──

            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            createMaterialBatch(String f, com.cretas.aims.dto.material.CreateMaterialBatchRequest r, Long u) { return null; }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            updateMaterialBatch(String f, String id, com.cretas.aims.dto.material.UpdateMaterialBatchRequest r) { return null; }
            @Override public void deleteMaterialBatch(String f, String id) {}
            @Override public com.cretas.aims.dto.common.PageResponse<com.cretas.aims.dto.material.MaterialBatchDTO>
            getMaterialBatchList(String f, com.cretas.aims.dto.common.PageRequest p) { return null; }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getMaterialBatchesByStatus(String f, com.cretas.aims.entity.enums.MaterialBatchStatus s) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getWipBatches(String f) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getAllWipBatches() { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getAvailableBatchesFIFO(String f, String mId) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getExpiringBatches(String f, Integer days) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getExpiredBatches(String f) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getMaterialBatchesBySupplier(String f, String sId) { return List.of(); }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            applyBatchQuantityDelta(String f, String id, BigDecimal delta, String reason) { return null; }
            @Override public void markBatchAsExpired(String f, String id) {}
            @Override public void markBatchAsUsedUp(String f, String id) {}
            @Override public void reserveBatchQuantity(String f, String id, BigDecimal q) {}
            @Override public void releaseBatchQuantity(String f, String id, BigDecimal q) {}
            @Override public BigDecimal calculateInventoryValue(String f) { return BigDecimal.ZERO; }
            @Override public Map<String, BigDecimal> getInventoryByMaterialType(String f) { return Map.of(); }
            @Override public List<Map<String, Object>> getLowStockWarnings(String f) { return List.of(); }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            batchCreateMaterialBatches(String f, List<com.cretas.aims.dto.material.CreateMaterialBatchRequest> r, Long u) { return List.of(); }
            @Override public List<Map<String, Object>> getBatchUsageHistory(String f, String id) { return List.of(); }
            @Override public boolean checkBatchNumberExists(String n) { return false; }
            @Override public void autoCheckAndUpdateExpiredBatches() {}
            @Override public byte[] exportInventoryReport(String f, java.time.LocalDate s, java.time.LocalDate e, boolean mask) { return new byte[0]; }
            @Override public List<com.cretas.aims.dto.material.MaterialBatchDTO>
            getFEFOBatches(String f, String mId, BigDecimal q) { return List.of(); }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            useBatchMaterial(String f, String id, BigDecimal q, String planId) { return null; }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            updateBatchStatus(String f, String id, com.cretas.aims.entity.enums.MaterialBatchStatus s) { return null; }
            @Override public void reserveBatchMaterial(String f, String id, BigDecimal q, String planId) {}
            @Override public void releaseBatchReservation(String f, String id, BigDecimal q, String planId) {}
            @Override public void consumeBatchMaterial(String f, String id, BigDecimal q, String planId) {}
            @Override public Map<String, Object> getInventoryStatistics(String f) { return Map.of(); }
            @Override public BigDecimal getInventoryValuation(String f) { return BigDecimal.ZERO; }
            @Override public int handleExpiredBatches(String f) { return 0; }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            convertToFrozen(String f, String id, com.cretas.aims.dto.material.ConvertToFrozenRequest r) { return null; }
            @Override public com.cretas.aims.dto.material.MaterialBatchDTO
            undoFrozen(String f, String id, com.cretas.aims.dto.material.UndoFrozenRequest r) { return null; }
            @Override public void recalculateMovingAvgPrice(String mId, BigDecimal qty, BigDecimal price, String bId) {}

            private com.cretas.aims.dto.material.MaterialBatchDTO batchDtoForFake(
                    String id, BigDecimal qty) {
                com.cretas.aims.dto.material.MaterialBatchDTO dto =
                        new com.cretas.aims.dto.material.MaterialBatchDTO();
                dto.setId(id);
                dto.setMaterialTypeId(MAT_ID);
                dto.setCurrentQuantity(qty);
                dto.setFactoryId(FACTORY);
                dto.setUnitPrice(BigDecimal.ZERO);
                return dto;
            }
        }

        private RestaurantInventoryPostingServiceImpl postingServiceWithStatefulBatch(
                StatefulMaterialBatchService batchSvc) {
            return new RestaurantInventoryPostingServiceImpl(
                    noteRepository,
                    supplierRepository,
                    rawMaterialTypeRepository,
                    purchaseService,
                    warehouseResolver,
                    batchSvc,
                    materialRequisitionRepository,
                    wastageRecordRepository,
                    stocktakingRecordRepository,
                    materialUomConverter);
        }

        @Test
        @DisplayName("领料过账后批次余量实际减少 (不只验 useBatchQuantity 被调用)")
        void approveRequisition_batchBalanceActuallyDecreases() {
            // GIVEN — batch starts at 5.0 kg
            StatefulMaterialBatchService batchSvc = new StatefulMaterialBatchService();
            batchSvc.seed("BATCH-A", new BigDecimal("5.0"));
            RestaurantInventoryPostingServiceImpl svc = postingServiceWithStatefulBatch(batchSvc);

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.0"), null);

            // WHEN
            svc.postMaterialRequisitionIssue(FACTORY, req, USER);

            // THEN — balance is 3.0 (not still 5.0 as it would be with a do-nothing mock)
            assertEquals(0, new BigDecimal("3.0").compareTo(batchSvc.remaining("BATCH-A")),
                    "usedQuantity must have been applied: 5.0 - 2.0 = 3.0 remaining");
            assertNotNull(req.getInventoryPostedAt(),
                    "inventoryPostedAt must be set in the same operation");
        }

        @Test
        @DisplayName("损耗过账后批次余量实际减少")
        void approveWastage_batchBalanceActuallyDecreases() {
            StatefulMaterialBatchService batchSvc = new StatefulMaterialBatchService();
            batchSvc.seed("BATCH-W", new BigDecimal("10.0"));
            RestaurantInventoryPostingServiceImpl svc = postingServiceWithStatefulBatch(batchSvc);

            WastageRecord record = wastage(MAT_ID, new BigDecimal("0.5"), null);

            svc.postWastageDeduction(FACTORY, record, USER);

            assertEquals(0, new BigDecimal("9.5").compareTo(batchSvc.remaining("BATCH-W")),
                    "balance must drop: 10.0 - 0.5 = 9.5");
            assertNotNull(record.getInventoryPostedAt());
        }

        @Test
        @DisplayName("盘亏过账后批次余量实际减少")
        void completeStocktaking_shortage_batchBalanceActuallyDecreases() {
            StatefulMaterialBatchService batchSvc = new StatefulMaterialBatchService();
            batchSvc.seed("BATCH-S", new BigDecimal("5.0")); // system quantity
            RestaurantInventoryPostingServiceImpl svc = postingServiceWithStatefulBatch(batchSvc);

            // actual=3, system (from summing batches)=5, shortage=2
            StocktakingRecord record = stocktaking(MAT_ID, new BigDecimal("3.0"), null);

            svc.postStocktakingAdjustment(FACTORY, record, USER);

            assertEquals(0, new BigDecimal("3.0").compareTo(batchSvc.remaining("BATCH-S")),
                    "balance must drop: 5.0 - 2.0 shortage = 3.0");
            assertNotNull(record.getInventoryPostedAt());
        }

        @Test
        @DisplayName("库存不足时批次余量不变 (抛出 409, 余量保持 1.0)")
        void approveRequisition_insufficientInventory_balanceUnchanged() {
            StatefulMaterialBatchService batchSvc = new StatefulMaterialBatchService();
            batchSvc.seed("BATCH-A", new BigDecimal("1.0")); // only 1 kg available
            RestaurantInventoryPostingServiceImpl svc = postingServiceWithStatefulBatch(batchSvc);

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("3.0"), null); // need 3 kg

            assertThrows(com.cretas.aims.exception.BusinessException.class,
                    () -> svc.postMaterialRequisitionIssue(FACTORY, req, USER));

            // balance must remain unchanged — no partial deduction allowed
            assertEquals(0, new BigDecimal("1.0").compareTo(batchSvc.remaining("BATCH-A")),
                    "balance must stay 1.0 when posting fails with INSUFFICIENT_INVENTORY");
        }

        @Test
        @DisplayName("幂等保护：二次调用不重复扣减余量")
        void approveRequisition_idempotent_balanceDeductedOnlyOnce() {
            StatefulMaterialBatchService batchSvc = new StatefulMaterialBatchService();
            batchSvc.seed("BATCH-A", new BigDecimal("5.0"));
            RestaurantInventoryPostingServiceImpl svc = postingServiceWithStatefulBatch(batchSvc);

            MaterialRequisition req = requisition(MAT_ID, new BigDecimal("2.0"), null);

            svc.postMaterialRequisitionIssue(FACTORY, req, USER);  // first call → deducts 2.0
            svc.postMaterialRequisitionIssue(FACTORY, req, USER);  // second call → idempotency guard fires

            // balance must still be 3.0 (not 1.0 from double-deduction)
            assertEquals(0, new BigDecimal("3.0").compareTo(batchSvc.remaining("BATCH-A")),
                    "idempotency guard must block re-deduction: balance = 3.0 not 1.0");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private MaterialBatchDTO batchDto(String id, BigDecimal currentQty) {
        MaterialBatchDTO dto = new MaterialBatchDTO();
        dto.setId(id);
        dto.setMaterialTypeId(MAT_ID);
        dto.setCurrentQuantity(currentQty);
        dto.setFactoryId(FACTORY);
        dto.setUnitPrice(BigDecimal.ZERO);
        return dto;
    }

    private MaterialRequisition requisition(String materialTypeId, BigDecimal qty, String batchId) {
        MaterialRequisition req = new MaterialRequisition();
        req.setId("REQ-" + materialTypeId);
        req.setFactoryId(FACTORY);
        req.setRawMaterialTypeId(materialTypeId);
        req.setActualQuantity(qty);
        req.setMaterialBatchId(batchId);
        req.setRequisitionNumber("REQ-2026060901");
        return req;
    }

    private WastageRecord wastage(String materialTypeId, BigDecimal qty, String batchId) {
        WastageRecord record = new WastageRecord();
        record.setId("WAS-" + materialTypeId);
        record.setFactoryId(FACTORY);
        record.setRawMaterialTypeId(materialTypeId);
        record.setQuantity(qty);
        record.setMaterialBatchId(batchId);
        record.setWastageNumber("WAS-2026060901");
        return record;
    }

    private StocktakingRecord stocktaking(String materialTypeId, BigDecimal actual,
                                           BigDecimal systemQty) {
        StocktakingRecord record = new StocktakingRecord();
        record.setId("STK-" + materialTypeId);
        record.setFactoryId(FACTORY);
        record.setRawMaterialTypeId(materialTypeId);
        record.setActualQuantity(actual);
        record.setSystemQuantity(systemQty);
        record.setStocktakingNumber("STK-20260609001");
        return record;
    }
}
