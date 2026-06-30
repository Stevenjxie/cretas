package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionSummaryServiceTest {
    @Mock ProcessSheetService processSheetService;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock OrderCostBreakdownService orderCostBreakdownService;
    @InjectMocks ProductionSummaryService service;

    private ProcessSheetInventoryItem item(int order, String status, BigDecimal input,
                                           BigDecimal produced, BigDecimal remaining, BigDecimal cumYield) {
        ProcessSheetInventoryItem i = new ProcessSheetInventoryItem();
        i.setProcessOrder(order); i.setStatus(status); i.setInputQuantity(input);
        i.setProduced(produced); i.setRemaining(remaining); i.setCumulativeYieldRate(cumYield);
        return i;
    }

    private ProductionBatch clkB(BigDecimal qty) {
        ProductionBatch b = new ProductionBatch(); b.setBatchType("REGULAR"); b.setQuantity(qty); return b;
    }

    @Test
    void totalRawInput_sumsFirstProcessAcrossBatches() {
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of(
                item(1, "IN_PROGRESS", new BigDecimal("2.0"), new BigDecimal("1.8"), new BigDecimal("0.2"), null),
                item(1, "IN_PROGRESS", new BigDecimal("3.0"), new BigDecimal("2.7"), new BigDecimal("0.3"), null),
                item(2, "IN_PROGRESS", new BigDecimal("1.8"), new BigDecimal("1.6"), new BigDecimal("1.6"), new BigDecimal("90"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(clkB(new BigDecimal("1.5")), clkB(new BigDecimal("0.5"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("5.0");
        assertThat(dto.getTotalFinishedOutput()).isEqualByComparingTo("2.0");
    }
}
