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
