package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D1 双仓流转 — warehouse code → warehouse_id 解析器 (2026-05-10 spec, PR #309 A1=A).
 *
 * <p>把逻辑 code (WH-LOG / WH-WKS) 解析为 {@code factory_warehouses.id} (UUID)。
 * 调用方传 code 表达业务意图; 这里转成 FK id 写入 batch。
 *
 * <p>FactoryWarehouse seed by V20260411_03 保证每个 factory 都有 WH-LOG + WH-WKS seed,
 * 所以默认 lookup 不应该 miss; miss 时抛 BusinessException (defensive)。
 */
@Service
@RequiredArgsConstructor
public class WarehouseResolver {

    private static final Logger log = LoggerFactory.getLogger(WarehouseResolver.class);

    private final FactoryWarehouseRepository factoryWarehouseRepository;

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

    /** 物流仓 (WH-LOG) id — 销售出货、原料持久库存默认仓。 */
    public String resolveLogisticsId(String factoryId) {
        return resolveId(factoryId, WarehouseCodes.WH_LOG);
    }

    /** 车间仓 (WH-WKS) id — 报工消耗、生产成品默认仓。 */
    public String resolveWorkshopId(String factoryId) {
        return resolveId(factoryId, WarehouseCodes.WH_WKS);
    }

    /**
     * 研发/中试库 (WH-RD) id — 试制批次 (is_trial=true) 产出专属仓库。
     * SP10 §RD-1, V20261023_01 seed。
     */
    public String resolveRdId(String factoryId) {
        return resolveId(factoryId, WarehouseCodes.WH_RD);
    }
}
