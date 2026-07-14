package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.impl.WorkflowClerkSheetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowClerkSheetServiceTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductionWorkflowInstanceRepository instanceRepository;
    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private WorkflowTaskPortRepository portRepository;
    @Mock private WorkProcessRepository workProcessRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ProductTypeRepository productTypeRepository;

    private WorkflowClerkSheetService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowClerkSheetServiceImpl(
                productionBatchRepository,
                instanceRepository,
                taskRepository,
                portRepository,
                workProcessRepository,
                productWorkProcessRepository,
                rawMaterialTypeRepository,
                productTypeRepository);
    }

    @Test
    void returnsNullWhenPlanHasNoWorkflowBatch() {
        ProductionBatch legacyBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.LEGACY);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(legacyBatch));

        WorkflowClerkSheetConfigDTO result = service.getWorkflowSheetConfig("F006", "PLAN-1");

        assertNull(result);
    }

    @Test
    void returnsNullWhenPlanHasNoBatchesAtAll() {
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of());

        assertNull(service.getWorkflowSheetConfig("F006", "PLAN-1"));
    }

    @Test
    void rejectsAmbiguousMultipleWorkflowBatches() {
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(
                        batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW),
                        batch(902L, ProductionBatch.WorkflowSelectionMode.WORKFLOW)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.getWorkflowSheetConfig("F006", "PLAN-1"));

        assertEquals("WORKFLOW_RUNTIME_BATCH_AMBIGUOUS", error.getErrorCode());
    }

    @Test
    void rejectsWorkflowBatchThatHasNotBeenMaterializedYet() {
        ProductionBatch workflowBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(workflowBatch));
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.getWorkflowSheetConfig("F006", "PLAN-1"));

        assertEquals("WORKFLOW_RUNTIME_NOT_MATERIALIZED", error.getErrorCode());
    }

    @Test
    void projectsOrderedDescriptorsWithResolvedNamesAndSingleOutput() {
        ProductionBatch workflowBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        ProductionWorkflowInstance instance = instance(501L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(workflowBatch));
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(instance));

        WorkProcessTask trim = task(801L, 501L, "trim", "TRIM", 1, "kg");
        WorkProcessTask pack = task(802L, 501L, "pack", "PACK", 2, "box");
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", 501L))
                .thenReturn(List.of(trim, pack));

        WorkflowTaskPort trimIn = port(801L, "trim-in", WorkflowTaskPort.Direction.INPUT,
                1, "RAW_MATERIAL", "RM-1", "kg", true);
        WorkflowTaskPort trimOut = port(801L, "trim-out", WorkflowTaskPort.Direction.OUTPUT,
                2, "SEMI_FINISHED", "PT-SEMI", "kg", true);
        WorkflowTaskPort packIn = port(802L, "pack-in", WorkflowTaskPort.Direction.INPUT,
                1, "SEMI_FINISHED", "PT-SEMI", "kg", true);
        WorkflowTaskPort packOut = port(802L, "pack-out", WorkflowTaskPort.Direction.OUTPUT,
                2, "FINISHED_GOOD", "PT-FG", "box", true);
        when(portRepository.findByFactoryIdAndWorkflowInstanceId("F006", 501L))
                .thenReturn(List.of(trimIn, trimOut, packIn, packOut));

        WorkProcess trimProcess = workProcess("TRIM", "修整", List.of(Map.of("key", "note")));
        WorkProcess packProcess = workProcess("PACK", "包装", null);
        when(workProcessRepository.findByFactoryIdAndId("F006", "TRIM"))
                .thenReturn(Optional.of(trimProcess));
        when(workProcessRepository.findByFactoryIdAndId("F006", "PACK"))
                .thenReturn(Optional.of(packProcess));

        ProductWorkProcess trimConfig = new ProductWorkProcess();
        trimConfig.setDefaultCostCategory("RAW_MATERIAL");
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndWorkProcessId("F006", "PT-PIG", "TRIM"))
                .thenReturn(Optional.of(trimConfig));
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndWorkProcessId("F006", "PT-PIG", "PACK"))
                .thenReturn(Optional.empty());

        RawMaterialType rawMaterial = rawMaterialType("RM-1", "F006", "去骨腿肉", "kg");
        when(rawMaterialTypeRepository.findById("RM-1")).thenReturn(Optional.of(rawMaterial));

        ProductType semiProduct = productType("PT-SEMI", "F006", "去骨腿肉半成品", "kg");
        ProductType finishedProduct = productType("PT-FG", "F006", "卤猪蹄成品", "box");
        when(productTypeRepository.findByIdAndFactoryId("PT-SEMI", "F006"))
                .thenReturn(Optional.of(semiProduct));
        when(productTypeRepository.findByIdAndFactoryId("PT-FG", "F006"))
                .thenReturn(Optional.of(finishedProduct));

        WorkflowClerkSheetConfigDTO result = service.getWorkflowSheetConfig("F006", "PLAN-1");

        assertEquals(901L, result.getWorkflowBatchId());
        assertEquals(501L, result.getWorkflowInstanceId());
        assertEquals("PT-PIG", result.getProductTypeId());
        assertEquals(2, result.getProcesses().size());

        WorkflowClerkSheetConfigDTO.ProcessDescriptor trimDescriptor = result.getProcesses().get(0);
        assertEquals("trim", trimDescriptor.getWorkflowNodeId());
        assertEquals("TRIM", trimDescriptor.getWorkProcessId());
        assertEquals("修整", trimDescriptor.getProcessName());
        assertEquals("RAW_MATERIAL", trimDescriptor.getDefaultCostCategory());
        assertEquals(1, trimDescriptor.getProcessOrder());
        assertEquals("kg", trimDescriptor.getPlannedUnit());
        assertFalse(trimDescriptor.getAllowMultipleUpstreamSources());
        assertFalse(trimDescriptor.getAllowFinishedGoodsSource());
        assertEquals(List.of(Map.of("key", "note")), trimDescriptor.getCustomFieldSchema());
        assertEquals(1, trimDescriptor.getInputs().size());
        assertEquals("去骨腿肉", trimDescriptor.getInputs().get(0).getMaterialName());
        assertTrue(trimDescriptor.getInputs().get(0).getSkuResolved());
        assertEquals("去骨腿肉半成品", trimDescriptor.getOutput().getMaterialName());
        assertFalse(trimDescriptor.getOutput().getFinished());

        WorkflowClerkSheetConfigDTO.ProcessDescriptor packDescriptor = result.getProcesses().get(1);
        assertEquals("pack", packDescriptor.getWorkflowNodeId());
        assertEquals("包装", packDescriptor.getProcessName());
        assertNull(packDescriptor.getDefaultCostCategory());
        assertNull(packDescriptor.getCustomFieldSchema());
        assertEquals("卤猪蹄成品", packDescriptor.getOutput().getMaterialName());
        assertTrue(packDescriptor.getOutput().getFinished());
        assertEquals("box", packDescriptor.getOutput().getUnit());
    }

    @Test
    void flagsUnresolvedSkuWithoutCrashingWhenReferencedProductWasDeleted() {
        ProductionBatch workflowBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        ProductionWorkflowInstance instance = instance(501L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(workflowBatch));
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(instance));

        WorkProcessTask trim = task(801L, 501L, "trim", "TRIM", 1, "kg");
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", 501L))
                .thenReturn(List.of(trim));

        WorkflowTaskPort trimOut = port(801L, "trim-out", WorkflowTaskPort.Direction.OUTPUT,
                1, "SEMI_FINISHED", "PT-DELETED", "kg", true);
        when(portRepository.findByFactoryIdAndWorkflowInstanceId("F006", 501L))
                .thenReturn(List.of(trimOut));
        when(workProcessRepository.findByFactoryIdAndId("F006", "TRIM")).thenReturn(Optional.empty());
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndWorkProcessId("F006", "PT-PIG", "TRIM"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("PT-DELETED", "F006"))
                .thenReturn(Optional.empty());

        WorkflowClerkSheetConfigDTO result = service.getWorkflowSheetConfig("F006", "PLAN-1");

        WorkflowClerkSheetConfigDTO.PortDescriptor output = result.getProcesses().get(0).getOutput();
        assertFalse(output.getSkuResolved());
        assertNull(output.getMaterialName());
        assertEquals("PT-DELETED", output.getSkuId());
        assertNull(result.getProcesses().get(0).getProcessName());
    }

    @Test
    void projectsMultipleOutputPortsFromPinnedRuntimeSnapshot() {
        ProductionBatch workflowBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        ProductionWorkflowInstance instance = instance(501L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(workflowBatch));
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(instance));

        WorkProcessTask trim = task(801L, 501L, "trim", "TRIM", 1, "kg");
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", 501L))
                .thenReturn(List.of(trim));

        WorkflowTaskPort outA = port(801L, "trim-out-a", WorkflowTaskPort.Direction.OUTPUT,
                1, "SEMI_FINISHED", "PT-SEMI", "kg", true);
        WorkflowTaskPort outB = port(801L, "trim-out-b", WorkflowTaskPort.Direction.OUTPUT,
                2, "SEMI_FINISHED", "PT-SEMI-2", "kg", true);
        when(portRepository.findByFactoryIdAndWorkflowInstanceId("F006", 501L))
                .thenReturn(List.of(outA, outB));

        WorkflowClerkSheetConfigDTO result = service.getWorkflowSheetConfig("F006", "PLAN-1");

        assertEquals(2, result.getProcesses().get(0).getOutputs().size());
        assertEquals("trim-out-a", result.getProcesses().get(0).getOutputs().get(0).getWorkflowPortId());
        assertEquals("trim-out-b", result.getProcesses().get(0).getOutputs().get(1).getWorkflowPortId());
        assertEquals("trim-out-a", result.getProcesses().get(0).getOutput().getWorkflowPortId());
    }

    @Test
    void crossFactorySkuMatchIsTreatedAsUnresolved() {
        ProductionBatch workflowBatch = batch(901L, ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        ProductionWorkflowInstance instance = instance(501L);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "PLAN-1"))
                .thenReturn(List.of(workflowBatch));
        when(instanceRepository.findByFactoryIdAndProductionBatchId("F006", 901L))
                .thenReturn(Optional.of(instance));

        WorkProcessTask trim = task(801L, 501L, "trim", "TRIM", 1, "kg");
        when(taskRepository.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc("F006", 501L))
                .thenReturn(List.of(trim));

        WorkflowTaskPort trimOut = port(801L, "trim-out", WorkflowTaskPort.Direction.OUTPUT,
                1, "RAW_MATERIAL", "RM-OTHER-FACTORY", "kg", true);
        when(portRepository.findByFactoryIdAndWorkflowInstanceId("F006", 501L))
                .thenReturn(List.of(trimOut));
        when(workProcessRepository.findByFactoryIdAndId("F006", "TRIM")).thenReturn(Optional.empty());
        when(productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdAndWorkProcessId("F006", "PT-PIG", "TRIM"))
                .thenReturn(Optional.empty());

        RawMaterialType otherFactoryMaterial =
                rawMaterialType("RM-OTHER-FACTORY", "F999", "别厂原料", "kg");
        when(rawMaterialTypeRepository.findById("RM-OTHER-FACTORY"))
                .thenReturn(Optional.of(otherFactoryMaterial));

        WorkflowClerkSheetConfigDTO result = service.getWorkflowSheetConfig("F006", "PLAN-1");

        WorkflowClerkSheetConfigDTO.PortDescriptor output = result.getProcesses().get(0).getOutput();
        assertFalse(output.getSkuResolved());
        assertNull(output.getMaterialName());
    }

    private ProductionBatch batch(Long id, ProductionBatch.WorkflowSelectionMode mode) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId("F006");
        batch.setProductTypeId("PT-PIG");
        batch.setWorkflowSelectionMode(mode);
        return batch;
    }

    private ProductionWorkflowInstance instance(Long id) {
        ProductionWorkflowInstance instance = ProductionWorkflowInstance.create(
                "F006", 901L, "PT-PIG", 44L, 3, "[]", "[]",
                LocalDateTime.of(2026, 7, 11, 12, 0));
        instance.setId(id);
        return instance;
    }

    private WorkProcessTask task(
            Long id, Long instanceId, String nodeId, String processId, int order, String unit) {
        return WorkProcessTask.builder()
                .id(id)
                .factoryId("F006")
                .productionBatchId(901L)
                .productTypeId("PT-PIG")
                .workflowInstanceId(instanceId)
                .workflowNodeId(nodeId)
                .workProcessId(processId)
                .processOrder(order)
                .plannedUnit(unit)
                .status(WorkProcessTask.Status.PENDING)
                .build();
    }

    private WorkflowTaskPort port(
            Long taskId, String portId, WorkflowTaskPort.Direction direction, int ordinal,
            String materialKind, String skuId, String unit, boolean required) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId("F006");
        port.setWorkflowInstanceId(501L);
        port.setTaskId(taskId);
        port.setWorkflowPortId(portId);
        port.setDirection(direction);
        port.setOrdinal(ordinal);
        port.setMaterialNodeId("material-" + portId);
        port.setMaterialKind(materialKind);
        port.setSkuId(skuId);
        port.setUnit(unit);
        port.setRequired(required);
        return port;
    }

    private WorkProcess workProcess(String id, String name, List<Map<String, Object>> customFieldSchema) {
        WorkProcess workProcess = new WorkProcess();
        workProcess.setId(id);
        workProcess.setFactoryId("F006");
        workProcess.setProcessName(name);
        workProcess.setCustomFieldSchema(customFieldSchema);
        return workProcess;
    }

    private RawMaterialType rawMaterialType(String id, String factoryId, String name, String unit) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setFactoryId(factoryId);
        type.setName(name);
        type.setUnit(unit);
        return type;
    }

    private ProductType productType(String id, String factoryId, String name, String unit) {
        ProductType type = new ProductType();
        type.setId(id);
        type.setFactoryId(factoryId);
        type.setName(name);
        type.setUnit(unit);
        return type;
    }
}
