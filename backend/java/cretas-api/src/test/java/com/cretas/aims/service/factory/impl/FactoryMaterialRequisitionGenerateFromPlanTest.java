package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ② Part A: 领料需求单来源仓 = 采购实际落点仓 (resolvePurchaseInboundWh) 单测.
 *
 * <p>验证 {@code generateFromPlan} 把 {@code sourceWarehouseId} 设为
 * {@link WarehouseResolver#resolvePurchaseInboundWh} 的结果:
 * <ul>
 *   <li>有 PURCHASE_INBOUND 配置 (e.g. LIUSHANMEN=原料仓) → source = 原料仓 → 连上采购落点</li>
 *   <li>无配置 (F006 等) → resolver 回退 WH-LOG → source = 物流仓 = 现状 (向后兼容)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactoryMaterialRequisition generateFromPlan source-warehouse resolution")
class FactoryMaterialRequisitionGenerateFromPlanTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "plan-001";
    private static final String PRODUCT_TYPE_ID = "prod-001";
    private static final String WH_RAW = "wh-raw";
    private static final String WH_LOG = "wh-log";
    private static final String WH_WORKSHOP = "wh-workshop";

    @Mock
    private FactoryMaterialRequisitionRepository repository;
    @Mock
    private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private BomRecipeItemRepository bomItemRepository;
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
    @Mock
    private WarehouseResolver warehouseResolver;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    @BeforeEach
    void setup() {
        // warehouseResolver 是 @Autowired 字段, @InjectMocks 走构造器注入策略不会填它 → 手动反射注入.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PLAN-F006-001");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setExpectedCompletionDate(LocalDate.now().plusDays(3));
        lenient().when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(plan));

        BomRecipeItem bom = new BomRecipeItem();
        bom.setId(1L);
        bom.setMaterialTypeId("MAT-001");
        bom.setMaterialName("猪蹄");
        bom.setStandardQuantity(new BigDecimal("2"));
        bom.setUnit("kg");
        bom.setMaterialCategory("RAW");
        lenient().when(bomItemRepository
                        .findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(bom));

        // 车间/生产仓 (target) lookup — 两个 case 都需要.
        lenient().when(warehouseRepository
                        .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID, WarehouseType.WORKSHOP))
                .thenReturn(List.of(warehouse(WH_WORKSHOP, WarehouseType.WORKSHOP)));

        lenient().when(repository.countByFactoryIdAndRequisitionNoPrefix(eq(FACTORY_ID), any()))
                .thenReturn(0L);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("有 PURCHASE_INBOUND 配置时 source = 采购落点原料仓 (LIUSHANMEN 场景)")
    void generateFromPlan_withPurchaseInboundConfig_shouldSetSourceToRawWarehouse() {
        // resolver 已解析出配置的原料仓 (config→RAW warehouse)
        when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn(WH_RAW);

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertEquals(WH_RAW, mr.getSourceWarehouseId(),
                "领料来源仓应为采购落点原料仓, 而不是硬编码物流仓");
        assertEquals(WH_WORKSHOP, mr.getTargetWarehouseId(), "目标仓应为车间/生产仓");
        assertEquals("PLAN-F006-001", mr.getProductionPlanNumber(), "需求单应保存生产计划编号快照");
        verify(warehouseResolver).resolvePurchaseInboundWh(FACTORY_ID);
    }

    @Test
    @DisplayName("无 PURCHASE_INBOUND 配置时 source 回退 WH-LOG (向后兼容, 与现状一致)")
    void generateFromPlan_withoutConfig_shouldFallBackToLogisticsWarehouse() {
        // resolver 内部无配置 → 回退 WH-LOG 的 id (no-config→WH-LOG fallback)
        when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn(WH_LOG);

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertEquals(WH_LOG, mr.getSourceWarehouseId(),
                "无配置工厂领料来源仓应保持物流仓 (向后兼容)");
        assertEquals(WH_WORKSHOP, mr.getTargetWarehouseId(), "目标仓应为车间/生产仓");
        verify(warehouseResolver).resolvePurchaseInboundWh(FACTORY_ID);
    }

    @Test
    @DisplayName("10箱销售快照归一为500件后，200g/件 BOM 应生成100000g领料上限")
    void generateFromPlan_usesNormalizedProductQuantityForGramBomRequirement() {
        ProductionPlan normalizedPlan = new ProductionPlan();
        normalizedPlan.setId(PLAN_ID);
        normalizedPlan.setFactoryId(FACTORY_ID);
        normalizedPlan.setProductTypeId(PRODUCT_TYPE_ID);
        normalizedPlan.setPlanNumber("PLAN-10-BOX");
        normalizedPlan.setPlannedQuantity(new BigDecimal("500"));
        normalizedPlan.setPlannedUnit("piece");
        normalizedPlan.setExpectedCompletionDate(LocalDate.now().plusDays(3));
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID))
                .thenReturn(Optional.of(normalizedPlan));

        BomRecipeItem gramBom = new BomRecipeItem();
        gramBom.setId(2L);
        gramBom.setMaterialTypeId("MAT-GRAM");
        gramBom.setMaterialName("原料克重");
        gramBom.setStandardQuantity(new BigDecimal("200"));
        gramBom.setUnit("g");
        gramBom.setMaterialCategory("RAW");
        when(bomItemRepository
                .findCurrentByProduct(
                        FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(gramBom));
        when(materialBatchRepository.findStockUnitsByMaterialType(FACTORY_ID, "MAT-GRAM"))
                .thenReturn(List.of("g"));
        when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn(WH_RAW);

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertEquals(1, mr.getItems().size());
        assertEquals(0, new BigDecimal("100000").compareTo(mr.getItems().get(0).getRequiredQty()));
        assertEquals("g", mr.getItems().get(0).getUnit());
    }

    /**
     * 追踪码 2CC05928 (客户 2026-07-29 14:45 按 SOP 路线 B 生成物料需求单失败) 回归测试.
     *
     * <p>BOM 行允许只登记"配方资格"不填 standardQuantity (线上 143 行里 69 行如此),
     * 此时 {@code calculateActualQuantity()} 返回 null, 旧代码 {@code plannedQty.multiply(null)}
     * 抛 NPE → 用户只看到"系统处理异常，请稍后重试"且无从知道是哪一味原料缺配置.
     */
    @Test
    @DisplayName("追踪码 2CC05928: BOM 未填标准用量 → 409 指名原料, 而不是 NPE 系统异常")
    void generateFromPlan_bomWithoutStandardQuantity_shouldRejectWithActionableMessage() {
        BomRecipeItem noQtyBom = new BomRecipeItem();
        noQtyBom.setId(3L);
        noQtyBom.setMaterialTypeId("MAT-NOQTY");
        noQtyBom.setMaterialName("SOP-20260727-01-黄油鸡-原料A");
        noQtyBom.setStandardQuantity(null);   // 客户线上真实数据形态
        noQtyBom.setUnit("kg");
        noQtyBom.setMaterialCategory("RAW");
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(noQtyBom));
        lenient().when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn(WH_RAW);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L),
                "BOM 缺标准用量应显式拦下, 而不是 NPE");

        assertEquals(Integer.valueOf(409), ex.getCode(), "应为业务冲突而非 500 系统异常");
        assertTrue(ex.getMessage().contains("SOP-20260727-01-黄油鸡-原料A"),
                "错误信息必须指名是哪一味原料缺配置, 实际: " + ex.getMessage());
        verify(repository, never()).save(any());
    }

    private FactoryWarehouse warehouse(String id, WarehouseType type) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(id);
        wh.setFactoryId(FACTORY_ID);
        wh.setType(type);
        return wh;
    }
}
