package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@SuppressWarnings("unused")

/**
 * 仓库库存守卫服务 (SP7 §3.3 — 生产仓库类型约束).
 *
 * <p>规则:
 * <ul>
 *   <li>WIP 仓: 只接受 半成品 (SEMI_FINISHED) 物料移入</li>
 *   <li>RAW 仓: 只接受 原料 (RAW_MATERIAL) 物料移入</li>
 *   <li>SALTED 仓: 只接受 原料 (RAW_MATERIAL) 物料 (SP7 §3.1)</li>
 *   <li>FINISHED 仓: 只接受 成品 (FINISHED_GOODS) 移入</li>
 * </ul>
 *
 * <p>注意: ⚠️ 不碰 SemiFinishedInventoryTransaction (SP2 scope-lock)。
 * 本服务只做校验，不修改 SP2 管理的实体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseInventoryGuardService {

    private final FactoryWarehouseRepository warehouseRepo;

    /**
     * 校验给定的入库类型是否允许移入目标仓库类型。
     * 不通过时抛 BusinessException 422，附带 actionHint 指引。
     *
     * <p>例: WIP 仓只允许 inboundType=SEMI_FINISHED；RAW/SALTED 仓只允许 inboundType=RAW。
     *
     * @param warehouseId 目标仓库 ID
     * @param factoryId   工厂 ID
     * @param inboundCategory 入库物料大类 ("RAW", "SEMI_FINISHED", "FINISHED") — null 则跳过校验
     */
    public void assertCanReceive(String warehouseId, String factoryId, String inboundCategory) {
        if (inboundCategory == null) return;

        Optional<FactoryWarehouse> warehouseOpt =
                warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId);
        if (warehouseOpt.isEmpty()) {
            // 仓库不存在或不属于该工厂 — 放行让上层业务返回 404
            return;
        }

        FactoryWarehouse warehouse = warehouseOpt.get();
        FactoryWarehouse.WarehouseType type = warehouse.getType();
        if (type == null) return;

        String warehouseName = warehouse.getName() != null ? warehouse.getName() : type.name();
        switch (type) {
            case WIP -> {
                if (!"SEMI_FINISHED".equals(inboundCategory)) {
                    throw new BusinessException(422, warehouseName + "(WIP仓)只接受半成品入库，不允许入: " + inboundCategory)
                            .withCode("WAREHOUSE_TYPE_MISMATCH")
                            .withHint("请选择正确的仓库或检查物料类型");
                }
            }
            case FINISHED -> {
                if (!"FINISHED".equals(inboundCategory)) {
                    throw new BusinessException(422, warehouseName + "(成品仓)只接受成品入库，不允许入: " + inboundCategory)
                            .withCode("WAREHOUSE_TYPE_MISMATCH")
                            .withHint("请选择正确的仓库或检查物料类型");
                }
            }
            case RAW, SALTED -> {
                if (!"RAW".equals(inboundCategory)) {
                    String label = (type == FactoryWarehouse.WarehouseType.SALTED) ? "盐化仓" : "原料仓";
                    throw new BusinessException(422, warehouseName + "(" + label + ")只接受原料入库，不允许入: " + inboundCategory)
                            .withCode("WAREHOUSE_TYPE_MISMATCH")
                            .withHint("请选择正确的仓库或检查物料类型");
                }
            }
            default -> {
                // TEMP, QC, LINESIDE, RETURNS, OUTSOURCE, SCRAP, TRANSFER 不做类型约束
            }
        }
    }

    /**
     * 校验仓库是否属于允许盘点的类型 (SP7 §5.1).
     * 只有实物仓库才能发起盘点（排除 TRANSFER 在途仓等虚拟仓）。
     */
    public void assertCanStocktake(String warehouseId, String factoryId) {
        Optional<FactoryWarehouse> warehouseOpt =
                warehouseRepo.findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId);
        if (warehouseOpt.isEmpty()) {
            throw new BusinessException(404, "仓库不存在: " + warehouseId);
        }
        FactoryWarehouse warehouse = warehouseOpt.get();
        FactoryWarehouse.WarehouseType type = warehouse.getType();
        if (type == FactoryWarehouse.WarehouseType.TRANSFER) {
            throw new BusinessException(422, "调拨在途仓不支持盘点")
                    .withHint("在途仓库由系统自动管理，无法人工盘点");
        }
    }

}
