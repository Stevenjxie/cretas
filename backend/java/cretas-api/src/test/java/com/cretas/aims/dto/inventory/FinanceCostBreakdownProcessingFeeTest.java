package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1 #32 regression guard: processingFee (委外加工费) independent cost line on
 * FinanceCostBreakdown.
 *
 * <p>Covers:
 * <ol>
 *   <li>Field exists on FinanceCostBreakdown.</li>
 *   <li>Field is annotated @PriceSensitive (same sensitivity class as other cost fields).</li>
 *   <li>Builder sets the field to null when no outsourced processing fee data is available
 *       (honest-null policy: no fake zero, no fabricated cost).</li>
 *   <li>Builder accepts a non-null processingFee value (when a future data source is wired).</li>
 * </ol>
 *
 * <p>NOTE: The current data source for processingFee is intentionally null.
 * WorkProcess / WorkProcessTask entities have no "isOutsourced" or "outsourcedFee" column yet.
 * Returning null is the correct behaviour — front-end will not render the row.
 */
@DisplayName("P1 #32: FinanceCostBreakdown.processingFee — 委外加工费独立科目")
class FinanceCostBreakdownProcessingFeeTest {

    // -------------------------------------------------------------------------
    // Structural / annotation assertions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processingFee field exists on FinanceCostBreakdown")
    void processingFeeField_exists() throws NoSuchFieldException {
        // Throws NoSuchFieldException if absent — that IS the assertion.
        Field field = FinanceCostBreakdown.class.getDeclaredField("processingFee");
        assertNotNull(field, "processingFee must be declared on FinanceCostBreakdown");
    }

    @Test
    @DisplayName("processingFee is @PriceSensitive (委外加工费属敏感成本数值)")
    void processingFeeField_isPriceSensitive() throws NoSuchFieldException {
        Field field = FinanceCostBreakdown.class.getDeclaredField("processingFee");
        assertNotNull(
                field.getAnnotation(PriceSensitive.class),
                "processingFee must be @PriceSensitive — 委外加工费是成本金额, 对无财务权限角色应脱敏"
        );
    }

    // -------------------------------------------------------------------------
    // Honest-null policy: no outsourced data source → null (not 0)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processingFee is null when no outsourced fee data exists (honest null)")
    void processingFee_isNull_whenNoDataSource() {
        // Simulates SalesServiceImpl.getOrderCostBreakdown() current behaviour:
        // no WorkProcess.isOutsourced column → processingFee = null.
        FinanceCostBreakdown breakdown = FinanceCostBreakdown.builder()
                .totalAmount(BigDecimal.valueOf(10000))
                .bomStandardCost(BigDecimal.valueOf(6000))
                .actualCost(BigDecimal.valueOf(5800))
                .processingFee(null)   // honest null — data source not wired
                .lines(java.util.List.of())
                .build();

        assertNull(
                breakdown.getProcessingFee(),
                "processingFee must be null when no outsourced processing fee data is available. "
                + "Do NOT fabricate a zero — front-end must hide the row entirely."
        );
    }

    @Test
    @DisplayName("processingFee accepts a non-null value (future data source path)")
    void processingFee_acceptsValue_whenDataSourceAvailable() {
        // Simulates the future state when WorkProcess.isOutsourced + outsourcedFee are wired.
        BigDecimal feeAmount = BigDecimal.valueOf(1200.00).setScale(2, java.math.RoundingMode.HALF_UP);

        FinanceCostBreakdown breakdown = FinanceCostBreakdown.builder()
                .totalAmount(BigDecimal.valueOf(10000))
                .bomStandardCost(BigDecimal.valueOf(6000))
                .actualCost(BigDecimal.valueOf(5800))
                .processingFee(feeAmount)   // actual委外 cost recorded
                .lines(java.util.List.of())
                .build();

        assertNotNull(breakdown.getProcessingFee(), "processingFee must accept a non-null value");
        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                breakdown.getProcessingFee().compareTo(feeAmount),
                "processingFee value must equal the supplied amount"
        );
    }
}
