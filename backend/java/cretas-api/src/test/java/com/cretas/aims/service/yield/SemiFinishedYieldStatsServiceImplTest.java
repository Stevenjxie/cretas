package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.SemiFinishedYieldStatsDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.SemiFinishedYieldStatsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("半成品 SKU 全历史加权出成率")
class SemiFinishedYieldStatsServiceImplTest {

    @Mock
    private ProductionReportRepository productionReportRepository;

    @Mock
    private ProductionReportRepository.SemiFinishedYieldAggregate aggregate;

    @InjectMocks
    private SemiFinishedYieldStatsServiceImpl service;

    @Test
    @DisplayName("用总产出除以总投入，不平均各批次百分比")
    void calculatesWeightedYieldFromAggregateSums() {
        when(productionReportRepository.aggregateSettledSemiFinishedYield("F006", "SKU-SEMI"))
                .thenReturn(aggregate);
        when(aggregate.getTotalInputKg()).thenReturn(new BigDecimal("110.000000"));
        when(aggregate.getTotalOutputKg()).thenReturn(new BigDecimal("60.000000"));
        when(aggregate.getBatchCount()).thenReturn(2L);

        SemiFinishedYieldStatsDTO result = service.getStats("F006", "SKU-SEMI");

        assertThat(result.getFactoryId()).isEqualTo("F006");
        assertThat(result.getSemiFinishedSkuId()).isEqualTo("SKU-SEMI");
        assertThat(result.getTotalInputKg()).isEqualByComparingTo("110.000000");
        assertThat(result.getTotalOutputKg()).isEqualByComparingTo("60.000000");
        assertThat(result.getWeightedYieldRate()).isEqualByComparingTo("0.545455");
        assertThat(result.getBatchCount()).isEqualTo(2L);
        assertThat(result.getSource()).isEqualTo("SETTLED_PROCESS_SHEET_ROWS");
    }

    @Test
    @DisplayName("无有效已小结批次时返回 null 出成率，不伪造 0%")
    void returnsNullRateWhenThereAreNoValidBatches() {
        when(productionReportRepository.aggregateSettledSemiFinishedYield("F006", "SKU-EMPTY"))
                .thenReturn(aggregate);
        when(aggregate.getTotalInputKg()).thenReturn(BigDecimal.ZERO);
        when(aggregate.getTotalOutputKg()).thenReturn(BigDecimal.ZERO);
        when(aggregate.getBatchCount()).thenReturn(0L);

        SemiFinishedYieldStatsDTO result = service.getStats("F006", "SKU-EMPTY");

        assertThat(result.getBatchCount()).isZero();
        assertThat(result.getWeightedYieldRate()).isNull();
        assertThat(result.getTotalInputKg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalOutputKg()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
