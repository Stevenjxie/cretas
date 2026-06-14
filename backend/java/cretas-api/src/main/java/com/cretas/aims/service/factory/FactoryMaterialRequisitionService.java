package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * 工厂物料需求单 Service (P0-5, W2-3).
 *
 * <p>核心流程: 排产 → 按 BOM 展开 → 备料 → 调拨 → 签收 → 生产 → 关单退料.</p>
 */
public interface FactoryMaterialRequisitionService {

    /** 按生产计划 BOM 自动展开生成物料需求单. */
    FactoryMaterialRequisition generateFromPlan(String factoryId, String productionPlanId, Long requestedBy);

    /** 详情 (含明细). */
    FactoryMaterialRequisition getById(String factoryId, String id);

    /** 分页列表, status 可为 null. */
    Page<FactoryMaterialRequisition> list(String factoryId, Status status, Pageable pageable);

    /** 按计划反查. */
    List<FactoryMaterialRequisition> listByPlan(String factoryId, String productionPlanId);

    /** 开始备料. */
    FactoryMaterialRequisition startPicking(String factoryId, String id, Long operatorId);

    /**
     * 确认备料 — items: [{itemId, pickedQty, batchNumbers:[{batchNo,qty},..]}].
     */
    FactoryMaterialRequisition confirmPicking(String factoryId, String id, Long operatorId, List<Map<String, Object>> items);

    /** 调拨到工厂仓. */
    FactoryMaterialRequisition transferToFactory(String factoryId, String id, Long operatorId);

    /** 工厂签收. */
    FactoryMaterialRequisition receive(String factoryId, String id, Long operatorId);

    /** 关单 + 自动退料 (returned = issued - consumed). */
    FactoryMaterialRequisition close(String factoryId, String id, Long operatorId);

    /** 关单 + 人工损耗确认: closeItems: [{itemId, wastageQty}]. */
    FactoryMaterialRequisition close(String factoryId, String id, Long operatorId, List<Map<String, Object>> closeItems);

    /** 取消. */
    FactoryMaterialRequisition cancel(String factoryId, String id, Long operatorId, String reason);
}
