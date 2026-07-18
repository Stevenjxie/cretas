package com.cretas.aims.entity.bom;

import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression guard for PR #455 BUG-2 — BomRecipeItem.unitPrice leak sweep.
 *
 * <p>Before fix: warehouse_manager fetching the current recipe detail
 * received real supplier {@code unitPrice} values because {@link BomRecipeItem#unitPrice} was not
 * annotated {@code @PriceSensitive}. This violated the same RBAC contract that PR #423/#443
 * established for sales/procurement domains.
 *
 * <p>Annotation introspection asserts the FIELD-target annotation is present so the project's
 * {@link com.cretas.aims.security.PriceFieldResponseAdvice} reflective walk picks it up.
 * Behavioral coverage (advice strips it end-to-end on response) is verified separately in
 * {@code PriceFieldResponseAdviceTest}'s BOM cases.
 */
class BomItemPriceSensitiveTest {

    @Test
    void unitPrice_isAnnotated_priceSensitive() throws NoSuchFieldException {
        Field unitPrice = BomRecipeItem.class.getDeclaredField("unitPrice");
        assertNotNull(
                unitPrice.getAnnotation(PriceSensitive.class),
                "BomRecipeItem.unitPrice must be @PriceSensitive — without the annotation "
                        + "PriceFieldResponseAdvice cannot strip the value for warehouse_manager / "
                        + "operator / quality_inspector roles and supplier unit pricing leaks via "
                        + "/api/mobile/{factoryId}/bom/recipes/by-product/{productId}/current."
        );
    }
}
