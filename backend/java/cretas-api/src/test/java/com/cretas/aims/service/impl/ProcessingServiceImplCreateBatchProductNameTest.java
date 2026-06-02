package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SystemLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * P0-1 产品名绑定 (F006 报工审计修复) — verifies new logic in
 * {@link ProcessingServiceImpl#createBatch}:
 *
 * <ol>
 *   <li>传了 productTypeId → 从 product_types 解析真实品名设为 productName
 *       (消除 "待设置产品名称" 占位符)。</li>
 *   <li>用户显式传了 productName → 保留用户传入, 不被 productType.name 覆盖
 *       (向后兼容)。</li>
 *   <li>productTypeId 传了但 product_types 查不到 → 抛 400 BusinessException
 *       (fool-proof 快速失败, 不静默落占位符)。</li>
 *   <li>缺省回归: productTypeId 和 productName 都没传 → 保留原占位逻辑
 *       "待设置产品名称" (不硬断)。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplCreateBatchProductNameTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    private static final String F_ID = "F006";

    private ProductionBatch buildBatch(String batchNumber, String productTypeId, String productName) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(batchNumber);
        b.setProductTypeId(productTypeId);
        b.setProductName(productName);
        return b;
    }

    private ProductType buildProductType(String id, String name) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setFactoryId(F_ID);
        pt.setName(name);
        return pt;
    }

    @Test
    @DisplayName("P0-1: 传 productTypeId → 解析真实品名, 不落占位符")
    void productTypeId_resolvesRealProductName() {
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(productTypeRepository.findByIdAndFactoryId(eq("PT-猪舌"), eq(F_ID)))
                .thenReturn(Optional.of(buildProductType("PT-猪舌", "叮咚好食光轻卤门腔（猪舌）120g")));

        // productName 缺省, 应被 productType.name 填充
        ProductionBatch batch = buildBatch("BATCH-P01", "PT-猪舌", null);
        ProductionBatch saved = service.createBatch(F_ID, batch);

        assertEquals("叮咚好食光轻卤门腔（猪舌）120g", saved.getProductName(),
                "productName 应从 productTypeId 解析而非落占位符");
    }

    @Test
    @DisplayName("P0-1: 用户显式传 productName → 保留用户传入, 不被 productType.name 覆盖")
    void userProvidedProductName_preserved() {
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // productType 仍存在 (会被校验), 但 name 不应覆盖用户传入的品名
        when(productTypeRepository.findByIdAndFactoryId(eq("PT-猪舌"), eq(F_ID)))
                .thenReturn(Optional.of(buildProductType("PT-猪舌", "叮咚好食光轻卤门腔（猪舌）120g")));

        ProductionBatch batch = buildBatch("BATCH-P02", "PT-猪舌", "自定义品名-试产");
        ProductionBatch saved = service.createBatch(F_ID, batch);

        assertEquals("自定义品名-试产", saved.getProductName(),
                "用户显式传入的 productName 必须保留");
    }

    @Test
    @DisplayName("P0-1: productTypeId 查不到 → 抛 400 BusinessException (快速失败, 不落占位符)")
    void productTypeNotFound_throwsBusinessException() {
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        when(productTypeRepository.findByIdAndFactoryId(eq("PT-不存在"), eq(F_ID)))
                .thenReturn(Optional.empty());

        ProductionBatch batch = buildBatch("BATCH-P03", "PT-不存在", null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createBatch(F_ID, batch));
        assert ex.getMessage().contains("产品类型不存在");
    }

    @Test
    @DisplayName("P0-1 缺省回归: 无 productTypeId 也无 productName → 保留原占位逻辑")
    void noProductTypeId_noProductName_fallsBackToPlaceholder() {
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductionBatch batch = buildBatch("BATCH-P04", null, null);
        ProductionBatch saved = service.createBatch(F_ID, batch);

        assertEquals("待设置产品名称", saved.getProductName(),
                "无 productTypeId 无 productName → 向后兼容占位符");
        // 确认未触碰 productTypeRepository (无 productTypeId 时不应查)
        org.mockito.Mockito.verifyNoInteractions(productTypeRepository);
    }

    @Test
    @DisplayName("P0-1 缺省回归: 无 productTypeId 但传了 productName → 保留用户传入")
    void noProductTypeId_withProductName_keepsUserName() {
        lenient().when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductionBatch batch = buildBatch("BATCH-P05", null, "手工录入品名");
        ProductionBatch saved = service.createBatch(F_ID, batch);

        assertEquals("手工录入品名", saved.getProductName());
        org.mockito.Mockito.verifyNoInteractions(productTypeRepository);
    }
}
