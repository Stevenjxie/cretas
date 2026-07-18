package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression guard for PR #455 BUG-2 — sister-site sweep across BOM cost-config / production-work
 * cost / raw-material master entities.
 *
 * <p>Beyond the directly-leaking {@link BomRecipeItem#unitPrice} that BUG-2 named, the same RBAC gap
 * exists on 6 sibling persisted monetary fields reachable via BomController + ProductionBatch
 * + RawMaterialType controllers. All of them returned real values to {@code warehouse_manager}
 * before this fix because {@code PriceFieldResponseAdvice} only walks fields carrying
 * {@code @PriceSensitive}.
 *
 * <p>Out-of-scope siblings (different domains, deferred to follow-up PRs): finance/ AR/AP/Invoice/Payment,
 * rd/ QuotationTask, sales/ OperationalQuote, inventory/ FinishedGoodsBatch + SalesDelivery + ShipmentRecord,
 * SalesOrderItem.costUnitPrice, smartbi/ aggregates. These either rely on different permission gates
 * or are not in BUG-2's stated scope ("BOM / recipe / material_cost / production_cost").
 */
class BomDomainSisterSitePriceSensitiveTest {

    private static Stream<Arguments> bomDomainPriceFields() {
        return Stream.of(
                // bom/ cost-config entities — leak via BomController @GetMapping("/labor") / "/overhead"
                Arguments.of(LaborCostConfig.class,    "unitPrice"),
                Arguments.of(OverheadCostConfig.class, "unitPrice"),
                // production-cost work-session entities — leak via batch / employee work-session endpoints
                Arguments.of(BatchWorkSession.class,    "laborCost"),
                Arguments.of(EmployeeWorkSession.class, "hourlyRate"),
                Arguments.of(EmployeeWorkSession.class, "laborCost"),
                // material-master entity — leak via raw-material-type catalogue endpoint;
                // unit_price column is supplier cost (ProductType.unitPrice already covered upstream).
                Arguments.of(RawMaterialType.class, "unitPrice")
        );
    }

    @ParameterizedTest(name = "{0}.{1} is @PriceSensitive")
    @MethodSource("bomDomainPriceFields")
    @DisplayName("BOM-domain sister sites carry @PriceSensitive (BUG-2 sweep)")
    void bomDomainPriceField_isAnnotated_priceSensitive(Class<?> owner, String fieldName)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(fieldName);
        assertNotNull(
                field.getAnnotation(PriceSensitive.class),
                owner.getSimpleName() + "." + fieldName
                        + " must be @PriceSensitive — leaks supplier / labor / overhead pricing "
                        + "to warehouse_manager / operator / quality_inspector roles otherwise."
        );
    }
}
