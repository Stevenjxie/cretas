package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 反向调拨自动触发服务测试 (PR #309 A3=A, 2026-05-10 spec)。
 *
 * <p>验证:
 * <ul>
 *   <li>正常路径: WH-WKS 有余料 + plan 有成品 → 创建 DRAFT 单 (item 数 = 余料 type 数 + 成品 type 数)</li>
 *   <li>多 batch 同 materialTypeId → 聚合为 1 行 item, quantity 累加</li>
 *   <li>无余料但有成品 → 仍创建调拨单 (仅成品 items)</li>
 *   <li>0 余料 0 成品 → 跳过创建</li>
 *   <li>异常隔离: TransferService.createTransfer 抛异常 → log error 不 propagate</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("D1: ReverseTransferService — 反向调拨自动触发 (PR #309 A3=A)")
class ReverseTransferServiceTest {

    private static final String FACTORY_ID = "F001";
    private static final String PLAN_ID = "plan-uuid-1";
    private static final String PLAN_NUMBER = "PP-AUTO-20260511-0001";
    private static final String PRODUCT_TYPE_ID = "prod-uuid-1";
    private static final String WKS_WAREHOUSE_ID = "wh-wks-uuid";
    private static final String LOG_WAREHOUSE_ID = "wh-log-uuid";

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private TransferService transferService;

    @InjectMocks
    private ReverseTransferService service;

    /**
     * Stub the WarehouseResolver for success-path tests. Failure-path test
     * ({@link #warehouseResolverFailure_swallowed}) overrides the workshop resolver
     * to throw before this is called.
     */
    private void stubWarehouseHappy() {
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn(WKS_WAREHOUSE_ID);
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn(LOG_WAREHOUSE_ID);
    }

    private ProductionCompletedEvent newEvent() {
        return new ProductionCompletedEvent(this, FACTORY_ID, PLAN_ID, PLAN_NUMBER,
                PRODUCT_TYPE_ID, new BigDecimal("100"));
    }

    private MaterialBatch newMaterial(String materialTypeId, String receipt, String used, String reserved) {
        MaterialBatch m = new MaterialBatch();
        m.setId(java.util.UUID.randomUUID().toString());
        m.setFactoryId(FACTORY_ID);
        m.setMaterialTypeId(materialTypeId);
        m.setWarehouseId(WKS_WAREHOUSE_ID);
        m.setStatus(MaterialBatchStatus.AVAILABLE);
        m.setReceiptQuantity(new BigDecimal(receipt));
        m.setUsedQuantity(new BigDecimal(used));
        m.setReservedQuantity(new BigDecimal(reserved));
        return m;
    }

    private FinishedGoodsBatch newFG(String productTypeId, String productName, String unit, String produced) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(java.util.UUID.randomUUID().toString());
        fg.setFactoryId(FACTORY_ID);
        fg.setProductTypeId(productTypeId);
        fg.setProductName(productName);
        fg.setUnit(unit);
        fg.setUnitPrice(new BigDecimal("10.00"));
        fg.setProducedQuantity(new BigDecimal(produced));
        fg.setShippedQuantity(BigDecimal.ZERO);
        fg.setReservedQuantity(BigDecimal.ZERO);
        fg.setProductionPlanId(PLAN_ID);
        fg.setWarehouseId(WKS_WAREHOUSE_ID);
        fg.setStatus("AVAILABLE");
        return fg;
    }

    private RawMaterialType newRawType(String id, String name, String unit) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setName(name);
        t.setUnit(unit);
        return t;
    }

    @Test
    @DisplayName("正常路径: 1 种余料 + 1 种成品 → 创建 DRAFT 单, items=2")
    void happyPath_oneMaterialOneProduct() {
        stubWarehouseHappy();
        // 余料: 1 batch, materialTypeId=mat-A, 可用 = 100 - 20 - 0 = 80
        MaterialBatch m1 = newMaterial("mat-A", "100", "20", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));

        // 成品: 1 batch
        FinishedGoodsBatch fg = newFG("prod-X", "面包", "个", "50");
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(fg));

        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(mockTransfer("xfer-1", "IT-20260511-0001"));

        service.onProductionCompleted(newEvent());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), captor.capture(), anyLong());

        CreateTransferRequest req = captor.getValue();
        assertThat(req.getTransferType()).isEqualTo("BRANCH_TO_HQ");
        assertThat(req.getTargetFactoryId()).isEqualTo(FACTORY_ID);
        assertThat(req.getSourceWarehouseId()).isEqualTo(WKS_WAREHOUSE_ID);
        assertThat(req.getTargetWarehouseId()).isEqualTo(LOG_WAREHOUSE_ID);
        assertThat(req.getItems()).hasSize(2);

        // Material item
        CreateTransferRequest.TransferItemDTO matItem = req.getItems().stream()
                .filter(i -> "RAW_MATERIAL".equals(i.getItemType()))
                .findFirst().orElseThrow();
        assertThat(matItem.getMaterialTypeId()).isEqualTo("mat-A");
        assertThat(matItem.getItemName()).isEqualTo("面粉");
        assertThat(matItem.getUnit()).isEqualTo("kg");
        assertThat(matItem.getQuantity()).isEqualByComparingTo(new BigDecimal("80"));

        // FG item
        CreateTransferRequest.TransferItemDTO fgItem = req.getItems().stream()
                .filter(i -> "FINISHED_GOODS".equals(i.getItemType()))
                .findFirst().orElseThrow();
        assertThat(fgItem.getProductTypeId()).isEqualTo("prod-X");
        assertThat(fgItem.getItemName()).isEqualTo("面包");
        assertThat(fgItem.getUnit()).isEqualTo("个");
        assertThat(fgItem.getQuantity()).isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    @DisplayName("多 batch 同 materialTypeId → 聚合为 1 行 item, quantity 累加")
    void multipleBatches_aggregateByMaterialType() {
        stubWarehouseHappy();
        // 3 batch 都是 mat-A: 50, 30, 20 全可用
        MaterialBatch m1 = newMaterial("mat-A", "50", "0", "0");
        MaterialBatch m2 = newMaterial("mat-A", "30", "0", "0");
        MaterialBatch m3 = newMaterial("mat-A", "20", "0", "0");
        // 1 batch mat-B: 40
        MaterialBatch m4 = newMaterial("mat-B", "40", "0", "0");

        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1, m2, m3, m4));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(rawMaterialTypeRepository.findById("mat-B"))
                .thenReturn(Optional.of(newRawType("mat-B", "糖", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(mockTransfer("xfer-2", "IT-20260511-0002"));

        service.onProductionCompleted(newEvent());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), captor.capture(), anyLong());
        CreateTransferRequest req = captor.getValue();

        assertThat(req.getItems()).hasSize(2);
        CreateTransferRequest.TransferItemDTO matA = req.getItems().stream()
                .filter(i -> "mat-A".equals(i.getMaterialTypeId())).findFirst().orElseThrow();
        assertThat(matA.getQuantity()).isEqualByComparingTo(new BigDecimal("100"));  // 50+30+20

        CreateTransferRequest.TransferItemDTO matB = req.getItems().stream()
                .filter(i -> "mat-B".equals(i.getMaterialTypeId())).findFirst().orElseThrow();
        assertThat(matB.getQuantity()).isEqualByComparingTo(new BigDecimal("40"));
    }

    @Test
    @DisplayName("0 余料但有成品 → 仍创建调拨单, items 仅成品")
    void zeroRemaining_butFinishedGoods() {
        stubWarehouseHappy();
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        FinishedGoodsBatch fg = newFG("prod-X", "蛋糕", "个", "10");
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(fg));
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(mockTransfer("xfer-3", "IT-20260511-0003"));

        service.onProductionCompleted(newEvent());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), captor.capture(), anyLong());
        assertThat(captor.getValue().getItems()).hasSize(1);
        assertThat(captor.getValue().getItems().get(0).getItemType()).isEqualTo("FINISHED_GOODS");
    }

    @Test
    @DisplayName("0 余料 + 0 成品 → 跳过创建 (不调用 transferService)")
    void zeroEverything_skipCreation() {
        stubWarehouseHappy();
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());

        service.onProductionCompleted(newEvent());

        verify(transferService, never()).createTransfer(anyString(), any(CreateTransferRequest.class), anyLong());
    }

    @Test
    @DisplayName("异常隔离: TransferService.createTransfer 抛异常 → 静默吞 (不抛出)")
    void exceptionFromTransferService_swallowed() {
        stubWarehouseHappy();
        MaterialBatch m1 = newMaterial("mat-A", "50", "0", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenThrow(new RuntimeException("Simulated DB failure"));

        // 不应抛出 (异常隔离: 生产完成主流程不能被反向调拨阻塞)
        service.onProductionCompleted(newEvent());

        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), any(), anyLong());
    }

    @Test
    @DisplayName("已耗尽的 batch (used == receipt) 不参与聚合 (defensive — repo query 已过滤)")
    void exhaustedBatches_excluded() {
        stubWarehouseHappy();
        // 即使 repo 没过滤 (defensive double-check), service 也应跳过 0 available
        MaterialBatch m1 = newMaterial("mat-A", "50", "50", "0");  // 0 available
        MaterialBatch m2 = newMaterial("mat-A", "30", "0", "0");   // 30 available
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1, m2));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(mockTransfer("xfer-5", "IT-20260511-0005"));

        service.onProductionCompleted(newEvent());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), captor.capture(), anyLong());

        // 聚合只有 30 (m1 的 0 available 被跳过)
        assertThat(captor.getValue().getItems()).hasSize(1);
        assertThat(captor.getValue().getItems().get(0).getQuantity())
                .isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    @DisplayName("RawMaterialType 找不到 → fallback 名称 '未知物料' + 单位 'kg'")
    void rawMaterialTypeNotFound_fallback() {
        stubWarehouseHappy();
        MaterialBatch m1 = newMaterial("mat-MISSING", "50", "0", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-MISSING"))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(mockTransfer("xfer-6", "IT-20260511-0006"));

        service.onProductionCompleted(newEvent());

        ArgumentCaptor<CreateTransferRequest> captor = ArgumentCaptor.forClass(CreateTransferRequest.class);
        verify(transferService).createTransfer(eq(FACTORY_ID), captor.capture(), anyLong());

        CreateTransferRequest.TransferItemDTO item = captor.getValue().getItems().get(0);
        assertThat(item.getItemName()).isEqualTo("未知物料");
        assertThat(item.getUnit()).isEqualTo("kg");
    }

    @Test
    @DisplayName("WarehouseResolver 抛异常 → 静默吞 (生产完成不应阻塞)")
    void warehouseResolverFailure_swallowed() {
        when(warehouseResolver.resolveWorkshopId(FACTORY_ID))
                .thenThrow(new RuntimeException("Warehouse seed missing"));

        // 不应抛出
        service.onProductionCompleted(newEvent());

        verify(transferService, never()).createTransfer(anyString(), any(), anyLong());
    }

    // ==================== R6 #5 同厂自动推进 ====================

    @Test
    @DisplayName("R6 #5 同厂: 创建 DRAFT 后自动推进 request→approve→confirm")
    void intraFactory_autoAdvancesToConfirmed() {
        stubWarehouseHappy();
        MaterialBatch m1 = newMaterial("mat-A", "100", "20", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        // targetFactoryId == FACTORY_ID (同厂) → 应自动推进
        InternalTransfer draft = mockTransfer("xfer-intra", "TRF-INTRA");
        draft.setSourceFactoryId(FACTORY_ID);
        draft.setTargetFactoryId(FACTORY_ID);
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(draft);

        service.onProductionCompleted(newEvent());

        // 验证 5 步生命周期全部以同厂 + system user 调用
        verify(transferService, times(1)).requestTransfer(eq(FACTORY_ID), eq("xfer-intra"), anyLong());
        verify(transferService, times(1)).approveTransfer(eq(FACTORY_ID), eq("xfer-intra"), anyLong());
        verify(transferService, times(1)).confirmTransfer(eq(FACTORY_ID), eq("xfer-intra"), anyLong());
    }

    @Test
    @DisplayName("R6 #5 跨厂: 创建 DRAFT 后保留手动多步, 不自动推进")
    void crossFactory_doesNotAutoAdvance() {
        stubWarehouseHappy();
        MaterialBatch m1 = newMaterial("mat-A", "100", "20", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        // targetFactoryId != FACTORY_ID (跨厂) → 不应自动推进
        InternalTransfer draft = mockTransfer("xfer-cross", "TRF-CROSS");
        draft.setSourceFactoryId(FACTORY_ID);
        draft.setTargetFactoryId("F002");
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(draft);

        service.onProductionCompleted(newEvent());

        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), any(), anyLong());
        verify(transferService, never()).requestTransfer(anyString(), anyString(), anyLong());
        verify(transferService, never()).approveTransfer(anyString(), anyString(), anyLong());
        verify(transferService, never()).shipTransfer(anyString(), anyString(), anyLong());
        verify(transferService, never()).receiveTransfer(anyString(), anyString(), anyLong());
        verify(transferService, never()).confirmTransfer(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("R6 #5 同厂推进失败异常隔离: confirm 抛 409 → 静默吞 (草稿已创建, 不抛出)")
    void intraFactory_advanceFailure_swallowed() {
        stubWarehouseHappy();
        MaterialBatch m1 = newMaterial("mat-A", "100", "20", "0");
        when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                .thenReturn(List.of(m1));
        when(rawMaterialTypeRepository.findById("mat-A"))
                .thenReturn(Optional.of(newRawType("mat-A", "面粉", "kg")));
        when(finishedGoodsBatchRepository.findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                .thenReturn(Collections.emptyList());
        InternalTransfer draft = mockTransfer("xfer-fail", "TRF-FAIL");
        draft.setSourceFactoryId(FACTORY_ID);
        draft.setTargetFactoryId(FACTORY_ID);
        when(transferService.createTransfer(eq(FACTORY_ID), any(CreateTransferRequest.class), anyLong()))
                .thenReturn(draft);
        // confirm 抛异常 (e.g. 库存不足 409) — 不应 propagate 出 onProductionCompleted
        when(transferService.confirmTransfer(eq(FACTORY_ID), eq("xfer-fail"), anyLong()))
                .thenThrow(new RuntimeException("Simulated stock insufficient"));

        // 不应抛出 (异常隔离: 生产完成主流程不能被自动推进失败阻塞)
        service.onProductionCompleted(newEvent());

        // 草稿仍被创建; confirm 被尝试
        verify(transferService, times(1)).createTransfer(eq(FACTORY_ID), any(), anyLong());
        verify(transferService, times(1)).confirmTransfer(eq(FACTORY_ID), eq("xfer-fail"), anyLong());
    }

    /** 辅助: 构造一个 mock 的 InternalTransfer 返回值。 */
    private InternalTransfer mockTransfer(String id, String number) {
        InternalTransfer t = new InternalTransfer();
        t.setId(id);
        t.setTransferNumber(number);
        t.setStatus(TransferStatus.DRAFT);
        // 默认同厂 (大多数测试场景); 跨厂测试显式覆盖 targetFactoryId
        t.setSourceFactoryId(FACTORY_ID);
        t.setTargetFactoryId(FACTORY_ID);
        return t;
    }
}
