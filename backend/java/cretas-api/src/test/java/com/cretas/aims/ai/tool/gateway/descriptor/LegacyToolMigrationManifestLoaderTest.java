package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyToolMigrationManifestLoaderTest {

    private final LegacyToolMigrationManifestLoader loader =
            new LegacyToolMigrationManifestLoader();

    @Test
    void defaultManifestContainsOnlyTheThreeAuditedRestaurantReadTools() {
        LegacyToolMigrationManifest manifest = loader.loadDefault();

        assertThat(manifest.expectedToolCount()).isEqualTo(3);
        assertThat(manifest.tools())
                .extracting(LegacyToolMigrationEntry::toolName)
                .containsExactlyInAnyOrder(
                        "restaurant_dish_list",
                        "restaurant_ingredient_stock",
                        "restaurant_order_statistics");
        assertThat(manifest.tools()).allSatisfy(entry -> {
            assertThat(entry.actionType().name()).isIn("READ", "ANALYZE");
            assertThat(entry.riskLevel().name()).isEqualTo("LOW");
            assertThat(entry.supportsPreview()).isFalse();
            assertThat(entry.requiresPermission()).isFalse();
            assertThat(entry.requiredPermissions()).isEmpty();
            assertThat(entry.allowedRoles()).isEmpty();
            assertThat(entry.allowedBusinessTypes())
                    .extracting(FactoryType::name)
                    .containsExactlyInAnyOrder("RESTAURANT", "BRANCH");
            assertThat(entry.dataClassification().name()).isEqualTo("CONFIDENTIAL");
        });
    }

    @Test
    void rejectsToolOutsideInitialAllowlist() {
        assertThatThrownBy(() -> loader.load(new StringReader(
                yaml("customer_list", "READ", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the initial migration allowlist");
    }

    @Test
    void rejectsWriteOrPreviewEntries() {
        assertThatThrownBy(() -> loader.load(new StringReader(
                yaml("restaurant_dish_list", "WRITE", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READ/ANALYZE");
        assertThatThrownBy(() -> loader.load(new StringReader(
                yaml("restaurant_dish_list", "READ", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READ/ANALYZE");
    }

    private static String yaml(String toolName, String actionType, boolean supportsPreview) {
        return """
                schemaVersion: 1
                expectedToolCount: 1
                tools:
                  - implementationClass: "com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishListTool"
                    toolName: "%s"
                    actionType: %s
                    riskLevel: LOW
                    supportsPreview: %s
                    requiresPermission: false
                    requiredPermissions: []
                    allowedRoles: []
                    allowedBusinessTypes: [RESTAURANT, BRANCH]
                    version: "1.0.0"
                    domainTags: ["restaurant"]
                    dataClassification: CONFIDENTIAL
                """.formatted(toolName, actionType, supportsPreview);
    }
}
