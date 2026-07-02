package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * D1 双仓流转 — warehouse code → warehouse_id 解析器 (2026-05-10 spec, PR #309 A1=A).
 *
 * <p>把逻辑 code (WH-LOG / WH-WKS / WH-RD) 解析为 {@code factory_warehouses.id} (UUID)。
 * 调用方传 code 表达业务意图; 这里转成 FK id 写入 batch。
 *
 * <p>FactoryWarehouse seed by V20260411_03 保证每个 factory 都有 WH-LOG + WH-WKS seed,
 * 所以默认 lookup 不应该 miss; miss 时抛 BusinessException (defensive)。
 *
 * <p><b>可配置默认仓 (V20261027_30)</b>: {@link #resolveLogisticsId}/{@link #resolveWorkshopId}/
 * {@link #resolveRdId} 先查 {@link FactoryWarehouseDefault} 覆盖配置; 有有效配置 → 用配置的仓库,
 * 否则回退到硬编码 code 查询 (向后兼容: 未配置的工厂行为与现状 100% 一致)。悬空配置
 * (指向已软删/不存在的仓库) 也回退, 不报错。参见 {@code WarehouseResolverTest}。
 */
@Service
@RequiredArgsConstructor
public class WarehouseResolver {

    private static final Logger log = LoggerFactory.getLogger(WarehouseResolver.class);

    private final FactoryWarehouseRepository factoryWarehouseRepository;
    private final FactoryWarehouseDefaultRepository factoryWarehouseDefaultRepository;

    /**
     * 解析 warehouse code → warehouse_id (UUID)。
     *
     * @param factoryId 工厂 ID
     * @param code      warehouse code (WH-LOG / WH-WKS / 其他)
     * @return factory_warehouses.id (UUID)
     * @throws BusinessException 当 factory 缺少对应 code 的 warehouse seed (defensive — 应由 V20260411_03 seed 保证)
     */
    public String resolveId(String factoryId, String code) {
        return factoryWarehouseRepository
                .findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, code)
                .map(FactoryWarehouse::getId)
                .orElseThrow(() -> new BusinessException(500,
                        String.format("Factory [%s] 缺少 warehouse seed [%s] — 数据库 seed 异常 (V20260411_03 未跑?)",
                                factoryId, code))
                        .withHint("请联系运维检查 factory_warehouses 表是否有该工厂的双仓 seed"));
    }

    /** 物流仓 (WH-LOG) id — 销售出货、原料持久库存默认仓。有配置覆盖则优先用配置。 */
    public String resolveLogisticsId(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.LOGISTICS_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_LOG));
    }

    /** 车间仓 (WH-WKS) id — 报工消耗、生产成品默认仓。有配置覆盖则优先用配置。 */
    public String resolveWorkshopId(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.WORKSHOP_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_WKS));
    }

    /**
     * 研发/中试库 (WH-RD) id — 试制批次 (is_trial=true) 产出专属仓库。
     * SP10 §RD-1, V20261023_01 seed。有配置覆盖则优先用配置。
     */
    public String resolveRdId(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.RD_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_RD));
    }

    // ==================== 用途拆分 resolver (2026-07-02, warehouse-purpose-split Part A) ====================
    // resolveLogisticsId 原本被采购入库 / 销售出货 / 生产报工三个冲突语义共用 → 拆成独立 resolver +
    // 独立 config purpose。三者 fallback 全部 WH-LOG = 现状, 未配置零行为变化 (向后兼容)。

    /**
     * 采购入库默认仓 id — 采购收货物化 raw MaterialBatch 的落点。
     * 有 {@link WarehouseDefaultPurpose#PURCHASE_INBOUND_DEFAULT} 配置则优先用配置, 否则回退 WH-LOG
     * (与拆分前 resolveLogisticsId 行为一致)。
     */
    public String resolvePurchaseInboundWh(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_LOG));
    }

    /**
     * 销售出货默认仓 id — 成品发货 FIFO / 可用批次默认来源仓 (per-row sourceWarehouseCode 为空时的默认)。
     * 有 {@link WarehouseDefaultPurpose#SALES_OUTBOUND_DEFAULT} 配置则优先用配置, 否则回退 WH-LOG
     * (与拆分前 resolveLogisticsId 行为一致)。
     */
    public String resolveSalesOutboundWh(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_LOG));
    }

    /**
     * 生产领料/报工原料默认仓 id — 逐道报工消耗 raw 的来源仓。
     * 有 {@link WarehouseDefaultPurpose#PRODUCTION_RAW_DEFAULT} 配置则优先用配置, 否则回退 WH-LOG
     * (与拆分前 resolveLogisticsId 行为一致)。
     */
    public String resolveProductionRawWh(String factoryId) {
        return resolveConfiguredWarehouseId(factoryId, WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT)
                .orElseGet(() -> resolveId(factoryId, WarehouseCodes.WH_LOG));
    }

    /**
     * 判断某仓库是否为原料仓 (RAW) 或物流仓 (LOGISTICS) 类型 — 生产报工原料来源仓校验用。
     *
     * <p>{@code ProcessSheetServiceImpl.ensureRawMaterialWarehouse} 对齐文案「原料仓/物流仓」: 除了
     * 配置的生产领料默认仓 ({@link #resolveProductionRawWh}) 外, 任何 RAW / LOGISTICS 类型的<b>有效
     * (未软删、同工厂)</b> 仓库也允许作为报工原料来源。
     *
     * <p>诚实-null: {@code warehouseId} 为 null/blank 或指向已删除/跨工厂仓库 → 返回 {@code false}
     * (调用方据此 loud-fail, 不静默放行)。
     *
     * @return true 当且仅当 warehouseId 指向本工厂未软删的 RAW/LOGISTICS 类型仓库。
     */
    public boolean isRawOrLogisticsWarehouse(String factoryId, String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return false;
        }
        return factoryWarehouseRepository
                .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                .map(FactoryWarehouse::getType)
                .filter(type -> type == FactoryWarehouse.WarehouseType.RAW
                        || type == FactoryWarehouse.WarehouseType.LOGISTICS)
                .isPresent();
    }

    /**
     * 判断某仓库是否为生产/车间仓 (WORKSHOP) 类型 — 生产报工原料来源仓校验用 (2026-07-03)。
     *
     * <p>生产领料把原料从原料仓迁到生产仓 (WH-WKS) 后, 报工从生产仓消耗是合法料流。因此
     * {@code ProcessSheetServiceImpl.ensureRawMaterialWarehouse} 在 Part B Gate 关闭 (宽松) 时,
     * 除 RAW/LOGISTICS 外也放行 WORKSHOP 类型仓库的批次。
     *
     * <p>诚实-null: warehouseId 为 null/blank 或指向已删除/跨工厂仓库 → 返回 {@code false}。
     */
    public boolean isWorkshopWarehouse(String factoryId, String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return false;
        }
        return factoryWarehouseRepository
                .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                .map(FactoryWarehouse::getType)
                .filter(type -> type == FactoryWarehouse.WarehouseType.WORKSHOP)
                .isPresent();
    }

    /**
     * 查 {@link FactoryWarehouseDefault} 覆盖配置, 并校验它仍指向同工厂有效 (未软删) 的
     * factory_warehouses 记录。
     *
     * @return 配置的 warehouse_id (若存在且有效); 否则 {@link Optional#empty()} → 调用方回退硬编码 code。
     */
    private Optional<String> resolveConfiguredWarehouseId(String factoryId, WarehouseDefaultPurpose purpose) {
        return factoryWarehouseDefaultRepository
                .findByFactoryIdAndPurposeAndDeletedAtIsNull(factoryId, purpose)
                .map(FactoryWarehouseDefault::getWarehouseId)
                .filter(warehouseId -> {
                    boolean live = factoryWarehouseRepository
                            .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                            .isPresent();
                    if (!live) {
                        // 悬空配置: 指向已软删/不存在/跨工厂的仓库 → 回退硬编码, 不报错。
                        log.warn("Factory [{}] purpose [{}] 默认仓配置指向失效仓库 [{}] — 回退硬编码默认仓",
                                factoryId, purpose, warehouseId);
                    }
                    return live;
                });
    }
}
