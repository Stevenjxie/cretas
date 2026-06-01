package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.YieldAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class YieldAnalysisServiceImpl implements YieldAnalysisService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final ProductionReportRepository reportRepo;

    @Override
    public List<ProcessYieldAggDTO> aggregateByProcess(String factoryId, LocalDate startDate, LocalDate endDate, String productTypeId) {
        // audit SQL-1: sentinel 避 PG null 参数类型推断失败
        LocalDate start = startDate != null ? startDate : LocalDate.of(1900, 1, 1);
        LocalDate end = endDate != null ? endDate : LocalDate.of(2999, 12, 31);
        String pt = productTypeId != null ? productTypeId : "";

        List<Map<String, Object>> rows = reportRepo.aggregateYieldByProcess(factoryId, start, end, pt);
        List<ProcessYieldAggDTO> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal totalInput = toBig(r.get("total_input"));
            BigDecimal totalOutput = toBig(r.get("total_output"));
            String inUnit = asStr(r.get("input_unit"));
            String outUnit = asStr(r.get("output_unit"));
            boolean comparable = Objects.equals(inUnit, outUnit);

            BigDecimal conversionRate = null;
            BigDecimal wastageRate = null;
            if (comparable && totalInput.compareTo(BigDecimal.ZERO) > 0) {
                conversionRate = totalOutput.multiply(HUNDRED).divide(totalInput, 1, RoundingMode.HALF_UP);
                BigDecimal waste = totalInput.subtract(totalOutput).multiply(HUNDRED)
                        .divide(totalInput, 1, RoundingMode.HALF_UP);
                wastageRate = waste.max(BigDecimal.ZERO);
            }

            result.add(ProcessYieldAggDTO.builder()
                    .processName(asStr(r.get("process_name")))
                    .inputQuantity(totalInput.setScale(2, RoundingMode.HALF_UP))
                    .outputQuantity(totalOutput.setScale(2, RoundingMode.HALF_UP))
                    .conversionRate(conversionRate)
                    .wastageRate(wastageRate)
                    .unit(inUnit)
                    .unitComparable(comparable)
                    .batchCount(toInt(r.get("batch_count")))
                    .build());
        }
        return result;
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static String asStr(Object v) {
        return v == null ? null : v.toString();
    }
}
