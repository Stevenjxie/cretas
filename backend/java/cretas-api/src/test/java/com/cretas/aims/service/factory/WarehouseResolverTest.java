package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * D1 双仓流转 — WarehouseResolver 单元测试 (2026-05-10 spec, PR #309 A1=A).
 *
 * <p>验证:
 * <ol>
 *   <li>WH-LOG / WH-WKS code 解析为正确的 factory_warehouses.id</li>
 *   <li>seed 缺失时抛 BusinessException (defensive)</li>
 *   <li>跨 factory 隔离: factoryA 不能拿到 factoryB 的 warehouse</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseResolver D1 单元测试")
class WarehouseResolverTest {

    @Mock
    private FactoryWarehouseRepository factoryWarehouseRepository;

    @Mock
    private FactoryWarehouseDefaultRepository factoryWarehouseDefaultRepository;

    @InjectMocks
    private WarehouseResolver warehouseResolver;

    private static final String FACTORY_ID = "F001";
    private static final String WH_LOG_ID = "wh-log-uuid-001";
    private static final String WH_WKS_ID = "wh-wks-uuid-001";
    private static final String WH_FG_ID = "wh-fg-uuid-001";

    private FactoryWarehouse whLog;
    private FactoryWarehouse whWks;
    private FactoryWarehouse whFg;

    @BeforeEach
    void setUp() {
        whLog = new FactoryWarehouse();
        whLog.setId(WH_LOG_ID);
        whLog.setFactoryId(FACTORY_ID);
        whLog.setCode(WarehouseCodes.WH_LOG);
        whLog.setName("物流仓");
        whLog.setType(WarehouseType.LOGISTICS);
        whLog.setIsActive(true);

        whWks = new FactoryWarehouse();
        whWks.setId(WH_WKS_ID);
        whWks.setFactoryId(FACTORY_ID);
        whWks.setCode(WarehouseCodes.WH_WKS);
        whWks.setName("鲜棉仓");
        whWks.setType(WarehouseType.WORKSHOP);
        whWks.setIsActive(true);

        whFg = new FactoryWarehouse();
        whFg.setId(WH_FG_ID);
        whFg.setFactoryId(FACTORY_ID);
        whFg.setCode(WarehouseCodes.WH_FG);
        whFg.setName("finished goods");
        whFg.setType(WarehouseType.FINISHED);
        whFg.setIsActive(true);
    }

    @Test
    @DisplayName("resolveLogisticsId 返回 WH-LOG 的 factory_warehouses.id")
    void resolveLogisticsId_returnsCorrectId() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.of(whLog));

        String id = warehouseResolver.resolveLogisticsId(FACTORY_ID);

        assertEquals(WH_LOG_ID, id);
        verify(factoryWarehouseRepository).findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG);
    }

    @Test
    @DisplayName("resolveWorkshopId 返回 WH-WKS 的 factory_warehouses.id")
    void resolveWorkshopId_returnsCorrectId() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_WKS))
                .thenReturn(Optional.of(whWks));

        String id = warehouseResolver.resolveWorkshopId(FACTORY_ID);

        assertEquals(WH_WKS_ID, id);
    }

    @Test
    @DisplayName("resolveFinishedGoodsId returns WH-FG factory_warehouses.id")
    void resolveFinishedGoodsId_returnsCorrectId() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_FG))
                .thenReturn(Optional.of(whFg));

        String id = warehouseResolver.resolveFinishedGoodsId(FACTORY_ID);

        assertEquals(WH_FG_ID, id);
    }

    @Test
    @DisplayName("resolveId 任意 code: 找到则返回 id")
    void resolveId_arbitraryCode_returnsId() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, "WH-OTHER"))
                .thenReturn(Optional.of(whLog));

        String id = warehouseResolver.resolveId(FACTORY_ID, "WH-OTHER");

        assertEquals(WH_LOG_ID, id);
    }

    @Test
    @DisplayName("resolveLogisticsId: seed 缺失 (factory 没 WH-LOG) 抛 BusinessException")
    void resolveLogisticsId_missingSeed_throwsBusinessException() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> warehouseResolver.resolveLogisticsId(FACTORY_ID));

        assertTrue(ex.getMessage().contains("WH-LOG"), "异常应包含 WH-LOG code");
        assertTrue(ex.getMessage().contains(FACTORY_ID), "异常应包含 factoryId");
    }

    @Test
    @DisplayName("resolveWorkshopId: seed 缺失抛 BusinessException")
    void resolveWorkshopId_missingSeed_throwsBusinessException() {
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_WKS))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> warehouseResolver.resolveWorkshopId(FACTORY_ID));
    }

    @Test
    @DisplayName("WarehouseCodes 常量值一致 (WH-LOG, WH-WKS)")
    void warehouseCodes_constantsAreCorrect() {
        // 与 V20260411_03 / V20260424_08 seed 必须一致
        assertEquals("WH-LOG", WarehouseCodes.WH_LOG);
        assertEquals("WH-WKS", WarehouseCodes.WH_WKS);
        assertEquals("WH-FG", WarehouseCodes.WH_FG);
    }

    // ==================== V20261027_30 可配置默认仓 ====================

    private static final String CONFIGURED_WH_ID = "wh-custom-uuid-777";

    /**
     * (a) 向后兼容 — 最重要的测试。
     *
     * <p>工厂无默认仓配置 (findByFactoryIdAndPurpose 返 empty, Mockito Optional 默认值)
     * → resolver 必须回退到硬编码 WH-LOG/WH-WKS/WH-RD 查询, 行为与现状 100% 一致。
     * 上面 resolveLogisticsId_returnsCorrectId / resolveWorkshopId_returnsCorrectId 已隐式覆盖
     * (它们不 stub 默认仓 repo, 走的正是回退路径); 这里显式断言三个 purpose 全回退。
     */
    @Test
    @DisplayName("(a) 无配置 → 三个 resolver 全回退硬编码仓 (向后兼容)")
    void noConfig_fallsBackToHardcodedWarehouses() {
        // 未 stub factoryWarehouseDefaultRepository → 默认返 Optional.empty() (无配置)
        FactoryWarehouse whRd = new FactoryWarehouse();
        whRd.setId("wh-rd-uuid-001");
        whRd.setFactoryId(FACTORY_ID);
        whRd.setCode(WarehouseCodes.WH_RD);

        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.of(whLog));
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_WKS))
                .thenReturn(Optional.of(whWks));
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_RD))
                .thenReturn(Optional.of(whRd));

        assertEquals(WH_LOG_ID, warehouseResolver.resolveLogisticsId(FACTORY_ID));
        assertEquals(WH_WKS_ID, warehouseResolver.resolveWorkshopId(FACTORY_ID));
        assertEquals("wh-rd-uuid-001", warehouseResolver.resolveRdId(FACTORY_ID));

        // 回退路径不查默认仓的 id 有效性 (只有配置存在时才校验)
        verify(factoryWarehouseRepository, never())
                .findByIdAndFactoryIdAndDeletedAtIsNull(anyString(), anyString());
    }

    /**
     * (b) 有配置且指向有效仓库 → resolver 返回配置的仓库 (不走硬编码)。
     */
    @Test
    @DisplayName("(b) 有配置 (指向有效仓库) → resolver 返回配置仓库")
    void withConfig_returnsConfiguredWarehouse() {
        FactoryWarehouseDefault config = new FactoryWarehouseDefault();
        config.setFactoryId(FACTORY_ID);
        config.setPurpose(WarehouseDefaultPurpose.LOGISTICS_DEFAULT);
        config.setWarehouseId(CONFIGURED_WH_ID);

        FactoryWarehouse custom = new FactoryWarehouse();
        custom.setId(CONFIGURED_WH_ID);
        custom.setFactoryId(FACTORY_ID);
        custom.setCode("WH-MAIN");
        custom.setType(WarehouseType.RAW);

        when(factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.LOGISTICS_DEFAULT))
                .thenReturn(Optional.of(config));
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(CONFIGURED_WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(custom));

        String id = warehouseResolver.resolveLogisticsId(FACTORY_ID);

        assertEquals(CONFIGURED_WH_ID, id);
        // 配置命中 → 不回退硬编码 code 查询
        verify(factoryWarehouseRepository, never())
                .findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG);
    }

    /**
     * (c) 悬空配置 (指向已软删/不存在的仓库) → resolver 回退硬编码, 不抛异常。
     */
    @Test
    @DisplayName("(c) 悬空配置 (仓库已删除) → 回退硬编码, 不报错")
    void danglingConfig_fallsBackToHardcoded() {
        FactoryWarehouseDefault config = new FactoryWarehouseDefault();
        config.setFactoryId(FACTORY_ID);
        config.setPurpose(WarehouseDefaultPurpose.WORKSHOP_DEFAULT);
        config.setWarehouseId("wh-deleted-uuid-999");

        when(factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.WORKSHOP_DEFAULT))
                .thenReturn(Optional.of(config));
        // 指向的仓库已软删 / 不存在
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("wh-deleted-uuid-999", FACTORY_ID))
                .thenReturn(Optional.empty());
        // 回退硬编码 WH-WKS
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_WKS))
                .thenReturn(Optional.of(whWks));

        String id = warehouseResolver.resolveWorkshopId(FACTORY_ID);

        assertEquals(WH_WKS_ID, id, "悬空配置应回退到硬编码 WH-WKS, 不抛异常");
    }

    // ==================== 用途拆分 resolver (2026-07-02, warehouse-purpose-split Part A) ====================
    // resolvePurchaseInboundWh / resolveSalesOutboundWh / resolveProductionRawWh 全部 fallback WH-LOG。
    // 无配置 = 现状 (WH-LOG); 有配置 = 配置仓; 悬空配置 = 回退 WH-LOG。

    /**
     * (Split-a) 向后兼容 — 最重要: 三个新 resolver 无配置全回退硬编码 WH-LOG (= 拆分前 resolveLogisticsId 行为)。
     */
    @Test
    @DisplayName("(Split-a) 无配置 → 采购/销售/生产领料三 resolver 全回退 WH-LOG (向后兼容)")
    void splitResolvers_noConfig_fallBackToWhLog() {
        // 未 stub factoryWarehouseDefaultRepository → Optional.empty() (无配置)
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.of(whLog));

        assertEquals(WH_LOG_ID, warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID));
        assertEquals(WH_LOG_ID, warehouseResolver.resolveSalesOutboundWh(FACTORY_ID));
        assertEquals(WH_LOG_ID, warehouseResolver.resolveProductionRawWh(FACTORY_ID));

        // 回退路径不校验默认仓 id 有效性
        verify(factoryWarehouseRepository, never())
                .findByIdAndFactoryIdAndDeletedAtIsNull(anyString(), anyString());
    }

    /**
     * (Split-b) 有配置且指向有效仓库 → resolver 返回配置的仓库 (不走硬编码 WH-LOG)。
     * 以 PURCHASE_INBOUND_DEFAULT 为代表 (三个 resolver 共用 resolveConfiguredWarehouseId 逻辑)。
     */
    @Test
    @DisplayName("(Split-b) 采购入库配置 (指向有效仓) → 返回配置仓, 不回退 WH-LOG")
    void resolvePurchaseInboundWh_withConfig_returnsConfiguredWarehouse() {
        FactoryWarehouseDefault config = new FactoryWarehouseDefault();
        config.setFactoryId(FACTORY_ID);
        config.setPurpose(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT);
        config.setWarehouseId(CONFIGURED_WH_ID);

        FactoryWarehouse custom = new FactoryWarehouse();
        custom.setId(CONFIGURED_WH_ID);
        custom.setFactoryId(FACTORY_ID);
        custom.setCode("WH-MAIN");
        custom.setType(WarehouseType.RAW);

        when(factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT))
                .thenReturn(Optional.of(config));
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(CONFIGURED_WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(custom));

        assertEquals(CONFIGURED_WH_ID, warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID));
        // 配置命中 → 不回退硬编码 WH-LOG
        verify(factoryWarehouseRepository, never())
                .findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG);
    }

    /**
     * (Split-c) 悬空配置 (仓库已软删/不存在) → 回退硬编码 WH-LOG, 不抛异常。
     * 以 SALES_OUTBOUND_DEFAULT 为代表。
     */
    @Test
    @DisplayName("(Split-c) 销售出货悬空配置 → 回退 WH-LOG, 不报错")
    void resolveSalesOutboundWh_danglingConfig_fallsBackToWhLog() {
        FactoryWarehouseDefault config = new FactoryWarehouseDefault();
        config.setFactoryId(FACTORY_ID);
        config.setPurpose(WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT);
        config.setWarehouseId("wh-deleted-uuid-888");

        when(factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT))
                .thenReturn(Optional.of(config));
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("wh-deleted-uuid-888", FACTORY_ID))
                .thenReturn(Optional.empty());
        when(factoryWarehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(FACTORY_ID, WarehouseCodes.WH_LOG))
                .thenReturn(Optional.of(whLog));

        assertEquals(WH_LOG_ID, warehouseResolver.resolveSalesOutboundWh(FACTORY_ID),
                "悬空配置应回退到硬编码 WH-LOG, 不抛异常");
    }

    /**
     * (Split-d) resolveProductionRawWh 有配置 → 返回配置仓 (报工 gate 用)。
     */
    @Test
    @DisplayName("(Split-d) 生产领料配置 (指向有效仓) → 返回配置仓")
    void resolveProductionRawWh_withConfig_returnsConfiguredWarehouse() {
        FactoryWarehouseDefault config = new FactoryWarehouseDefault();
        config.setFactoryId(FACTORY_ID);
        config.setPurpose(WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT);
        config.setWarehouseId(CONFIGURED_WH_ID);

        FactoryWarehouse custom = new FactoryWarehouse();
        custom.setId(CONFIGURED_WH_ID);
        custom.setFactoryId(FACTORY_ID);
        custom.setCode("WH-RAW");
        custom.setType(WarehouseType.RAW);

        when(factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(FACTORY_ID, WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT))
                .thenReturn(Optional.of(config));
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(CONFIGURED_WH_ID, FACTORY_ID))
                .thenReturn(Optional.of(custom));

        assertEquals(CONFIGURED_WH_ID, warehouseResolver.resolveProductionRawWh(FACTORY_ID));
    }

    // ==================== isRawOrLogisticsWarehouse (报工原料来源仓校验) ====================

    @Test
    @DisplayName("isRawOrLogisticsWarehouse: RAW 类型仓 → true")
    void isRawOrLogisticsWarehouse_rawType_returnsTrue() {
        FactoryWarehouse raw = new FactoryWarehouse();
        raw.setId("wh-raw-1");
        raw.setFactoryId(FACTORY_ID);
        raw.setType(WarehouseType.RAW);
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("wh-raw-1", FACTORY_ID))
                .thenReturn(Optional.of(raw));

        assertTrue(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, "wh-raw-1"));
    }

    @Test
    @DisplayName("isRawOrLogisticsWarehouse: LOGISTICS 类型仓 → true")
    void isRawOrLogisticsWarehouse_logisticsType_returnsTrue() {
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(WH_LOG_ID, FACTORY_ID))
                .thenReturn(Optional.of(whLog));

        assertTrue(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, WH_LOG_ID));
    }

    @Test
    @DisplayName("isRawOrLogisticsWarehouse: WORKSHOP 类型仓 → false (报工不能从车间仓领原料)")
    void isRawOrLogisticsWarehouse_workshopType_returnsFalse() {
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(WH_WKS_ID, FACTORY_ID))
                .thenReturn(Optional.of(whWks));

        assertFalse(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, WH_WKS_ID));
    }

    @Test
    @DisplayName("isRawOrLogisticsWarehouse: null / blank / 不存在(跨工厂/已删) → false (诚实-null)")
    void isRawOrLogisticsWarehouse_nullBlankOrMissing_returnsFalse() {
        assertFalse(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, null));
        assertFalse(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, "  "));

        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("wh-gone", FACTORY_ID))
                .thenReturn(Optional.empty());
        assertFalse(warehouseResolver.isRawOrLogisticsWarehouse(FACTORY_ID, "wh-gone"));
    }
}
