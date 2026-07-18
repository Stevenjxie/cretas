package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDescriptorCatalogTest {

    private final ToolDescriptorInventoryLoader loader = new ToolDescriptorInventoryLoader();

    @Test
    void loadsCurrentInventoryWithExactAuditStatisticsAndReviewDebt() {
        ToolDescriptorCatalog catalog = new ToolDescriptorCatalog(loader.loadDefault());
        ToolDescriptorStatistics statistics = catalog.statistics();

        assertThat(catalog.inventory().schemaVersion()).isEqualTo(1);
        assertThat(catalog.inventory().expectedToolCount()).isEqualTo(601);
        assertThat(catalog.inventory().expectedLegacyCount()).isEqualTo(601);
        assertThat(statistics.total()).isEqualTo(601);
        assertThat(statistics.legacy()).isEqualTo(601);
        assertThat(statistics.actionTypes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.ActionType.READ, 455L,
                ToolExecutor.ActionType.WRITE, 65L,
                ToolExecutor.ActionType.UPDATE, 27L,
                ToolExecutor.ActionType.DELETE, 13L,
                ToolExecutor.ActionType.ANALYZE, 18L,
                ToolExecutor.ActionType.GENERATE, 15L,
                ToolExecutor.ActionType.NOTIFY, 8L));
        assertThat(statistics.riskLevels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.RiskLevel.LOW, 522L,
                ToolExecutor.RiskLevel.MEDIUM, 76L,
                ToolExecutor.RiskLevel.HIGH, 3L,
                ToolExecutor.RiskLevel.CRITICAL, 0L));
        assertThat(statistics.previewSupported()).isEqualTo(39);
        assertThat(statistics.requiresPermission()).isEqualTo(38);
        assertThat(statistics.governanceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolGovernanceStatus.REVIEW_REQUIRED, 581L,
                ToolGovernanceStatus.REVIEW_REQUIRED_P0, 20L,
                ToolGovernanceStatus.APPROVED, 0L,
                ToolGovernanceStatus.WAIVED, 0L));

        assertThat(catalog.inventory().descriptors())
                .allSatisfy(entry -> {
                    assertThat(entry.provenance()).isEqualTo(DescriptorProvenance.LEGACY_INFERRED);
                    assertThat(entry.version()).isEqualTo("1.0.0");
                    assertThat(entry.requiredPermissions()).isEmpty();
                    assertThat(entry.governanceStatus()).isIn(
                            ToolGovernanceStatus.REVIEW_REQUIRED,
                            ToolGovernanceStatus.REVIEW_REQUIRED_P0);
                });
    }

    @Test
    void exposesUniqueLookupsAndKeepsTheExactP0SetUnapproved() {
        ToolDescriptorCatalog catalog = ToolDescriptorCatalog.loadDefault();
        Set<String> actualP0 = catalog.inventory().descriptors().stream()
                .filter(entry -> entry.governanceStatus() == ToolGovernanceStatus.REVIEW_REQUIRED_P0)
                .map(ToolDescriptorInventoryEntry::toolName)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actualP0).isEqualTo(ToolDescriptorInventoryLoader.P0_TOOL_NAMES);
        assertThat(actualP0).hasSize(20);
        for (String toolName : ToolDescriptorInventoryLoader.P0_TOOL_NAMES) {
            ToolDescriptorInventoryEntry byName = catalog.findByToolName(toolName).orElseThrow();
            assertThat(byName.governanceStatus()).isEqualTo(ToolGovernanceStatus.REVIEW_REQUIRED_P0);
            assertThat(catalog.findByImplementationClass(byName.implementationClass()))
                    .containsSame(byName);
        }
        assertThat(catalog.findByToolName("does_not_exist")).isEmpty();
        assertThat(catalog.findByImplementationClass("com.example.DoesNotExist")).isEmpty();
    }

    @Test
    void rejectsDuplicateKeysAliasesAndOversizedDocuments() {
        String valid = oneExplicitDescriptorYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nschemaVersion: 1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String aliased = valid
                .replace("expectedToolCount: 1", "expectedToolCount: 2")
                .replace("  - toolName:", "  - &entry\n    toolName:")
                + "  - *entry\n";
        assertThatThrownBy(() -> loader.load(new StringReader(aliased)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String oversized = valid.replace(
                "domainTags: [test]",
                "domainTags: ['" + "a".repeat(ToolDescriptorInventoryLoader.YAML_CODE_POINT_LIMIT)
                        + "']");
        assertThatThrownBy(() -> loader.load(new StringReader(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);
    }

    @Test
    void rejectsUnknownFieldsIllegalEnumsBlankIdentityAndMissingResources() {
        String valid = oneExplicitDescriptorYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nunknownRoot: true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown=[unknownRoot]");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("actionType: READ", "actionType: EXECUTE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal value EXECUTE");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("implementationClass: com.example.SafeTool",
                        "implementationClass: ''"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implementationClass must be a non-blank string");
        assertThatThrownBy(() -> loader.loadResource("ai/tool/gateway/missing.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource not found");
    }

    @Test
    void rejectsApprovalOrWaiverForAnyP0Tool() {
        for (ToolGovernanceStatus unsafe : Set.of(
                ToolGovernanceStatus.APPROVED, ToolGovernanceStatus.WAIVED)) {
            String yaml = oneExplicitDescriptorYaml()
                    .replace("safe_test", "canvas_set_user_permission")
                    .replace("governanceStatus: APPROVED", "governanceStatus: " + unsafe);

            assertThatThrownBy(() -> loader.load(new StringReader(yaml)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("P0 tool must remain REVIEW_REQUIRED_P0");
        }
    }

    private String oneExplicitDescriptorYaml() {
        return """
                schemaVersion: 1
                expectedToolCount: 1
                expectedLegacyCount: 0
                descriptors:
                  - toolName: safe_test
                    implementationClass: com.example.SafeTool
                    provenance: EXPLICIT
                    actionType: READ
                    riskLevel: LOW
                    supportsPreview: false
                    requiresPermission: false
                    requiredPermissions: []
                    version: 1.0.0
                    domainTags: [test]
                    overrideFlags:
                      actionType: true
                      riskLevel: true
                      supportsPreview: true
                      requiresPermission: true
                      hasPermission: true
                      requiredPermissions: true
                      version: true
                      domainTags: true
                    governanceStatus: APPROVED
                """;
    }
}
