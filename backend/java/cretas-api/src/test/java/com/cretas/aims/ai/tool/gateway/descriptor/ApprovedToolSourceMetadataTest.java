package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishDeleteTool;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantOwnerActionAdvisorTool;
import com.cretas.aims.client.RestaurantOwnerActionClient;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedToolSourceMetadataTest {

    @Test
    void ownerAdvisorPublishesExplicitAnalyzePermissionAndEgressMetadata() {
        RestaurantOwnerActionAdvisorTool tool =
                new RestaurantOwnerActionAdvisorTool(
                        org.mockito.Mockito.mock(RestaurantOwnerActionClient.class));

        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.ANALYZE);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.LOW);
        assertThat(tool.getRequiredPermissions()).containsExactly("analytics:read");
        assertThat(tool.getDomainTags())
                .containsExactlyInAnyOrder("restaurant", "analytics", "decision-support");
        assertThat(tool.getVersion()).isEqualTo("2.0.0");
        assertThat(tool.supportsPreview()).isFalse();
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.hasPermission("restaurant_owner")).isFalse();
        assertThat(tool.getEgressDestinationIds())
                .containsExactly("python-smartbi.owner-action-chat.v1");
    }

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
