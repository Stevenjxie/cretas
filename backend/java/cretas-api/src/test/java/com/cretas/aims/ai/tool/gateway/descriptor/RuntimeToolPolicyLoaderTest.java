package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeToolPolicyLoaderTest {

    private final RuntimeToolPolicyLoader loader = new RuntimeToolPolicyLoader();

    @Test
    void loadsAllTenCompleteExplicitRuntimePolicies() {
        RuntimeToolPolicyManifest manifest = loader.loadDefault();

        assertThat(manifest.schemaVersion()).isEqualTo(1);
        assertThat(manifest.expectedPolicyCount()).isEqualTo(10);
        assertThat(manifest.policies())
                .extracting(RuntimeToolPolicyEntry::toolName)
                .containsExactly(
                        "user_disable",
                        "restaurant_dish_delete",
                        "restaurant_owner_action_advisor",
                        "canvas_product_work_process_config",
                        "canvas_work_process_catalog",
                        "product_create",
                        "bom_adjust",
                        "material_stock_summary",
                        "material_batch_query",
                        "material_expired_query");
        assertThat(manifest.policies()).allSatisfy(policy -> {
            assertThat(policy.provenance()).isEqualTo(DescriptorProvenance.EXPLICIT);
            assertThat(policy.requiredPermissions().isEmpty() && policy.allowedRoles().isEmpty())
                    .isFalse();
            assertThat(policy.allowedBusinessTypes()).isNotEmpty();
            assertThat(policy.domainTags()).isNotEmpty();
            assertThat(policy.approvalPolicy())
                    .isEqualTo(ApprovalPolicy.NOT_REQUIRED);
        });
        assertThat(manifest.policies().subList(0, 2)).allSatisfy(policy -> {
            assertThat(policy.version()).isEqualTo("2.0.0");
            assertThat(policy.supportsPreview()).isFalse();
            assertThat(policy.confirmationPolicy())
                    .isEqualTo(ConfirmationPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.idempotencyPolicy())
                    .isEqualTo(IdempotencyPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.allowedSources()).containsExactly(ToolExecutionSource.AI_CHAT);
        });
        assertThat(manifest.policies().subList(3, 5)).allSatisfy(policy -> {
            assertThat(policy.version()).isEqualTo("1.0.0");
            assertThat(policy.supportsPreview()).isTrue();
            assertThat(policy.confirmationPolicy()).isEqualTo(ConfirmationPolicy.NOT_REQUIRED);
            assertThat(policy.idempotencyPolicy()).isEqualTo(IdempotencyPolicy.NOT_REQUIRED);
            assertThat(policy.allowedSources())
                    .containsExactly(ToolExecutionSource.HTTP_CONTROLLER);
        });
        assertThat(manifest.policies().subList(5, 7)).allSatisfy(policy -> {
            assertThat(policy.version()).isEqualTo("1.0.0");
            assertThat(policy.supportsPreview()).isTrue();
            assertThat(policy.confirmationPolicy())
                    .isEqualTo(ConfirmationPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.idempotencyPolicy())
                    .isEqualTo(IdempotencyPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.allowedSources())
                    .containsExactly(ToolExecutionSource.HTTP_CONTROLLER);
        });
        assertThat(manifest.policies().subList(7, 10)).allSatisfy(policy -> {
            assertThat(policy.actionType()).isEqualTo(
                    com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ);
            assertThat(policy.riskLevel()).isEqualTo(
                    com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW);
            assertThat(policy.version()).isEqualTo("1.0.0");
            assertThat(policy.supportsPreview()).isFalse();
            assertThat(policy.requiredPermissions()).containsExactlyInAnyOrder(
                    "warehouse:read", "warehouse:read_write",
                    "inventory:read", "inventory:read_write");
            assertThat(policy.allowedBusinessTypes())
                    .containsExactlyInAnyOrder(FactoryType.FACTORY, FactoryType.CENTRAL_KITCHEN);
            assertThat(policy.confirmationPolicy()).isEqualTo(ConfirmationPolicy.NOT_REQUIRED);
            assertThat(policy.idempotencyPolicy()).isEqualTo(IdempotencyPolicy.NOT_REQUIRED);
            assertThat(policy.allowedSources()).containsExactly(ToolExecutionSource.SKILL_WORKFLOW);
            assertThat(policy.dataClassification()).isEqualTo(DataClassification.CONFIDENTIAL);
        });
        assertThat(manifest.policies().get(0).dataClassification())
                .isEqualTo(DataClassification.RESTRICTED);
        assertThat(manifest.policies().get(1).dataClassification())
                .isEqualTo(DataClassification.CONFIDENTIAL);
        assertThat(manifest.policies().get(2)).satisfies(policy -> {
            assertThat(policy.toolName()).isEqualTo("restaurant_owner_action_advisor");
            assertThat(policy.version()).isEqualTo("2.0.0");
            assertThat(policy.requiredPermissions()).containsExactly("analytics:read");
            assertThat(policy.allowedRoles()).isEmpty();
            assertThat(policy.allowedBusinessTypes())
                    .containsExactlyInAnyOrder(FactoryType.RESTAURANT, FactoryType.BRANCH);
            assertThat(policy.confirmationPolicy()).isEqualTo(ConfirmationPolicy.NOT_REQUIRED);
            assertThat(policy.idempotencyPolicy()).isEqualTo(IdempotencyPolicy.NOT_REQUIRED);
            assertThat(policy.allowedSources()).containsExactly(ToolExecutionSource.AI_CHAT);
            assertThat(policy.dataClassification()).isEqualTo(DataClassification.CONFIDENTIAL);
            assertThat(policy.egressPolicy().mode()).isEqualTo(EgressMode.ALLOWLIST_ONLY);
            assertThat(policy.egressPolicy().allowedDestinations())
                    .containsExactly("python-smartbi.owner-action-chat.v1");
        });
        assertThat(manifest.policies())
                .filteredOn(policy -> !policy.toolName().equals("restaurant_owner_action_advisor"))
                .allSatisfy(policy -> {
                    assertThat(policy.egressPolicy().mode()).isEqualTo(EgressMode.DENY_ALL);
                    assertThat(policy.egressPolicy().allowedDestinations()).isEmpty();
                });
    }

    @Test
    void rejectsDuplicateKeysAliasesAndOversizedDocuments() {
        String valid = onePolicyYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nschemaVersion: 1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid runtime tool policy YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String aliased = valid
                .replace("expectedPolicyCount: 1", "expectedPolicyCount: 2")
                .replace("  - implementationClass:",
                        "  - &policy\n    implementationClass:")
                + "  - *policy\n";
        assertThatThrownBy(() -> loader.load(new StringReader(aliased)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid runtime tool policy YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String oversized = valid.replace(
                "domainTags: [test]",
                "domainTags: ['" + "a".repeat(RuntimeToolPolicyLoader.YAML_CODE_POINT_LIMIT)
                        + "']");
        assertThatThrownBy(() -> loader.load(new StringReader(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid runtime tool policy YAML")
                .hasCauseInstanceOf(YAMLException.class);
    }

    @Test
    void rejectsUnknownMissingBlankIllegalAndDuplicateBindings() {
        String valid = onePolicyYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nunknownRoot: true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown=[unknownRoot]");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("    provenance: EXPLICIT\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing=[provenance]");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("    allowedRoles: []\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedRoles");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("    allowedBusinessTypes: [FACTORY]\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedBusinessTypes");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("toolName: safe_test", "toolName: ''"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName must be a non-blank string");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("riskLevel: HIGH", "riskLevel: EXTREME"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal value EXTREME");

        String duplicatePolicy = valid
                .replace("expectedPolicyCount: 1", "expectedPolicyCount: 2")
                + valid.substring(valid.indexOf("  - implementationClass:"));
        assertThatThrownBy(() -> loader.load(new StringReader(duplicatePolicy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate toolName");
    }

    @Test
    void rejectsNonExplicitProvenanceEmptyPermissionsAndMissingResources() {
        String valid = onePolicyYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("provenance: EXPLICIT", "provenance: LEGACY_INFERRED"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance must be EXPLICIT");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("requiredPermissions: [test:execute]", "requiredPermissions: []"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions or an allowed role");
        assertThatThrownBy(() -> loader.loadResource("ai/tool/gateway/missing-runtime.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource not found");
    }

    private String onePolicyYaml() {
        return """
                schemaVersion: 1
                expectedPolicyCount: 1
                policies:
                  - implementationClass: com.example.SafeTool
                    toolName: safe_test
                    actionType: UPDATE
                    riskLevel: HIGH
                    requiredPermissions: [test:execute]
                    allowedRoles: []
                    allowedBusinessTypes: [FACTORY]
                    domainTags: [test]
                    version: 2.0.0
                    supportsPreview: false
                    confirmationPolicy: REQUIRED_FOR_EXECUTION
                    approvalPolicy: NOT_REQUIRED
                    idempotencyPolicy: REQUIRED_FOR_EXECUTION
                    dataClassification: INTERNAL
                    allowedSources: [AI_CHAT]
                    egressPolicy:
                      mode: DENY_ALL
                      allowedDestinations: []
                    provenance: EXPLICIT
                """;
    }
}
