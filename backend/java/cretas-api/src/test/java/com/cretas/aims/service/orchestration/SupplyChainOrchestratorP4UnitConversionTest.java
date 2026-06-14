package com.cretas.aims.service.orchestration;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P4 kg↔盒单位标准化 — SupplyChainOrchestrator.createFinishedGoodsFromBatch 单元测试
 *
 * <p>覆盖 4 个核心分支:
 * <ol>
 *   <li>kg 产出 + 产品 unit=盒 + gramsPerUnit 已配置 → 换算为盒入库</li>
 *   <li>盒 产出 + 产品 unit=盒 → 防双重换算, 原值入库</li>
 *   <li>kg 产出 + gramsPerUnit=null → 不换算, 保持 kg 入库</li>
 *   <li>kg 产出 + 产品 unit≠盒 (如 kg 产品) → 不换算, 原值入库</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P4 SupplyChainOrchestrator kg→盒换算")
class SupplyChainOrchestratorP4UnitConversionTest {

    @Mock private InventoryMatchingService inventoryMatchingService;
    @Mock private BomExpansionService bomExpansionService;
    @Mock private ProcurementSuggestionService procurementSuggestionService;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private BatchConsumptionService batchConsumptionService;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FactorySettingsRepository factorySettingsRepository;

    @InjectMocks
    private SupplyChainOrchestrator orchestrator;

    @BeforeEach
    void injectFieldDependencies() {
        // warehouseResolver is @Autowired field injection (not in constructor),
        // @InjectMocks won't inject it — use ReflectionTestUtils
        ReflectionTestUtils.setField(orchestrator, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(orchestrator, "factorySettingsRepository", factorySettingsRepository);
    }

    // ---------- helpers ----------

    private ProductionBatch makeBatch(long batchId, String productTypeId, BigDecimal goodQty, String unit) {
        ProductionBatch b = new ProductionBatch();
        b.setId(batchId);
        b.setFactoryId("F006");
        b.setProductTypeId(productTypeId);
        b.setProductName("测试产品");
        b.setGoodQuantity(goodQty);
        b.setUnit(unit);
        b.setStatus(ProductionBatchStatus.COMPLETED);
        return b;
    }

    private ProductType makeProductType(String unit, BigDecimal gramsPerUnit) {
        ProductType pt = new ProductType();
        pt.setUnit(unit);
        pt.setGramsPerUnit(gramsPerUnit);
        return pt;
    }

    private void stubCommon(ProductionBatch batch, ProductType productType) {
        // FG 幂等检查: 不存在同编号 → 允许创建
        when(factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(batch.getFactoryId()))
                .thenReturn(false);
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // ProductType 查询
        if (productType != null) {
            when(productTypeRepository.findById(batch.getProductTypeId()))
                    .thenReturn(Optional.of(productType));
        } else {
            when(productTypeRepository.findById(batch.getProductTypeId()))
                    .thenReturn(Optional.empty());
        }
        // FG save 返回入参
        when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 批次质检: 无 supervisorId → 跳过 (不需要 qualityInspection mock)
        // warehouseResolver
        when(warehouseResolver.resolveWorkshopId(anyString())).thenReturn("WH-WKS-F006");
        // batchConsumptionService: fail-soft
        when(productionBatchRepository.countByProductionPlanIdAndStatusNot(any(), any()))
                .thenReturn(0L);
    }

    // ---------- tests ----------

    @Test
    @DisplayName("Case1: 540kg + 产品unit=盒 + gramsPerUnit=120 → 4500.00盒入库")
    void case1_kgToBox_withGramsPerUnit() {
        ProductionBatch batch = makeBatch(1L, "PT-001",
                new BigDecimal("540"), "kg");
        ProductType pt = makeProductType("盒", new BigDecimal("120"));
        stubCommon(batch, pt);

        BatchCompletedEvent event = new BatchCompletedEvent(this, batch);
        orchestrator.onBatchCompleted(event);

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        org.mockito.Mockito.verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        assertThat(fg.getUnit()).isEqualTo("盒");
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("4500.00"));
        assertThat(fg.getRemark()).contains("[P4]原kg=540");
    }

    @Test
    @DisplayName("Case2: 4500盒产出 + 产品unit=盒 → 防双重换算, 4500盒直接入库")
    void case2_alreadyBox_noDoubleConversion() {
        ProductionBatch batch = makeBatch(2L, "PT-001",
                new BigDecimal("4500"), "盒");
        ProductType pt = makeProductType("盒", new BigDecimal("120"));
        stubCommon(batch, pt);

        BatchCompletedEvent event = new BatchCompletedEvent(this, batch);
        orchestrator.onBatchCompleted(event);

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        org.mockito.Mockito.verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        assertThat(fg.getUnit()).isEqualTo("盒");
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("4500"));
        // 无换算 remark
        assertThat(fg.getRemark()).isNull();
    }

    @Test
    @DisplayName("Case3: 540kg + gramsPerUnit=null → 不换算, 保持 kg 入库")
    void case3_kgWithoutGramsPerUnit_noConversion() {
        ProductionBatch batch = makeBatch(3L, "PT-001",
                new BigDecimal("540"), "kg");
        ProductType pt = makeProductType("盒", null);  // gramsPerUnit 未配置
        stubCommon(batch, pt);

        BatchCompletedEvent event = new BatchCompletedEvent(this, batch);
        orchestrator.onBatchCompleted(event);

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        org.mockito.Mockito.verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        // 保持原 kg 入库, 不换算
        assertThat(fg.getUnit()).isEqualTo("kg");
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("540"));
        assertThat(fg.getRemark()).isNull();
    }

    @Test
    @DisplayName("Case4: 540kg + 产品unit=kg (非盒产品) → 不换算, 保持 kg 入库")
    void case4_kgProductUnit_noConversion() {
        ProductionBatch batch = makeBatch(4L, "PT-001",
                new BigDecimal("540"), "kg");
        ProductType pt = makeProductType("kg", new BigDecimal("120"));  // 产品本身 unit=kg
        stubCommon(batch, pt);

        BatchCompletedEvent event = new BatchCompletedEvent(this, batch);
        orchestrator.onBatchCompleted(event);

        ArgumentCaptor<FinishedGoodsBatch> cap = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        org.mockito.Mockito.verify(finishedGoodsBatchRepository).save(cap.capture());
        FinishedGoodsBatch fg = cap.getValue();

        // 产品不是盒, 不换算
        assertThat(fg.getUnit()).isEqualTo("kg");
        assertThat(fg.getProducedQuantity()).isEqualByComparingTo(new BigDecimal("540"));
        assertThat(fg.getRemark()).isNull();
    }
}
