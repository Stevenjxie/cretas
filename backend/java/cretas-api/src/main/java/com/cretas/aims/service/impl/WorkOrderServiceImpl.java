package com.cretas.aims.service.impl;

import com.cretas.aims.entity.WorkOrder;
import com.cretas.aims.repository.WorkOrderRepository;
import com.cretas.aims.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工单服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderRepository workOrderRepository;

    @Override
    public Page<WorkOrder> getWorkOrders(String factoryId, Pageable pageable) {
        return workOrderRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public Page<WorkOrder> getWorkOrdersByStatus(String factoryId, String status, Pageable pageable) {
        return workOrderRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
    }

    @Override
    public Optional<WorkOrder> getWorkOrderById(String factoryId, String id) {
        return workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
    }

    @Override
    public Optional<WorkOrder> getByOrderNumber(String factoryId, String orderNumber) {
        return workOrderRepository.findByOrderNumberAndDeletedAtIsNull(orderNumber)
                .filter(wo -> factoryId.equals(wo.getFactoryId()));
    }

    @Override
    @Deprecated
    public Optional<WorkOrder> getByOrderNumber(String orderNumber) {
        return workOrderRepository.findByOrderNumberAndDeletedAtIsNull(orderNumber);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder createWorkOrder(WorkOrder workOrder) {
        if (workOrder.getId() == null || workOrder.getId().isEmpty()) {
            workOrder.setId(UUID.randomUUID().toString());
        }
        if (workOrder.getOrderNumber() == null || workOrder.getOrderNumber().isEmpty()) {
            workOrder.setOrderNumber(generateOrderNumber(workOrder.getFactoryId()));
        }
        if (workOrder.getStatus() == null) {
            workOrder.setStatus("PENDING");
        }
        if (workOrder.getProgress() == null) {
            workOrder.setProgress(0);
        }
        log.info("创建工单: factoryId={}, orderNumber={}",
                workOrder.getFactoryId(), workOrder.getOrderNumber());
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder updateWorkOrder(String factoryId, String id, WorkOrder updateData) {
        WorkOrder existing = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));

        if (updateData.getTitle() != null) existing.setTitle(updateData.getTitle());
        if (updateData.getDescription() != null) existing.setDescription(updateData.getDescription());
        if (updateData.getPriority() != null) existing.setPriority(updateData.getPriority());
        if (updateData.getPlannedStartTime() != null) existing.setPlannedStartTime(updateData.getPlannedStartTime());
        if (updateData.getPlannedEndTime() != null) existing.setPlannedEndTime(updateData.getPlannedEndTime());
        if (updateData.getEstimatedHours() != null) existing.setEstimatedHours(updateData.getEstimatedHours());
        if (updateData.getRemarks() != null) existing.setRemarks(updateData.getRemarks());
        if (updateData.getUpdatedBy() != null) existing.setUpdatedBy(updateData.getUpdatedBy());

        return workOrderRepository.save(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder updateStatus(String factoryId, String id, String status, Long updatedBy) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setStatus(status);
        workOrder.setUpdatedBy(updatedBy);
        log.info("更新工单状态: id={}, status={}", id, status);
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder assignWorkOrder(String factoryId, String id, Long assignedTo, Long updatedBy) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setAssignedTo(assignedTo);
        workOrder.setUpdatedBy(updatedBy);
        log.info("分配工单: id={}, assignedTo={}", id, assignedTo);
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder startWorkOrder(String factoryId, String id, Long updatedBy) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setStatus("IN_PROGRESS");
        workOrder.setActualStartTime(LocalDateTime.now());
        workOrder.setUpdatedBy(updatedBy);
        log.info("开始工单: id={}", id);
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder completeWorkOrder(String factoryId, String id, Long updatedBy) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setStatus("COMPLETED");
        workOrder.setActualEndTime(LocalDateTime.now());
        workOrder.setProgress(100);
        workOrder.setUpdatedBy(updatedBy);

        // 计算实际工时
        if (workOrder.getActualStartTime() != null) {
            long hours = java.time.Duration.between(
                    workOrder.getActualStartTime(),
                    workOrder.getActualEndTime()
            ).toHours();
            workOrder.setActualHours(java.math.BigDecimal.valueOf(hours));
        }

        log.info("完成工单: id={}", id);
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder cancelWorkOrder(String factoryId, String id, String reason, Long updatedBy) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setStatus("CANCELLED");
        workOrder.setRemarks((workOrder.getRemarks() != null ? workOrder.getRemarks() + "\n" : "")
                + "取消原因: " + reason);
        workOrder.setUpdatedBy(updatedBy);
        log.info("取消工单: id={}, reason={}", id, reason);
        return workOrderRepository.save(workOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrder(String factoryId, String id) {
        WorkOrder workOrder = workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new RuntimeException("工单不存在: " + id));
        workOrder.setDeletedAt(LocalDateTime.now());
        workOrderRepository.save(workOrder);
        log.info("删除工单: id={}", id);
    }

    @Override
    public Page<WorkOrder> getWorkOrdersByAssignee(String factoryId, Long assignedTo, Pageable pageable) {
        return workOrderRepository.findByFactoryIdAndAssignedToAndDeletedAtIsNull(
                factoryId, assignedTo, pageable);
    }

    @Override
    public Page<WorkOrder> getWorkOrdersByDepartment(String factoryId, String departmentId, Pageable pageable) {
        return workOrderRepository.findByFactoryIdAndDepartmentIdAndDeletedAtIsNull(
                factoryId, departmentId, pageable);
    }

    @Override
    public List<WorkOrder> getWorkOrdersByBatch(Long productionBatchId) {
        return workOrderRepository.findByProductionBatchIdAndDeletedAtIsNull(productionBatchId);
    }

    @Override
    public List<WorkOrder> getOverdueWorkOrders(String factoryId) {
        return workOrderRepository.findOverdueWorkOrders(factoryId, LocalDateTime.now());
    }

    @Override
    public long countByFactory(String factoryId) {
        return workOrderRepository.countByFactoryIdAndDeletedAtIsNull(factoryId);
    }

    @Override
    public long countByStatus(String factoryId, String status) {
        return workOrderRepository.countByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status);
    }

    /**
     * 生成工单编号
     */
    private String generateOrderNumber(String factoryId) {
        String dateStr = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = String.format("%04d", (int) (Math.random() * 10000));
        return "WO-" + factoryId + "-" + dateStr + "-" + seq;
    }
}
