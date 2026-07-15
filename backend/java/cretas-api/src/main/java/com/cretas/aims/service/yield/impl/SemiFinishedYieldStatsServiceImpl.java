package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.SemiFinishedYieldStatsDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.SemiFinishedYieldStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class SemiFinishedYieldStatsServiceImpl implements SemiFinishedYieldStatsService {

    static final String SOURCE = "SETTLED_PROCESS_SHEET_ROWS";
    private static final int RATE_SCALE = 6;

    private final ProductionReportRepository productionReportRepository;

    @Override
    @Transactional(readOnly = true)
    public SemiFinishedYieldStatsDTO getStats(String factoryId, String semiFinishedSkuId) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 不能为空");
        }
        if (semiFinishedSkuId == null || semiFinishedSkuId.isBlank()) {
            throw new BusinessException(400, "semiFinishedSkuId 不能为空");
        }

        ProductionReportRepository.SemiFinishedYieldAggregate aggregate =
                productionReportRepository.aggregateSettledSemiFinishedYield(
                        factoryId, semiFinishedSkuId);
        BigDecimal totalInput = valueOrZero(aggregate == null ? null : aggregate.getTotalInputKg());
        BigDecimal totalOutput = valueOrZero(aggregate == null ? null : aggregate.getTotalOutputKg());
        long batchCount = aggregate == null || aggregate.getBatchCount() == null
                ? 0L : aggregate.getBatchCount();
        BigDecimal weightedRate = batchCount == 0L || totalInput.signum() <= 0
                ? null
                : totalOutput.divide(totalInput, RATE_SCALE, RoundingMode.HALF_UP);

        return SemiFinishedYieldStatsDTO.builder()
                .factoryId(factoryId)
                .semiFinishedSkuId(semiFinishedSkuId)
                .totalInputKg(totalInput)
                .totalOutputKg(totalOutput)
                .weightedYieldRate(weightedRate)
                .batchCount(batchCount)
                .source(SOURCE)
                .build();
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
