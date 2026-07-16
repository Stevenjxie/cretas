package com.cretas.aims.integration;

import com.cretas.aims.controller.ProductProcessWorkflowController;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.impl.ProductProcessWorkflowServiceImpl;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in PostgreSQL verification for product-process workflow persistence.
 *
 * <p>This test intentionally does not use H2 or Mockito repositories. It is skipped unless
 * {@code CRETAS_WORKFLOW_PG_VERIFY=true} is explicitly set, and requires a disposable local
 * PostgreSQL database supplied through the environment variables documented in the Task 8
 * verification report.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductProcessWorkflowPostgresIntegrationTest {

    private static final String FACTORY_ID = "WF-PG-F001";
    private static final String PRODUCT_ID = "WF-PG-PIG-001";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String safeUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> safeUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("logging.level.org.hibernate.SQL", () -> "OFF");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProductProcessWorkflowRepository repository;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUpControllerWithRealRepository() {
        ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
        when(productTypeRepository.findByIdAndFactoryId(PRODUCT_ID, FACTORY_ID))
                .thenReturn(java.util.Optional.of(mock(ProductType.class)));

        ProductProcessWorkflowCatalogValidator catalogValidator =
                mock(ProductProcessWorkflowCatalogValidator.class);
        com.cretas.aims.repository.ProductProcessWorkflowActivationRepository activationRepository =
                mock(com.cretas.aims.repository.ProductProcessWorkflowActivationRepository.class);
        ProductProcessWorkflowServiceImpl service = new ProductProcessWorkflowServiceImpl(
                repository,
                activationRepository,
                objectMapper,
                new ProductProcessWorkflowValidator(),
                catalogValidator,
                mock(ProductProcessWorkflowUnitValidator.class),
                productTypeRepository,
                mock(com.cretas.aims.repository.RawMaterialTypeRepository.class));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProductProcessWorkflowController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @Order(1)
    void freshWorkflowMigrationCreatesJsonbVersionStatusAndSingleDraftContract() throws Exception {
        String schema = "workflow_migration_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(schema.startsWith("workflow_migration_"));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20261028_50__product_process_workflow.sql"));

            assertEquals(3, scalarInt(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'product_process_workflows'
                      AND column_name IN ('nodes_json', 'edges_json', 'viewport_json')
                      AND data_type = 'jsonb'
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'product_process_workflows'
                      AND column_name = 'lock_version'
                      AND is_nullable = 'NO'
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*)
                    FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND tablename = 'product_process_workflows'
                      AND indexname = 'uk_product_process_workflow_active_draft'
                    """));

            statement.execute("""
                    INSERT INTO product_process_workflows
                        (factory_id, product_type_id, status, definition_version)
                    VALUES ('F-ONE', 'P-ONE', 'DRAFT', 1)
                    """);
            SQLException duplicateDraft = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO product_process_workflows
                        (factory_id, product_type_id, status, definition_version)
                    VALUES ('F-ONE', 'P-ONE', 'DRAFT', 2)
                    """));
            assertEquals("23505", duplicateDraft.getSQLState());

            statement.execute("""
                    INSERT INTO product_process_workflows
                        (factory_id, product_type_id, status, definition_version)
                    VALUES ('F-ONE', 'P-ONE', 'PUBLISHED', 1)
                    """);
            SQLException invalidStatus = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO product_process_workflows
                        (factory_id, product_type_id, status, definition_version)
                    VALUES ('F-TWO', 'P-TWO', 'INVALID', 1)
                    """));
            assertEquals("23514", invalidStatus.getSQLState());
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    @Order(2)
    void existingWorkProcessesMigrationBackfillsAndConstrainsOutputKind() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("SET search_path TO public");
            statement.execute("DROP TABLE IF EXISTS public.work_processes CASCADE");
            statement.execute("""
                    CREATE TABLE public.work_processes (
                        id BIGINT PRIMARY KEY,
                        process_name VARCHAR(100) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO public.work_processes (id, process_name)
                    VALUES (1, 'existing-a'), (2, 'existing-b')
                    """);

            ClassPathResource migration = new ClassPathResource(
                    "db/flyway/V20261028_51__work_process_default_output_material_kind.sql");
            String migrationSql = new String(
                    migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(migrationSql);

            assertEquals(2, scalarInt(connection, """
                    SELECT count(*) FROM public.work_processes
                    WHERE default_output_material_kind = 'SEMI_FINISHED'
                    """));
            assertEquals(1, scalarInt(connection, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'work_processes'
                      AND column_name = 'default_output_material_kind'
                      AND is_nullable = 'NO'
                      AND column_default LIKE '%SEMI_FINISHED%'
                    """));

            statement.execute("""
                    INSERT INTO public.work_processes (id, process_name)
                    VALUES (3, 'new-default')
                    """);
            assertEquals("SEMI_FINISHED", scalarString(connection, """
                    SELECT default_output_material_kind
                    FROM public.work_processes WHERE id = 3
                    """));

            SQLException invalidKind = assertThrows(SQLException.class, () -> statement.execute("""
                    UPDATE public.work_processes
                    SET default_output_material_kind = 'RAW_MATERIAL'
                    WHERE id = 1
                    """));
            assertEquals("23514", invalidKind.getSQLState());
        }
    }

    @Test
    @Order(3)
    void realPostgresHttpSaveReadConflictPublishReadbackPreservesCompleteGraph() throws Exception {
        ProductProcessWorkflowDTO firstReader = validMultiInputMultiOutputDefinition();
        String requestBody = objectMapper.writeValueAsString(firstReader);

        MockHttpServletResponse saved = mockMvc.perform(put(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}/draft",
                        FACTORY_ID, PRODUCT_ID)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.lockVersion").value(0))
                .andReturn().getResponse();

        JsonNode savedData = objectMapper.readTree(saved.getContentAsString()).path("data");
        assertEquals(0L, savedData.path("lockVersion").asLong());

        mockMvc.perform(get(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}",
                        FACTORY_ID, PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(7))
                .andExpect(jsonPath("$.data.edges.length()").value(6))
                .andExpect(jsonPath("$.data.nodes[2].data.ports.length()").value(3))
                .andExpect(jsonPath("$.data.nodes[4].data.ports.length()").value(3))
                .andExpect(jsonPath("$.data.nodes[4].data.inputUnit").value("kg"))
                .andExpect(jsonPath("$.data.nodes[4].data.outputUnit").value("box"))
                .andExpect(jsonPath("$.data.nodes[4].data.conversionRule.mode").value("FIXED_RATIO"))
                .andExpect(jsonPath("$.data.nodes[4].data.conversionRule.expression")
                        .value("200 kg = 400 box"));

        ProductProcessWorkflowDTO secondReader = objectMapper.treeToValue(
                savedData, ProductProcessWorkflowDTO.class);
        ProductProcessWorkflowDTO staleReader = objectMapper.treeToValue(
                savedData, ProductProcessWorkflowDTO.class);
        secondReader.getViewport().setZoom(1.25D);
        staleReader.getViewport().setZoom(0.75D);

        MockHttpServletResponse updated = mockMvc.perform(put(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}/draft",
                        FACTORY_ID, PRODUCT_ID)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(secondReader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockVersion").value(1))
                .andExpect(jsonPath("$.data.viewport.zoom").value(1.25D))
                .andReturn().getResponse();

        mockMvc.perform(put(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}/draft",
                        FACTORY_ID, PRODUCT_ID)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(staleReader)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_PROCESS_WORKFLOW_CONFLICT"));

        long currentLockVersion = objectMapper.readTree(updated.getContentAsString())
                .path("data").path("lockVersion").asLong();
        mockMvc.perform(post(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}/publish",
                        FACTORY_ID, PRODUCT_ID)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new ProductProcessWorkflowDTO.PublishRequest(currentLockVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.nodes[4].data.ports[1].materialNodeId")
                        .value("finished"))
                .andExpect(jsonPath("$.data.nodes[4].data.ports[2].materialNodeId")
                        .value("loss"));

        entityManager.clear();
        mockMvc.perform(get(
                        "/api/mobile/{factoryId}/product-process-workflows/{productId}",
                        FACTORY_ID, PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.nodes[0].data.skuId").value("RM-PIG-A"))
                .andExpect(jsonPath("$.data.nodes[1].data.skuId").value("RM-PIG-B"))
                .andExpect(jsonPath("$.data.edges[5].sourceHandle").value("out-loss"))
                .andExpect(jsonPath("$.data.viewport.zoom").value(1.25D));

        Object[] storedJson = (Object[]) entityManager.createNativeQuery("""
                        SELECT jsonb_array_length(nodes_json),
                               jsonb_array_length(edges_json),
                               nodes_json #>> '{4,data,conversionRule,expression}'
                        FROM product_process_workflows
                        WHERE factory_id = :factoryId
                          AND product_type_id = :productTypeId
                          AND status = 'PUBLISHED'
                        """)
                .setParameter("factoryId", FACTORY_ID)
                .setParameter("productTypeId", PRODUCT_ID)
                .getSingleResult();
        assertEquals(7, ((Number) storedJson[0]).intValue());
        assertEquals(6, ((Number) storedJson[1]).intValue());
        assertEquals("200 kg = 400 box", storedJson[2]);
    }

    private ProductProcessWorkflowDTO validMultiInputMultiOutputDefinition() {
        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setSchemaVersion(1);
        definition.setNodes(new ArrayList<>(List.of(
                material("raw-a", "RAW_MATERIAL", "Pig trotter A", "RM-PIG-A"),
                material("raw-b", "RAW_MATERIAL", "Pig trotter B", "RM-PIG-B"),
                process("trim", "Trim", "kg", "kg", List.of(
                        port("in-a", "INPUT", "raw-a", "RAW_MATERIAL", "kg", 0),
                        port("in-b", "INPUT", "raw-b", "RAW_MATERIAL", "kg", 1),
                        port("out-trim", "OUTPUT", "trimmed", "SEMI_FINISHED", "kg", 0)),
                        Map.of("mode", "ACTUAL_WEIGHT", "expression", "700 kg = 623.5 kg")),
                material("trimmed", "SEMI_FINISHED", "Trimmed trotter", "SFI-TRIMMED"),
                process("cook", "Cook and pack", "kg", "box", List.of(
                        port("in-cook", "INPUT", "trimmed", "SEMI_FINISHED", "kg", 0),
                        port("out-good", "OUTPUT", "finished", "FINISHED_GOOD", "box", 0),
                        port("out-loss", "OUTPUT", "loss", "SEMI_FINISHED", "kg", 1)),
                        Map.of("mode", "FIXED_RATIO", "expression", "200 kg = 400 box")),
                material("loss", "SEMI_FINISHED", "Process loss", "SFI-LOSS"),
                material("finished", "FINISHED_GOOD", "Braised trotter 400g", "FG-BRAISED-400"))));
        definition.setEdges(new ArrayList<>(List.of(
                edge("e1", "raw-a", "output", "trim", "in-a"),
                edge("e2", "raw-b", "output", "trim", "in-b"),
                edge("e3", "trim", "out-trim", "trimmed", "input"),
                edge("e4", "trimmed", "output", "cook", "in-cook"),
                edge("e5", "cook", "out-good", "finished", "input"),
                edge("e6", "cook", "out-loss", "loss", "input"))));
        definition.setViewport(new ProductProcessWorkflowDTO.Viewport(12D, 34D, 1D));
        return definition;
    }

    private ProductProcessWorkflowDTO.Node material(
            String id, String kind, String name, String skuId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("skuId", skuId);
        data.put("skuCode", skuId);
        return new ProductProcessWorkflowDTO.Node(
                id, kind, new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private ProductProcessWorkflowDTO.Node process(
            String id,
            String name,
            String inputUnit,
            String outputUnit,
            List<Map<String, Object>> ports,
            Map<String, Object> conversionRule) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workProcessId", "WP-" + id);
        data.put("processName", name);
        data.put("inputUnit", inputUnit);
        data.put("outputUnit", outputUnit);
        data.put("ports", new ArrayList<>(ports));
        data.put("conversionRule", new LinkedHashMap<>(conversionRule));
        return new ProductProcessWorkflowDTO.Node(
                id, "PROCESS", new ProductProcessWorkflowDTO.Position(0D, 0D), data);
    }

    private Map<String, Object> port(
            String id,
            String direction,
            String materialNodeId,
            String materialKind,
            String unit,
            int ordinal) {
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
            String value = result.getString(1);
            assertNotNull(value);
            return value;
        }
    }

    private void dropSchema(String schema) throws SQLException {
        if (!schema.startsWith("workflow_migration_")) {
            throw new IllegalArgumentException("Refusing to drop unscoped schema: " + schema);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("SET search_path TO public");
        }
    }
}
