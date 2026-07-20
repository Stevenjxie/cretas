package com.cretas.aims.ai.workflow.inventory;

import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPack.PackStatus;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical inventory analysis: Workflow -> warehouse Capability Pack -> Gateway -> Presenter.
 *
 * <p>The execution plan and parameters are code-owned. Skill definitions, database overrides,
 * prompts, and extracted parameters cannot add, remove, reorder, or parameterize these calls.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public final class InventoryAnalysisWorkflow {

    public static final String CANONICAL_SKILL_NAME = "inventory-analysis";
    public static final String CAPABILITY_PACK_ID = "factory.warehouse";
    public static final String CAPABILITY_REFERENCE = "INTENT:INVENTORY_ANALYSIS";
    public static final List<String> APPROVED_TOOLS = List.of(
            "material_stock_summary",
            "material_batch_query",
            "material_expired_query");

    private static final String DESCRIPTOR_VERSION = "1.0.0";
    private static final String ASSERTED_ROLE_PLACEHOLDER = "skill_workflow";

    private final ToolExecutionGateway gateway;
    private final AuthenticatedToolPrincipalFactory principalFactory;
    private final FactoryCapabilityPackRegistry capabilityPackRegistry;
    private final InventoryAnalysisPresenter presenter;

    public InventoryAnalysisWorkflowResult execute(InventoryAnalysisWorkflowInput input) {
        FactoryCapabilityPack pack = requirePublishedWarehousePack();
        ExecutionPrincipal principal = principalFactory.create(
                input.factoryId(), input.userId(), ASSERTED_ROLE_PLACEHOLDER);
        Instant deadline = Instant.now().plusMillis(input.timeoutMs());
        String correlationId = correlationId(input.sessionId());
        String traceId = "inventory-analysis-" + UUID.randomUUID();
        List<String> executedTools = new ArrayList<>(APPROVED_TOOLS.size());
        List<ToolExecutionResult> results = new ArrayList<>(APPROVED_TOOLS.size());

        for (int index = 0; index < APPROVED_TOOLS.size(); index++) {
            String toolName = APPROVED_TOOLS.get(index);
            ToolExecutionCommand command = command(
                    toolName, index, principal, correlationId, traceId, deadline);
            ToolExecutionResult result;
            try {
                result = gateway.execute(command);
            } catch (RuntimeException gatewayFailure) {
                log.warn("Inventory workflow Gateway failure: tool={}, type={}",
                        toolName, gatewayFailure.getClass().getSimpleName());
                return InventoryAnalysisWorkflowResult.failed(
                        executedTools, "Inventory analysis failed at " + toolName);
            }
            if (result.status() != ToolExecutionStatus.SUCCEEDED) {
                log.warn("Inventory workflow stopped: tool={}, status={}",
                        toolName, result.status());
                return InventoryAnalysisWorkflowResult.failed(
                        executedTools,
                        "Inventory analysis was not authorized or completed at " + toolName);
            }
            executedTools.add(toolName);
            results.add(result);
        }

        return new InventoryAnalysisWorkflowResult(
                true,
                presenter.present(pack, results),
                executedTools,
                "Inventory analysis completed");
    }

    private FactoryCapabilityPack requirePublishedWarehousePack() {
        FactoryCapabilityPack pack = capabilityPackRegistry.findById(CAPABILITY_PACK_ID)
                .filter(candidate -> candidate.status() == PackStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException(
                        "Published warehouse capability pack is unavailable"));
        if (!pack.readToolAllowlist().containsAll(APPROVED_TOOLS)) {
            throw new IllegalStateException(
                    "Warehouse capability pack does not allow the inventory workflow tools");
        }
        boolean hasReference = pack.workflowReferences().stream()
                .anyMatch(reference -> CAPABILITY_REFERENCE.equals(reference.referenceId())
                        && !reference.mutation()
                        && !reference.approvalRequired());
        if (!hasReference) {
            throw new IllegalStateException(
                    "Warehouse capability pack does not publish inventory analysis");
        }
        return pack;
    }

    private static ToolExecutionCommand command(
            String toolName,
            int index,
            ExecutionPrincipal principal,
            String correlationId,
            String traceId,
            Instant deadline) {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        if ("material_batch_query".equals(toolName)) {
            parameters.put("page", 1);
            parameters.put("size", 20);
        }
        return new ToolExecutionCommand(
                "inventory-analysis-" + (index + 1) + "-" + UUID.randomUUID(),
                correlationId,
                traceId,
                toolName,
                DESCRIPTOR_VERSION,
                parameters,
                principal,
                ToolExecutionSource.SKILL_WORKFLOW,
                ToolExecutionMode.EXECUTE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                deadline);
    }

    private static String correlationId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return "inventory-analysis-session-" + sessionId;
        }
        return "inventory-analysis-" + UUID.randomUUID();
    }
}
