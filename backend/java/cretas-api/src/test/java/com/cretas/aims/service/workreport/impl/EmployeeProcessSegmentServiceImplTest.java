package com.cretas.aims.service.workreport.impl;

import com.cretas.aims.entity.workreport.EmployeeProcessSegment;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.CheckoutReason;
import com.cretas.aims.entity.workreport.EmployeeProcessSegment.SegmentStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workreport.EmployeeProcessSegmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P1-1 EmployeeProcessSegmentServiceImpl test — 扫码欠退/换岗核心逻辑.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeProcessSegmentService (P1-1)")
class EmployeeProcessSegmentServiceImplTest {

    private static final String FACTORY_ID = "F001";
    private static final Long EMPLOYEE_ID = 42L;

    @Mock
    private EmployeeProcessSegmentRepository repository;

    @InjectMocks
    private EmployeeProcessSegmentServiceImpl service;

    @Test
    @DisplayName("startSegment 无 ACTIVE 片段时应开启新 ACTIVE")
    void startSegment_noActive_createsNew() {
        when(repository.findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(SegmentStatus.ACTIVE)))
                .thenReturn(List.of());
        when(repository.save(any(EmployeeProcessSegment.class)))
                .thenAnswer(inv -> {
                    EmployeeProcessSegment s = inv.getArgument(0);
                    s.setId("seg-1");
                    return s;
                });

        EmployeeProcessSegment seg = service.startSegment(FACTORY_ID, EMPLOYEE_ID, 1, "process-a", "batch-1");

        assertNotNull(seg);
        assertEquals(SegmentStatus.ACTIVE, seg.getStatus());
        assertEquals(1, seg.getWorkTypeId());
        assertEquals("process-a", seg.getProcessId());
        assertNotNull(seg.getStartAt());
    }

    @Test
    @DisplayName("startSegment 已有 ACTIVE 应抛 BusinessException")
    void startSegment_hasActive_throws() {
        EmployeeProcessSegment existing = new EmployeeProcessSegment();
        existing.setStatus(SegmentStatus.ACTIVE);
        when(repository.findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(SegmentStatus.ACTIVE)))
                .thenReturn(List.of(existing));

        assertThrows(BusinessException.class,
                () -> service.startSegment(FACTORY_ID, EMPLOYEE_ID, 1, null, null));
    }

    @Test
    @DisplayName("checkOut with EARLY_LEAVE 应标记 CLOSED_EARLY")
    void checkOut_earlyLeave_closedEarly() {
        EmployeeProcessSegment active = new EmployeeProcessSegment();
        active.setId("seg-1");
        active.setStatus(SegmentStatus.ACTIVE);
        when(repository.findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(SegmentStatus.ACTIVE)))
                .thenReturn(List.of(active));
        when(repository.save(any(EmployeeProcessSegment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmployeeProcessSegment closed = service.checkOut(
                FACTORY_ID, EMPLOYEE_ID, CheckoutReason.EARLY_LEAVE, "生病");

        assertEquals(SegmentStatus.CLOSED_EARLY, closed.getStatus());
        assertEquals(CheckoutReason.EARLY_LEAVE, closed.getCheckoutReason());
        assertNotNull(closed.getEndAt());
    }

    @Test
    @DisplayName("checkOut with END_OF_SHIFT 应标记 CLOSED_NORMAL")
    void checkOut_endOfShift_closedNormal() {
        EmployeeProcessSegment active = new EmployeeProcessSegment();
        active.setStatus(SegmentStatus.ACTIVE);
        when(repository.findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(SegmentStatus.ACTIVE)))
                .thenReturn(List.of(active));
        when(repository.save(any(EmployeeProcessSegment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmployeeProcessSegment closed = service.checkOut(
                FACTORY_ID, EMPLOYEE_ID, CheckoutReason.END_OF_SHIFT, null);

        assertEquals(SegmentStatus.CLOSED_NORMAL, closed.getStatus());
    }

    @Test
    @DisplayName("checkOut 无 ACTIVE 应抛 BusinessException")
    void checkOut_noActive_throws() {
        when(repository.findByFactoryIdAndEmployeeIdAndStatusAndDeletedAtIsNull(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(SegmentStatus.ACTIVE)))
                .thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.checkOut(FACTORY_ID, EMPLOYEE_ID, CheckoutReason.END_OF_SHIFT, null));
    }

    // ---- 单元C (F006 REQ-14): 多区间工时 SUM 聚合 ----

    private static final String PROCESS_ID = "process-a";

    @Test
    @DisplayName("getTotalMinutes 应返回 repository SUM 结果 (整分钟)")
    void getTotalMinutes_returnsSum() {
        when(repository.sumMinutesByEmployeeAndProcess(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(PROCESS_ID)))
                .thenReturn(90.0);

        int total = service.getTotalMinutes(FACTORY_ID, EMPLOYEE_ID, PROCESS_ID);

        assertEquals(90, total);
    }

    @Test
    @DisplayName("getTotalMinutes 无 segment (repository 返 null) 应返回 0")
    void getTotalMinutes_nullSum_returnsZero() {
        when(repository.sumMinutesByEmployeeAndProcess(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(PROCESS_ID)))
                .thenReturn(null);

        int total = service.getTotalMinutes(FACTORY_ID, EMPLOYEE_ID, PROCESS_ID);

        assertEquals(0, total);
    }

    @Test
    @DisplayName("getTotalMinutes 小数应四舍五入到整分钟 (90.7 -> 91)")
    void getTotalMinutes_roundsHalfUp() {
        when(repository.sumMinutesByEmployeeAndProcess(
                eq(FACTORY_ID), eq(EMPLOYEE_ID), eq(PROCESS_ID)))
                .thenReturn(90.7);

        int total = service.getTotalMinutes(FACTORY_ID, EMPLOYEE_ID, PROCESS_ID);

        assertEquals(91, total);
    }
}
