package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.factory.WarehouseResolver;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * W1 红线 #03: WarehouseInventoryGuardService 接线验证 (原料入库路径).
 *
 * <p>转录铁律 (张权 F006 仓管员): 仓管是纯操作员无库存自主权 —
 * 不能往错类型仓库收货. 守卫之前有实现+测试但零生产调用点, 形同虚设.
 * 本测试证明 {@link MaterialBatchServiceImpl#createMaterialBatch} 已真正调用守卫.
 *
 * <p>用真实 {@link WarehouseInventoryGuardService} + mocked 仓库 repo 跑真实守卫逻辑:
 * <ul>
 *   <li>目标仓库为 WIP 类型 + 原料 (RAW) 入库 → 422 WAREHOUSE_TYPE_MISMATCH (守卫被调用)</li>
 *   <li>目标仓库为 RAW 类型 + 原料入库 → 守卫放行 (类型匹配)</li>
 *   <li>目标仓库为 legacy LOGISTICS 类型 → 守卫放行 (向后兼容, 不误拦 F006 现有仓库)</li>
 *   <li>目标仓库 type=null → 守卫放行 (向后兼容)</li>
 * </ul>
 */
@DisplayName("W1 红线 #03: MaterialBatchServiceImpl — 仓库类型守卫接线验证")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplWarehouseGuardTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FactoryWarehouseRepository warehouseRepo;

    private MaterialBatchServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String MAT_PORK = "mt-pork";
    private static final String WH_ID = "WH-TARGET";

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
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);

        // Real guard wired with mocked warehouse repo → exercises real type-matching logic
        WarehouseInventoryGuardService guard = new WarehouseInventoryGuardService(warehouseRepo);
        ReflectionTestUtils.setField(service, "warehouseInventoryGuardService", guard);

        // Material type: pork, canonical=kg (UoM guard passes for kg)
        RawMaterialType pork = new RawMaterialType();
        pork.setId(MAT_PORK);
        pork.setName("冷冻猪舌");
        pork.setUnit("kg");
        pork.setFactoryId(FACTORY);
        pork.setIsAbacaPackaging(false);
        when(materialTypeRepository.findById(MAT_PORK)).thenReturn(Optional.of(pork));

        // Mapper returns a MaterialBatch carrying the DTO-supplied warehouseId
        when(materialBatchMapper.toEntity(any(), anyString(), any(Long.class)))
                .thenAnswer(inv -> {
                    CreateMaterialBatchRequest req = inv.getArgument(0);
                    MaterialBatch b = new MaterialBatch();
                    b.setWarehouseId(req.getWarehouseId());
                    b.setMaterialTypeId(req.getMaterialTypeId());
                    b.setReceiptQuantity(req.getReceiptQuantity());
                    return b;
                });
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ============================================================
    // Guard BLOCKS: wrong warehouse type (proves wiring is live)
    // ============================================================

    @Test
    @DisplayName("WIP 仓 + 原料入库 → 422 WAREHOUSE_TYPE_MISMATCH (守卫被调用)")
    void createBatch_wipWarehouse_rawMaterial_blocked() {
        stubWarehouseType(FactoryWarehouse.WarehouseType.WIP);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, req, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("FINISHED 仓 + 原料入库 → 422 (守卫被调用)")
    void createBatch_finishedWarehouse_rawMaterial_blocked() {
        stubWarehouseType(FactoryWarehouse.WarehouseType.FINISHED);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, req, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assert be.getCode() != null && be.getCode() == 422 : "expected 422, got " + be.getCode();
                    assert "WAREHOUSE_TYPE_MISMATCH".equals(be.getErrorCode())
                            : "expected errorCode WAREHOUSE_TYPE_MISMATCH, got: " + be.getErrorCode();
                });
    }

    // ============================================================
    // Guard PASSES: correct type / legacy / null (no误拦)
    // ============================================================

    @Test
    @DisplayName("RAW 仓 + 原料入库 → 守卫放行 (类型匹配)")
    void createBatch_rawWarehouse_rawMaterial_passes() {
        stubWarehouseType(FactoryWarehouse.WarehouseType.RAW);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertGuardDoesNotBlock(req);
    }

    @Test
    @DisplayName("legacy LOGISTICS 仓 → 守卫放行 (向后兼容, 防误拦 F006)")
    void createBatch_legacyLogisticsWarehouse_passes() {
        stubWarehouseType(FactoryWarehouse.WarehouseType.LOGISTICS);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertGuardDoesNotBlock(req);
    }

    @Test
    @DisplayName("legacy WORKSHOP 仓 → 守卫放行 (向后兼容)")
    void createBatch_legacyWorkshopWarehouse_passes() {
        stubWarehouseType(FactoryWarehouse.WarehouseType.WORKSHOP);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertGuardDoesNotBlock(req);
    }

    @Test
    @DisplayName("仓库 type=null → 守卫放行 (向后兼容)")
    void createBatch_nullTypeWarehouse_passes() {
        stubWarehouseType(null);
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertGuardDoesNotBlock(req);
    }

    @Test
    @DisplayName("仓库不存在 → 守卫放行 (留给上层 404)")
    void createBatch_warehouseNotFound_passes() {
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY))
                .thenReturn(Optional.empty());
        CreateMaterialBatchRequest req = inboundRequest(WH_ID);

        assertGuardDoesNotBlock(req);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void stubWarehouseType(FactoryWarehouse.WarehouseType type) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(WH_ID);
        wh.setFactoryId(FACTORY);
        wh.setType(type);
        wh.setName("测试仓库");
        when(warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(WH_ID, FACTORY))
                .thenReturn(Optional.of(wh));
    }

    /**
     * Assert the warehouse-type guard does NOT block. Downstream steps (mapper, event publish,
     * moving-avg) may throw for other reasons; we only require that no
     * WAREHOUSE_TYPE_MISMATCH / 422 is thrown by the guard.
     */
    private void assertGuardDoesNotBlock(CreateMaterialBatchRequest req) {
        assertThatCode(() -> {
            try {
                service.createMaterialBatch(FACTORY, req, 1L);
            } catch (BusinessException be) {
                Integer code = be.getCode();
                String msg = be.getMessage() != null ? be.getMessage() : "";
                if (code != null && code == 422 && msg.contains("仓")) {
                    throw be; // guard blocked — fail the test
                }
                // other BusinessException from downstream — acceptable
            } catch (Exception ignored) {
                // non-BusinessException downstream — acceptable, guard passed
            }
        }).doesNotThrowAnyException();
    }

    private CreateMaterialBatchRequest inboundRequest(String warehouseId) {
        CreateMaterialBatchRequest req = new CreateMaterialBatchRequest();
        req.setMaterialTypeId(MAT_PORK);
        req.setWarehouseId(warehouseId);
        req.setQuantityUnit("kg");
        req.setReceiptQuantity(BigDecimal.valueOf(100));
        req.setReceiptDate(LocalDate.now());
        req.setSourceDocType("MANUAL_ADJUST");
        req.setNotes("W1 #03 guard wiring test");
        return req;
    }
}
