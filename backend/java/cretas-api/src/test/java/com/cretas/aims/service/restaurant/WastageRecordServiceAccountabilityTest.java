package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.WastageAccountability;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.restaurant.impl.WastageRecordServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WastageRecordServiceImpl.getAccountability 单元测试 (Wave2 损耗按人/档口责任制)。
 *
 * Coverage:
 * <ol>
 *   <li>按责任人聚合 — 回填姓名 + null operator → 「未指定」</li>
 *   <li>按档口聚合 — code → 中文 label + null section → 「未指定」</li>
 *   <li>totalCount / totalCost 来自专用 count 查询 + 聚合行求和</li>
 *   <li>空数据 → 空列表 + totalCost=0 (不返假数据)</li>
 * </ol>
 *
 * @since 2026-06-04 (Wave2)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WastageRecordServiceImpl.getAccountability 单元测试")
class WastageRecordServiceAccountabilityTest {

    @Mock WastageRecordRepository wastageRecordRepository;
    @Mock UserRepository userRepository;
    @InjectMocks WastageRecordServiceImpl service;

    private static final String FID = "F006";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Test
    @DisplayName("按责任人聚合 — 回填姓名, null operatorId 归并为「未指定」")
    void byOperator_backfillsName_andNullIsUnassigned() {
        // operatorId, count, sumQuantity, sumCost
        when(wastageRecordRepository.getStatisticsByOperator(FID, START, END)).thenReturn(List.of(
                new Object[]{10L, 3L, new BigDecimal("12.5"), new BigDecimal("300.00")},
                new Object[]{null, 1L, new BigDecimal("2.0"), new BigDecimal("50.00")}
        ));
        when(wastageRecordRepository.getStatisticsBySection(FID, START, END)).thenReturn(List.of());
        when(wastageRecordRepository.countApprovedByDateRange(FID, START, END)).thenReturn(4L);

        User u = new User();
        u.setId(10L);
        u.setFullName("张权");
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of(u));

        WastageAccountability result = service.getAccountability(FID, START, END);

        assertEquals(2, result.getByOperator().size());
        WastageAccountability.ByOperator first = result.getByOperator().get(0);
        assertEquals(10L, first.getOperatorId());
        assertEquals("张权", first.getOperatorName());
        assertEquals(0, new BigDecimal("300.00").compareTo(first.getTotalCost()));
        assertEquals(3L, first.getCount());

        WastageAccountability.ByOperator unassigned = result.getByOperator().get(1);
        assertNull(unassigned.getOperatorId());
        assertEquals("未指定", unassigned.getOperatorName());

        // totalCount from dedicated count query, totalCost from row sum
        assertEquals(4L, result.getTotalCount());
        assertEquals(0, new BigDecimal("350.00").compareTo(result.getTotalCost()));
    }

    @Test
    @DisplayName("按档口聚合 — code 映射中文 label, null section → 「未指定」")
    void bySection_mapsCodeToChineseLabel() {
        when(wastageRecordRepository.getStatisticsByOperator(FID, START, END)).thenReturn(List.of());
        when(wastageRecordRepository.getStatisticsBySection(FID, START, END)).thenReturn(List.of(
                new Object[]{"SEAFOOD", 5L, new BigDecimal("20.0"), new BigDecimal("800.00")},
                new Object[]{"COLD_DISH", 2L, new BigDecimal("3.0"), new BigDecimal("90.00")},
                new Object[]{null, 1L, new BigDecimal("1.0"), new BigDecimal("10.00")}
        ));
        when(wastageRecordRepository.countApprovedByDateRange(FID, START, END)).thenReturn(8L);

        WastageAccountability result = service.getAccountability(FID, START, END);

        assertEquals(3, result.getBySection().size());
        assertEquals("SEAFOOD", result.getBySection().get(0).getSectionCode());
        assertEquals("海鲜", result.getBySection().get(0).getSectionName());
        assertEquals("冷菜", result.getBySection().get(1).getSectionName());
        assertNull(result.getBySection().get(2).getSectionCode());
        assertEquals("未指定", result.getBySection().get(2).getSectionName());

        // no operators looked up
        verify(userRepository, never()).findByIdIn(anyCollection());
    }

    @Test
    @DisplayName("空数据 — 空列表 + totalCost=0, 不查用户")
    void emptyData_returnsZeroNotFake() {
        when(wastageRecordRepository.getStatisticsByOperator(FID, START, END)).thenReturn(List.of());
        when(wastageRecordRepository.getStatisticsBySection(FID, START, END)).thenReturn(List.of());
        when(wastageRecordRepository.countApprovedByDateRange(FID, START, END)).thenReturn(0L);

        WastageAccountability result = service.getAccountability(FID, START, END);

        assertTrue(result.getByOperator().isEmpty());
        assertTrue(result.getBySection().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalCost()));
        assertEquals(0L, result.getTotalCount());
        assertEquals(START.toString(), result.getStartDate());
        assertEquals(END.toString(), result.getEndDate());
        verify(userRepository, never()).findByIdIn(anyCollection());
    }
}
