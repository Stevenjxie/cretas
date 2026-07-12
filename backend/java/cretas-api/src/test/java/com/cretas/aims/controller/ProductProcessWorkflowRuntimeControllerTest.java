package com.cretas.aims.controller;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductProcessWorkflowRuntimeControllerTest {

    private ProductProcessWorkflowRuntimeService runtimeService;
    private ProductProcessWorkflowRuntimeController controller;

    @BeforeEach
    void setUp() {
        runtimeService = mock(ProductProcessWorkflowRuntimeService.class);
        controller = new ProductProcessWorkflowRuntimeController(runtimeService);
    }

    @Test
    void delegatesFactoryScopedBatchAndReturnsNullWhenNoInstanceExists() {
        when(runtimeService.getRuntime("F006", 901L)).thenReturn(null);

        ApiResponse<ProductionWorkflowRuntimeDTO> response = controller.getRuntime("F006", 901L);

        assertNull(response.getData());
        verify(runtimeService).getRuntime("F006", 901L);
    }

    @Test
    void returnsOrderedTasksAndPortsWithoutCanvasPositionOrViewportFields() throws Exception {
        ProductionWorkflowRuntimeDTO runtime = ProductionWorkflowRuntimeDTO.builder()
                .workflowInstanceId(51L)
                .factoryId("F006")
                .productionBatchId(901L)
                .nodesJson("[{\"id\":\"trim\"}]")
                .edgesJson("[]")
                .tasks(List.of(
                        task("trim", 1, List.of(port("in", 1), port("out", 2))),
                        task("pack", 2, List.of(port("pack-in", 1)))))
                .build();
        when(runtimeService.getRuntime("F006", 901L)).thenReturn(runtime);

        ProductionWorkflowRuntimeDTO response = controller.getRuntime("F006", 901L).getData();

        assertEquals(List.of("trim", "pack"), response.getTasks().stream()
                .map(item -> item.getTask().getWorkflowNodeId()).toList());
        assertEquals(List.of("in", "out"), response.getTasks().get(0).getPorts().stream()
                .map(ProductionWorkflowRuntimeDTO.PortDTO::getWorkflowPortId).toList());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode json = mapper.valueToTree(response);
        assertFalse(json.has("position"));
        assertFalse(json.has("viewport"));
        assertFalse(json.toString().contains("\"position\""));
        assertFalse(json.toString().contains("\"viewport\""));
    }

    private ProductionWorkflowRuntimeDTO.TaskRuntimeDTO task(
            String nodeId, int order, List<ProductionWorkflowRuntimeDTO.PortDTO> ports) {
        return ProductionWorkflowRuntimeDTO.TaskRuntimeDTO.builder()
                .task(WorkProcessTaskDTO.builder()
                        .workflowNodeId(nodeId)
                        .processOrder(order)
                        .build())
                .ports(ports)
                .build();
    }

    private ProductionWorkflowRuntimeDTO.PortDTO port(String id, int ordinal) {
        return ProductionWorkflowRuntimeDTO.PortDTO.builder()
                .workflowPortId(id)
                .direction(WorkflowTaskPort.Direction.INPUT)
                .ordinal(ordinal)
                .build();
    }
}
