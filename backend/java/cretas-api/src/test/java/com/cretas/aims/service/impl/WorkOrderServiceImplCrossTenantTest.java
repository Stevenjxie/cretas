package com.cretas.aims.service.impl;

import com.cretas.aims.entity.WorkOrder;
import com.cretas.aims.repository.WorkOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 round5 多租户隔离: 工单 7 个变更方法 (update/updateStatus/assign/start/complete/cancel/delete)
 * 改用 findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId) → 跨租户 (别家工厂工单) 返空 → 404,
 * 此前用 findById(id) 不校验 → F006 用户可改/分配/启动/完工/取消/删除别家工厂的工单。
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplCrossTenantTest {

    @Mock private WorkOrderRepository workOrderRepository;

    @InjectMocks
    private WorkOrderServiceImpl service;

    @Test
    void mutations_crossTenant_notFound_noSave() {
        // 跨租户: 按 (id, factoryId) 查不到别家工厂的工单 → empty → orElseThrow
        when(workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WO-1", "F006"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startWorkOrder("F006", "WO-1", 1L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.completeWorkOrder("F006", "WO-1", 1L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.cancelWorkOrder("F006", "WO-1", "r", 1L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.assignWorkOrder("F006", "WO-1", 9L, 1L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.deleteWorkOrder("F006", "WO-1")).isInstanceOf(RuntimeException.class);
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void mutation_sameFactory_proceeds() {
        WorkOrder wo = new WorkOrder();
        wo.setId("WO-2");
        wo.setFactoryId("F006");
        when(workOrderRepository.findByIdAndFactoryIdAndDeletedAtIsNull("WO-2", "F006"))
                .thenReturn(Optional.of(wo));
        when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(i -> i.getArgument(0));

        service.startWorkOrder("F006", "WO-2", 1L);
        verify(workOrderRepository).save(any(WorkOrder.class));
        assertThat(wo.getStatus()).isEqualTo("IN_PROGRESS");
    }
}
