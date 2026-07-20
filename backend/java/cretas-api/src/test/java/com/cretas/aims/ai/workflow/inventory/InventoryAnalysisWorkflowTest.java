package com.cretas.aims.ai.workflow.inventory;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryAnalysisWorkflowTest {

    @Test
    void executesExactlyThreePackApprovedGatewayCallsInFixedOrder() {
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        AuthenticatedToolPrincipalFactory principalFactory =
                mock(AuthenticatedToolPrincipalFactory.class);
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F006", "FACTORY", "42", PrincipalType.USER,
                Set.of("skill_workflow"), Set.of(), Set.of());
        when(principalFactory.create("F006", 42L, "skill_workflow")).thenReturn(principal);
        when(gateway.execute(any())).thenAnswer(invocation -> succeeded(
                invocation.<ToolExecutionCommand>getArgument(0)));

        InventoryAnalysisWorkflow workflow = new InventoryAnalysisWorkflow(
                gateway,
                principalFactory,
                new FactoryCapabilityPackRegistry(),
                new InventoryAnalysisPresenter());

        InventoryAnalysisWorkflowResult result = workflow.execute(
                new InventoryAnalysisWorkflowInput(
                        "F006", 42L, "session-1", "分析库存", 30_000));

        assertThat(result.success()).isTrue();
        assertThat(result.executedTools()).containsExactlyElementsOf(
                InventoryAnalysisWorkflow.APPROVED_TOOLS);
        assertThat(result.data()).containsEntry("toolCount", 3);
        assertThat(result.data()).containsKeys(
                "inventorySummary", "batchInventory", "expiredInventory");

        ArgumentCaptor<ToolExecutionCommand> commands =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway, times(3)).execute(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(ToolExecutionCommand::toolName)
                .containsExactlyElementsOf(InventoryAnalysisWorkflow.APPROVED_TOOLS);
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.source()).isEqualTo(ToolExecutionSource.SKILL_WORKFLOW);
            assertThat(command.mode()).isEqualTo(ToolExecutionMode.EXECUTE);
            assertThat(command.expectedDescriptorVersion()).isEqualTo("1.0.0");
            assertThat(command.principal()).isSameAs(principal);
            assertThat(command.idempotencyKey()).isEmpty();
            assertThat(command.confirmationProof()).isEmpty();
            assertThat(command.approvalProof()).isEmpty();
        });
        assertThat(commands.getAllValues().get(0).parameters()).isEmpty();
        assertThat(commands.getAllValues().get(1).parameters().path("page").asInt()).isEqualTo(1);
        assertThat(commands.getAllValues().get(1).parameters().path("size").asInt()).isEqualTo(20);
        assertThat(commands.getAllValues().get(2).parameters()).isEmpty();
    }

    @Test
    void failsClosedAndStopsAfterFirstNonSuccessGatewayResult() {
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        AuthenticatedToolPrincipalFactory principalFactory =
                mock(AuthenticatedToolPrincipalFactory.class);
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F006", "FACTORY", "42", PrincipalType.USER,
                Set.of("skill_workflow"), Set.of(), Set.of());
        when(principalFactory.create(any(), any(), any())).thenReturn(principal);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            if ("material_batch_query".equals(command.toolName())) {
                return result(command, ToolExecutionStatus.DENIED);
            }
            return succeeded(command);
        });
        InventoryAnalysisWorkflow workflow = new InventoryAnalysisWorkflow(
                gateway,
                principalFactory,
                new FactoryCapabilityPackRegistry(),
                new InventoryAnalysisPresenter());

        InventoryAnalysisWorkflowResult result = workflow.execute(
                new InventoryAnalysisWorkflowInput(
                        "F006", 42L, "session-1", "分析库存", 30_000));

        assertThat(result.success()).isFalse();
        assertThat(result.executedTools()).containsExactly("material_stock_summary");
        verify(gateway, times(2)).execute(any());
    }

    private static ToolExecutionResult succeeded(ToolExecutionCommand command) {
        return result(command, ToolExecutionStatus.SUCCEEDED);
    }

    private static ToolExecutionResult result(
            ToolExecutionCommand command, ToolExecutionStatus status) {
        return new ToolExecutionResult(
                command.requestId(),
                command.toolName(),
                command.expectedDescriptorVersion(),
                "audit-" + command.toolName(),
                command.traceId(),
                status,
                JsonNodeFactory.instance.objectNode().put("tool", command.toolName()),
                status.name(),
                false);
    }
}
