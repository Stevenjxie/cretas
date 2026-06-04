package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.restaurant.WastageRecord;

import java.time.LocalDate;
import java.util.Map;

/**
 * 损耗管理服务接口
 *
 * @author Cretas Team
 * @since 2026-02-20
 */
public interface WastageRecordService {

    // ==================== 查询 ====================

    /**
     * 分页查询损耗记录（支持按日期范围、状态、类型筛选）
     */
    PageResponse<WastageRecord> getWastageRecords(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            WastageRecord.Status status,
            WastageRecord.WastageType type,
            int page, int size);

    /**
     * 查询损耗记录详情
     */
    WastageRecord getWastageRecordById(String factoryId, String wastageId);

    // ==================== 创建 ====================

    /**
     * 创建损耗记录
     */
    WastageRecord createWastageRecord(String factoryId, WastageRecord record, Long userId);

    // ==================== 审批流 ====================

    /**
     * 提交损耗记录（DRAFT -> SUBMITTED）
     */
    WastageRecord submitWastageRecord(String factoryId, String wastageId, Long userId);

    /**
     * 审批损耗记录（SUBMITTED -> APPROVED），自动扣减物料批次库存并记录成本
     */
    WastageRecord approveWastageRecord(String factoryId, String wastageId, Long approvedBy);

    // ==================== 统计 ====================

    /**
     * 损耗统计：按类型、时间范围汇总（数量 + 金额）
     *
     * @return {
     *   "byType": [{type, count, totalQuantity, totalCost}, ...],
     *   "byMaterial": [{rawMaterialTypeId, totalQuantity, totalCost}, ...],
     *   "totalCost": BigDecimal,
     *   "period": {startDate, endDate}
     * }
     */
    Map<String, Object> getStatistics(String factoryId, LocalDate startDate, LocalDate endDate);

    /**
     * 损耗责任制汇总：按责任人 + 档口聚合 APPROVED 损耗 (Wave2)。
     *
     * <p>兑现邓总诉求："今天损耗明天屌你" / "同样 1 万营业额哪个档口哪个人成本涨了"。
     * 回填责任人姓名 + 档口中文名。仅统计 APPROVED 记录。</p>
     *
     * <p>金额脱敏由 controller 端点的 {@code @RequirePermission} 整体门控
     * （procurement:price:view OR finance:read），无价权角色无法调用，故 service
     * 直接返回金额，与 {@link #getStatistics} 同 RBAC 模型。</p>
     */
    com.cretas.aims.dto.restaurant.WastageAccountability getAccountability(
            String factoryId, LocalDate startDate, LocalDate endDate);
}
