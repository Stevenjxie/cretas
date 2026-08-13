package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishDeleteTool;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantOwnerActionAdvisorTool;
import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool;
import com.cretas.aims.ai.tool.impl.material.MaterialStockSummaryTool;
import com.cretas.aims.client.RestaurantOwnerActionClient;
import com.cretas.aims.ai.tool.impl.user.UserDisableTool;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedToolSourceMetadataTest {

    @Test
    void inventoryWorkflowToolsPublishExplicitReadOnlyPermissionMetadata() {
        Set<String> permissions = Set.of(
                "warehouse:read",
                "warehouse:read_write",
                "inventory:read",
                "inventory:read_write");
        assertInventoryReadTool(new MaterialStockSummaryTool(), permissions);
        assertInventoryReadTool(new MaterialBatchQueryTool(), permissions);
        assertInventoryReadTool(new MaterialExpiredQueryTool(), permissions);
    }

    private static void assertInventoryReadTool(
            ToolExecutor tool, Set<String> expectedPermissions) {
        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.READ);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.LOW);
        assertThat(tool.supportsPreview()).isFalse();
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.hasPermission("factory_super_admin")).isFalse();
        assertThat(tool.getRequiredPermissions())
                .containsExactlyInAnyOrderElementsOf(expectedPermissions);
        assertThat(tool.getVersion()).isEqualTo("1.0.0");
        assertThat(tool.getDomainTags()).containsExactly("material");
    }

    @Test
    void fixedWriteToolsPublishExplicitAnyOfPermissionsAndPreviewMetadata() {
        ProductCreateTool productCreate = new ProductCreateTool();

        assertThat(productCreate.getActionType()).isEqualTo(ToolExecutor.ActionType.WRITE);
        assertThat(productCreate.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.MEDIUM);
        assertThat(productCreate.getRequiredPermissions())
                .containsExactlyInAnyOrder("production:read_write", "system:read_write");
        assertThat(productCreate.getDomainTags()).containsExactly("product");
        assertThat(productCreate.supportsPreview()).isTrue();
        assertThat(productCreate.requiresPermission()).isTrue();
        assertThat(productCreate.hasPermission("factory_super_admin")).isFalse();

        // 2026-08-14: BomAdjustTool 随 9 个老式 BOM 写入工具一并退役
        // (BOM 只能在画布页面上改, AI 也不例外)。这段守的「写工具必须显式公布
        // anyOf 权限 + 预览元数据」仍由上方 ProductCreateTool 覆盖, 不因删除而失去覆盖。
    }

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
