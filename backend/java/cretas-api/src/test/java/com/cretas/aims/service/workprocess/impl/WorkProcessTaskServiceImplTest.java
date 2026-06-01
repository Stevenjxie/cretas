package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * WorkProcessTaskServiceImpl 单元测试 (Phase D Task 0).
 *
 * 覆盖: toDTO 透出 standardYieldMin / standardYieldMax (A7 标准出成率区间)。
 * toDTO 为 private, 通过 public getById 间接验证。
 */
@DisplayName("WorkProcessTaskServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class WorkProcessTaskServiceImplTest {

    @Mock
    private WorkProcessTaskRepository taskRepository;

    @Mock
    private ProductWorkProcessRepository productWorkProcessRepository;

    @Mock
    private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private WorkProcessTaskServiceImpl service;

    private static final String FACTORY_ID = "F001";
    private static final String WP_ID = "wp-1";
    private static final Long TASK_ID = 100L;

    @Test
    @DisplayName("getById: definition 配了 standardYieldMin/Max → DTO 透出区间")
    void getById_exposesStandardYieldRange() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("炸制")
                .processCategory("加工")
                .standardYieldMin(new BigDecimal("0.85"))
                .standardYieldMax(new BigDecimal("0.95"))
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertEquals(0, new BigDecimal("0.85").compareTo(dto.getStandardYieldMin()));
        assertEquals(0, new BigDecimal("0.95").compareTo(dto.getStandardYieldMax()));
    }

    @Test
    @DisplayName("getById: definition 未配区间 → DTO 字段 null (不校验)")
    void getById_nullStandardYieldWhenUnconfigured() {
        WorkProcessTask task = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY_ID)
                .workProcessId(WP_ID)
                .status(WorkProcessTask.Status.PENDING)
                .build();

        WorkProcess definition = WorkProcess.builder()
                .id(WP_ID)
                .factoryId(FACTORY_ID)
                .processName("包装")
                .processCategory("包装")
                .build();

        when(taskRepository.findByFactoryIdAndId(FACTORY_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(workProcessRepository.findByFactoryIdAndId(FACTORY_ID, WP_ID))
                .thenReturn(Optional.of(definition));

        WorkProcessTaskDTO dto = service.getById(FACTORY_ID, TASK_ID);

        assertNull(dto.getStandardYieldMin());
        assertNull(dto.getStandardYieldMax());
    }
}
