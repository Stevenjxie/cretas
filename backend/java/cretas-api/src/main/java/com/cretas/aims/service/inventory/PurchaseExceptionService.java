package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.inventory.PurchaseException;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购异常单 Service（SP6）
 *
 * <p>入库完成后由 PurchaseServiceImpl 调用 generateExceptionsForReceive(),
 * 后续采购员调用 decideException() 做 ACCEPT/RETURN 决策。
 */
public interface PurchaseExceptionService {

    /**
     * 按 receiveRecordId 生成异常单。
     * 超收（OVER_RECEIVE）或少收（UNDER_RECEIVE）各建一张异常单，status=PENDING。
     *
     * @param factoryId        工厂 ID
     * @param receiveRecordId  入库记录 ID
     * @param purchaseOrderId  采购订单 ID
     * @param supplierId       供应商 ID
     * @param materialTypeId   物料类型 ID
     * @param materialName     物料名称
     * @param poQuantity       PO 数量
     * @param receivedQuantity 实收数量
     * @param unit             单位
     * @param createdBy        创建人 userId
     * @return 创建的异常单列表（0 个或 1 个）
     */
    List<PurchaseException> generateExceptionsForReceive(
            String factoryId,
            String receiveRecordId,
            String purchaseOrderId,
            String supplierId,
            String materialTypeId,
            String materialName,
            BigDecimal poQuantity,
            BigDecimal receivedQuantity,
            String unit,
            Long createdBy);

    /**
     * 采购员对异常单做决策。
     *
     * <p>RETURN_OVER 分支：在独立事务（REQUIRES_NEW）中创建退货单，防止 doomed-tx 污染本事务。
     *
     * @param exceptionId   异常单 ID
     * @param factoryId     工厂 ID（隔离校验）
     * @param decision      决策类型
     * @param notes         备注说明
     * @param decisionBy    决策人 userId
     * @return 更新后的异常单
     */
    PurchaseException decideException(
            String exceptionId,
            String factoryId,
            ExceptionDecision decision,
            String notes,
            Long decisionBy);

    /**
     * 查询工厂异常单列表，可按 status 过滤。
     *
     * @param factoryId 工厂 ID
     * @param status    状态过滤（null = 全部）
     * @return 异常单列表，按创建时间倒序
     */
    List<PurchaseException> listExceptions(String factoryId, String status);
}
