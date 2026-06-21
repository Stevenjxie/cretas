package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报废记录服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface IDisposalRecordService {

    /**
     * 创建报废记录
     *
     * @param record 报废记录实体
     * @return 创建的报废记录
     */
    DisposalRecord createDisposalRecord(DisposalRecord record);

    /**
     * 审批报废记录 (通过 → APPROVED, 触发库存扣减)。
     *
     * <p>F-BUG-4: 审批人由 controller 从 token 取。F-BUG-5: 强制财务角色。
     *
     * @param factoryId 工厂ID (多租户隔离)
     * @param id 记录ID
     * @param approverId 审批人ID (token)
     * @param approverName 审批人姓名 (token)
     * @param requestRole 调用方角色码 (token, 用于财务角色守卫)
     * @return 审批后的记录
     */
    DisposalRecord approveDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String requestRole);

    /**
     * 拒绝报废记录 (→ REJECTED, 不扣减库存)。
     *
     * <p>F-BUG-2: 新增 reject 路径 (此前 "拒绝" 错误走 approve)。
     *
     * @param factoryId 工厂ID (多租户隔离)
     * @param id 记录ID
     * @param approverId 审批人ID (token)
     * @param approverName 审批人姓名 (token)
     * @param reason 拒绝原因
     * @param requestRole 调用方角色码 (token, 用于财务角色守卫)
     * @return 拒绝后的记录
     */
    DisposalRecord rejectDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String reason, String requestRole);

    /**
     * 根据ID获取报废记录 (按 factoryId 过滤, 多租户隔离)
     *
     * @param factoryId 工厂ID
     * @param id 记录ID
     * @return 报废记录（可选, 跨租户返空）
     */
    Optional<DisposalRecord> getById(String factoryId, Long id);

    /**
     * 分页查询工厂报废记录
     *
     * @param factoryId 工厂ID
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    Page<DisposalRecord> getByFactoryId(String factoryId, int page, int size);

    /**
     * 按报废类型分页查询
     *
     * @param factoryId 工厂ID
     * @param disposalType 报废类型
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    Page<DisposalRecord> getByFactoryIdAndType(String factoryId, String disposalType, int page, int size);

    /**
     * 获取待审批的报废记录
     *
     * @param factoryId 工厂ID
     * @return 待审批记录列表
     */
    List<DisposalRecord> getPendingApprovals(String factoryId);

    /**
     * 按日期范围查询
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 报废记录列表
     */
    List<DisposalRecord> getByDateRange(String factoryId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 获取报废统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 统计数据Map
     */
    Map<String, Object> getDisposalStats(String factoryId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 按类型统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 分类统计结果
     */
    List<Object[]> getStatsByType(String factoryId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 获取可回收报废记录
     *
     * @param factoryId 工厂ID
     * @return 可回收记录列表
     */
    List<DisposalRecord> getRecyclableDisposals(String factoryId);

    /**
     * 更新报废记录
     *
     * @param id 记录ID
     * @param updateData 更新数据
     * @return 更新后的记录
     */
    DisposalRecord updateDisposalRecord(String factoryId, Long id, DisposalRecord updateData);

    /**
     * 删除报废记录（软删除）
     *
     * @param factoryId 工厂ID
     * @param id 记录ID
     */
    void deleteDisposalRecord(String factoryId, Long id);

    /**
     * SP12 §5.3: 提交报废记录至审批工作流。
     *
     * <p>前置条件: 记录存在且 {@code isApproved == false}，且未已在流程中
     * ({@code workflowInstanceId == null})。违反时抛 409。
     *
     * <p>若工作流引擎不可用 (optional bean)，仍将 {@code workflowInstanceId} 置 null
     * 并标记已申请（由外部流程兜底），不抛异常。
     *
     * @param id 报废记录 ID
     * @param factoryId 工厂 ID（用于启动工作流）
     * @param userId 提交人 userId
     * @return 工作流实例 ID，若引擎不可用则返回 null
     */
    String submitForApproval(Long id, String factoryId, Long userId);
}
