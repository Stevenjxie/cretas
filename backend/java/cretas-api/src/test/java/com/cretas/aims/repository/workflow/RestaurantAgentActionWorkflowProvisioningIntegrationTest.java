package com.cretas.aims.repository.workflow;

import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionProposalContext;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentActionWorkflowResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.cretas.aims.service.impl.ApprovalWorkflowServiceImpl;
import com.cretas.aims.service.restaurant.RestaurantAgentActionProposalMapper;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowProvisioner;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowService;
import com.cretas.aims.service.restaurant.RestaurantAgentRunService;
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Hibernate/JPA lifecycle and concurrency gate for future Restaurant Agent tenants. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({RestaurantAgentActionWorkflowProvisioner.class, JacksonAutoConfiguration.class})
class RestaurantAgentActionWorkflowProvisioningIntegrationTest {

    private static final UUID RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String PROPOSAL = "COMPLETE_DISH_COST_DATA_PROPOSAL";
    private static final String TOKEN = "00000000-0000-0000-0000-000000000199";
    private static final List<String> FORBIDDEN_BUSINESS_TABLES = List.of(
            "recipes", "recipe_versions", "bom_recipes", "bom_recipe_items",
            "price_lists", "price_list_items", "product_types", "material_batches");

    @Autowired FactoryRepository factoryRepository;
    @Autowired ApprovalWorkflowRepository workflowRepository;
    @Autowired ApprovalWorkflowInstanceRepository instanceRepository;
    @Autowired ApprovalHistoryRepository historyRepository;
    @Autowired RestaurantAgentActionWorkflowProvisioner provisioner;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    @Test
    void newRestaurantCanPreviewAndConfirmIntoProvisionedHumanReviewWithZeroBusinessWrites() {
        Factory created = activeFactory(
                "R-LAZY-" + UUID.randomUUID(), FactoryType.RESTAURANT);
        factoryRepository.saveAndFlush(created);
        assertThat(workflows(created.getId())).isEmpty();

        ApprovalWorkflowServiceImpl workflowService =
                new ApprovalWorkflowServiceImpl(workflowRepository, new ObjectMapper());
        WorkflowEngineServiceImpl engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                new SandboxedSpelEvaluator(), null);
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(engine, "decisionTypeMetadataRegistry", registry);

        RestaurantAgentActionWorkflowService actionService =
                actionService(created.getId(), provisioner, workflowService, engine);
        Map<String, Long> before = snapshotBusinessTables();

        assertThat(actionService.preview(
                created.getId(), "42", "restaurant_owner", "corr-provision", RUN_ID, PROPOSAL)
                .previewToken()).isEqualTo(TOKEN);
        assertThat(workflows(created.getId())).isEmpty();
        RestaurantAgentActionWorkflowResponse response = actionService.confirm(
                created.getId(), "42", "restaurant_owner", "corr-provision",
                RUN_ID, PROPOSAL, TOKEN);
        instanceRepository.flush();
        historyRepository.flush();

        ApprovalWorkflow configured = activeWorkflow(created.getId());
        assertThat(configured.getName()).isEqualTo(RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        assertThat(configured.getVersion()).isEqualTo(1);
        assertThat(configured.getPublishStatus()).isEqualTo("published");
        assertThat(configured.getEnabled()).isTrue();
        ApprovalWorkflowInstance started = instanceRepository.findById(response.workflowInstanceId())
                .orElseThrow();
        assertThat(started.getWorkflowId()).isEqualTo(configured.getId());
        assertThat(started.getStatus()).isEqualTo(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        assertThat(started.getCurrentNodeIds()).containsExactly("human_review");
        assertThat(started.getContextJson()
                .get("_cretas_bound_workflow_definition_sha256"))
                .isInstanceOf(String.class)
                .asString()
                .matches("^[0-9a-f]{64}$");
        assertThat(snapshotBusinessTables()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"START_TO_END", "NOTIFY_NODE"})
    void driftedCanonicalKeyFailsClosedBeforeAnyWorkflowInstanceWrite(String driftKind) {
        Factory restaurant = activeFactory(
                "R-DRIFT-" + UUID.randomUUID(), FactoryType.RESTAURANT);
        factoryRepository.saveAndFlush(restaurant);
        workflowRepository.saveAndFlush(driftedWorkflow(restaurant.getId(), driftKind));

        ApprovalWorkflowServiceImpl workflowService =
                new ApprovalWorkflowServiceImpl(workflowRepository, new ObjectMapper());
        WorkflowEngineServiceImpl engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                new SandboxedSpelEvaluator(), null);
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(engine, "decisionTypeMetadataRegistry", registry);
        RestaurantAgentActionWorkflowService actionService =
                actionService(restaurant.getId(), provisioner, workflowService, engine);
        long instancesBefore = instanceRepository.count();

        assertThatThrownBy(() -> actionService.confirm(
                restaurant.getId(), "42", "restaurant_owner", "corr-provision",
                RUN_ID, PROPOSAL, TOKEN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID");

        assertThat(instanceRepository.count()).isEqualTo(instancesBefore);
        assertThat(workflows(restaurant.getId())).singleElement()
                .satisfies(workflow -> assertThat(provisioner.isCanonical(workflow)).isFalse());
    }

    @Test
    void exactBoundRunningInstanceRejectsDefinitionDriftBeforeStateOrHistoryWrite() {
        Factory restaurant = activeFactory(
                "R-BOUND-" + UUID.randomUUID(), FactoryType.RESTAURANT);
        factoryRepository.saveAndFlush(restaurant);
        assertThat(provisioner.provisionIfEligible(restaurant)).isTrue();
        ApprovalWorkflow configured = activeWorkflow(restaurant.getId());
        workflowRepository.saveAndFlush(ApprovalWorkflow.builder()
                .factoryId(restaurant.getId())
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .name("tenant.competing-action-workflow")
                .description("Higher-priority competing definition")
                .nodesJson("""
                        [
                          {"id":"start","type":"start","label":"Start","position":{"x":0,"y":0},"config":{}},
                          {"id":"approved","type":"end","label":"Approved","position":{"x":1,"y":0},"config":{"outcome":"APPROVED"}}
                        ]
                        """)
                .edgesJson("""
                        [{"id":"bypass","source":"start","target":"approved","priority":0}]
                        """)
                .startNodeId("start")
                .version(1)
                .publishStatus("published")
                .enabled(true)
                .priority(9000)
                .build());

        ApprovalWorkflowServiceImpl workflowService =
                new ApprovalWorkflowServiceImpl(workflowRepository, new ObjectMapper());
        WorkflowEngineServiceImpl engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                new SandboxedSpelEvaluator(), null);
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(engine, "decisionTypeMetadataRegistry", registry);

        ApprovalWorkflowInstance started = engine.startWorkflowWithDefinition(
                restaurant.getId(), RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                "restaurant-agent:bound-definition-drift",
                Map.of("proposalCode", PROPOSAL), 42L, configured);
        instanceRepository.flush();
        historyRepository.flush();
        assertThat(started.getStatus()).isEqualTo(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        assertThat(started.getWorkflowId()).isEqualTo(configured.getId());
        assertThat(started.getCurrentNodeIds()).containsExactly("human_review");
        int historyBefore = historyRepository
                .findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                        restaurant.getId(), started.getId())
                .size();

        jdbcTemplate.update(
                "UPDATE approval_workflows SET version = version + 1, publish_status = 'draft', "
                        + "nodes_json = CAST(? AS jsonb) WHERE id = ?",
                """
                        [
                          {"id":"start","type":"start","label":"Start","position":{"x":0,"y":0},"config":{}},
                          {"id":"approved","type":"end","label":"Approved","position":{"x":1,"y":0},"config":{"outcome":"APPROVED"}}
                        ]
                        """,
                configured.getId());
        // Force the exact workflow row to be reloaded after the native drift update while
        // keeping the managed instance in memory. H2's JSONB compatibility layer double-quotes
        // Map JSON on a full EntityManager reload; production PostgreSQL does not.
        entityManager.detach(configured);

        assertThatThrownBy(() -> engine.transitionNode(
                started.getId(), 42L, "restaurant_owner", HistoryAction.APPROVE, "approve"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WORKFLOW_BOUND_DEFINITION_CHANGED");

        assertThat(started.getStatus()).isEqualTo(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        assertThat(started.getCurrentNodeIds()).containsExactly("human_review");
        assertThat(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                restaurant.getId(), started.getId())).hasSize(historyBefore);
    }

    @Test
    void inactiveRestaurantIsProvisionedWhenReactivatedButFactoryNeverIs() {
        Factory inactiveRestaurant = activeFactory(
                "R-INACTIVE-" + UUID.randomUUID(), FactoryType.RESTAURANT);
        inactiveRestaurant.setIsActive(false);
        Factory manufacturingFactory = activeFactory(
                "F-ACTION-" + UUID.randomUUID(), FactoryType.FACTORY);
        factoryRepository.saveAndFlush(inactiveRestaurant);
        factoryRepository.saveAndFlush(manufacturingFactory);

        assertThat(provisioner.provisionIfEligible(inactiveRestaurant)).isFalse();
        assertThat(provisioner.provisionIfEligible(manufacturingFactory)).isFalse();
        assertThat(workflows(inactiveRestaurant.getId())).isEmpty();
        assertThat(workflows(manufacturingFactory.getId())).isEmpty();

        inactiveRestaurant.setIsActive(true);
        factoryRepository.saveAndFlush(inactiveRestaurant);
        assertThat(provisioner.provisionIfEligible(inactiveRestaurant)).isTrue();

        assertThat(workflows(inactiveRestaurant.getId()))
                .singleElement()
                .satisfies(workflow -> {
                    assertThat(workflow.getPublishStatus()).isEqualTo("published");
                    assertThat(workflow.getEnabled()).isTrue();
                });
        assertThat(workflows(manufacturingFactory.getId())).isEmpty();
    }

    @Test
    void repeatedProvisioningPreservesExistingTenantWorkflowWithoutCreatingAnotherVersion() {
        Factory restaurant = activeFactory("R-CUSTOM-" + UUID.randomUUID(), FactoryType.RESTAURANT);
        factoryRepository.saveAndFlush(restaurant);
        ApprovalWorkflow custom = workflowRepository.saveAndFlush(ApprovalWorkflow.builder()
                .factoryId(restaurant.getId())
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .name(RestaurantAgentActionProposalMapper.WORKFLOW_KEY)
                .description("Tenant-owned review policy")
                .nodesJson("""
                        [
                          {"id":"start","type":"start","label":"Custom start","position":{"x":0,"y":0},"config":{}},
                          {"id":"human_review","type":"approval","label":"Custom review","position":{"x":1,"y":0},"config":{"approverRoles":["restaurant_owner"],"requiredApprovers":1}},
                          {"id":"approved","type":"end","label":"Approved","position":{"x":2,"y":0},"config":{"outcome":"APPROVED"}}
                        ]
                        """)
                .edgesJson("""
                        [
                          {"id":"custom_review","source":"start","target":"human_review","priority":0},
                          {"id":"custom_approved","source":"human_review","target":"approved","priority":0}
                        ]
                        """)
                .startNodeId("start")
                .version(7)
                .publishStatus("published")
                .enabled(true)
                .priority(1777)
                .build());

        assertThat(provisioner.provisionIfEligible(restaurant)).isFalse();
        assertThat(provisioner.provisionIfEligible(restaurant)).isFalse();
        workflowRepository.flush();

        assertThat(workflows(restaurant.getId()))
                .singleElement()
                .satisfies(preserved -> {
                    assertThat(preserved.getId()).isEqualTo(custom.getId());
                    assertThat(preserved.getDescription()).isEqualTo("Tenant-owned review policy");
                    assertThat(preserved.getVersion()).isEqualTo(7);
                    assertThat(preserved.getPriority()).isEqualTo(1777);
                    assertThat(preserved.getNodesJson()).contains("Custom review");
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentProvisioningCreatesExactlyOneCanonicalWorkflow() throws Exception {
        String factoryId = "R-CONCURRENT-" + UUID.randomUUID();
        Factory restaurant = activeFactory(factoryId, FactoryType.BRANCH);
        factoryRepository.saveAndFlush(restaurant);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return provisioner.provisionIfEligible(restaurant);
                }));
            }
            ready.await();
            start.countDown();

            long creators = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    creators++;
                }
            }
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM approval_workflows "
                            + "WHERE factory_id = ? AND decision_type = ? AND name = ? "
                            + "AND publish_status = 'published' AND enabled = TRUE AND deleted_at IS NULL",
                    Integer.class,
                    factoryId,
                    DecisionType.RESTAURANT_AGENT_ACTION_REVIEW.name(),
                    RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
            assertThat(creators).isEqualTo(1);
            assertThat(count).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbcTemplate.update("DELETE FROM approval_workflows WHERE factory_id = ?", factoryId);
            jdbcTemplate.update("DELETE FROM factories WHERE id = ?", factoryId);
        }
    }

    private RestaurantAgentActionWorkflowService actionService(
            String factoryId,
            RestaurantAgentActionWorkflowProvisioner provisioner,
            ApprovalWorkflowServiceImpl workflowService,
            WorkflowEngineServiceImpl engine) {
        RestaurantAgentRunService runService = mock(RestaurantAgentRunService.class);
        RestaurantAgentActionProposalMapper mapper = mock(RestaurantAgentActionProposalMapper.class);
        PreviewTokenService previewTokenService = mock(PreviewTokenService.class);
        RestaurantAgentRunReplayResponse replay = mock(RestaurantAgentRunReplayResponse.class);
        RestaurantAgentActionProposalContext context = proposalContext();
        IntentPreviewToken token = boundToken();
        when(runService.replay(factoryId, "42", "restaurant_owner", "corr-provision",
                RUN_ID, 0L)).thenReturn(replay);
        when(mapper.fromReplay(RUN_ID, PROPOSAL, replay)).thenReturn(context);
        when(previewTokenService.createBoundToken(any())).thenReturn(token);
        when(token.getToken()).thenReturn(TOKEN);
        when(previewTokenService.claimToken(TOKEN, factoryId, 42L))
                .thenReturn(ClaimResult.success(token, "claim-provision", tokenParameters()));
        when(previewTokenService.resolveClaim(
                eq(TOKEN), eq("claim-provision"), eq(true), any())).thenReturn(true);
        when(mapper.toWorkflowResponse(eq(context), any(), eq(false)))
                .thenAnswer(invocation -> {
                    ApprovalWorkflowInstance instance = invocation.getArgument(1);
                    return new RestaurantAgentActionWorkflowResponse(
                            "1.0", RUN_ID.toString(), PROPOSAL,
                            RestaurantAgentActionProposalMapper.WORKFLOW_KEY,
                            instance.getId(), instance.getStatus().name(), false, null);
                });
        return new RestaurantAgentActionWorkflowService(
                runService, mapper, previewTokenService, engine, workflowService,
                factoryRepository, provisioner, true);
    }

    private ApprovalWorkflow driftedWorkflow(String factoryId, String driftKind) {
        String nodes;
        String edges;
        if ("START_TO_END".equals(driftKind)) {
            nodes = """
                    [
                      {"id":"start","type":"start","label":"Start","position":{"x":0,"y":0},"config":{}},
                      {"id":"approved","type":"end","label":"Approved","position":{"x":1,"y":0},"config":{"outcome":"APPROVED"}}
                    ]
                    """;
            edges = """
                    [{"id":"bypass","source":"start","target":"approved","priority":0}]
                    """;
        } else {
            nodes = """
                    [
                      {"id":"start","type":"start","label":"Start","position":{"x":0,"y":0},"config":{}},
                      {"id":"notify","type":"notify","label":"Notify","position":{"x":1,"y":0},"config":{}},
                      {"id":"human_review","type":"approval","label":"Review","position":{"x":2,"y":0},"config":{"approverRoles":["restaurant_owner"],"requiredApprovers":1}},
                      {"id":"approved","type":"end","label":"Approved","position":{"x":3,"y":0},"config":{"outcome":"APPROVED"}}
                    ]
                    """;
            edges = """
                    [
                      {"id":"to_notify","source":"start","target":"notify","priority":0},
                      {"id":"to_review","source":"notify","target":"human_review","priority":0},
                      {"id":"to_end","source":"human_review","target":"approved","priority":0}
                    ]
                    """;
        }
        return ApprovalWorkflow.builder()
                .factoryId(factoryId)
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .name(RestaurantAgentActionProposalMapper.WORKFLOW_KEY)
                .description("Drift fixture")
                .nodesJson(nodes)
                .edgesJson(edges)
                .startNodeId("start")
                .version(1)
                .publishStatus("published")
                .enabled(true)
                .priority(1000)
                .build();
    }

    private Factory activeFactory(String id, FactoryType type) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("Lifecycle test " + id);
        factory.setType(type);
        factory.setIndustryCode("RT");
        factory.setRegionCode("9999");
        factory.setIsActive(true);
        factory.setAiWeeklyQuota(50);
        factory.setSubscriptionPlan("BASIC");
        return factory;
    }

    private List<ApprovalWorkflow> workflows(String factoryId) {
        return workflowRepository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
                factoryId, DecisionType.RESTAURANT_AGENT_ACTION_REVIEW);
    }

    private ApprovalWorkflow activeWorkflow(String factoryId) {
        return workflowRepository.findActiveByDecisionType(
                        factoryId, DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .stream()
                .filter(workflow -> RestaurantAgentActionProposalMapper.WORKFLOW_KEY
                        .equals(workflow.getName()))
                .findFirst()
                .orElseThrow();
    }

    private RestaurantAgentActionProposalContext proposalContext() {
        return new RestaurantAgentActionProposalContext(
                RUN_ID.toString(), PROPOSAL, "REVIEW_DISH_COST_DATA", "READ_ONLY_PROPOSAL",
                List.of("DISH_MARGIN_UNAVAILABLE"),
                List.of(new RestaurantAgentActionProposalContext.EvidenceReference(
                        "evidence-1", "fact-1", "GROSS_MARGIN_DECLINE_OBSERVED",
                        "gross_margin_change_pct", "-3.5", "pct")),
                "a".repeat(64));
    }

    private IntentPreviewToken boundToken() {
        IntentPreviewToken token = mock(IntentPreviewToken.class);
        when(token.getIntentCode()).thenReturn("RESTAURANT_AGENT_ACTION_REVIEW");
        when(token.getToolName()).thenReturn("restaurant_agent_action_workflow");
        when(token.getDescriptorVersion()).thenReturn(
                RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        when(token.getExecutionMode()).thenReturn(ToolExecutionMode.EXECUTE);
        when(token.getEntityType()).thenReturn("RESTAURANT_AGENT_RUN");
        when(token.getEntityId()).thenReturn(
                "restaurant-agent:" + RUN_ID + ":" + PROPOSAL);
        when(token.getOperation()).thenReturn("START_HUMAN_REVIEW");
        return token;
    }

    private Map<String, Object> tokenParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("runId", RUN_ID.toString());
        parameters.put("proposalCode", PROPOSAL);
        parameters.put("outcomeDigest", "a".repeat(64));
        parameters.put("workflowKey", RestaurantAgentActionProposalMapper.WORKFLOW_KEY);
        return parameters;
    }

    private Map<String, Long> snapshotBusinessTables() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FORBIDDEN_BUSINESS_TABLES) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = current_schema() AND table_name = ?",
                    Integer.class, table);
            if (exists != null && exists == 1) {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class);
                counts.put(table, count);
            }
        }
        return counts;
    }
}
