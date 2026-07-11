package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.impl.ProductProcessWorkflowRuntimeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowRuntimeServiceTest {

    @Mock private FactoryRepository factoryRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductProcessWorkflowActivationRepository activationRepository;
    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private ProductionWorkflowInstanceRepository instanceRepository;
    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private WorkflowTaskPortRepository portRepository;
    @Mock private ProductProcessWorkflowRuntimeCompiler compiler;

    private ProductProcessWorkflowRuntimeService service;
    private final List<ProductionWorkflowInstance> savedInstances = new ArrayList<>();
    private final List<WorkProcessTask> savedTasks = new ArrayList<>();
    private final List<WorkflowTaskPort> savedPorts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProductProcessWorkflowRuntimeServiceImpl(
                factoryRepository,
                batchRepository,
                productTypeRepository,
                activationRepository,
                workflowRepository,
                instanceRepository,
                taskRepository,
                portRepository,
                compiler,
                new ObjectMapper());
    }

    @Test
    void materializesOneImmutableInstanceAndTasksByWorkflowNodeIdentity() {
        givenValidOwnedBatch();
        givenEnabledPublishedWorkflow();
        givenFreshRuntimePersistence(true);
        when(compiler.compile(any())).thenReturn(compiledWorkflow());

        Optional<List<WorkProcessTaskDTO>> result =
                service.materializeIfActive("F006", 901L, "PT-PIG");

        assertTrue(result.isPresent());
        assertEquals(List.of("trim-1", "trim-2", "pack"),
                savedTasks.stream().map(WorkProcessTask::getWorkflowNodeId).toList());
        assertEquals(List.of("TRIM", "TRIM", "PACK"),
                savedTasks.stream().map(WorkProcessTask::getWorkProcessId).toList());
        assertTrue(savedTasks.stream().allMatch(t -> t.getProductWorkProcessId() == null));
        assertTrue(savedTasks.stream().allMatch(t -> t.getWorkflowInstanceId().equals(501L)));
        assertEquals(6, savedPorts.size());
        assertTrue(savedPorts.stream().allMatch(p -> p.getFactoryId().equals("F006")));
        assertTrue(savedPorts.stream().allMatch(p -> p.getWorkflowInstanceId().equals(501L)));
        assertEquals(List.of("trim-1", "trim-2", "pack"), result.orElseThrow().stream()
                .map(WorkProcessTaskDTO::getWorkflowNodeId).toList());
        assertTrue(result.orElseThrow().stream()
                .allMatch(task -> task.getWorkflowInstanceId().equals(501L)));
    }

    @Test
    void validatesFactoryBatchAndProductBeforeCheckingRuntimeOrActivation() {
        when(factoryRepository.existsById("MISSING")).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("MISSING", 901L, "PT-PIG"));

        verifyNoInteractions(batchRepository, productTypeRepository, instanceRepository,
                activationRepository, workflowRepository, compiler, taskRepository, portRepository);
    }

    @Test
    void rejectsCrossFactoryBatchAndBatchProductMismatch() {
        when(factoryRepository.existsById("F006")).thenReturn(true);
        when(batchRepository.findByIdAndFactoryId(901L, "F006")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));
        verifyNoInteractions(productTypeRepository, instanceRepository, activationRepository,
                workflowRepository, compiler, taskRepository, portRepository);

        ProductionBatch otherProduct = batch("F006", "PT-CHICKEN");
        when(batchRepository.findByIdAndFactoryId(901L, "F006"))
                .thenReturn(Optional.of(otherProduct));
        when(productTypeRepository.findByIdAndFactoryId("PT-PIG", "F006"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(com.cretas.aims.entity.ProductType.class)));

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));
        verifyNoInteractions(instanceRepository, activationRepository, workflowRepository, compiler,
                taskRepository, portRepository);
    }

    @Test
    void inactiveWorkflowReturnsEmptyWithoutWritingRuntimeRows() {
        givenValidOwnedBatch();
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.empty());

        Optional<List<WorkProcessTaskDTO>> result =
                service.materializeIfActive("F006", 901L, "PT-PIG");

        assertTrue(result.isEmpty());
        verifyNoInteractions(workflowRepository, compiler, taskRepository, portRepository);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void disabledActivationReturnsEmptyWithoutReadingDefinitionOrWritingRuntimeRows() {
        givenValidOwnedBatch();
        ProductProcessWorkflowActivation disabled = activation();
        disabled.setEnabled(false);
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(disabled));

        Optional<List<WorkProcessTaskDTO>> result =
                service.materializeIfActive("F006", 901L, "PT-PIG");

        assertTrue(result.isEmpty());
        verifyNoInteractions(workflowRepository, compiler, taskRepository, portRepository);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void activationRejectsNonPublishedTarget() {
        givenValidOwnedBatch();
        ProductProcessWorkflow draft = workflow();
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        givenActivationTarget(draft);

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));

        verifyNoInteractions(compiler, taskRepository, portRepository);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void activationRejectsWrongProductTarget() {
        givenValidOwnedBatch();
        ProductProcessWorkflow wrongProduct = workflow();
        wrongProduct.setProductTypeId("PT-CHICKEN");
        givenActivationTarget(wrongProduct);

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));

        verifyNoInteractions(compiler, taskRepository, portRepository);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void repeatedCallReturnsExistingTasksWithoutReadingChangedDefinition() {
        givenValidOwnedBatch();
        ProductionWorkflowInstance existing = instance(501L, "old-nodes", "old-edges");
        WorkProcessTask existingTask = task(801L, 501L, "trim-1", "TRIM", 1);
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(existing));
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                "F006", 501L)).thenReturn(List.of(existingTask));

        List<WorkProcessTaskDTO> result = service
                .materializeIfActive("F006", 901L, "PT-PIG")
                .orElseThrow();

        assertEquals(1, result.size());
        assertEquals("trim-1", result.get(0).getWorkflowNodeId());
        verifyNoInteractions(activationRepository, workflowRepository, compiler, portRepository);
        verify(instanceRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void nonReportableNodeRemainsOnlyInSnapshotAndHasNoTaskOrPorts() {
        givenValidOwnedBatch();
        givenEnabledPublishedWorkflow();
        givenFreshRuntimePersistence(true);
        CompiledProductProcessWorkflow compiled = compiledWorkflow();
        when(compiler.compile(any())).thenReturn(compiled);

        service.materializeIfActive("F006", 901L, "PT-PIG");

        assertTrue(savedInstances.get(0).getNodesJson().contains("inspect-no-report"));
        assertFalse(savedTasks.stream()
                .anyMatch(task -> "inspect-no-report".equals(task.getWorkflowNodeId())));
        assertFalse(savedPorts.stream()
                .anyMatch(port -> port.getWorkflowPortId().startsWith("inspect")));
    }

    @Test
    void exactActivationMustStillPointToSamePublishedVersionAndProduct() {
        givenValidOwnedBatch();
        ProductProcessWorkflowActivation activation = activation();
        ProductProcessWorkflow changed = workflow();
        changed.setDefinitionVersion(4);
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(activation));
        when(workflowRepository.findByIdAndFactoryId(44L, "F006"))
                .thenReturn(Optional.of(changed));

        assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));

        verifyNoInteractions(compiler, taskRepository, portRepository);
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void portFailureBubblesOutOfTransactionalBoundaryWithoutBeingSwallowed() throws Exception {
        givenValidOwnedBatch();
        givenEnabledPublishedWorkflow();
        givenFreshRuntimePersistence(false);
        when(compiler.compile(any())).thenReturn(compiledWorkflow());
        RuntimeException failure = new RuntimeException("port write failed");
        doThrow(failure).when(portRepository).save(any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.materializeIfActive("F006", 901L, "PT-PIG"));

        assertSame(failure, thrown);
        assertNotNull(ProductProcessWorkflowRuntimeServiceImpl.class
                .getMethod("materializeIfActive", String.class, Long.class, String.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void getRuntimeIsFactoryScopedAndStableByProcessOrderThenPortOrdinal() {
        ProductionWorkflowInstance existing = instance(501L, "nodes", "edges");
        WorkProcessTask second = task(802L, 501L, "pack", "PACK", 2);
        WorkProcessTask first = task(801L, 501L, "trim-1", "TRIM", 1);
        WorkflowTaskPort firstOut = port(904L, 501L, 801L, "trim-out", 2);
        WorkflowTaskPort firstIn = port(903L, 501L, 801L, "trim-in", 1);
        WorkflowTaskPort secondIn = port(905L, 501L, 802L, "pack-in", 1);
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(existing));
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                "F006", 501L)).thenReturn(List.of(first, second));
        when(portRepository.findByFactoryIdAndWorkflowInstanceId("F006", 501L))
                .thenReturn(List.of(firstOut, secondIn, firstIn));

        ProductionWorkflowRuntimeDTO runtime = service.getRuntime("F006", 901L);

        assertEquals(List.of("trim-1", "pack"), runtime.getTasks().stream()
                .map(taskRuntime -> taskRuntime.getTask().getWorkflowNodeId()).toList());
        assertEquals(List.of("trim-in", "trim-out"), runtime.getTasks().get(0).getPorts()
                .stream().map(ProductionWorkflowRuntimeDTO.PortDTO::getWorkflowPortId).toList());
        assertEquals(List.of("pack-in"), runtime.getTasks().get(1).getPorts()
                .stream().map(ProductionWorkflowRuntimeDTO.PortDTO::getWorkflowPortId).toList());
    }

    private void givenValidOwnedBatch() {
        when(factoryRepository.existsById("F006")).thenReturn(true);
        when(batchRepository.findByIdAndFactoryId(901L, "F006"))
                .thenReturn(Optional.of(batch("F006", "PT-PIG")));
        when(productTypeRepository.findByIdAndFactoryId("PT-PIG", "F006"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(com.cretas.aims.entity.ProductType.class)));
    }

    private void givenEnabledPublishedWorkflow() {
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(activation()));
        when(workflowRepository.findByIdAndFactoryId(44L, "F006"))
                .thenReturn(Optional.of(workflow()));
    }

    private void givenActivationTarget(ProductProcessWorkflow workflow) {
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());
        when(activationRepository.findByFactoryIdAndProductTypeId("F006", "PT-PIG"))
                .thenReturn(Optional.of(activation()));
        when(workflowRepository.findByIdAndFactoryId(44L, "F006"))
                .thenReturn(Optional.of(workflow));
    }

    private void givenFreshRuntimePersistence(boolean persistPorts) {
        AtomicLong taskIds = new AtomicLong(800L);
        AtomicLong portIds = new AtomicLong(900L);
        when(instanceRepository.save(any())).thenAnswer(invocation -> {
            ProductionWorkflowInstance instance = invocation.getArgument(0);
            instance.setId(501L);
            savedInstances.add(instance);
            return instance;
        });
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            WorkProcessTask task = invocation.getArgument(0);
            task.setId(taskIds.incrementAndGet());
            savedTasks.add(task);
            return task;
        });
        if (persistPorts) {
            when(portRepository.save(any())).thenAnswer(invocation -> {
                WorkflowTaskPort port = invocation.getArgument(0);
                port.setId(portIds.incrementAndGet());
                savedPorts.add(port);
                return port;
            });
        }
    }

    private ProductionBatch batch(String factoryId, String productTypeId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(901L);
        batch.setFactoryId(factoryId);
        batch.setProductTypeId(productTypeId);
        return batch;
    }

    private ProductProcessWorkflowActivation activation() {
        ProductProcessWorkflowActivation activation = new ProductProcessWorkflowActivation();
        activation.setFactoryId("F006");
        activation.setProductTypeId("PT-PIG");
        activation.setActiveWorkflowId(44L);
        activation.setActiveDefinitionVersion(3);
        activation.setEnabled(true);
        return activation;
    }

    private ProductProcessWorkflow workflow() {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(44L);
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("PT-PIG");
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(3);
        workflow.setSchemaVersion(1);
        workflow.setNodesJson("[]");
        workflow.setEdgesJson("[]");
        workflow.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");
        return workflow;
    }

    private CompiledProductProcessWorkflow compiledWorkflow() {
        return new CompiledProductProcessWorkflow(
                "[{\"id\":\"trim-1\"},{\"id\":\"trim-2\"},"
                        + "{\"id\":\"inspect-no-report\"},{\"id\":\"pack\"}]",
                "[{\"source\":\"trim-1\",\"target\":\"trim-2\"}]",
                List.of(
                        new CompiledProductProcessWorkflow.CompiledTask(
                                "trim-1", "TRIM", 1, "kg", 10, true),
                        new CompiledProductProcessWorkflow.CompiledTask(
                                "trim-2", "TRIM", 2, "kg", 10, true),
                        new CompiledProductProcessWorkflow.CompiledTask(
                                "inspect-no-report", "INSPECT", 3, "kg", 5, false),
                        new CompiledProductProcessWorkflow.CompiledTask(
                                "pack", "PACK", 4, "box", 15, true)),
                List.of(
                        compiledPort("trim-1", "trim-1-in", "INPUT", 1),
                        compiledPort("trim-1", "trim-1-out", "OUTPUT", 2),
                        compiledPort("trim-2", "trim-2-in", "INPUT", 1),
                        compiledPort("trim-2", "trim-2-out", "OUTPUT", 2),
                        compiledPort("inspect-no-report", "inspect-in", "INPUT", 1),
                        compiledPort("pack", "pack-in", "INPUT", 1),
                        compiledPort("pack", "pack-out", "OUTPUT", 2)));
    }

    private CompiledProductProcessWorkflow.CompiledPort compiledPort(
            String nodeId, String portId, String direction, int ordinal) {
        return new CompiledProductProcessWorkflow.CompiledPort(
                nodeId, portId, direction, ordinal, "material-" + portId,
                "RAW_MATERIAL", "SKU-" + portId, "kg", true, null, null);
    }

    private ProductionWorkflowInstance instance(
            Long id, String nodesJson, String edgesJson) {
        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                "F006", 901L, "PT-PIG", 44L, 3, nodesJson, edgesJson,
                java.time.LocalDateTime.of(2026, 7, 11, 12, 0));
        instance.setId(id);
        return instance;
    }

    private WorkProcessTask task(
            Long id, Long instanceId, String nodeId, String processId, int order) {
        return WorkProcessTask.builder()
                .id(id)
                .factoryId("F006")
                .productionBatchId(901L)
                .productTypeId("PT-PIG")
                .workflowInstanceId(instanceId)
                .workflowNodeId(nodeId)
                .workProcessId(processId)
                .processOrder(order)
                .status(WorkProcessTask.Status.PENDING)
                .build();
    }

    private WorkflowTaskPort port(
            Long id, Long instanceId, Long taskId, String portId, int ordinal) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setId(id);
        port.setFactoryId("F006");
        port.setWorkflowInstanceId(instanceId);
        port.setTaskId(taskId);
        port.setWorkflowPortId(portId);
        port.setDirection(WorkflowTaskPort.Direction.INPUT);
        port.setOrdinal(ordinal);
        port.setMaterialNodeId("material-" + portId);
        port.setMaterialKind("RAW_MATERIAL");
        port.setSkuId("SKU-" + portId);
        port.setUnit("kg");
        port.setRequired(true);
        return port;
    }
}
