package com.cretas.aims.service.factory;

import com.cretas.aims.dto.factory.CreateSaltedDeductionRequest;
import com.cretas.aims.dto.factory.SaltedReportDTO;
import com.cretas.aims.entity.factory.SaltedWarehouseDeduction;

import java.time.LocalDate;
import java.util.List;

/**
 * 盐化仓独立扣量服务接口 (SP7 F11).
 *
 * <p>核心约束:
 * <ul>
 *   <li>只允许对 type=SALTED 仓库操作</li>
 *   <li>扣量同步减 MaterialBatch.usedQuantity (复用现有扣量基建)</li>
 *   <li>不破坏通用仓 — 现有盐化走通用仓的兜底路径保留</li>
 *   <li>幂等: 同 orderNumber 重复提交返 409 + existingId</li>
 * </ul>
 */
public interface SaltedWarehouseService {

    /**
     * 创建盐化仓扣量记录并同步扣减 MaterialBatch 库存。
     *
     * @param factoryId 工厂 ID
     * @param req       请求 DTO
     * @param operatorId 操作人 user_id
     * @return 创建的扣量记录
     */
    SaltedWarehouseDeduction createDeduction(String factoryId,
                                             CreateSaltedDeductionRequest req,
                                             Long operatorId);

    /**
     * 查询盐化仓报表 (独立口径，不混入销售报表).
     *
     * @param factoryId   工厂 ID
     * @param warehouseId 盐化仓 ID (null = 工厂下所有盐化仓)
     * @param startDate   开始日期
     * @param endDate     结束日期
     * @return 报表 DTO
     */
    SaltedReportDTO getReport(String factoryId,
                              String warehouseId,
                              LocalDate startDate,
                              LocalDate endDate);

    /**
     * 查询某盐化仓的扣量记录列表 (分页由 caller 传 Pageable — 此简版返回 List).
     *
     * @param factoryId   工厂 ID
     * @param warehouseId 盐化仓 ID
     * @param startDate   开始日期
     * @param endDate     结束日期
     */
    List<SaltedWarehouseDeduction> listDeductions(String factoryId,
                                                  String warehouseId,
                                                  LocalDate startDate,
                                                  LocalDate endDate);
}
