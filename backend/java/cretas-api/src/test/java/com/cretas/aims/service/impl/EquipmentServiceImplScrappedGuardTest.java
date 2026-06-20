package com.cretas.aims.service.impl;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.EquipmentMaintenanceRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.UserRepository;
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
 * 审计 round2: 报废(scrapped)终态设备防呆 —— 报废设备不可启动/停止/经通用状态接口 un-scrap。
 * scrapEquipment 是唯一进入 scrapped 的入口; 退出需走专门恢复流程。
 */
@ExtendWith(MockitoExtension.class)
class EquipmentServiceImplScrappedGuardTest {

    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentMaintenanceRepository maintenanceRepository;
    @Mock private BatchEquipmentUsageRepository usageRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private EquipmentServiceImpl service;

    private FactoryEquipment eq(long id, String status) {
        FactoryEquipment e = new FactoryEquipment();
        e.setId(id);
        e.setFactoryId("F006");
        e.setStatus(status);
        return e;
    }

    @Test
    void startEquipment_onScrapped_throws() {
        when(equipmentRepository.findByIdAndFactoryId(1L, "F006"))
                .thenReturn(Optional.of(eq(1L, "scrapped")));

        assertThatThrownBy(() -> service.startEquipment("F006", "1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("报废"));
        verify(equipmentRepository, never()).save(any());
    }

    @Test
    void stopEquipment_onScrapped_throws() {
        when(equipmentRepository.findByIdAndFactoryId(2L, "F006"))
                .thenReturn(Optional.of(eq(2L, "scrapped")));

        assertThatThrownBy(() -> service.stopEquipment("F006", "2", 3))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("报废"));
        verify(equipmentRepository, never()).save(any());
    }

    @Test
    void updateEquipmentStatus_unScrap_throws() {
        when(equipmentRepository.findByIdAndFactoryId(3L, "F006"))
                .thenReturn(Optional.of(eq(3L, "scrapped")));

        assertThatThrownBy(() -> service.updateEquipmentStatus("F006", "3", "active"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessage()).contains("报废"));
        verify(equipmentRepository, never()).save(any());
    }

    @Test
    void updateEquipmentStatus_idempotentScrapToScrap_allowed() {
        when(equipmentRepository.findByIdAndFactoryId(4L, "F006"))
                .thenReturn(Optional.of(eq(4L, "scrapped")));
        when(equipmentRepository.save(any(FactoryEquipment.class))).thenAnswer(i -> i.getArgument(0));

        // scrapped→scrapped 不应被拦 (幂等)
        service.updateEquipmentStatus("F006", "4", "scrapped");
        verify(equipmentRepository).save(any(FactoryEquipment.class));
    }

    @Test
    void startEquipment_onActive_passesGuard() {
        when(equipmentRepository.findByIdAndFactoryId(5L, "F006"))
                .thenReturn(Optional.of(eq(5L, "active")));
        when(equipmentRepository.save(any(FactoryEquipment.class))).thenAnswer(i -> i.getArgument(0));

        // active 设备启动正常 (守卫放行)
        service.startEquipment("F006", "5");
        verify(equipmentRepository).save(any(FactoryEquipment.class));
    }
}
