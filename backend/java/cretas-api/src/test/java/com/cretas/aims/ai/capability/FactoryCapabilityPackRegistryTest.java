package com.cretas.aims.ai.capability;

import com.cretas.aims.ai.capability.FactoryCapabilityPack.PackStatus;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.WorkflowReferenceType;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventory;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolGovernanceStatus;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryCapabilityPackRegistryTest {

    @Test
    void loadsExactlyFourPublishedDigestPinnedCompletePacks() {
        FactoryCapabilityPackRegistry registry = new FactoryCapabilityPackRegistry();
        assertThat(registry.packs()).hasSize(4);
        assertThat(registry.packs()).extracting(FactoryCapabilityPack::packId)
                .containsExactlyInAnyOrder(
                        "factory.operator", "factory.warehouse",
                        "factory.quality", "factory.manager");
        assertThat(registry.packs()).allSatisfy(pack -> {
            assertThat(pack.status()).isEqualTo(PackStatus.PUBLISHED);
            assertThat(pack.version()).matches("[0-9]+\\.[0-9]+\\.[0-9]+");
            assertThat(pack.businessTypes()).isEqualTo(
                    Set.of(FactoryType.FACTORY, FactoryType.CENTRAL_KITCHEN));
            assertThat(pack.roles()).isNotEmpty();
            assertThat(pack.instructions()).isNotBlank();
            assertThat(pack.readToolAllowlist()).isNotEmpty();
            assertThat(pack.workflowReferences()).isNotEmpty();
            assertThat(pack.outputSchema().schemaId()).isNotBlank();
            assertThat(pack.outputSchema().fields()).isNotEmpty();
            assertThat(pack.rules()).isNotEmpty();
            assertThat(pack.forbiddenActions()).isNotEmpty();
            assertThat(pack.fewShots()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(pack.evalCases()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(pack.digest()).isEqualTo(
                    FactoryCapabilityPackRegistry.EXPECTED_RESOURCE_DIGESTS.get(
                            pack.resourcePath()));
        });
    }

    @Test
    void allAllowlistedToolsRemainInventoryReadLowWithoutGovernancePromotion() {
        ToolDescriptorInventory inventory = new ToolDescriptorInventoryLoader().loadDefault();
        Map<String, ToolDescriptorInventoryEntry> byName = inventory.descriptors().stream()
                .collect(Collectors.toMap(
                        ToolDescriptorInventoryEntry::toolName, Function.identity()));
        new FactoryCapabilityPackRegistry().packs().forEach(pack ->
                pack.readToolAllowlist().forEach(tool -> {
                    ToolDescriptorInventoryEntry entry = byName.get(tool);
                    assertThat(entry).as(tool).isNotNull();
                    assertThat(entry.actionType()).as(tool).isEqualTo(ToolExecutor.ActionType.READ);
                    assertThat(entry.riskLevel()).as(tool).isEqualTo(ToolExecutor.RiskLevel.LOW);
                    assertThat(entry.governanceStatus()).as(tool)
                            .isEqualTo(ToolGovernanceStatus.REVIEW_REQUIRED);
                }));
    }

    @Test
    void workflowAndDomainBoundariesAreExplicitPerPack() {
        FactoryCapabilityPackRegistry registry = new FactoryCapabilityPackRegistry();
        FactoryCapabilityPack operator = registry.findById("factory.operator").orElseThrow();
        assertThat(operator.workflowReferences()).extracting(
                        FactoryCapabilityPack.WorkflowReference::referenceId)
                .contains("FORM:PRODUCTION_REPORT", "FORM:PRODUCTION_EXCEPTION_REPORT");

        FactoryCapabilityPack warehouse = registry.findById("factory.warehouse").orElseThrow();
        assertThat(warehouse.workflowReferences()).extracting(
                        FactoryCapabilityPack.WorkflowReference::referenceId)
                .contains("FORM:INVENTORY_INBOUND", "FORM:INVENTORY_OUTBOUND",
                        "FORM:INVENTORY_COUNT");

        FactoryCapabilityPack quality = registry.findById("factory.quality").orElseThrow();
        assertThat(quality.workflowReferences())
                .filteredOn(FactoryCapabilityPack.WorkflowReference::approvalRequired)
                .singleElement()
                .extracting(FactoryCapabilityPack.WorkflowReference::referenceId)
                .isEqualTo("INTENT:QUALITY_DISPOSITION_EXECUTE");

        FactoryCapabilityPack manager = registry.findById("factory.manager").orElseThrow();
        assertThat(manager.workflowReferences()).allSatisfy(reference -> {
            assertThat(reference.type()).isEqualTo(WorkflowReferenceType.NAVIGATION);
            assertThat(reference.mutation()).isFalse();
            assertThat(reference.approvalRequired()).isFalse();
        });
        assertThat(manager.instructions()).contains("预定义组合").contains("不得创建临时执行计划");
    }
}
