package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishDeleteTool;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedToolSourceMetadataTest {

    @Test
    void userDisablePublishesExplicitHighRiskUpdateMetadataAndFailsLegacyRoleOnlyAccess() {
        UserDisableTool tool = new UserDisableTool();

        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.UPDATE);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.HIGH);
        assertThat(tool.getRequiredPermissions()).containsExactly("hr:read_write");
        assertThat(tool.getDomainTags()).isEqualTo(Set.of("user", "hr", "identity"));
        assertThat(tool.getVersion()).isEqualTo("2.0.0");
        assertThat(tool.supportsPreview()).isFalse();
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.hasPermission("factory_super_admin")).isFalse();
    }

    @Test
    void dishSoftDeletePublishesExplicitHighRiskUpdateMetadataAndFailsLegacyRoleOnlyAccess() {
        RestaurantDishDeleteTool tool = new RestaurantDishDeleteTool();

        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.UPDATE);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.HIGH);
        assertThat(tool.getRequiredPermissions()).containsExactly("restaurant:read_write");
        assertThat(tool.getDomainTags())
                .isEqualTo(Set.of("restaurant", "menu", "product-master"));
        assertThat(tool.getVersion()).isEqualTo("2.0.0");
        assertThat(tool.supportsPreview()).isFalse();
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.hasPermission("factory_super_admin")).isFalse();
    }
}
