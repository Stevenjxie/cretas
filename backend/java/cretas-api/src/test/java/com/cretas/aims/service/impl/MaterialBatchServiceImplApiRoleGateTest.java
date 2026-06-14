package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4 红线 (#686 残留-r2): MaterialBatch PUT/DELETE API 层角色守卫验证。
 *
 * <p>转录铁律 (张权 F006 仓管员): "做仓管的纯操作员无库存自主权, 库存变动必须单据+审批"。
 * Fable 审计抓到: 即便 #686 修了 RN 直改库存 + 接了入库守卫, API 层 PUT /material-batches/{id}
 * (warehouse:read_write 即可) 仍允许仓管经 MaterialBatchMapper.updateEntity 改 receiptQuantity
 * (影响台账/出成率分母/成本); DELETE 允许仓管删未消耗批次 (=无单据移除库存)。
 *
 * <p>本测试验证 service 角色守卫重载:
 * <ul>
 *   <li>仓管 (warehouse_worker / operator) 改 receiptQuantity / unitPrice → 403 (拦截)</li>
 *   <li>仓管 改 storageLocation / notes (非库存字段) → 放行</li>
 *   <li>仓管 删批次 → 403 (拦截)</li>
 *   <li>管理员 (factory_super_admin / warehouse_manager / 平台管理员) 改入库量 / 删 → 放行</li>
 *   <li>callerRole=null (内部/AI-tool 路径) → 放行 (W0 WriteGuard 另行把关)</li>
 * </ul>
 */
@DisplayName("W4 红线: MaterialBatch PUT/DELETE API 层角色守卫")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplApiRoleGateTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    private MaterialBatchServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String BATCH_ID = "MB-W4-001";

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(
                materialBatchRepository,
                materialBatchAdjustmentRepository,
                materialTypeRepository,
                materialBatchMapper,
                materialConsumptionRepository,
                productionPlanBatchUsageRepository,
                excelUtil,
                futurePlanMatchingService);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 现存批次: 入库量 100kg, 单价 10, 重量 100, 总价 1000, AVAILABLE, currentQty=receiptQty (可删)。 */
    private MaterialBatch existingBatch() {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptQuantity(BigDecimal.valueOf(100));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(BigDecimal.valueOf(10));
        b.setWeightPerUnit(BigDecimal.ONE);
        when(materialBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(b));
        return b;
    }

    // ============================================================
    // PUT — 仓管改库存字段 → 403
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 改 receiptQuantity → 403 (拦截), 不落库")
    void put_warehouseWorker_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130)); // 改入库量 (放大出成率分母)

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);

        verify(materialBatchMapper, never()).updateEntity(any(), any());
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("operator 改 unitPrice → 403 (拦截)")
    void put_operator_changeUnitPrice_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setUnitPrice(BigDecimal.valueOf(99));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("warehouse_worker 发 totalValue (间接改成本) → 403")
    void put_warehouseWorker_changeTotalValue_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setTotalValue(BigDecimal.valueOf(5000));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // ============================================================
    // PUT — 仓管改非库存字段 → 放行 (向后兼容合法编辑)
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 改 storageLocation (非库存字段) → 放行")
    void put_warehouseWorker_changeStorageLocation_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setStorageLocation("A区-02货架");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();

        verify(materialBatchMapper, times(1)).updateEntity(any(), any());
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("warehouse_worker 改 notes (非库存字段) → 放行")
    void put_warehouseWorker_changeNotes_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setNotes("位置调整说明");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("warehouse_worker 发与现值相同的 receiptQuantity (no-op) → 放行 (无实际变更)")
    void put_warehouseWorker_sameReceiptQuantity_passes() {
        existingBatch(); // receiptQuantity = 100
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(100)); // 同值, scale 不同也算相等
        req.setStorageLocation("A区-03货架");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // PUT — 管理员 / null 改库存字段 → 放行
    // ============================================================

    @Test
    @DisplayName("factory_super_admin 改 receiptQuantity → 放行")
    void put_factorySuperAdmin_changeReceiptQuantity_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "factory_super_admin"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("warehouse_manager 改 receiptQuantity → 放行")
    void put_warehouseManager_changeReceiptQuantity_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("platform_admin 改 receiptQuantity → 放行 (平台管理员 bypass)")
    void put_platformAdmin_changeReceiptQuantity_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "platform_admin"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("callerRole=null (内部/AI-tool 路径) 改 receiptQuantity → 放行 (向后兼容)")
    void put_nullRole_changeReceiptQuantity_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("无角色重载 (旧签名) 改 receiptQuantity → 放行 (delegate null)")
    void put_legacyOverload_changeReceiptQuantity_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // DELETE — 仓管删 → 403; 管理员/null 删 → 放行
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 删批次 → 403 (拦截), 不删除")
    void delete_warehouseWorker_blocked() {
        existingBatch();

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);

        verify(materialBatchRepository, never()).delete(any());
    }

    @Test
    @DisplayName("operator 删批次 → 403 (拦截)")
    void delete_operator_blocked() {
        existingBatch();

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("factory_super_admin 删未消耗批次 → 放行 (执行删除)")
    void delete_factorySuperAdmin_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "factory_super_admin"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("warehouse_manager 删未消耗批次 → 放行")
    void delete_warehouseManager_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "warehouse_manager"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("callerRole=null (内部路径) 删 → 放行 (向后兼容)")
    void delete_nullRole_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, null))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("无角色重载 (旧签名) 删 → 放行 (delegate null)")
    void delete_legacyOverload_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("管理员删但批次已部分消耗 → 409 (现有业务规则不被角色守卫绕过)")
    void delete_admin_consumedBatch_still409() {
        MaterialBatch b = existingBatch();
        b.setUsedQuantity(BigDecimal.valueOf(30)); // currentQty != receiptQty → 已消耗

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(409);
    }
}
