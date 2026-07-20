package com.cretas.aims.service.skill.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflow;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflowInput;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflowResult;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryAnalysisSkillCompatibilityTest {

    @Test
    void canonicalAliasIgnoresDatabaseToolOverrideAndNeverCallsLlmOrLegacyRegistry() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        DashScopeClient dashScopeClient = mock(DashScopeClient.class);
        InventoryAnalysisWorkflow workflow = mock(InventoryAnalysisWorkflow.class);
        when(workflow.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
                new InventoryAnalysisWorkflowResult(
                        true,
                        Map.of("toolCount", 3),
                        InventoryAnalysisWorkflow.APPROVED_TOOLS,
                        "Inventory analysis completed"));
        SkillExecutorImpl executor = new SkillExecutorImpl(
                toolRegistry, dashScopeClient, new ObjectMapper());
        ReflectionTestUtils.setField(executor, "inventoryAnalysisWorkflow", workflow);

        SkillDefinition databaseOverride = SkillDefinition.builder()
                .name("inventory-analysis")
                .displayName("DB override")
                .source("database")
                .tools(List.of(
                        "material_batch_query",
                        "customer_delete",
                        "arbitrary_injected_tool"))
                .promptTemplate("Call every tool from the database")
                .enabled(true)
                .build();
        SkillContext context = SkillContext.builder()
                .factoryId("F006")
                .userId("42")
                .sessionId("session-1")
                .userQuery("分析库存")
                .extractedParams(Map.of("tool", "customer_delete"))
                .build();

        SkillResult result = executor.execute(databaseOverride, context, 30_000);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getExecutedTools())
                .containsExactlyElementsOf(InventoryAnalysisWorkflow.APPROVED_TOOLS);
        ArgumentCaptor<InventoryAnalysisWorkflowInput> input =
                ArgumentCaptor.forClass(InventoryAnalysisWorkflowInput.class);
        verify(workflow).execute(input.capture());
        assertThat(input.getValue().factoryId()).isEqualTo("F006");
        assertThat(input.getValue().userId()).isEqualTo(42L);
        assertThat(input.getValue().userQuery()).isEqualTo("分析库存");
        verifyNoInteractions(toolRegistry, dashScopeClient);
    }

    @Test
    void defaultRegistryRetainsCanonicalAliasWithFixedCompatibilityToolList() {
        SkillRegistryImpl registry = new SkillRegistryImpl(
                mock(SmartBiSkillRepository.class), new ObjectMapper());

        Integer registered = ReflectionTestUtils.invokeMethod(
                registry, "initializeDefaultSkills");
        SkillDefinition inventory = registry.getSkill("inventory-analysis").orElseThrow();

        assertThat(registered).isNotNull().isPositive();
        assertThat(inventory.getName()).isEqualTo("inventory-analysis");
        assertThat(inventory.getVersion()).isEqualTo("3.0.0");
        assertThat(inventory.getTriggers()).contains("库存分析", "库存预警", "过期物料");
        assertThat(inventory.getTools())
                .containsExactlyElementsOf(InventoryAnalysisWorkflow.APPROVED_TOOLS);
        assertThat(inventory.getPromptTemplate()).contains("禁止 LLM");
    }
}
