package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.service.impl.ApprovalWorkflowServiceImpl;
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and persistence gate for the Restaurant Agent workflow slice. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class RestaurantAgentActionWorkflowRepositoryQueryValidationTest {

    private static final String FACTORY_ID = "R-JPA-ACTION";
    private static final String MODULE = "restaurant.dish-cost-data-review.v1";
    private static final String ENTITY_ID =
            "restaurant-agent:00000000-0000-0000-0000-000000000001:COMPLETE_DISH_COST_DATA_PROPOSAL";
    private static final List<String> FORBIDDEN_BUSINESS_TABLES = List.of(
            "recipes", "recipe_versions", "bom_recipes", "bom_recipe_items",
            "price_lists", "price_list_items", "product_types", "material_batches");

    @Autowired ApprovalWorkflowRepository workflowRepository;
    @Autowired ApprovalWorkflowInstanceRepository instanceRepository;
    @Autowired ApprovalHistoryRepository historyRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void repositoryQueriesParseAndEnginePersistsOnlyWorkflowAuditTables() {
        ApprovalWorkflow workflow = workflowRepository.saveAndFlush(ApprovalWorkflow.builder()
                .factoryId(FACTORY_ID)
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .name(MODULE)
                .description("Human-only dish cost data review")
                .nodesJson("""
                        [
                          {"id":"start","type":"start","label":"Start","position":{"x":0,"y":0},"config":{}},
                          {"id":"human_review","type":"approval","label":"Review","position":{"x":1,"y":0},"config":{"approverRoles":["restaurant_owner"],"requiredApprovers":1}},
                          {"id":"approved","type":"end","label":"Approved","position":{"x":2,"y":0},"config":{"outcome":"APPROVED"}}
                        ]
                        """)
                .edgesJson("""
                        [
                          {"id":"start_review","source":"start","target":"human_review","priority":0},
                          {"id":"review_approved","source":"human_review","target":"approved","priority":0}
                        ]
                        """)
                .startNodeId("start")
                .version(1)
                .publishStatus("published")
                .enabled(true)
                .priority(1000)
                .build());

        assertThat(workflowRepository.findActiveByDecisionType(
                FACTORY_ID, DecisionType.RESTAURANT_AGENT_ACTION_REVIEW))
                .extracting(ApprovalWorkflow::getId)
                .containsExactly(workflow.getId());

        ApprovalWorkflowServiceImpl workflowService =
                new ApprovalWorkflowServiceImpl(workflowRepository, new ObjectMapper());
        WorkflowEngineServiceImpl engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                new SandboxedSpelEvaluator(), null);
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(engine, "decisionTypeMetadataRegistry", registry);

        instanceRepository.saveAndFlush(ApprovalWorkflowInstance.builder()
                .id("00000000-0000-0000-0000-000000000099")
                .factoryId(FACTORY_ID)
                .workflowId(workflow.getId())
                .moduleCode(MODULE)
                .businessEntityId(ENTITY_ID)
                .status(ApprovalWorkflowInstance.InstanceStatus.APPROVED)
                .currentNodeIds(List.of())
                .contextJson(Map.of())
                .initiatedBy(42L)
                .build());
        assertThat(engine.getCurrentInstance(FACTORY_ID, MODULE, ENTITY_ID)).isEmpty();

        Map<String, Long> before = snapshotBusinessTables();

        ApprovalWorkflowInstance started = engine.startWorkflow(
                FACTORY_ID, MODULE, ENTITY_ID,
                Map.of(
                        "proposalCode", "COMPLETE_DISH_COST_DATA_PROPOSAL",
                        "executionMode", "READ_ONLY_PROPOSAL",
                        "outcomeDigest", "a".repeat(64)),
                42L);
        instanceRepository.flush();
        historyRepository.flush();

        assertThat(started.getStatus()).isEqualTo(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        assertThat(started.getCurrentNodeIds()).containsExactly("human_review");
        assertThat(instanceRepository.findByFactoryIdAndModuleCodeAndBusinessEntityIdAndStatus(
                FACTORY_ID, MODULE, ENTITY_ID,
                ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .get()
                .extracting(ApprovalWorkflowInstance::getId)
                .isEqualTo(started.getId());
        assertThat(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                FACTORY_ID, started.getId())).hasSize(1);

        historyRepository.saveAndFlush(ApprovalHistory.builder()
                .factoryId(FACTORY_ID)
                .instanceId(started.getId())
                .nodeId("human_review")
                .action(ApprovalHistory.HistoryAction.APPROVE)
                .actorId(42L)
                .actorRole("restaurant_owner")
                .notes("repository query gate")
                .build());
        assertThat(instanceRepository.findActedBy(
                FACTORY_ID, 42L, PageRequest.of(0, 10)).getContent())
                .extracting(ApprovalWorkflowInstance::getId)
                .containsExactly(started.getId());
        assertThat(instanceRepository.findByFactoryIdAndIdIn(
                FACTORY_ID, List.of(started.getId())))
                .extracting(ApprovalWorkflowInstance::getId)
                .containsExactly(started.getId());
        assertThat(historyRepository.findByFactoryIdAndActionOrderByCreatedAtDesc(
                FACTORY_ID, ApprovalHistory.HistoryAction.APPROVE))
                .extracting(ApprovalHistory::getInstanceId)
                .contains(started.getId());
        assertThat(snapshotBusinessTables()).isEqualTo(before);
        assertThat(before).hasSize(FORBIDDEN_BUSINESS_TABLES.size());
    }

    private Map<String, Long> snapshotBusinessTables() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : FORBIDDEN_BUSINESS_TABLES) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = current_schema() AND table_name = ?",
                    Integer.class, table);
            if (exists != null && exists == 1) {
                Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
                counts.put(table, count);
            }
        }
        return counts;
    }
}
