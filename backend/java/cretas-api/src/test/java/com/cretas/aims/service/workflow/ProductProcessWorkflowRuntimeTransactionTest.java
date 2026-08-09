package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductType;
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
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.impl.ProductProcessWorkflowRuntimeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// Broadened from "com.cretas.aims.entity" to the root package after origin/main added
// sibling entity packages (e.g. com.cretas.aims.logistics.entity) whose repositories the
// JPA slice wires; @EntityScan only registers @Entity classes, mirroring the real app.
@EntityScan(basePackages = "com.cretas.aims")
@Import({
        ProductProcessWorkflowRuntimeServiceImpl.class,
        ProductProcessWorkflowRuntimeCompiler.class,
        ProductProcessWorkflowValidator.class,
        ProductProcessWorkflowRuntimeTransactionTest.TestBeans.class
})
class ProductProcessWorkflowRuntimeTransactionTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean private FactoryRepository factoryRepository;
    @MockBean private ProductionBatchRepository batchRepository;
    @MockBean private ProductTypeRepository productTypeRepository;
    @MockBean private ProductProcessWorkflowActivationRepository activationRepository;
    @MockBean private ProductProcessWorkflowRepository workflowRepository;
    @MockBean private com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository
            revisionRepository;
    @MockBean private com.cretas.aims.repository.unit.ProductUnitConversionRepository conversionRepository;
    @MockBean private com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator unitValidator;
    @MockBean private com.cretas.aims.service.unit.UnitContractService unitContractService;
    @MockBean private WorkflowReportingUnitResolver workflowReportingUnitResolver;

    @Autowired private ProductProcessWorkflowRuntimeService runtimeService;
    @Autowired private ProductionWorkflowInstanceRepository instanceRepository;
    @Autowired private WorkProcessTaskRepository taskRepository;
    @Autowired private WorkflowTaskPortRepository portRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private ProductProcessWorkflowRuntimeCompiler compiler;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void portConstraintFailureRollsBackInstanceTasksAndPortsAcrossRealTransaction() {
        givenValidScopeAndActivation();
        doReturn(compiledWithInvalidPort()).when(compiler).compile(any());

        assertThrows(RuntimeException.class,
                () -> runtimeService.materializeIfActive("TX-F001", 9901L, "TX-PIG"));

        TransactionTemplate freshTransaction = new TransactionTemplate(transactionManager);
        long[] persistedCounts = freshTransaction.execute(status -> new long[] {
                instanceRepository.count(),
                taskRepository.count(),
                portRepository.count()
        });
        assertArrayEquals(new long[] {0L, 0L, 0L}, persistedCounts);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void selectionGroupSnapshotPersistsAndReloadsThroughRealJpaContext() {
        givenValidScopeAndActivation();
        doReturn(compiledWithSelectionGroup()).when(compiler).compile(any());

        runtimeService.materializeIfActive("TX-F001", 9901L, "TX-PIG");

        TransactionTemplate freshTransaction = new TransactionTemplate(transactionManager);
        WorkflowTaskPort persisted = freshTransaction.execute(status -> portRepository.findAll().getFirst());
        assertEquals("input-alternatives", persisted.getSelectionGroupId());
        assertEquals("替代投入", persisted.getSelectionGroupLabel());
        assertEquals("EXACTLY_ONE", persisted.getSelectionGroupMode());
        assertEquals(1, persisted.getSelectionGroupMinSelections());
        assertEquals(1, persisted.getSelectionGroupMaxSelections());
    }

    private void givenValidScopeAndActivation() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(9901L);
        batch.setFactoryId("TX-F001");
        batch.setProductTypeId("TX-PIG");
        batch.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        batch.setSelectedWorkflowId(7701L);
        batch.setSelectedWorkflowVersion(4);
        // 🔴 「画布即 BOM」之后, 批次必须带**完整**权威(精确 revision + BOM 家族/配方/版本/产出单位),
        //    否则 materializeIfActive 直接 WORKFLOW_BATCH_AUTHORITY_INCOMPLETE。
        //    本测真正要验的是端口选择组能否穿过真 JPA 往返, 权威只是入场券 —— 补齐即可。
        batch.setSelectedWorkflowRevisionId(8801L);
        batch.setSelectedWorkflowRevisionHash("tx-revision-hash");
        batch.setSelectedBomFamilyId("TX-BOM-FAMILY");
        batch.setSelectedBomRecipeIdsByProduct(
                new java.util.LinkedHashMap<>(java.util.Map.of("TX-PIG", "TX-RECIPE")));
        batch.setSelectedBomVersionsByProduct(
                new java.util.LinkedHashMap<>(java.util.Map.of("TX-PIG", 1)));
        batch.setWorkflowOutputUnitsByProduct(
                new java.util.LinkedHashMap<>(java.util.Map.of("TX-PIG", "kg")));
        batch.setTargetFinishedGoodIds(java.util.List.of("TX-PIG"));

        com.cretas.aims.entity.ProductProcessWorkflowRevision revision =
                new com.cretas.aims.entity.ProductProcessWorkflowRevision();
        revision.setId(8801L);
        revision.setFactoryId("TX-F001");
        revision.setProductTypeId("TX-PIG");
        revision.setWorkflowId(7701L);
        revision.setDefinitionVersion(4);
        revision.setRevisionNumber(1);
        revision.setRevisionHash("tx-revision-hash");
        revision.setStatus(
                com.cretas.aims.entity.ProductProcessWorkflowRevision.Status.PUBLISHED);

        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(7701L);
        workflow.setFactoryId("TX-F001");
        workflow.setProductTypeId("TX-PIG");
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(4);
        workflow.setSchemaVersion(1);
        workflow.setNodesJson("[]");
        workflow.setEdgesJson("[]");
        workflow.setViewportJson("{\"x\":0,\"y\":0,\"zoom\":1}");

        when(factoryRepository.existsById("TX-F001")).thenReturn(true);
        when(batchRepository.findByIdAndFactoryId(9901L, "TX-F001"))
                .thenReturn(Optional.of(batch));
        when(productTypeRepository.findByIdAndFactoryId("TX-PIG", "TX-F001"))
                .thenReturn(Optional.of(mock(ProductType.class)));
        when(workflowRepository.lockByIdAndFactoryId(7701L, "TX-F001"))
                .thenReturn(Optional.of(workflow));
        when(revisionRepository.findByIdAndFactoryId(8801L, "TX-F001"))
                .thenReturn(Optional.of(revision));
        // unitContractService 是 @MockBean, 不打桩就返回 null → normalized.recognized() NPE。
        // 单位识别不是本测的被测行为, 给一个「认得出的 kg」即可。
        com.cretas.aims.service.unit.CanonicalUnit kg = new com.cretas.aims.service.unit.CanonicalUnit(
                "kg", com.cretas.aims.service.unit.UnitDimension.MASS, "kg",
                java.math.BigDecimal.ONE, "千克", 3,
                java.util.Set.of(), null, true);
        when(unitContractService.normalize(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.cretas.aims.service.unit.UnitNormalizationResult("kg", "kg", kg));
        when(unitContractService.describe(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(kg));
    }

    private CompiledProductProcessWorkflow compiledWithInvalidPort() {
        return new CompiledProductProcessWorkflow(
                "[{\"id\":\"tx-process\"}]",
                "[]",
                List.of(new CompiledProductProcessWorkflow.CompiledTask(
                        "tx-process", "TX-WP", 1, "kg", 5, true)),
                List.of(new CompiledProductProcessWorkflow.CompiledPort(
                        "tx-process",
                        null,
                        "INPUT",
                        1,
                        "tx-material",
                        "RAW_MATERIAL",
                        "TX-SKU",
                        "kg",
                        true,
                        null,
                        null)));
    }

    private CompiledProductProcessWorkflow compiledWithSelectionGroup() {
        return new CompiledProductProcessWorkflow(
                "[{\"id\":\"tx-process\"}]",
                "[]",
                List.of(new CompiledProductProcessWorkflow.CompiledTask(
                        "tx-process", "TX-WP", 1, "kg", 5, true)),
                List.of(new CompiledProductProcessWorkflow.CompiledPort(
                        "tx-process",
                        "tx-input",
                        "INPUT",
                        1,
                        "tx-material",
                        "RAW_MATERIAL",
                        "TX-SKU",
                        "kg",
                        "kg",
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        "input-alternatives",
                        "替代投入",
                        "EXACTLY_ONE",
                        1,
                        1)));
    }
}
