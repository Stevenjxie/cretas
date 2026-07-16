package com.cretas.aims.service.inventory.cost;

import com.cretas.aims.service.uom.MaterialUomConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Calculates inventory moving-average cost in the raw material's master unit. */
@Component
@RequiredArgsConstructor
public class MaterialMovingAverageCalculator {

    private final MaterialUomConverter materialUomConverter;

    public CalculationResult calculate(
            String materialTypeId,
            String masterUnit,
            List<CostLayer> layers) {
        BigDecimal normalizedQuantity = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        List<String> issues = new ArrayList<>();

        if (masterUnit == null || masterUnit.isBlank()) {
            return new CalculationResult(null, BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of("material master unit is missing"));
        }

        for (CostLayer layer : layers == null ? List.<CostLayer>of() : layers) {
            if (layer.quantity() == null || layer.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (layer.unitPrice() == null || layer.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                issues.add(label(layer) + ": unit price is missing");
                continue;
            }
            MaterialUomConverter.ConversionResult converted = materialUomConverter.toComparableQuantity(
                    materialTypeId, layer.quantity(), layer.unit(), masterUnit);
            if (!converted.isConverted() || converted.getQuantity() == null) {
                issues.add(label(layer) + ": cannot convert " + layer.unit() + " to " + masterUnit);
                continue;
            }
            normalizedQuantity = normalizedQuantity.add(converted.getQuantity());
            // The layer price is per original layer unit, so value is calculated before quantity conversion.
            totalValue = totalValue.add(layer.quantity().multiply(layer.unitPrice()));
        }

        if (!issues.isEmpty() || normalizedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return new CalculationResult(null, normalizedQuantity, totalValue, List.copyOf(issues));
        }
        BigDecimal averagePrice = totalValue.divide(normalizedQuantity, 4, RoundingMode.HALF_UP);
        return new CalculationResult(averagePrice, normalizedQuantity, totalValue, List.of());
    }

    private static String label(CostLayer layer) {
        return layer.sourceId() == null || layer.sourceId().isBlank() ? "inventory layer" : layer.sourceId();
    }

    public record CostLayer(BigDecimal quantity, String unit, BigDecimal unitPrice, String sourceId) {}

    public record CalculationResult(
            BigDecimal averagePrice,
            BigDecimal normalizedQuantity,
            BigDecimal totalValue,
            List<String> issues) {
        public boolean complete() {
            return averagePrice != null && issues != null && issues.isEmpty();
        }
    }
}
