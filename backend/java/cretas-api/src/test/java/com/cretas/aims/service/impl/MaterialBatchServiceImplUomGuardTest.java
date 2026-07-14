package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.mapper.MaterialBatchMapper;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T159-B-anchor: MaterialBatchServiceImpl UoM guard on 入库 write.
 *
 * <p>Tests the hard-block (R4) at 入库 (material receipt batch creation) write time:
 * <ul>
 *   <li>Cross-dimension unit (个 vs kg-canonical) → throws BusinessException with 防呆 message</li>
 *   <li>Same-dimension unit (g vs kg-canonical) → allowed</li>
 *   <li>Null/blank unit → no block (backwards compat)</li>
 * </ul>
 *
 * <p>Uses real MaterialUomConverter wired with mocked repos to exercise the real dimension logic.
 */
@DisplayName("T159-B R4: MaterialBatchServiceImpl — UoM guard at 入库 write time")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplUomGuardTest {

    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock
    private RawMaterialTypeRepository materialTypeRepository;
    @Mock
    private MaterialBatchMapper materialBatchMapper;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock
    private ExcelUtil excelUtil;
    @Mock
    private FuturePlanMatchingService futurePlanMatchingService;
    @Mock
    private WarehouseResolver warehouseResolver;

    private MaterialUomConverter uomConverter;
    private MaterialBatchServiceImpl service;

    private static final String FACTORY = "F001";
    private static final String MAT_PORK = "mt-pork";  // canonical=kg, not abaca
    private static final String MAT_BOX = "mt-box";    // canonical=件, not abaca

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

        // Wire real UomConverter
        MaterialPackagingHierarchyRepository packagingRepo =
                mock(MaterialPackagingHierarchyRepository.class);
        when(packagingRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());
        uomConverter = new MaterialUomConverter(packagingRepo, materialTypeRepository,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());
        ReflectionTestUtils.setField(service, "materialUomConverter", uomConverter);

        // Default material
        when(materialTypeRepository.findById(anyString())).thenReturn(Optional.empty());

        // Pork: canonical=kg
        RawMaterialType pork = new RawMaterialType();
        pork.setId(MAT_PORK);
        pork.setName("冷冻猪舌");
        pork.setUnit("kg");
        pork.setFactoryId(FACTORY);
        pork.setIsAbacaPackaging(false);
        when(materialTypeRepository.findById(MAT_PORK)).thenReturn(Optional.of(pork));

        // Box: canonical=件
        RawMaterialType box = new RawMaterialType();
        box.setId(MAT_BOX);
        box.setName("吸塑盒2014-3.5");
        box.setUnit("件");
        box.setFactoryId(FACTORY);
        box.setIsAbacaPackaging(false);
        when(materialTypeRepository.findById(MAT_BOX)).thenReturn(Optional.of(box));
    }

    // ============================================================
    // Cross-dimension BLOCKED
    // ============================================================

    @Test
    @DisplayName("(c) createBatch: 个 unit vs kg-canonical material → BusinessException with 防呆 message")
    void createBatch_crossDimension_个vsKg_blocked() {
        CreateMaterialBatchRequest req = inboundRequest(MAT_PORK, "个");

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, req, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThatBeMessage(be, "冷冻猪舌", "个", "kg");
                });
    }

    @Test
    @DisplayName("(c) createBatch: pcs unit vs kg-canonical material → BlockedException")
    void createBatch_crossDimension_pcsVsKg_blocked() {
        CreateMaterialBatchRequest req = inboundRequest(MAT_PORK, "pcs");

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, req, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冷冻猪舌");
    }

    @Test
    @DisplayName("(c) createBatch: kg unit vs 件-canonical material → BlockedException")
    void createBatch_crossDimension_kgVsJian_blocked() {
        CreateMaterialBatchRequest req = inboundRequest(MAT_BOX, "kg");

        assertThatThrownBy(() -> service.createMaterialBatch(FACTORY, req, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("吸塑盒");
    }

    // ============================================================
    // Same-dimension ALLOWED — guard must not throw the dimension-mismatch exception.
    // Downstream steps (mapper, warehouse resolver) may throw for other reasons; that's OK.
    // We only assert the UoM guard itself doesn't block.
    // ============================================================

    @Test
    @DisplayName("(a) createBatch: g unit vs kg-canonical → UoM guard does NOT throw")
    void createBatch_sameDimension_gVsKg_noUomException() {
        assertUomGuardDoesNotBlock(MAT_PORK, "g");
    }

    @Test
    @DisplayName("(a) createBatch: kg unit vs kg-canonical → UoM guard does NOT throw")
    void createBatch_sameUnit_kgVsKg_noUomException() {
        assertUomGuardDoesNotBlock(MAT_PORK, "kg");
    }

    @Test
    @DisplayName("(b) createBatch: 个 vs 件-canonical → UoM guard does NOT throw")
    void createBatch_countDimension_geVsJian_noUomException() {
        assertUomGuardDoesNotBlock(MAT_BOX, "个");
    }

    // ============================================================
    // Null/blank unit → no block
    // ============================================================

    @Test
    @DisplayName("null quantityUnit → UoM guard does NOT throw (backwards compat)")
    void createBatch_nullUnit_noUomBlock() {
        assertUomGuardDoesNotBlock(MAT_PORK, null);
    }

    @Test
    @DisplayName("blank quantityUnit → UoM guard does NOT throw")
    void createBatch_blankUnit_noUomBlock() {
        assertUomGuardDoesNotBlock(MAT_PORK, "  ");
    }

    /**
     * Helper: assert that the UoM guard does not block the given (materialId, unit) combination.
     * The guard has already done its job before reaching mapper/warehouse steps; if a later step
     * throws something that is NOT "计量维度不符", we consider the guard passed.
     */
    private void assertUomGuardDoesNotBlock(String materialId, String unit) {
        CreateMaterialBatchRequest req = inboundRequest(materialId, unit);
        try {
            service.createMaterialBatch(FACTORY, req, 1L);
        } catch (BusinessException be) {
            String msg = be.getMessage() != null ? be.getMessage() : "";
            if (msg.contains("计量维度不符")) {
                throw new AssertionError(
                        "UoM guard should NOT block " + unit + " vs canonical unit of " + materialId
                                + ", but threw: " + msg, be);
            }
            // Any other BusinessException from downstream is acceptable — guard passed
        } catch (Exception e) {
            // Non-BusinessException from downstream is acceptable — guard passed
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static void assertThatBeMessage(BusinessException be, String matName, String dtoUnit, String canonical) {
        String msg = be.getMessage() != null ? be.getMessage() : "";
        assert msg.contains(matName) : "Expected material name '" + matName + "' in: " + msg;
        assert msg.contains(dtoUnit) : "Expected dto unit '" + dtoUnit + "' in: " + msg;
        assert msg.contains(canonical) : "Expected canonical unit '" + canonical + "' in: " + msg;
        assert msg.contains("计量维度不符") : "Expected '计量维度不符' in: " + msg;
        assert be.getCode() != null && (be.getCode() == 409 || be.getCode() == 400) :
                "Expected HTTP 409 or 400 code";
        assert be.getActionHint() != null && !be.getActionHint().isBlank() :
                "Expected non-blank actionHint for 防呆";
    }

    private CreateMaterialBatchRequest inboundRequest(String materialTypeId, String quantityUnit) {
        CreateMaterialBatchRequest req = new CreateMaterialBatchRequest();
        req.setMaterialTypeId(materialTypeId);
        req.setQuantityUnit(quantityUnit);
        req.setReceiptQuantity(java.math.BigDecimal.valueOf(100));
        req.setReceiptDate(java.time.LocalDate.now());
        req.setSourceDocType("MANUAL_ADJUST");
        req.setNotes("T159 test");
        return req;
    }
}
