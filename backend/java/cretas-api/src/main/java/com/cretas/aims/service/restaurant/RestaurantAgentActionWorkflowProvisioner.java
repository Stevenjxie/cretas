package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Provisions the versioned, human-only Restaurant Agent action-review workflow.
 *
 * <p>The trusted Factory row is locked before the existence check and insert,
 * serializing concurrent provisioning across application processes. The database
 * unique key on {@code (factory_id, decision_type, name)} remains the final guard.
 */
@Service
@Slf4j
public class RestaurantAgentActionWorkflowProvisioner {

    static final String DECISION_TYPE = "RESTAURANT_AGENT_ACTION_REVIEW";
    static final String WORKFLOW_NAME = RestaurantAgentActionProposalMapper.WORKFLOW_KEY;
    static final int WORKFLOW_VERSION = 1;
    static final int WORKFLOW_PRIORITY = 1000;

    private static final Set<FactoryType> ELIGIBLE_TYPES =
            Set.of(FactoryType.RESTAURANT, FactoryType.BRANCH);

    private static final String DESCRIPTION =
            "Human review of missing dish cost data. Approval only unlocks navigation to the recipe data page.";

    private static final String NODES_JSON = """
            [
              {"id":"start","type":"start","label":"Submit review","position":{"x":60,"y":120},"config":{}},
              {"id":"human_review","type":"approval","label":"Review dish cost data","position":{"x":320,"y":120},"config":{"approverRoles":["restaurant_owner","restaurant_manager","finance_manager"],"requiredApprovers":1,"timeoutMinutes":1440}},
              {"id":"approved","type":"end","label":"Approved","position":{"x":600,"y":120},"config":{"outcome":"APPROVED"}}
            ]
            """;

    private static final String EDGES_JSON = """
            [
              {"id":"start_review","source":"start","target":"human_review","priority":0},
              {"id":"review_approved","source":"human_review","target":"approved","priority":0}
            ]
            """;

    private final FactoryRepository factoryRepository;
    private final ApprovalWorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    public RestaurantAgentActionWorkflowProvisioner(
            FactoryRepository factoryRepository,
            ApprovalWorkflowRepository workflowRepository,
            ObjectMapper objectMapper) {
        this.factoryRepository = factoryRepository;
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Provision only for active restaurant tenants. Inactive tenants are covered
     * when their normal activation lifecycle runs.
     *
     * @return {@code true} only when this call inserted the canonical workflow
     */
    @Transactional
    public boolean provisionIfEligible(Factory factory) {
        if (factory == null || factory.getId() == null) {
            return false;
        }
        Factory lockedFactory = factoryRepository.findByIdForUpdate(factory.getId())
                .orElse(null);
        if (lockedFactory == null
                || !Boolean.TRUE.equals(lockedFactory.getIsActive())
                || !ELIGIBLE_TYPES.contains(lockedFactory.getType())) {
            return false;
        }
        if (workflowRepository.existsByFactoryIdAndDecisionTypeAndName(
                lockedFactory.getId(),
                DecisionType.RESTAURANT_AGENT_ACTION_REVIEW,
                WORKFLOW_NAME)) {
            log.debug("Restaurant Agent action workflow already exists; preserving tenant config: factoryId={}",
                    lockedFactory.getId());
            return false;
        }
        ApprovalWorkflow workflow = ApprovalWorkflow.builder()
                .factoryId(lockedFactory.getId())
                .decisionType(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW)
                .name(WORKFLOW_NAME)
                .description(DESCRIPTION)
                .nodesJson(NODES_JSON)
                .edgesJson(EDGES_JSON)
                .startNodeId("start")
                .version(WORKFLOW_VERSION)
                .publishStatus("published")
                .enabled(true)
                .priority(WORKFLOW_PRIORITY)
                .build();
        workflowRepository.saveAndFlush(workflow);
        log.info("Provisioned Restaurant Agent action workflow: factoryId={}, version={}",
                lockedFactory.getId(), WORKFLOW_VERSION);
        return true;
    }

    /**
     * Checks the complete code-owned safety contract without mutating tenant data.
     * Description and priority may be tenant-adjusted; execution topology may not.
     */
    public boolean isCanonical(ApprovalWorkflow workflow) {
        if (workflow == null
                || workflow.getDecisionType() != DecisionType.RESTAURANT_AGENT_ACTION_REVIEW
                || !WORKFLOW_NAME.equals(workflow.getName())
                || !Integer.valueOf(WORKFLOW_VERSION).equals(workflow.getVersion())
                || !"published".equals(workflow.getPublishStatus())
                || !Boolean.TRUE.equals(workflow.getEnabled())
                || !"start".equals(workflow.getStartNodeId())) {
            return false;
        }
        try {
            return objectMapper.readTree(NODES_JSON).equals(objectMapper.readTree(workflow.getNodesJson()))
                    && objectMapper.readTree(EDGES_JSON).equals(
                            objectMapper.readTree(workflow.getEdgesJson()));
        } catch (JsonProcessingException | RuntimeException invalidJson) {
            return false;
        }
    }
}
