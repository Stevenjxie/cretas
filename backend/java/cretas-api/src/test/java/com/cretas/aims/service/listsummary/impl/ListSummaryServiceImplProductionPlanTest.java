package com.cretas.aims.service.listsummary.impl;

import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.service.MaterialBatchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListSummaryServiceImplProductionPlanTest {

    private ListSummaryServiceImpl service;
    private EntityManager entityManager;
    private Query query;

    @BeforeEach
    void setUp() {
        service = new ListSummaryServiceImpl(mock(MaterialBatchService.class));
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        ReflectionTestUtils.setField(service, "em", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    }

    @Test
    void keywordAndStatusUseListSemanticsAndSingleUnitCompletesAtOneHundredPercent() {
        when(query.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"box", 1L, new BigDecimal("5"), new BigDecimal("5")}));

        ListSummaryResponse result = service.computeSummary(
                "F006", "productionPlan",
                new ListSummaryRequest(Map.of(
                        "keyword", "PLAN-1784523993145",
                        "status", "COMPLETED"), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue())
                .contains("LEFT JOIN product_types")
                .contains("LOWER(p.plan_number)")
                .contains("LOWER(pt.name)")
                .contains("UPPER(p.status) = UPPER(:status)")
                .contains("GROUP BY")
                .contains("WHEN '盒' THEN 'box'")
                // 2026-07-30 线上事故: :keyword 只出现在 CONCAT 里 (variadic "any"),
                // PostgreSQL 推不出类型 → 「could not determine data type of parameter $2」,
                // 生产计划列表一用搜索框底部汇总条就整条挂掉。native SQL 必须显式 CAST。
                .contains("CONCAT('%', CAST(:keyword AS text), '%')")
                .doesNotContain("CONCAT('%', :keyword, '%')");
        verify(query).setParameter("fid", "F006");
        verify(query).setParameter("keyword", "PLAN-1784523993145");
        verify(query).setParameter("status", "COMPLETED");

        assertThat(result.getStats())
                .extracting(ListSummaryResponse.SummaryStat::getLabel)
                .containsExactly("共", "计划数量", "完成数量", "完成率");
        assertThat(result.getStats().get(0).getValue()).isEqualTo(1L);
        assertThat(result.getStats().get(1).getValue()).isEqualTo(new BigDecimal("5"));
        assertThat(result.getStats().get(1).getUnit()).isEqualTo("盒");
        assertThat(result.getStats().get(2).getValue()).isEqualTo(new BigDecimal("5"));
        assertThat(result.getStats().get(3).getValue()).isEqualTo(new BigDecimal("100.0"));
    }

    @Test
    void mixedUnitsAreRenderedAsSeparateGroupsAndNeverSummedTogether() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"box", 2L, new BigDecimal("10"), new BigDecimal("5")},
                new Object[]{"kg", 3L, new BigDecimal("100"), new BigDecimal("80")}));

        ListSummaryResponse result = service.computeSummary(
                "F006", "productionPlan", new ListSummaryRequest(Map.of(), null, null, null));

        assertThat(result.getStats().get(0).getValue()).isEqualTo(5L);
        assertThat(result.getStats())
                .extracting(ListSummaryResponse.SummaryStat::getLabel)
                .containsExactly(
                        "共",
                        "计划数量（盒）", "完成数量（盒）", "完成率（盒）",
                        "计划数量（kg）", "完成数量（kg）", "完成率（kg）");
        assertThat(result.getStats())
                .extracting(ListSummaryResponse.SummaryStat::getValue)
                .doesNotContain(new BigDecimal("110"), new BigDecimal("85"));
    }

    @Test
    void unfinishedVirtualStatusUsesPendingAndInProgressWithoutBindingEnumValue() {
        when(query.getResultList()).thenReturn(List.of());

        service.computeSummary(
                "F006", "productionPlan",
                new ListSummaryRequest(Map.of("status", "UNFINISHED"), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("IN ('PENDING', 'IN_PROGRESS')");
        org.mockito.Mockito.verify(query, org.mockito.Mockito.never())
                .setParameter(org.mockito.ArgumentMatchers.eq("status"), org.mockito.ArgumentMatchers.any());
    }
}
