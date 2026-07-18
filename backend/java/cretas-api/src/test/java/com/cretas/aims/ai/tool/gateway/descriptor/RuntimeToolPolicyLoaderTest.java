package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeToolPolicyLoaderTest {

    private final RuntimeToolPolicyLoader loader = new RuntimeToolPolicyLoader();

    @Test
    void loadsOnlyTheTwoCompleteExplicitRuntimePolicies() {
        RuntimeToolPolicyManifest manifest = loader.loadDefault();

        assertThat(manifest.schemaVersion()).isEqualTo(1);
        assertThat(manifest.expectedPolicyCount()).isEqualTo(2);
        assertThat(manifest.policies())
                .extracting(RuntimeToolPolicyEntry::toolName)
                .containsExactly("user_disable", "restaurant_dish_delete");
        assertThat(manifest.policies()).allSatisfy(policy -> {
            assertThat(policy.provenance()).isEqualTo(DescriptorProvenance.EXPLICIT);
            assertThat(policy.requiredPermissions()).isNotEmpty();
            assertThat(policy.domainTags()).isNotEmpty();
            assertThat(policy.version()).isEqualTo("2.0.0");
            assertThat(policy.supportsPreview()).isFalse();
            assertThat(policy.confirmationPolicy())
                    .isEqualTo(ConfirmationPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.approvalPolicy())
                    .isEqualTo(ApprovalPolicy.NOT_REQUIRED);
            assertThat(policy.idempotencyPolicy())
                    .isEqualTo(IdempotencyPolicy.REQUIRED_FOR_EXECUTION);
            assertThat(policy.allowedSources()).containsExactly(ToolExecutionSource.AI_CHAT);
            assertThat(policy.egressPolicy().mode()).isEqualTo(EgressMode.DENY_ALL);
            assertThat(policy.egressPolicy().allowedDestinations()).isEmpty();
        });
        assertThat(manifest.policies().get(0).dataClassification())
                .isEqualTo(DataClassification.RESTRICTED);
        assertThat(manifest.policies().get(1).dataClassification())
                .isEqualTo(DataClassification.CONFIDENTIAL);
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
                .hasMessageContaining("at least one explicit permission code");
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
