package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowSpawnCompatibilityTest {

    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductProcessWorkflowRuntimeService runtimeService;

    private WorkProcessTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkProcessTaskServiceImpl(
                taskRepository,
                productWorkProcessRepository,
                workProcessRepository,
                userRepository,
                productionBatchRepository,
                productTypeRepository,
                runtimeService);
    }

    @Test
    void activeWorkflowWinsAndPreservesRepeatedWorkProcessNodes() {
        List<WorkProcessTaskDTO> workflowTasks = List.of(
                workflowTask("wf-node-trim-1", "TRIM"),
                workflowTask("wf-node-trim-2", "TRIM"));
        when(runtimeService.materializeIfActive("F006", 901L, "PT-PIG"))
                .thenReturn(Optional.of(workflowTasks));

        List<WorkProcessTaskDTO> result = service.spawnTasks("F006", 901L, "PT-PIG");

        assertEquals(List.of("wf-node-trim-1", "wf-node-trim-2"), result.stream()
                .map(WorkProcessTaskDTO::getWorkflowNodeId).toList());
        assertEquals(List.of("TRIM", "TRIM"), result.stream()
                .map(WorkProcessTaskDTO::getWorkProcessId).toList());
        verifyNoInteractions(productWorkProcessRepository, workProcessRepository);
        verify(taskRepository, never()).saveAll(any());
    }

    @Test
    void explicitSkipCreatesTwoSentinelTasksWithoutConsultingWorkflow() {
        when(taskRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<WorkProcessTaskDTO> result = service.spawnTasks(
                "F006", 902L, "PT-PIG", true, 71L, 72L);

        assertEquals(List.of("__MATERIAL_INPUT__", "__FINAL_OUTPUT__"), result.stream()
                .map(WorkProcessTaskDTO::getWorkProcessId).toList());
        verifyNoInteractions(runtimeService, productWorkProcessRepository);
    }

    @Test
    void alreadySpawnedBatchReturnsExistingTasksBeforeWorkflowLookup() {
        WorkProcessTask existing = WorkProcessTask.builder()
                .id(81L)
                .factoryId("F006")
                .productionBatchId(903L)
                .productTypeId("PT-PIG")
                .workProcessId("LEGACY")
                .processOrder(1)
                .status(WorkProcessTask.Status.PENDING)
                .build();
        when(taskRepository.existsByFactoryIdAndProductionBatchIdAndProductTypeId(
                "F006", 903L, "PT-PIG")).thenReturn(true);
        when(taskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                "F006", 903L)).thenReturn(List.of(existing));
        when(workProcessRepository.findByFactoryIdAndIdIn("F006", List.of("LEGACY")))
                .thenReturn(List.of());

        List<WorkProcessTaskDTO> result = service.spawnTasks("F006", 903L, "PT-PIG");

        assertEquals(81L, result.get(0).getId());
        verifyNoInteractions(runtimeService, productWorkProcessRepository);
    }

    @Test
    void inactiveOrDisabledActivationExecutesExactLegacyBranch() {
        ProductWorkProcess legacy = ProductWorkProcess.builder()
                .id(91L)
                .factoryId("F006")
                .productTypeId("PT-CHICKEN")
                .workProcessId("CUT")
                .processOrder(1)
                .isActive(true)
                .reportingRequired(true)
                .build();
        when(runtimeService.materializeIfActive("F006", 904L, "PT-CHICKEN"))
                .thenReturn(Optional.empty());
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
                "F006", "PT-CHICKEN")).thenReturn(List.of(legacy));
        when(workProcessRepository.findByFactoryIdAndIdIn("F006", List.of("CUT")))
                .thenReturn(List.of());
        when(taskRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<WorkProcessTaskDTO> result = service.spawnTasks("F006", 904L, "PT-CHICKEN");

        assertEquals(1, result.size());
        assertEquals(91L, result.get(0).getProductWorkProcessId());
        assertEquals("CUT", result.get(0).getWorkProcessId());
        assertEquals(null, result.get(0).getWorkflowNodeId());
    }

    private WorkProcessTaskDTO workflowTask(String nodeId, String processId) {
        return WorkProcessTaskDTO.builder()
                .factoryId("F006")
                .productionBatchId(901L)
                .productTypeId("PT-PIG")
                .workflowNodeId(nodeId)
                .workProcessId(processId)
                .build();
    }
}
