package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 🔒🔒 QC 入库门 (食品安全): 收货行质检结果决定物料批次入库状态。
 *
 * <p>非 PASS (DAMAGED/PARTIAL_LOST/OTHER) → DEFECTIVE (隔离, FEFO/领料/销售自动排除);
 * PASS / null → AVAILABLE (正常流不受影响)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseServiceImpl confirmReceive QC-fail auto-quarantine")
class PurchaseServiceImplConfirmReceiveQcQuarantineTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private AttachmentRepository attachmentRepository;

    private PurchaseServiceImpl service;

    private static final String FACTORY = "RES_3101_009";
    private static final String RECEIVE_ID = "RCV-QC-001";
    private static final String MATERIAL_ID = "RMT-QC-PORK";
    private static final Long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                materialTypeRepository,
                materialBatchRepository,
                bomItemRepository,
                arApService,
                applicationEventPublisher,
                materialBatchService);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        // PR #1577 起 confirmReceive 前置「收货凭证门」: attachmentRepository 为 null 直接 503
        // (fail-closed, 无凭证服务不写库存), 有服务但零附件则 409。本用例验证的是 QC/差异逻辑,
        // 所以把门开着 — 门本身另有 PurchaseServiceImplConfirmReceiveOverReceiveTest 专门覆盖。
        ReflectionTestUtils.setField(service, "attachmentRepository", attachmentRepository);
        lenient().when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                FACTORY, com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT, RECEIVE_ID))
                .thenReturn(1L);
    }

    private MaterialBatch confirmWithQcResult(String qcResult) {
        PurchaseReceiveRecord record = draftRecord(qcResult);
        // PR #1577 起 confirmReceive 改用 findByIdAndFactoryIdForUpdate(receiveId, factoryId):
        // 租户过滤下沉进 SQL 并同时取 PESSIMISTIC_WRITE 锁 (堵并发重复确认), 取代旧的
        // findById + 手工 factoryId 比对。租户隔离只增不减, 这里只是 mock 没跟上换掉的 finder。
        when(receiveRecordRepository.findByIdAndFactoryIdForUpdate(RECEIVE_ID, FACTORY))
                .thenReturn(Optional.of(record));
        when(materialTypeRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch batch = inv.getArgument(0);
            batch.setId("BATCH-QC");
            return batch;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReceive(FACTORY, RECEIVE_ID, USER_ID);

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("qcResult=DAMAGED → batch DEFECTIVE (quarantined, excluded from FEFO/领料/销售)")
    void damaged_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("DAMAGED").getStatus());
    }

    @Test
    @DisplayName("qcResult=PARTIAL_LOST → batch DEFECTIVE (whole line, over-safe; no qty-split modeled)")
    void partialLost_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("PARTIAL_LOST").getStatus());
    }

    @Test
    @DisplayName("qcResult=OTHER → batch DEFECTIVE (ambiguous → over-safe quarantine)")
    void other_quarantined() {
        assertEquals(MaterialBatchStatus.DEFECTIVE, confirmWithQcResult("OTHER").getStatus());
    }

    @Test
    @DisplayName("qcResult=PASS → batch AVAILABLE (normal flow unaffected)")
    void pass_available() {
        assertEquals(MaterialBatchStatus.AVAILABLE, confirmWithQcResult("PASS").getStatus());
    }

    @Test
    @DisplayName("qcResult=null (QC not recorded) → batch AVAILABLE (safe default, not quarantined)")
    void nullQc_available() {
        assertEquals(MaterialBatchStatus.AVAILABLE, confirmWithQcResult(null).getStatus());
    }

    /**
     * 🔴 2026-08-16: 采购入库建出来的批次必须回写来源单据。
     *
     * <p>prod 实测（端到端走查 PO-20260816-0001 → RCV-20260816-4564 → MT-20260816-7814）：
     * {@code source_doc_type} / {@code source_doc_id} <b>全空</b> —— 在一个溯源系统里，
     * 从批次追不回采购单/供应商。
     *
     * <p>成因：本路径直接 {@code new MaterialBatch + repo.save}，绕开了
     * {@code MaterialBatchService.createMaterialBatch}；而对来源的强校验
     * （必填 + 校验单据存在）恰恰在被绕开的那条路上 ——
     * <b>手工入库被严格校验来源，自动化的采购主路径反而一个字不写。</b>
     */
    @Test
    @DisplayName("🔴 采购入库的批次必须回写 sourceDocType/sourceDocId（否则从批次追不回采购单）")
    void purchaseReceive_writesSourceDoc() {
        MaterialBatch batch = confirmWithQcResult("PASS");

        // 取值与 MaterialBatch#sourceDocType 的 javadoc 一致, 也与应付挂账传的那个值同源
        assertEquals("PURCHASE_RECEIVE", batch.getSourceDocType(),
                "来源单类型必须是 PURCHASE_RECEIVE —— MaterialBatchServiceImpl 有对应的 case 分支在等它");
        assertEquals(RECEIVE_ID, batch.getSourceDocId(),
                "来源单 id 必须指回这张收货单, 否则溯源断在这一环");
    }

    @Test
    @DisplayName("阳性对照: 同一批次的其它来源字段本来就在写(证明不是整个对象都没填)")
    void purchaseReceive_otherFieldsStillWritten() {
        MaterialBatch batch = confirmWithQcResult("PASS");
        // 没有这一条, 上面那两个断言红了也分不清「来源没写」还是「批次根本没建起来」
        assertEquals(FACTORY, batch.getFactoryId());
        assertNotNull(batch.getBatchNumber(), "批次号应当已生成");
        assertNotNull(batch.getReceiptQuantity(), "入库数量应当已写入");
    }

    private PurchaseReceiveRecord draftRecord(String qcResult) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(RECEIVE_ID);
        record.setFactoryId(FACTORY);
        record.setReceiveNumber("RCV-QC-20260704-001");
        record.setSupplierId("SUP-QC");
        record.setReceiveDate(LocalDate.of(2026, 7, 4));
        record.setWarehouseId("WH-RAW-01");
        record.setStatus(PurchaseReceiveStatus.DRAFT);
        record.setReceivedBy(USER_ID);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialTypeId(MATERIAL_ID);
        item.setMaterialName("pork trotter");
        item.setReceivedQuantity(new BigDecimal("50.0000"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("18.00"));
        item.setQcResult(qcResult);
        record.getItems().add(item);
        return record;
    }

    private RawMaterialType rawMaterial() {
        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL_ID);
        material.setFactoryId(FACTORY);
        material.setName("pork trotter");
        return material;
    }
}
