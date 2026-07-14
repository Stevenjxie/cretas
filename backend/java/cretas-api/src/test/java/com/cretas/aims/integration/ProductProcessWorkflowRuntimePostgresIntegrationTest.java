package com.cretas.aims.integration;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.CompiledProductProcessWorkflow;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.cretas.aims.service.workflow.impl.ProductProcessWorkflowActivationServiceImpl;
import com.cretas.aims.service.workflow.impl.ProductProcessWorkflowRuntimeServiceImpl;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Opt-in runtime 2A verification against a disposable local PostgreSQL database.
 * The environment gate is evaluated before Spring creates a datasource, so the normal test run
 * skips this class without attempting any connection.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@Import({
        ProductProcessWorkflowRuntimeServiceImpl.class,
        ProductProcessWorkflowActivationServiceImpl.class,
        ProductProcessWorkflowRuntimeCompiler.class,
        ProductProcessWorkflowValidator.class,
        WorkProcessTaskServiceImpl.class,
        ProductProcessWorkflowRuntimePostgresIntegrationTest.TestBeans.class
})
class ProductProcessWorkflowRuntimePostgresIntegrationTest {

    private static final String FACTORY = "WF-RUNTIME-PG";
    private static final String PRODUCT = "WF-RUNTIME-PIG";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProductProcessWorkflowRepository workflowRepository;
    @Autowired private ProductProcessWorkflowActivationRepository activationRepository;
    @Autowired private ProductionWorkflowInstanceRepository instanceRepository;
    @Autowired private WorkProcessTaskRepository taskRepository;
    @Autowired private WorkflowTaskPortRepository portRepository;
    @Autowired private ProductProcessWorkflowActivationService activationService;
    @Autowired private ProductProcessWorkflowRuntimeService runtimeService;
    @Autowired private WorkProcessTaskService taskService;
    @SpyBean private ProductProcessWorkflowRuntimeCompiler compiler;

    @MockBean private FactoryRepository factoryRepository;
    @MockBean private ProductionBatchRepository batchRepository;
    @MockBean private ProductTypeRepository productTypeRepository;
    @MockBean private ProductWorkProcessRepository productWorkProcessRepository;
    @MockBean private WorkProcessRepository workProcessRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private ProductProcessWorkflowCatalogValidator catalogValidator;
    @MockBean private com.cretas.aims.repository.unit.ProductUnitConversionRepository conversionRepository;
    @MockBean private com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator unitValidator;
    @MockBean private com.cretas.aims.service.unit.UnitContractService unitContractService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v55CreatesOwnedRuntimeSchemaAndRejectsInvalidPortRows() throws Exception {
        String schema = "workflow_runtime_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("""
                    CREATE TABLE product_process_workflows (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL, definition_version INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE production_batches (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_type_id VARCHAR(64) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE work_process_tasks (
                      id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                      product_work_process_id BIGINT NOT NULL, deleted_at TIMESTAMP)
                    """);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource(
                            "db/flyway/V20261028_52__product_process_workflow_runtime.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource(
                            "db/flyway/V20261028_53__pin_batch_workflow_selection.sql"));

            assertEquals(2, scalarInt(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'production_workflow_instances'
                      AND column_name IN ('nodes_json', 'edges_json')
                      AND data_type = 'jsonb'
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND indexname = 'uk_workflow_task_node'
                    """));
            assertEquals("YES", scalarString(connection, """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'work_process_tasks'
                      AND column_name = 'product_work_process_id'
                    """));

            statement.execute("""
                    INSERT INTO product_process_workflows
                      (id, factory_id, product_type_id, definition_version)
                    VALUES (1, 'F', 'P', 1)
                    """);
            statement.execute("""
                    INSERT INTO product_process_workflow_activations
                      (factory_id, product_type_id, active_workflow_id,
                       active_definition_version, enabled)
                    VALUES ('F', 'P', 1, 1, TRUE)
                    """);
            statement.execute("""
                    INSERT INTO production_batches (id, factory_id, product_type_id)
                    VALUES (10, 'F', 'P')
                    """);
            assertEquals("WORKFLOW", scalarString(connection, """
                    SELECT workflow_selection_mode FROM production_batches WHERE id = 10
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT selected_workflow_version FROM production_batches WHERE id = 10
                    """));
            statement.execute("""
                    INSERT INTO product_process_workflows
                      (id, factory_id, product_type_id, definition_version)
                    VALUES (2, 'F', 'P', 2)
                    """);
            statement.execute("""
                    UPDATE product_process_workflow_activations
                       SET active_workflow_id = 2, active_definition_version = 2
                     WHERE factory_id = 'F' AND product_type_id = 'P'
                    """);
            assertEquals(1, scalarInt(connection, """
                    SELECT selected_workflow_version FROM production_batches WHERE id = 10
                    """));
            statement.execute("""
                    INSERT INTO production_batches (id, factory_id, product_type_id)
                    VALUES (11, 'F', 'P')
                    """);
            assertEquals(2, scalarInt(connection, """
                    SELECT selected_workflow_version FROM production_batches WHERE id = 11
                    """));
            statement.execute("""
                    UPDATE product_process_workflow_activations SET enabled = FALSE
                     WHERE factory_id = 'F' AND product_type_id = 'P'
                    """);
            statement.execute("""
                    INSERT INTO production_batches (id, factory_id, product_type_id)
                    VALUES (12, 'F', 'P')
                    """);
            assertEquals("LEGACY", scalarString(connection, """
                    SELECT workflow_selection_mode FROM production_batches WHERE id = 12
                    """));
            statement.execute("""
                    INSERT INTO production_workflow_instances
                      (id, factory_id, production_batch_id, product_type_id, workflow_id,
                       definition_version, nodes_json, edges_json)
                    VALUES (20, 'F', 10, 'P', 1, 1, '[]', '[]')
                    """);
            statement.execute("""
                    INSERT INTO work_process_tasks
                      (id, factory_id, workflow_instance_id, workflow_node_id)
                    VALUES (30, 'F', 20, 'node-a')
                    """);
            SQLException invalidPort = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO workflow_task_ports
                      (factory_id, workflow_instance_id, task_id, workflow_port_id, direction,
                       ordinal, material_node_id, material_kind, sku_id, unit)
                    VALUES ('F', 20, 30, NULL, 'INPUT', 0, 'raw', 'RAW_MATERIAL', 'SKU', 'kg')
                    """));
            assertEquals("23502", invalidPort.getSQLState());

            statement.execute("""
                    INSERT INTO workflow_task_ports
                      (factory_id, workflow_instance_id, task_id, workflow_port_id, direction,
                       ordinal, material_node_id, material_kind, sku_id, unit)
                    VALUES ('F', 20, 30, 'legacy-port', 'INPUT', 0,
                            'raw', 'RAW_MATERIAL', 'SKU', repeat('x', 32))
                    """);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource(
                            "db/flyway/V20261028_63__workflow_port_unit_conversion_snapshot.sql"));
            assertEquals(32, scalarInt(connection, """
                    SELECT character_maximum_length FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'workflow_task_ports'
                      AND column_name = 'unit_code'
                    """));
            assertEquals(32, scalarInt(connection, """
                    SELECT length(unit_code) FROM workflow_task_ports
                    WHERE workflow_port_id = 'legacy-port'
                    """));
            assertEquals(5, scalarInt(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'workflow_task_ports'
                      AND column_name IN (
                        'material_primary_unit_code', 'conversion_from_unit_code',
                        'conversion_to_unit_code', 'conversion_factor_snapshot',
                        'port_to_primary_factor_snapshot')
                    """));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void advisoryLockMakesActivationAndBatchCommitOrderThePinningDecision() throws Exception {
        String schema = "workflow_runtime_" + UUID.randomUUID().toString().replace("-", "");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (Connection setup = dataSource.getConnection();
                 Statement statement = setup.createStatement()) {
                setup.setAutoCommit(true);
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        CREATE TABLE product_process_workflows (
                          id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                          product_type_id VARCHAR(64) NOT NULL, definition_version INTEGER NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE production_batches (
                          id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                          product_type_id VARCHAR(64) NOT NULL)
                        """);
                statement.execute("""
                        CREATE TABLE work_process_tasks (
                          id BIGINT PRIMARY KEY, factory_id VARCHAR(64) NOT NULL,
                          product_work_process_id BIGINT NOT NULL, deleted_at TIMESTAMP)
                        """);
                ScriptUtils.executeSqlScript(setup, new ClassPathResource(
                        "db/flyway/V20261028_52__product_process_workflow_runtime.sql"));
                ScriptUtils.executeSqlScript(setup, new ClassPathResource(
                        "db/flyway/V20261028_53__pin_batch_workflow_selection.sql"));
                statement.execute("""
                        INSERT INTO product_process_workflows
                          (id, factory_id, product_type_id, definition_version)
                        VALUES (1, 'F', 'ACTIVATION_FIRST', 1),
                               (2, 'F', 'BATCH_FIRST', 1)
                        """);
            }

            try (Connection activationFirst = transactionalConnection(schema);
                 Connection blockedBatch = transactionalConnection(schema)) {
                activationFirst.createStatement().execute("""
                        INSERT INTO product_process_workflow_activations
                          (factory_id, product_type_id, active_workflow_id,
                           active_definition_version, enabled)
                        VALUES ('F', 'ACTIVATION_FIRST', 1, 1, TRUE)
                        """);

                CountDownLatch insertStarted = new CountDownLatch(1);
                Future<?> batchInsert = executor.submit(() -> {
                    insertStarted.countDown();
                    try (Statement statement = blockedBatch.createStatement()) {
                        statement.execute("""
                                INSERT INTO production_batches (id, factory_id, product_type_id)
                                VALUES (101, 'F', 'ACTIVATION_FIRST')
                                """);
                        blockedBatch.commit();
                    }
                    return null;
                });
                assertTrue(insertStarted.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,
                        () -> batchInsert.get(250, TimeUnit.MILLISECONDS));
                activationFirst.commit();
                batchInsert.get(2, TimeUnit.SECONDS);
            }

            try (Connection batchFirst = transactionalConnection(schema);
                 Connection blockedActivation = transactionalConnection(schema)) {
                batchFirst.createStatement().execute("""
                        INSERT INTO production_batches (id, factory_id, product_type_id)
                        VALUES (201, 'F', 'BATCH_FIRST')
                        """);

                CountDownLatch insertStarted = new CountDownLatch(1);
                Future<?> activationInsert = executor.submit(() -> {
                    insertStarted.countDown();
                    try (Statement statement = blockedActivation.createStatement()) {
                        statement.execute("""
                                INSERT INTO product_process_workflow_activations
                                  (factory_id, product_type_id, active_workflow_id,
                                   active_definition_version, enabled)
                                VALUES ('F', 'BATCH_FIRST', 2, 1, TRUE)
                                """);
                        blockedActivation.commit();
                    }
                    return null;
                });
                assertTrue(insertStarted.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,
                        () -> activationInsert.get(250, TimeUnit.MILLISECONDS));
                batchFirst.commit();
                activationInsert.get(2, TimeUnit.SECONDS);
            }

            try (Connection verification = dataSource.getConnection();
                 Statement statement = verification.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                assertEquals("WORKFLOW", scalarString(verification, """
                        SELECT workflow_selection_mode FROM production_batches WHERE id = 101
                        """));
                assertEquals(1, scalarInt(verification, """
                        SELECT selected_workflow_version FROM production_batches WHERE id = 101
                        """));
                assertEquals("LEGACY", scalarString(verification, """
                        SELECT workflow_selection_mode FROM production_batches WHERE id = 201
                        """));
            }
        } finally {
            executor.shutdownNow();
            dropSchema(schema);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activationNewBatchSnapshotAndLegacyFallbackRoundTrip() throws Exception {
        Map<Long, ProductionBatch> batches = new LinkedHashMap<>();
        batches.put(9106L, legacyBatch(9106L));
        when(factoryRepository.existsById(FACTORY)).thenReturn(true);
        when(batchRepository.findByIdAndFactoryId(any(Long.class), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(batches.get(invocation.getArgument(0))));
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT, FACTORY))
                .thenReturn(Optional.of(new ProductType()));

        ProductProcessWorkflow v1 = savePublished(1, "v1");
        ProductProcessWorkflowActivationDTO activeV1 =
                activationService.activate(FACTORY, v1.getId(), 7001L);
        assertEquals(1, activeV1.getActiveDefinitionVersion());
        batches.put(9101L, workflowBatch(9101L, v1));
        batches.put(9102L, workflowBatch(9102L, v1));
        assertTrue(runtimeService.materializeIfActive(FACTORY, 9106L, PRODUCT).isEmpty());
        assertEquals(null, runtimeService.getRuntime(FACTORY, 9106L));

        List<WorkProcessTaskDTO> batchA = taskService.spawnTasks(FACTORY, 9101L, PRODUCT);
        assertEquals(List.of("v1-cook-a", "v1-cook-b", "v1-pack"), batchA.stream()
                .map(WorkProcessTaskDTO::getWorkflowNodeId).toList());
        assertEquals(2, batchA.stream().filter(task -> "COOK".equals(task.getWorkProcessId())).count());
        long instanceCountAfterA = instanceRepository.count();
        long taskCountAfterA = taskRepository.count();
        assertTrue(runtimeService.materializeIfActive(FACTORY, 9101L, PRODUCT).isPresent());
        assertEquals(instanceCountAfterA, instanceRepository.count());
        assertEquals(taskCountAfterA, taskRepository.count());

        ProductProcessWorkflow v2 = savePublished(2, "v2");
        List<WorkProcessTaskDTO> batchB = taskService.spawnTasks(FACTORY, 9102L, PRODUCT);
        assertEquals(1, runtimeService.getRuntime(FACTORY, 9102L).getDefinitionVersion());

        ProductProcessWorkflowActivationDTO activeV2 =
                activationService.activate(FACTORY, v2.getId(), 7002L);
        assertEquals(2, activeV2.getActiveDefinitionVersion());
        batches.put(9103L, workflowBatch(9103L, v2));
        batches.put(9105L, workflowBatch(9105L, v2));
        List<WorkProcessTaskDTO> batchC = taskService.spawnTasks(FACTORY, 9103L, PRODUCT);
        assertEquals(List.of("v2-cook-a", "v2-cook-b", "v2-pack"), batchC.stream()
                .map(WorkProcessTaskDTO::getWorkflowNodeId).toList());

        ProductProcessWorkflowActivationDTO disabled = activationService.deactivate(
                FACTORY, PRODUCT, activeV2.getLockVersion());
        assertFalse(disabled.getEnabled());
        batches.put(9104L, legacyBatch(9104L));
        ProductWorkProcess legacy = ProductWorkProcess.builder()
                .id(8801L)
                .factoryId(FACTORY)
                .productTypeId(PRODUCT)
                .workProcessId("LEGACY-CUT")
                .processOrder(1)
                .reportingRequired(true)
                .isActive(true)
                .build();
        when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
                FACTORY, PRODUCT)).thenReturn(List.of(legacy));
        when(workProcessRepository.findByFactoryIdAndIdIn(FACTORY, List.of("LEGACY-CUT")))
                .thenReturn(List.of());
        List<WorkProcessTaskDTO> batchD = taskService.spawnTasks(FACTORY, 9104L, PRODUCT);
        assertEquals(1, batchD.size());
        assertEquals(8801L, batchD.get(0).getProductWorkProcessId());
        assertEquals("LEGACY-CUT", batchD.get(0).getWorkProcessId());
        assertEquals(null, batchD.get(0).getWorkflowInstanceId());
        assertEquals(null, runtimeService.getRuntime(FACTORY, 9104L));

        ProductionWorkflowRuntimeDTO immutableA = runtimeService.getRuntime(FACTORY, 9101L);
        assertEquals(v1.getId(), immutableA.getWorkflowId());
        assertEquals(1, immutableA.getDefinitionVersion());
        assertTrue(immutableA.getNodesJson().contains("v1-cook-a"));
        assertFalse(immutableA.getNodesJson().contains("v2-cook-a"));
        assertFalse(immutableA.getNodesJson().contains("position"));
        assertFalse(immutableA.getNodesJson().contains("viewport"));
        assertEquals(3, immutableA.getTasks().size());
        assertEquals(7, immutableA.getTasks().stream()
                .mapToInt(task -> task.getPorts().size()).sum());
        assertEquals(1, runtimeService.getRuntime(FACTORY, 9102L).getDefinitionVersion());
        assertEquals(2, runtimeService.getRuntime(FACTORY, 9103L).getDefinitionVersion());
        assertNotNull(batchB);

        activationService.activate(FACTORY, v2.getId(), 7003L);
        long instancesBeforeFailure = instanceRepository.count();
        long tasksBeforeFailure = taskRepository.count();
        long portsBeforeFailure = portRepository.count();
        doReturn(compiledWithInvalidPort()).when(compiler).compile(any());
        assertThrows(RuntimeException.class,
                () -> runtimeService.materializeIfActive(FACTORY, 9105L, PRODUCT));
        assertEquals(instancesBeforeFailure, instanceRepository.count());
        assertEquals(tasksBeforeFailure, taskRepository.count());
        assertEquals(portsBeforeFailure, portRepository.count());
        assertEquals(null, runtimeService.getRuntime(FACTORY, 9105L));
    }

    private ProductProcessWorkflow savePublished(int version, String prefix) throws Exception {
        ProductProcessWorkflowDTO definition = repeatedProcessWorkflow(prefix);
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setFactoryId(FACTORY);
        workflow.setProductTypeId(PRODUCT);
        workflow.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        workflow.setDefinitionVersion(version);
        workflow.setSchemaVersion(1);
        workflow.setNodesJson(objectMapper.writeValueAsString(definition.getNodes()));
        workflow.setEdgesJson(objectMapper.writeValueAsString(definition.getEdges()));
        workflow.setViewportJson(objectMapper.writeValueAsString(definition.getViewport()));
        return workflowRepository.saveAndFlush(workflow);
    }

    private ProductProcessWorkflowDTO repeatedProcessWorkflow(String prefix) {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setSchemaVersion(1);
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(40D, 30D, 1.1D));
        definition.setNodes(new ArrayList<>(List.of(
                material(prefix + "-raw", "RAW_MATERIAL", prefix + "-RAW"),
                process(prefix + "-cook-a", "COOK", "kg", List.of(
                        port(prefix + "-in-a", "INPUT", prefix + "-raw", "RAW_MATERIAL", "kg", 0),
                        port(prefix + "-out-a", "OUTPUT", prefix + "-semi-a", "SEMI_FINISHED", "kg", 0))),
                material(prefix + "-semi-a", "SEMI_FINISHED", prefix + "-SEMI-A"),
                process(prefix + "-cook-b", "COOK", "kg", List.of(
                        port(prefix + "-in-b", "INPUT", prefix + "-raw", "RAW_MATERIAL", "kg", 0),
                        port(prefix + "-out-b", "OUTPUT", prefix + "-semi-b", "SEMI_FINISHED", "kg", 0))),
                material(prefix + "-semi-b", "SEMI_FINISHED", prefix + "-SEMI-B"),
                process(prefix + "-pack", "PACK", "box", List.of(
                        port(prefix + "-pack-a", "INPUT", prefix + "-semi-a", "SEMI_FINISHED", "kg", 0),
                        port(prefix + "-pack-b", "INPUT", prefix + "-semi-b", "SEMI_FINISHED", "kg", 1),
                        port(prefix + "-pack-out", "OUTPUT", prefix + "-finished", "FINISHED_GOOD", "box", 0))),
                material(prefix + "-finished", "FINISHED_GOOD", prefix + "-FG"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge(prefix + "-e1", prefix + "-raw", "output", prefix + "-cook-a", prefix + "-in-a"),
                edge(prefix + "-e2", prefix + "-cook-a", prefix + "-out-a", prefix + "-semi-a", "input"),
                edge(prefix + "-e3", prefix + "-raw", "output", prefix + "-cook-b", prefix + "-in-b"),
                edge(prefix + "-e4", prefix + "-cook-b", prefix + "-out-b", prefix + "-semi-b", "input"),
                edge(prefix + "-e5", prefix + "-semi-a", "output", prefix + "-pack", prefix + "-pack-a"),
                edge(prefix + "-e6", prefix + "-semi-b", "output", prefix + "-pack", prefix + "-pack-b"),
                edge(prefix + "-e7", prefix + "-pack", prefix + "-pack-out", prefix + "-finished", "input"))));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(String id, String kind, String sku) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", id);
        data.put("skuId", sku);
        data.put("skuCode", sku);
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(10D, 20D), data);
    }

    private ProductProcessWorkflowDTO.Node process(
            String id, String workProcessId, String outputUnit, List<Map<String, Object>> ports) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", workProcessId);
        data.put("processName", id);
        data.put("outputUnit", outputUnit);
        data.put("standardTime", 10);
        data.put("reportingRequired", true);
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(300D, 200D), data);
    }

    private Map<String, Object> port(
            String id, String direction, String materialNodeId, String materialKind,
            String unit, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("materialKind", materialKind);
        port.put("unit", unit);
        port.put("ordinal", ordinal);
        return port;
    }

    private ProductProcessWorkflowDTO.Edge edge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        return new ProductProcessWorkflowDTO.Edge(id, source, sourceHandle, target, targetHandle);
    }

    private ProductionBatch batch(long id) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId(PRODUCT);
        return batch;
    }

    private ProductionBatch legacyBatch(long id) {
        ProductionBatch batch = batch(id);
        batch.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.LEGACY);
        return batch;
    }

    private ProductionBatch workflowBatch(long id, ProductProcessWorkflow workflow) {
        ProductionBatch batch = batch(id);
        batch.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        batch.setSelectedWorkflowId(workflow.getId());
        batch.setSelectedWorkflowVersion(workflow.getDefinitionVersion());
        return batch;
    }

    private CompiledProductProcessWorkflow compiledWithInvalidPort() {
        return new CompiledProductProcessWorkflow(
                "[{\"id\":\"rollback-node\"}]",
                "[]",
                List.of(new CompiledProductProcessWorkflow.CompiledTask(
                        "rollback-node", "ROLLBACK", 1, "kg", 5, true)),
                List.of(new CompiledProductProcessWorkflow.CompiledPort(
                        "rollback-node", null, "INPUT", 0, "raw", "RAW_MATERIAL",
                        "SKU", "kg", true, null, null)));
    }

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void dropSchema(String schema) throws SQLException {
        if (!schema.startsWith("workflow_runtime_")) {
            throw new IllegalArgumentException("Refusing to drop unscoped schema: " + schema);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("SET search_path TO public");
        }
    }

    private Connection transactionalConnection(String schema) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
