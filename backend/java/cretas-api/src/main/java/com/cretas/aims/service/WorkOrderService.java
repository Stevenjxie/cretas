package com.cretas.aims.service;

import com.cretas.aims.entity.WorkOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工单服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
public interface WorkOrderService {

    /**
     * 分页查询工单列表
     */
    Page<WorkOrder> getWorkOrders(String factoryId, Pageable pageable);

    /**
     * 根据状态分页查询
     */
    Page<WorkOrder> getWorkOrdersByStatus(String factoryId, String status, Pageable pageable);

    /**
     * 获取单个工单详情
     */
    Optional<WorkOrder> getWorkOrderById(String factoryId, String id);

    /**
     * 根据工单编号查询 (内部业务, 带 factoryId 校验, P0-1 MEDIUM fix).
     */
    Optional<WorkOrder> getByOrderNumber(String factoryId, String orderNumber);

    /**
     * @deprecated 使用 {@link #getByOrderNumber(String, String)} 传 factoryId.
     */
    @Deprecated
    Optional<WorkOrder> getByOrderNumber(String orderNumber);

    /**
     * 创建工单
     */
    WorkOrder createWorkOrder(WorkOrder workOrder);

    /**
     * 更新工单
     */
    WorkOrder updateWorkOrder(String factoryId, String id, WorkOrder updateData);

    /**
     * 更新工单状态
     */
    WorkOrder updateStatus(String factoryId, String id, String status, Long updatedBy);

    /**
     * 分配工单给用户
     */
    WorkOrder assignWorkOrder(String factoryId, String id, Long assignedTo, Long updatedBy);

    /**
     * 开始工单
     */
    WorkOrder startWorkOrder(String factoryId, String id, Long updatedBy);

    /**
     * 完成工单
     */
    WorkOrder completeWorkOrder(String factoryId, String id, Long updatedBy);

    /**
     * 取消工单
     */
    WorkOrder cancelWorkOrder(String factoryId, String id, String reason, Long updatedBy);

    /**
     * 删除工单（软删除）
     */
    void deleteWorkOrder(String factoryId, String id);

    /**
     * 查询用户的工单
     */
    Page<WorkOrder> getWorkOrdersByAssignee(String factoryId, Long assignedTo, Pageable pageable);

    /**
     * 查询部门的工单
     */
    Page<WorkOrder> getWorkOrdersByDepartment(String factoryId, String departmentId, Pageable pageable);

    /**
     * 查询生产批次关联的工单
     */
    List<WorkOrder> getWorkOrdersByBatch(Long productionBatchId);

    /**
     * 查询逾期工单
     */
    List<WorkOrder> getOverdueWorkOrders(String factoryId);

    /**
     * 统计工单数量
     */
    long countByFactory(String factoryId);

    /**
     * 统计特定状态的工单数量
     */
    long countByStatus(String factoryId, String status);
}
