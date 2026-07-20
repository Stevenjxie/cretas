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
        assertThat(catalog.inventory().expectedToolCount()).isEqualTo(588);
        assertThat(catalog.inventory().expectedLegacyCount()).isEqualTo(580);
        assertThat(statistics.total()).isEqualTo(588);
        assertThat(statistics.legacy()).isEqualTo(580);
        assertThat(statistics.actionTypes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.ActionType.READ, 447L,
                ToolExecutor.ActionType.WRITE, 62L,
                ToolExecutor.ActionType.UPDATE, 27L,
                ToolExecutor.ActionType.DELETE, 11L,
                ToolExecutor.ActionType.ANALYZE, 19L,
                ToolExecutor.ActionType.GENERATE, 15L,
                ToolExecutor.ActionType.NOTIFY, 7L));
        assertThat(statistics.riskLevels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.RiskLevel.LOW, 511L,
                ToolExecutor.RiskLevel.MEDIUM, 72L,
                ToolExecutor.RiskLevel.HIGH, 5L,
                ToolExecutor.RiskLevel.CRITICAL, 0L));
        assertThat(statistics.previewSupported()).isEqualTo(38);
        assertThat(statistics.requiresPermission()).isEqualTo(39);
        assertThat(statistics.governanceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolGovernanceStatus.REVIEW_REQUIRED, 563L,
                ToolGovernanceStatus.REVIEW_REQUIRED_P0, 18L,
                ToolGovernanceStatus.APPROVED, 7L,
                ToolGovernanceStatus.WAIVED, 0L));

        assertThat(catalog.inventory().descriptors())
                .filteredOn(entry -> entry.provenance() == DescriptorProvenance.LEGACY_INFERRED)
                .allSatisfy(entry -> {
                    assertThat(entry.version()).isEqualTo("1.0.0");
                    assertThat(entry.requiredPermissions()).isEmpty();
                    assertThat(entry.governanceStatus()).isIn(
                            ToolGovernanceStatus.REVIEW_REQUIRED,
                            ToolGovernanceStatus.REVIEW_REQUIRED_P0);
                });
        assertThat(catalog.inventory().descriptors())
                .filteredOn(entry -> entry.governanceStatus() == ToolGovernanceStatus.APPROVED)
                .extracting(ToolDescriptorInventoryEntry::toolName)
                .containsExactlyInAnyOrder(
                        "user_disable",
                        "restaurant_dish_delete",
                        "restaurant_owner_action_advisor",
                        "canvas_product_work_process_config",
                        "canvas_work_process_catalog",
                        "product_create",
                        "bom_adjust");
    }

    @Test
    void exposesUniqueLookupsAndKeepsTheRemainingP0SetReviewBlocked() {
        ToolDescriptorCatalog catalog = ToolDescriptorCatalog.loadDefault();
        Set<String> actualP0 = catalog.inventory().descriptors().stream()
                .filter(entry -> entry.governanceStatus() == ToolGovernanceStatus.REVIEW_REQUIRED_P0)
                .map(ToolDescriptorInventoryEntry::toolName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> approvedP0 = Set.of("user_disable", "restaurant_dish_delete");

        assertThat(actualP0).containsExactlyInAnyOrderElementsOf(
                ToolDescriptorInventoryLoader.P0_TOOL_NAMES.stream()
                        .filter(toolName -> !approvedP0.contains(toolName))
                        .collect(Collectors.toUnmodifiableSet()));
        assertThat(actualP0).hasSize(18);
        for (String toolName : actualP0) {
            ToolDescriptorInventoryEntry byName = catalog.findByToolName(toolName).orElseThrow();
            assertThat(byName.governanceStatus()).isEqualTo(ToolGovernanceStatus.REVIEW_REQUIRED_P0);
            assertThat(catalog.findByImplementationClass(byName.implementationClass()))
                    .containsSame(byName);
        }
        for (String toolName : approvedP0) {
            assertThat(catalog.findByToolName(toolName).orElseThrow().governanceStatus())
                    .isEqualTo(ToolGovernanceStatus.APPROVED);
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
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("    allowedRoles: []\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit descriptor is missing allowedRoles");
        assertThatThrownBy(() -> loader.loadResource("ai/tool/gateway/missing.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource not found");
    }

    @Test
    void rejectsWaiverOrIncompleteApprovalForP0Tools() {
        String incompleteApproval = oneExplicitDescriptorYaml()
                .replace("safe_test", "canvas_set_user_permission")
                .replace("requiredPermissions: [test:execute]", "requiredPermissions: []");
        assertThatThrownBy(() -> loader.load(new StringReader(incompleteApproval)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete explicit source authorization metadata");

        String waiver = incompleteApproval.replace(
                "governanceStatus: APPROVED", "governanceStatus: WAIVED");
        assertThatThrownBy(() -> loader.load(new StringReader(waiver)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P0 tool must be REVIEW_REQUIRED_P0 or fully explicit APPROVED");
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
                    requiresPermission: true
                    requiredPermissions: [test:execute]
                    allowedRoles: []
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
