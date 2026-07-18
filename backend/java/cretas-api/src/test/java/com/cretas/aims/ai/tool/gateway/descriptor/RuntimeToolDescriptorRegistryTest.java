package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeToolDescriptorRegistryTest {

    private final ToolDescriptorInventory inventory =
            new ToolDescriptorInventoryLoader().loadDefault();
    private final RuntimeToolPolicyManifest manifest =
            new RuntimeToolPolicyLoader().loadDefault();

    @Test
    void exposesOnlyExplicitApprovedP0Policies() {
        RuntimeToolDescriptorRegistry registry =
                new RuntimeToolDescriptorRegistry(inventory, manifest);

        assertThat(registry.approvedToolNames())
                .containsExactlyInAnyOrder("user_disable", "restaurant_dish_delete");
        for (String toolName : registry.approvedToolNames()) {
            ToolDescriptor descriptor = registry.findApproved(toolName).orElseThrow();
            assertThat(descriptor.provenance()).isEqualTo(DescriptorProvenance.EXPLICIT);
            assertThat(descriptor.requiredPermissions()).isNotEmpty();
            assertThat(descriptor.version()).isEqualTo("2.0.0");
            assertThat(descriptor.supportsPreview()).isFalse();
            assertThat(descriptor.allowedSources()).containsExactly(ToolExecutionSource.AI_CHAT);
            assertThat(descriptor.egressPolicy().mode()).isEqualTo(EgressMode.DENY_ALL);
        }
        assertThat(ToolDescriptorInventoryLoader.P0_TOOL_NAMES)
                .filteredOn(toolName -> !registry.approvedToolNames().contains(toolName))
                .hasSize(18)
                .allSatisfy(toolName -> assertThat(registry.findApproved(toolName)).isEmpty());
        assertThat(registry.findApproved("restaurant_sales_overview")).isEmpty();
        assertThat(registry.findApproved("does_not_exist")).isEmpty();
        assertThat(registry.findApproved(" ")).isEmpty();
    }

    @Test
    void rejectsMissingExtraAndDriftedRuntimePolicies() {
        RuntimeToolPolicyManifest missing = new RuntimeToolPolicyManifest(
                1, 1, List.of(manifest.policies().get(0)));
        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(inventory, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exactly match APPROVED inventory")
                .hasMessageContaining("restaurant_dish_delete");

        RuntimeToolPolicyEntry first = manifest.policies().get(0);
        RuntimeToolPolicyEntry extraEntry = copy(first,
                "com.example.UnapprovedTool", "unapproved_tool", first.version());
        List<RuntimeToolPolicyEntry> extraPolicies = new ArrayList<>(manifest.policies());
        extraPolicies.add(extraEntry);
        RuntimeToolPolicyManifest extra = new RuntimeToolPolicyManifest(1, 3, extraPolicies);
        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(inventory, extra))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unapproved=[unapproved_tool]");

        RuntimeToolPolicyEntry driftedEntry = copy(
                first, first.implementationClass(), first.toolName(), "2.0.1");
        RuntimeToolPolicyManifest drifted = new RuntimeToolPolicyManifest(
                1, 2, List.of(driftedEntry, manifest.policies().get(1)));
        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(inventory, drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime policy drift for user_disable: version");

        RuntimeToolPolicyEntry sourceDrift = copy(
                first, "com.example.UserDisableTool", first.toolName(), first.version());
        RuntimeToolPolicyManifest sourceDriftManifest = new RuntimeToolPolicyManifest(
                1, 2, List.of(sourceDrift, manifest.policies().get(1)));
        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(
                inventory, sourceDriftManifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime policy drift for user_disable: implementationClass");

        RuntimeToolPolicyEntry permissionDrift = copyWithPermissions(
                first, Set.of("hr:read"));
        RuntimeToolPolicyManifest permissionDriftManifest = new RuntimeToolPolicyManifest(
                1, 2, List.of(permissionDrift, manifest.policies().get(1)));
        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(
                inventory, permissionDriftManifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime policy drift for user_disable: requiredPermissions");
    }

    @Test
    void flippingAnotherP0ToApprovedWithoutRuntimePolicyStillFailsClosed() {
        ToolDescriptorInventoryEntry target = inventory.descriptors().stream()
                .filter(entry -> entry.toolName().equals("canvas_set_user_permission"))
                .findFirst()
                .orElseThrow();
        ToolDescriptorInventoryEntry forgedApproval = new ToolDescriptorInventoryEntry(
                target.toolName(),
                target.implementationClass(),
                DescriptorProvenance.EXPLICIT,
                target.actionType(),
                target.riskLevel(),
                target.supportsPreview(),
                true,
                Set.of("permission:explicit-review-required"),
                "2.0.0",
                Set.of("canvas", "identity"),
                new ToolDescriptorOverrideFlags(true, true, true, true, true, true, true, true),
                ToolGovernanceStatus.APPROVED);
        List<ToolDescriptorInventoryEntry> forgedEntries = inventory.descriptors().stream()
                .map(entry -> entry.toolName().equals(target.toolName()) ? forgedApproval : entry)
                .toList();
        ToolDescriptorInventory forgedInventory = new ToolDescriptorInventory(
                1, inventory.expectedToolCount(), inventory.expectedLegacyCount() - 1, forgedEntries);

        assertThatThrownBy(() -> new RuntimeToolDescriptorRegistry(forgedInventory, manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing=[canvas_set_user_permission]");
    }

    private RuntimeToolPolicyEntry copy(
            RuntimeToolPolicyEntry source,
            String implementationClass,
            String toolName,
            String version) {
        return new RuntimeToolPolicyEntry(
                implementationClass,
                toolName,
                source.actionType(),
                source.riskLevel(),
                source.requiredPermissions(),
                source.domainTags(),
                version,
                source.supportsPreview(),
                source.confirmationPolicy(),
                source.approvalPolicy(),
                source.idempotencyPolicy(),
                source.dataClassification(),
                source.allowedSources(),
                source.egressPolicy(),
                source.provenance());
    }

    private RuntimeToolPolicyEntry copyWithPermissions(
            RuntimeToolPolicyEntry source,
            Set<String> requiredPermissions) {
        return new RuntimeToolPolicyEntry(
                source.implementationClass(),
                source.toolName(),
                source.actionType(),
                source.riskLevel(),
                requiredPermissions,
                source.domainTags(),
                source.version(),
                source.supportsPreview(),
                source.confirmationPolicy(),
                source.approvalPolicy(),
                source.idempotencyPolicy(),
                source.dataClassification(),
                source.allowedSources(),
                source.egressPolicy(),
                source.provenance());
    }
}
