package com.cretas.aims.ai.tool.impl.config;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.WorkflowTemplate;
import com.cretas.aims.service.WorkflowTemplateService;
import com.cretas.aims.service.workflow.WorkflowNodeRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Factory Config Agent Tool
 *
 * Implements the pursuit questioning mechanism for guided workflow configuration.
 * Uses WorkflowNodeRegistry to dynamically discover available node types.
 *
 * Actions:
 * - "start"       — begin new config session, return topic checklist
 * - "analyze"     — analyze user description, identify covered topics, suggest next question
 * - "generate"    — generate StateMachine JSON + nodeConfigs from collected answers
 * - "modify"      — modify existing config based on user instruction
 * - "node_schemas"— return available node schemas for AI context
 */
@Slf4j
@Component
public class FactoryConfigAgentTool extends AbstractBusinessTool {

    @Autowired
    private WorkflowNodeRegistry workflowNodeRegistry;

    @Autowired
    @Lazy
    private WorkflowTemplateService workflowTemplateService;

    private static final List<Map<String, Object>> CONFIG_TOPICS = List.of(
            Map.of("id", "industry", "name", "行业与产品", "required", true,
                    "question", "您工厂主要做什么产品？典型生产周期多少天？",
                    "extractKeys", List.of("industry", "productType", "cycleDays")),
            Map.of("id", "dimension", "name", "生产维度", "required", true,
                    "question", "生产是围绕工序组织还是围绕批次组织？",
                    "extractKeys", List.of("mode")),
            Map.of("id", "processes", "name", "工序流程", "required", true,
                    "question", "典型的工序有哪些？每道工序的计量单位是什么？",
                    "extractKeys", List.of("processList", "units")),
            Map.of("id", "reporting", "name", "报工规则", "required", true,
                    "question", "报工是每次累加还是一天统一报？达标后怎么处理？",
                    "extractKeys", List.of("reportingInterval", "onTargetReached")),
            Map.of("id", "checkin", "name", "签到方式", "required", true,
                    "question", "签到是主管扫码还是工人自助？用什么方式？",
                    "extractKeys", List.of("checkinMethod", "autoCheckout")),
            Map.of("id", "approval", "name", "审批流程", "required", true,
                    "question", "正常报工需要审批吗？补报怎么处理？",
                    "extractKeys", List.of("requireApproval", "supplementPolicy")),
            Map.of("id", "advanced", "name", "进阶配置", "required", false,
                    "question", "需要在某些工序后加质检环节吗？",
                    "extractKeys", List.of("qualityCheck", "autoComplete"))
    );

    @Override
    public String getToolName() {
        return "factory_config_agent";
    }

    @Override
    public ActionType getActionType() { return ActionType.GENERATE; }

    @Override
    public RiskLevel getRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public String getDescription() {
        return "工厂生产流程配置 AI Agent。通过追问机制逐步收集配置信息，" +
                "自动生成 StateMachine 工作流配置。" +
                "适用场景：配置新工厂生产流程、修改现有工作流、查询可用节点类型。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("action", Map.of("type", "string",
                "enum", List.of("start", "analyze", "generate", "modify", "node_schemas", "save_template", "list_templates"),
                "description", "操作类型: start-开始配置/analyze-分析描述/generate-生成配置/modify-修改配置/node_schemas-获取节点类型/save_template-保存为模板/list_templates-查看模板"));
        properties.put("userInput", Map.of("type", "string",
                "description", "用户输入的描述文本（analyze/modify 时使用）"));
        properties.put("topicsCovered", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "已覆盖的主题ID列表"));
        properties.put("collectedConfig", Map.of("type", "object",
                "description", "已收集的配置信息（generate 时使用）"));

        schema.put("properties", properties);
        schema.put("required", List.of("action"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("action");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String action = getString(params, "action");
        log.info("Factory Config Agent - action: {}, factoryId: {}", action, factoryId);

        switch (action) {
            case "start":
                return handleStart();
            case "analyze":
                return handleAnalyze(params);
            case "generate":
                return handleGenerate(factoryId, params);
            case "modify":
                return handleModify(params);
            case "node_schemas":
                return handleNodeSchemas();
            case "save_template":
                return handleSaveTemplate(factoryId, params);
            case "list_templates":
                return handleListTemplates(params);
            default:
                return buildSimpleResult("未知操作: " + action, null);
        }
    }

    private Map<String, Object> handleStart() {
        List<Map<String, Object>> checklist = CONFIG_TOPICS.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.get("id"));
            item.put("name", t.get("name"));
            item.put("required", t.get("required"));
            item.put("status", "pending");
            item.put("question", t.get("question"));
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("message", "工厂配置向导已启动。我将逐步引导您完成生产流程配置。\n\n" +
                "首先，请告诉我：您工厂主要做什么产品？典型生产周期多少天？");
        result.put("data", Map.of(
                "topicChecklist", checklist,
                "topicsCovered", List.of(),
                "topicsRemaining", CONFIG_TOPICS.stream().map(t -> t.get("id")).collect(Collectors.toList()),
                "progress", 0
        ));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAnalyze(Map<String, Object> params) {
        String userInput = getString(params, "userInput");
        List<String> covered = (List<String>) params.getOrDefault("topicsCovered", List.of());

        // Determine which topics remain
        List<String> remaining = CONFIG_TOPICS.stream()
                .map(t -> (String) t.get("id"))
                .filter(id -> !covered.contains(id))
                .collect(Collectors.toList());

        int progress = (int) ((double) covered.size() / CONFIG_TOPICS.size() * 100);

        // Find next question
        String nextQuestion = null;
        String nextTopicId = null;
        for (Map<String, Object> topic : CONFIG_TOPICS) {
            String id = (String) topic.get("id");
            if (!covered.contains(id)) {
                nextTopicId = id;
                nextQuestion = (String) topic.get("question");
                break;
            }
        }

        Map<String, Object> analysisResult = new LinkedHashMap<>();
        analysisResult.put("userInput", userInput);
        analysisResult.put("topicsCovered", covered);
        analysisResult.put("topicsRemaining", remaining);
        analysisResult.put("progress", progress);
        analysisResult.put("nextTopicId", nextTopicId);
        analysisResult.put("nextQuestion", nextQuestion);

        // Provide node schemas context for LLM to interpret user answers
        analysisResult.put("availableNodeTypes", workflowNodeRegistry.getAllNodeSchemas());

        String message;
        if (nextQuestion != null) {
            message = String.format("配置进度 %d%%。接下来，%s", progress, nextQuestion);
        } else {
            message = "所有配置主题已覆盖！可以使用 generate 操作生成工作流配置。";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("message", message);
        result.put("data", analysisResult);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleGenerate(String factoryId, Map<String, Object> params) {
        Map<String, Object> collectedConfig = (Map<String, Object>) params.getOrDefault("collectedConfig", Map.of());

        // Build StateMachine states and transitions based on collected config
        List<Map<String, Object>> states = new ArrayList<>();
        List<Map<String, Object>> transitions = new ArrayList<>();

        // Always include plan_created as initial state
        states.add(Map.of("code", "plan_created", "name", "已创建", "type", "initial", "color", "#409EFF"));
        states.add(Map.of("code", "in_progress", "name", "进行中", "type", "normal", "color", "#E6A23C"));
        states.add(Map.of("code", "target_reached", "name", "已达标", "type", "normal", "color", "#67C23A"));
        states.add(Map.of("code", "completed", "name", "已完成", "type", "final", "color", "#909399"));

        // Basic transitions
        transitions.add(Map.of("from", "plan_created", "to", "in_progress", "event", "start_work"));
        transitions.add(Map.of("from", "in_progress", "to", "target_reached", "event", "reach_target",
                "guard", "#isCompletedGtePlanned(id)"));
        transitions.add(Map.of("from", "target_reached", "to", "completed", "event", "mark_complete"));

        // Add supplementing if configured
        Object supplementPolicy = collectedConfig.get("supplementPolicy");
        if (supplementPolicy != null && !"forbidden".equals(supplementPolicy)) {
            states.add(Map.of("code", "supplementing", "name", "补报中", "type", "normal", "color", "#F56C6C"));
            transitions.add(Map.of("from", "completed", "to", "supplementing", "event", "start_supplement",
                    "action", "action:enter_supplementing"));
            transitions.add(Map.of("from", "supplementing", "to", "completed", "event", "finish_supplement",
                    "guard", "#hasNoPendingSupplements(id)", "action", "action:exit_supplementing"));
        }

        // Add closed state
        states.add(Map.of("code", "closed", "name", "已关闭", "type", "final", "color", "#C0C4CC"));
        transitions.add(Map.of("from", "completed", "to", "closed", "event", "close_task"));

        Map<String, Object> generatedConfig = new LinkedHashMap<>();
        generatedConfig.put("factoryId", factoryId);
        generatedConfig.put("entityType", "PRODUCTION_WORKFLOW");
        generatedConfig.put("states", states);
        generatedConfig.put("transitions", transitions);
        generatedConfig.put("nodeSchemas", workflowNodeRegistry.getAllNodeSchemas());

        Map<String, Object> result = new HashMap<>();
        result.put("message", "工作流配置已生成。包含 " + states.size() + " 个状态和 " +
                transitions.size() + " 个转换。请在设计器中查看和调整。");
        result.put("data", generatedConfig);
        return result;
    }

    private Map<String, Object> handleModify(Map<String, Object> params) {
        String userInput = getString(params, "userInput");

        // Return context for LLM to interpret modification request
        Map<String, Object> modifyContext = new LinkedHashMap<>();
        modifyContext.put("userInstruction", userInput);
        modifyContext.put("availableNodeTypes", workflowNodeRegistry.getAllNodeSchemas());

        Map<String, Object> result = new HashMap<>();
        result.put("message", "请在设计器中根据以下指令修改工作流: " + userInput);
        result.put("data", modifyContext);
        return result;
    }

    private Map<String, Object> handleNodeSchemas() {
        var schemas = workflowNodeRegistry.getAllNodeSchemas();

        Map<String, Object> result = new HashMap<>();
        result.put("message", "当前共有 " + schemas.size() + " 种可用的工作流节点类型");
        result.put("data", Map.of("nodeSchemas", schemas, "count", schemas.size()));
        return result;
    }

    private Map<String, Object> handleSaveTemplate(String factoryId, Map<String, Object> params) {
        String templateName = getString(params, "templateName");
        String description = getString(params, "description");
        String industryTags = getString(params, "industryTags");
        String entityType = getString(params, "entityType", "PRODUCTION_WORKFLOW");

        WorkflowTemplate template = workflowTemplateService.extractFromStateMachine(
                factoryId, entityType, templateName, description, industryTags);

        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("工作流模板「%s」已保存，状态为待审核。模板ID: %d",
                template.getTemplateName(), template.getId()));
        result.put("data", Map.of(
                "templateId", template.getId(),
                "templateName", template.getTemplateName(),
                "reviewStatus", template.getReviewStatus(),
                "sourceCount", template.getSourceCount()
        ));
        return result;
    }

    private Map<String, Object> handleListTemplates(Map<String, Object> params) {
        String keyword = getString(params, "keyword");
        List<WorkflowTemplate> templates = workflowTemplateService.search(keyword);

        List<Map<String, Object>> templateList = templates.stream().map(t -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId());
            item.put("name", t.getTemplateName());
            item.put("description", t.getDescription());
            item.put("reviewStatus", t.getReviewStatus());
            item.put("sourceCount", t.getSourceCount());
            item.put("isSeedData", t.getIsSeedData());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("共找到 %d 个工作流模板", templates.size()));
        result.put("data", Map.of("templates", templateList, "count", templates.size()));
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "action", "请选择操作类型: start-开始配置, analyze-分析描述, generate-生成配置, modify-修改配置",
            "userInput", "请描述您的工厂生产流程"
        );
        return questions.get(paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
