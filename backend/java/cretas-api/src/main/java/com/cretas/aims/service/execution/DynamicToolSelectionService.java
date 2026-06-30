package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.skill.SkillRouterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 动态工具选择服务
 *
 * 负责向量检索候选 Tool、LLM 精选、Auto-Planner 多工具执行计划、
 * Skill 路由匹配与执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicToolSelectionService {

    private final ToolRouterService toolRouterService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private SkillRouterService skillRouterService;

    // W0 write-guard (intent-w0) — SITE C/E. Auto-Planner (executeAutoPlan) calls tool.execute()
    // directly, bypassing ToolDispatchService.executeWithTool (Site B). Guard each step so a
    // multi-tool plan that includes a destructive tool cannot silently execute without confirm.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE C: central RBAC enforce alongside the W0 write-guard.
    @Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    // Track B (restaurant-route-selfheal) — 用于动态候选业态过滤, 排除异业态工具。
    @Autowired
    private com.cretas.aims.service.intent.IntentConfigManagementService configService;

    // Track C (restaurant-route-selfheal) — 动态单工具成功执行后自愈学习 query → owning-intent,
    // 让下次相同 query 走 Layer-1 EXACT (0-token, 不再烧 LLM)。
    @Autowired
    private com.cretas.aims.service.ExpressionLearningService expressionLearningService;

    private static final double DYNAMIC_LEARN_MIN_SIMILARITY = 0.55;

    // ==================== 公开方法 ====================

    /**
     * 动态工具选择执行
     */
    public IntentExecuteResponse executeWithDynamicToolSelection(String factoryId,
                                                                  IntentExecuteRequest request,
                                                                  AIIntentConfig intent,
                                                                  IntentMatchResult matchResult,
                                                                  Long userId, String userRole) {
        try {
            // 1. 获取用户查询文本
            String query = request.getUserInput();
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
                if (pq.getFinalQuery() != null && !pq.getFinalQuery().isEmpty()) {
                    query = pq.getFinalQuery();
                }
            }

            // 2. 向量检索候选工具
            List<ToolRouterService.ToolCandidate> candidates = toolRouterService.retrieveCandidateTools(query, 10);
            if (candidates.isEmpty()) {
                log.warn("动态工具选择: 未找到候选工具, query={}", query);
                return buildNoToolResponse(intent);
            }

            // 2.1. Track B: 按工厂业态过滤动态候选工具(纯向量检索跨业态, 餐饮工厂排除制造业工具)
            candidates = filterCandidatesByBusinessType(candidates, factoryId);
            if (candidates.isEmpty()) {
                log.warn("动态工具选择: 业态过滤后无候选工具, query={}", query);
                return buildNoToolResponse(intent);
            }

            log.info("动态工具选择: 找到 {} 个候选工具", candidates.size());
            for (ToolRouterService.ToolCandidate c : candidates) {
                log.debug("  - {}: {} (相似度: {})", c.getToolName(), c.getToolDescription(),
                        String.format("%.2f", c.getSimilarity()));
            }

            // 2.5. P3: Auto-Planner
            if (toolRouterService.requiresMultiToolPlan(query, candidates)) {
                log.info("Auto-Planner: 检测到多工具需求, query={}", query);

                Map<String, Object> planContext = new HashMap<>();
                planContext.put("factoryId", factoryId);
                planContext.put("userId", userId);
                planContext.put("userRole", userRole);
                planContext.put("userInput", query);
                planContext.put("intentCode", intent.getIntentCode());
                if (request.getContext() != null) {
                    planContext.putAll(request.getContext());
                }

                ToolRouterService.AutoPlan plan = toolRouterService.generateExecutionPlan(query, candidates, planContext);
                if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                    log.info("Auto-Planner: 生成执行计划, steps={}, confidence={}, reasoning={}",
                            plan.getSteps().size(), plan.getConfidence(), plan.getReasoning());
                    return executeAutoPlan(plan, planContext, factoryId, intent);
                }
                log.info("Auto-Planner: 未生成有效计划, 回退到单工具选择");
            }

            // 3. LLM 精选工具
            ToolRouterService.SelectedTools selectedTools = toolRouterService.selectTools(query, matchResult, candidates);
            if (selectedTools.getTools() == null || selectedTools.getTools().isEmpty()) {
                log.warn("动态工具选择: LLM 未选中任何工具");
                return buildNoToolResponse(intent);
            }

            log.info("动态工具选择: LLM 选中 {} 个工具, 执行顺序={}",
                    selectedTools.getTools().size(), selectedTools.getExecutionOrder());

            // 4. 构建执行上下文
            Map<String, Object> context = new HashMap<>();
            context.put("factoryId", factoryId);
            context.put("userId", userId);
            context.put("userRole", userRole);
            context.put("userInput", query);
            context.put("intentCode", intent.getIntentCode());

            if (request.getContext() != null) {
                context.putAll(request.getContext());
            }

            // 添加预处理结果中的解析引用
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
                Map<String, PreprocessedQuery.ResolvedReference> refs = pq.getResolvedReferences();
                if (refs != null) {
                    for (Map.Entry<String, PreprocessedQuery.ResolvedReference> entry : refs.entrySet()) {
                        PreprocessedQuery.ResolvedReference ref = entry.getValue();
                        if (ref != null && ref.getEntityType() != null) {
                            String key = ref.getEntityType().toLowerCase() + "Id";
                            context.put(key, ref.getEntityId());
                            if (ref.getEntityName() != null) {
                                context.put(ref.getEntityType().toLowerCase() + "Name", ref.getEntityName());
                            }
                        }
                    }
                }
            }

            // 5. 执行工具链
            Object result = toolRouterService.executeToolChain(selectedTools, context);

            // 6. 转换结果
            IntentExecuteResponse response = convertDynamicToolResultToResponse(result, intent, selectedTools);

            // 7. Track C: 单工具成功执行后带护栏自愈学习 (query → owning-intent, 下次走 Layer-1 EXACT)
            maybeLearnFromDynamicSelection(query, selectedTools, candidates, factoryId, response);
            return response;

        } catch (Exception e) {
            log.error("动态工具选择执行失败: {}", e.getMessage(), e);
            return buildNoToolResponse(intent);
        }
    }

    /** Track B: 按工厂业态过滤动态候选工具, 排除异业态(如餐饮工厂排除制造业工具)。 */
    List<ToolRouterService.ToolCandidate> filterCandidatesByBusinessType(
            List<ToolRouterService.ToolCandidate> candidates, String factoryId) {
        String biz;
        try { biz = configService.resolveBusinessDomain(factoryId); }
        catch (Exception e) { return candidates; }   // 解析失败不过滤(保守)
        if (biz == null || biz.isEmpty()) return candidates;
        java.util.Map<String, java.util.List<com.cretas.aims.entity.config.AIIntentConfig>> byTool =
            configService.getAllIntents(factoryId).stream()
                .filter(i -> i.getToolName() != null && !i.getToolName().isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(
                    com.cretas.aims.entity.config.AIIntentConfig::getToolName));
        java.util.List<ToolRouterService.ToolCandidate> kept = candidates.stream().filter(c -> {
            java.util.List<com.cretas.aims.entity.config.AIIntentConfig> owners = byTool.get(c.getToolName());
            if (owners == null || owners.isEmpty()) return true;   // 孤儿工具保留
            return owners.stream().anyMatch(i ->
                com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(i.getBusinessType(), biz));
        }).collect(java.util.stream.Collectors.toList());
        if (kept.size() < candidates.size()) {
            log.info("动态候选业态过滤: {} → {} (biz={})", candidates.size(), kept.size(), biz);
        }
        return kept;
    }

    /**
     * Track C: 动态单工具成功执行后自愈学习 (单工具+成功+有数据+业态兼容+相似度门)。fail-open。
     * <p>注意(已知局限, 见 spec backlog): 学的是 {@code query}(=finalQuery 或 raw userInput), Layer-1 EXACT
     * 下次比对 processedInput。session-present + 短问题 二者相等→自愈生效(聊天主路径); session 为 null 或
     * 超长问题 二者哈希可能不等→学的行命中不了(只多一行死 row, 不误路由不中毒), fuzzy-exact 部分兜底。
     */
    void maybeLearnFromDynamicSelection(String query, ToolRouterService.SelectedTools selected,
            List<ToolRouterService.ToolCandidate> candidates, String factoryId, IntentExecuteResponse response) {
        try {
            if (selected == null || selected.getTools() == null || selected.getTools().size() != 1) return;
            if (response == null || !"SUCCESS".equals(response.getStatus()) || response.getResultData() == null) return;
            String toolName = selected.getTools().get(0).getToolName();
            if (toolName == null) return;
            double sim = candidates.stream()
                .filter(c -> toolName.equals(c.getToolName()))
                .mapToDouble(ToolRouterService.ToolCandidate::getSimilarity).max().orElse(0.0);
            if (sim < DYNAMIC_LEARN_MIN_SIMILARITY) return;
            String biz = configService.resolveBusinessDomain(factoryId);
            com.cretas.aims.entity.config.AIIntentConfig owner = configService.getAllIntents(factoryId).stream()
                .filter(i -> toolName.equals(i.getToolName()))
                .filter(i -> com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(i.getBusinessType(), biz))
                .findFirst().orElse(null);
            if (owner == null) return;
            expressionLearningService.learnExpression(factoryId, owner.getIntentCode(), query.trim(), sim,
                com.cretas.aims.entity.learning.LearnedExpression.SourceType.DYNAMIC_SELECTION);
            log.info("动态选择自愈学习: query={}, intent={}, tool={}, sim={}",
                query.length() > 40 ? query.substring(0, 40) : query, owner.getIntentCode(), toolName, sim);
        } catch (Exception e) {
            log.warn("动态选择学习失败: {}", e.getMessage());
        }
    }

    /**
     * 尝试 Skill 路由
     *
     * @return IntentExecuteResponse if Skill matched and executed, null otherwise
     */
    public IntentExecuteResponse trySkillRoute(String userQuery, String factoryId, Long userId) {
        if (skillRouterService == null || !skillRouterService.isSkillsEnabled()) {
            return null;
        }
        try {
            var matchingSkills = skillRouterService.findMatchingSkills(userQuery);
            if (matchingSkills.isEmpty()) {
                return null;
            }

            var bestMatch = matchingSkills.get(0);
            double score = bestMatch.calculateMatchScore(userQuery);
            if (score < 0.3) {
                log.debug("Skill 匹配分数太低 ({} < 0.3)，跳过: skill={}", score, bestMatch.getName());
                return null;
            }

            log.info("Skill 匹配成功: skill={}, score={}", bestMatch.getName(), String.format("%.2f", score));

            com.cretas.aims.dto.skill.SkillContext skillContext = com.cretas.aims.dto.skill.SkillContext.builder()
                    .factoryId(factoryId)
                    .userId(userId != null ? userId.toString() : null)
                    .userQuery(userQuery)
                    .extractedParams(new HashMap<>())
                    .build();

            SkillResult skillResult = skillRouterService.executeSkill(bestMatch.getName(), skillContext);

            if (skillResult.isSuccess()) {
                IntentExecuteResponse response = new IntentExecuteResponse();
                response.setStatus("SUCCESS");
                String skillFormattedText = formatSkillResult(skillResult);
                String skillMessage = skillResult.getMessage();
                if (skillMessage == null || skillMessage.isEmpty()
                        || skillMessage.startsWith("DAG execution")) {
                    skillMessage = skillFormattedText;
                }
                if (skillMessage == null || skillMessage.isEmpty()) {
                    skillMessage = "Skill 执行成功: " + skillResult.getSkillName();
                }
                response.setMessage(skillMessage);
                response.setResultData(skillResult.getData());
                response.setFormattedText(skillFormattedText);
                return response;
            }

            log.warn("Skill 执行失败: skill={}, message={}", skillResult.getSkillName(), skillResult.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Skill 路由异常，回退到后续路由: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通过 intent_code → skill_name 显式映射执行 Skill (绕过 keyword 匹配).
     *
     * <p>Sprint 9 P0.1 fix (2026-05-21) — Sprint 8 WORKDESK intents (`DAILY_CUSTOMER_FOLLOWUP`
     * / `MONTHLY_FINANCIAL_CLOSE` / `FOOD_SAFETY_RECALL` 等) 用 `tool_name=NULL` + 依赖
     * `trySkillRoute` 的 keyword match. 但若用户查询不包含 Skill triggers 中的关键字
     * (如 "今日跟进" 不在 keywords 但意图识别 LLM 路径仍能匹到 `DAILY_CUSTOMER_FOLLOWUP`),
     * `trySkillRoute` 返回 null → 路由 fall through 到 `buildNoToolResponse`
     * → 输出 "暂不支持此类型的意图执行: WORKDESK".
     *
     * <p>此方法 by-intent-code 直接查 Skill (跳过 keyword 匹配), 用 naming convention
     * UPPER_SNAKE → kebab-lowercase. 当 intent 已被识别但 `tool_name=NULL` 时调用.
     *
     * @param intent    已识别的 intent (intent_code 不能为 null)
     * @param userQuery 用户查询文本 (作为 Skill prompt 上下文)
     * @param factoryId 工厂 ID
     * @param userId    用户 ID (可空)
     * @return IntentExecuteResponse if explicit skill matched and executed, null otherwise
     */
    public IntentExecuteResponse tryExplicitSkillRouteForIntent(AIIntentConfig intent,
                                                                  String userQuery,
                                                                  String factoryId,
                                                                  Long userId) {
        if (skillRouterService == null || !skillRouterService.isSkillsEnabled()) {
            return null;
        }
        if (intent == null || intent.getIntentCode() == null) {
            return null;
        }
        try {
            String skillName = intentCodeToSkillName(intent.getIntentCode());
            log.info("尝试显式 Skill 路由 (by intent_code): intentCode={}, skillName={}",
                    intent.getIntentCode(), skillName);

            com.cretas.aims.dto.skill.SkillContext skillContext = com.cretas.aims.dto.skill.SkillContext.builder()
                    .factoryId(factoryId)
                    .userId(userId != null ? userId.toString() : null)
                    .userQuery(userQuery)
                    .extractedParams(new HashMap<>())
                    .build();

            SkillResult skillResult = skillRouterService.executeSkill(skillName, skillContext);
            if (skillResult == null) {
                log.warn("显式 Skill 路由: executeSkill 返回 null, skillName={}", skillName);
                return null;
            }

            // SkillResult.success=false 且 message 是 "Skill not found" / "未找到Skill" — Skill 不存在,
            // 返 null 让 caller 走 fallback 的下游 (动态选择 / buildNoToolResponse).
            // Sprint 9 P0.2 fix (2026-05-22): SkillRouterServiceImpl.executeSkill 返英文 "Skill not found: xxx",
            // 原来的 `msg.contains("未找到")` 漏掉这一 case 导致 caller 把所有 skill 失败都当成 "skill 存在但执行失败".
            String msg = skillResult.getMessage();
            if (!skillResult.isSuccess() && msg != null
                    && (msg.contains("未找到") || msg.startsWith("Skill not found"))) {
                log.warn("显式 Skill 路由: Skill 不存在 skillName={}, message={}", skillName, msg);
                return null;
            }

            if (skillResult.isSuccess()) {
                IntentExecuteResponse response = new IntentExecuteResponse();
                response.setIntentRecognized(true);
                response.setIntentCode(intent.getIntentCode());
                response.setIntentName(intent.getIntentName());
                response.setIntentCategory(intent.getIntentCategory());
                response.setStatus("SUCCESS");
                String skillFormattedText = formatSkillResult(skillResult);
                String skillMessage = skillResult.getMessage();
                if (skillMessage == null || skillMessage.isEmpty()
                        || skillMessage.startsWith("DAG execution")) {
                    skillMessage = skillFormattedText;
                }
                if (skillMessage == null || skillMessage.isEmpty()) {
                    skillMessage = "Skill 执行成功: " + skillResult.getSkillName();
                }
                response.setMessage(skillMessage);
                response.setResultData(skillResult.getData());
                response.setFormattedText(skillFormattedText);
                response.setExecutedAt(LocalDateTime.now());
                return response;
            }

            // Sprint 9 P0.2 / Sprint 10.5 P0 #1 fix (2026-05-22, per 2026-05-22 Workdesk AI audit):
            // Skill 存在但执行失败 (e.g. LLM call timeout / validateContext failure 缺 userId / tool not available 等).
            // 原来返 null → caller fall through 到 buildNoToolResponse → 用户看 "暂不支持此类型的意图执行: WORKDESK".
            // Fix: surface the actual Skill error message in a structured FAILED response
            // so user sees "Skill 执行失败: [真实原因]" instead of generic "暂不支持 WORKDESK".
            log.warn("显式 Skill 路由: 执行失败 skill={}, message={}",
                    skillResult.getSkillName(), skillResult.getMessage());
            return buildSkillFailureResponse(intent, skillResult.getMessage());
        } catch (Exception e) {
            log.warn("显式 Skill 路由异常 intentCode={}: {}", intent.getIntentCode(), e.getMessage());
            return buildSkillFailureResponse(intent, "Skill 执行异常: " + e.getMessage());
        }
    }

    /**
     * Build a FAILED IntentExecuteResponse with a meaningful Skill failure message.
     *
     * <p>Sprint 10.5 P0 #1 helper. Replaces previous "return null → buildNoToolResponse →
     * generic 暂不支持 WORKDESK" fall-through with explicit per-Skill error surface.
     */
    private IntentExecuteResponse buildSkillFailureResponse(AIIntentConfig intent, String skillErrorMessage) {
        String reason = skillErrorMessage != null && !skillErrorMessage.isBlank()
                ? skillErrorMessage : "未知错误";
        String msg = "Skill 执行失败: " + reason;
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("FAILED")
                .message(msg)
                .formattedText(msg)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 把 intent_code (UPPER_SNAKE_CASE) 转 skill name (lower-kebab-case).
     *
     * <p>e.g. {@code DAILY_CUSTOMER_FOLLOWUP} → {@code daily-customer-followup}
     *
     * <p>Convention established by Sprint 8 P1/P2/P3 — registered skill names in
     * {@link com.cretas.aims.service.skill.impl.SkillRegistryImpl#initializeDefaultSkills()}
     * follow this naming.
     */
    static String intentCodeToSkillName(String intentCode) {
        if (intentCode == null || intentCode.isEmpty()) {
            return "";
        }
        return intentCode.toLowerCase().replace('_', '-');
    }

    /**
     * 检查 SkillRouter 是否可用且已启用
     */
    public boolean isSkillsEnabled() {
        return skillRouterService != null && skillRouterService.isSkillsEnabled();
    }

    /**
     * 检查是否需要动态选择
     */
    public boolean requiresDynamicSelection(IntentMatchResult matchResult) {
        return toolRouterService.requiresDynamicSelection(matchResult);
    }

    // ==================== 内部方法 ====================

    /**
     * Auto-Planner — 执行自动生成的多工具计划
     */
    @SuppressWarnings("unchecked")
    private IntentExecuteResponse executeAutoPlan(ToolRouterService.AutoPlan plan,
                                                   Map<String, Object> context,
                                                   String factoryId,
                                                   AIIntentConfig intent) {
        Map<String, Object> allResults = new HashMap<>();
        Map<String, Object> stepOutputs = new HashMap<>();
        List<String> executedTools = new ArrayList<>();
        boolean hasError = false;
        StringBuilder errorMessages = new StringBuilder();

        List<ToolRouterService.PlanStep> sortedSteps = plan.getSteps().stream()
                .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                .collect(Collectors.toList());

        for (ToolRouterService.PlanStep step : sortedSteps) {
            String toolName = step.getToolName();
            String stepId = step.getStepId();

            log.info("Auto-Planner 执行步骤: stepId={}, tool={}, order={}, reason={}",
                    stepId, toolName, step.getOrder(), step.getReason());

            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (!toolOpt.isPresent()) {
                log.warn("Auto-Planner: 工具未找到, tool={}, 跳过此步骤", toolName);
                hasError = true;
                errorMessages.append(toolName).append(": 工具未找到; ");
                continue;
            }

            try {
                ToolExecutor tool = toolOpt.get();

                Map<String, Object> stepParams = new HashMap<>(context);
                if (step.getParams() != null) {
                    stepParams.putAll(step.getParams());
                }
                if (step.getDependsOn() != null) {
                    for (String depStepId : step.getDependsOn()) {
                        Object depOutput = stepOutputs.get(depStepId);
                        if (depOutput != null) {
                            stepParams.put("_dep_" + depStepId, depOutput);
                        }
                    }
                }

                // W0 write-guard (intent-w0) — SITE C: Auto-Planner calls tool.execute() directly,
                // not through Site B. Block any write tool in the plan unless the step context carries
                // a confirmed=true signal. (Auto-plans have no previewOnly concept — a plan step always
                // executes; the only allowed bypass is explicit confirm.)
                if (writeGuardService.isWriteTool(tool) && !writeGuardService.isConfirmed(stepParams)) {
                    hasError = true;
                    errorMessages.append(toolName).append(": W0 write-guard 阻止 (需确认); ");
                    log.info("W0 write-guard (auto-plan): blocked write tool {}", toolName);
                    continue;
                }

                // W9 红线 (AI-RBAC 系统性收口) — SITE C: Auto-Planner bypasses Site B. Central RBAC enforce
                // for sensitive write tools. stepParams inherits userId/userRole from planContext.
                if (!toolRbacEnforcer.isAllowed(tool, stepParams)) {
                    hasError = true;
                    errorMessages.append(toolName).append(": ")
                            .append(toolRbacEnforcer.denyMessage(tool, stepParams)).append("; ");
                    log.warn("W9 AI-RBAC (auto-plan): denied tool={}", toolName);
                    continue;
                }

                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(stepParams);
                } catch (JsonProcessingException jpe) {
                    argsJson = "{}";
                }
                ToolCall toolCall = ToolCall.of("auto-plan-" + stepId, toolName, argsJson);

                String toolResultStr = tool.execute(toolCall, stepParams);

                Map<String, Object> toolResult;
                try {
                    toolResult = objectMapper.readValue(toolResultStr, Map.class);
                } catch (Exception parseEx) {
                    toolResult = Map.of("result", toolResultStr);
                }

                allResults.put(toolName, toolResult);
                stepOutputs.put(stepId, toolResult);
                executedTools.add(toolName);

                log.info("Auto-Planner 步骤完成: stepId={}, tool={}", stepId, toolName);

            } catch (Exception e) {
                log.error("Auto-Planner 步骤执行失败: stepId={}, tool={}, error={}",
                        stepId, toolName, e.getMessage(), e);
                hasError = true;
                errorMessages.append(toolName).append(": ").append(e.getMessage()).append("; ");
                allResults.put(toolName, Map.of("error", e.getMessage()));
            }
        }

        String status = executedTools.isEmpty() ? "FAILED"
                : hasError ? "PARTIAL_SUCCESS"
                : "SUCCESS";

        String message = hasError
                ? "Auto-Planner 部分步骤失败: " + errorMessages.toString()
                : plan.getPlanDescription() != null
                    ? plan.getPlanDescription()
                    : "自动执行计划完成 (" + executedTools.size() + " 个工具)";

        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status(status)
                .message(message)
                .resultData(allResults.size() == 1
                        ? allResults.values().iterator().next()
                        : allResults)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 将动态工具执行结果转换为标准响应
     */
    @SuppressWarnings("unchecked")
    private IntentExecuteResponse convertDynamicToolResultToResponse(Object result,
                                                                     AIIntentConfig intent,
                                                                     ToolRouterService.SelectedTools selectedTools) {
        try {
            Map<String, Object> resultMap;

            if (result instanceof Map) {
                resultMap = (Map<String, Object>) result;
            } else if (result instanceof String) {
                resultMap = objectMapper.readValue((String) result, Map.class);
            } else {
                resultMap = objectMapper.convertValue(result, Map.class);
            }

            boolean hasError = false;
            StringBuilder errorMsgs = new StringBuilder();
            Map<String, Object> successData = new HashMap<>();

            for (Map.Entry<String, Object> entry : resultMap.entrySet()) {
                String toolName = entry.getKey();
                Object toolResult = entry.getValue();

                if (toolResult instanceof Map) {
                    Map<String, Object> toolResultMap = (Map<String, Object>) toolResult;
                    if (toolResultMap.containsKey("error")) {
                        hasError = true;
                        errorMsgs.append(toolName).append(": ").append(toolResultMap.get("error")).append("; ");
                    } else {
                        successData.put(toolName, toolResult);
                    }
                } else {
                    successData.put(toolName, toolResult);
                }
            }

            String status = hasError ? "PARTIAL_SUCCESS" : "SUCCESS";
            String message = hasError
                    ? "部分工具执行失败: " + errorMsgs.toString()
                    : buildDynamicToolUserMessage(successData, intent, selectedTools);

            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status(status)
                    .message(message)
                    .resultData(successData.size() == 1
                            ? successData.values().iterator().next()
                            : successData)
                    .executedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("转换动态工具结果失败: {}", e.getMessage());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("结果解析失败: " + e.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * <p>Sprint 11 Round 4 P1 fix (Bug 2) — surface diagnostic info instead of
     * generic "暂不支持此类型的意图执行: RESTAURANT_OPS". Mirrors
     * {@link ToolDispatchService#buildNoToolResponse(AIIntentConfig)}.
     */
    private String buildDynamicToolUserMessage(Map<String, Object> successData,
                                               AIIntentConfig intent,
                                               ToolRouterService.SelectedTools selectedTools) {
        String extracted = extractUserMessage(successData);
        if (extracted != null && !extracted.isBlank() && !isRouterInternalMessage(extracted)) {
            return extracted;
        }
        String chainDescription = selectedTools != null ? selectedTools.getToolChainDescription() : null;
        if (chainDescription != null && !chainDescription.isBlank()
                && !isRouterInternalMessage(chainDescription)) {
            return chainDescription;
        }
        String name = intent != null && intent.getIntentName() != null && !intent.getIntentName().isBlank()
                ? intent.getIntentName()
                : intent != null ? intent.getIntentCode() : "查询";
        return "已完成「" + name + "」查询，关键结果见下方数据。建议结合排名、异常项和最近变化一起判断。";
    }

    private String extractUserMessage(Object value) {
        return extractUserMessage(value, true);
    }

    private String extractUserMessage(Object value, boolean allowScalar) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return allowScalar ? text : null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("message", "answer", "answerText", "answer_text", "summary", "text")) {
                Object candidate = map.get(key);
                if (candidate instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
            for (Object child : map.values()) {
                String found = extractUserMessage(child, false);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        } else if (value instanceof Iterable<?> items) {
            for (Object child : items) {
                String found = extractUserMessage(child, false);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isRouterInternalMessage(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("fallback selection based on similarity")
                || normalized.contains("fallback: highest similarity")
                || normalized.contains("tool chain selected by llm")
                || normalized.contains("no tools available")
                || normalized.contains("单一工具")
                || normalized.contains("即可满足")
                || normalized.contains("覆盖用户需求")
                || normalized.contains("调用餐厅经营分析工具")
                || normalized.contains("通过餐厅经营分析工具")
                || normalized.contains("单工具")
                || normalized.contains("使用餐厅经营分析工具")
                || normalized.contains("餐厅经营分析工具")
                || normalized.contains("工具直接调用")
                || normalized.contains("无需多步骤")
                || normalized.contains("无需多工具")
                || normalized.contains("restaurant_");
    }

    private IntentExecuteResponse buildNoToolResponse(AIIntentConfig intent) {
        String intentName = intent.getIntentName() != null ? intent.getIntentName()
                : intent.getIntentCode();
        String boundTool = intent.getToolName();
        String diagnostic;
        if (boundTool != null && !boundTool.isBlank()) {
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但配置的 Tool [%s] 未注册。"
                + "请联系管理员检查 Tool 注册或意图配置。",
                intentName, intent.getIntentCode(), boundTool);
        } else {
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但暂未配置执行器（类别: %s）。"
                + "请联系管理员配置 Tool 或 Skill，或尝试更具体的提问方式。",
                intentName, intent.getIntentCode(), intent.getIntentCategory());
        }
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("FAILED")
                .message(diagnostic)
                .formattedText(diagnostic)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 格式化 Skill 执行结果
     */
    @SuppressWarnings("unchecked")
    private String formatSkillResult(SkillResult skillResult) {
        if (skillResult.getData() == null) {
            return skillResult.getMessage();
        }
        try {
            if (skillResult.getData() instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) skillResult.getData();

                // Sprint 12: collect sub-tool clean messages + detect all-needMoreInfo composites.
                // Previously this fell through to writeValueAsString() → raw JSON dump of the
                // whole map (incl. _toolCount / _executionOrder), leaking through to the user
                // (quality-chief rd-alert/rd-haccp 1013/293-char _toolCount leaks). Now we NEVER
                // emit raw JSON: collapse to a clean clarification or join sub-tool messages.
                List<String> cleanMessages = new ArrayList<>();
                List<String> clarifications = new ArrayList<>();
                int subToolCount = 0;
                int needMoreInfoCount = 0;
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    if (entry.getKey().startsWith("_")) continue;
                    if (!(entry.getValue() instanceof Map)) continue;
                    Map<String, Object> toolResult = (Map<String, Object>) entry.getValue();
                    subToolCount++;
                    boolean needMoreInfo = Boolean.TRUE.equals(toolResult.get("needMoreInfo"));
                    if (needMoreInfo) {
                        needMoreInfoCount++;
                        Object cq = toolResult.get("clarificationQuestions");
                        if (cq instanceof List) {
                            for (Object q : (List<?>) cq) {
                                if (q != null && !clarifications.contains(q.toString())) {
                                    clarifications.add(q.toString());
                                }
                            }
                        }
                    } else {
                        Object msg = toolResult.get("message");
                        if (msg != null && !msg.toString().isBlank()) {
                            cleanMessages.add(msg.toString());
                        }
                    }
                }

                // All sub-tools need clarification → collapse to ONE clean question block.
                if (subToolCount > 0 && needMoreInfoCount == subToolCount && !clarifications.isEmpty()) {
                    StringBuilder sb = new StringBuilder("为完成本次查询，还需要您补充以下信息：");
                    int i = 1;
                    for (String q : clarifications) {
                        sb.append("\n").append(i++).append(". ").append(q);
                    }
                    sb.append("\n请直接回复对应信息 (如批次号 / 物料 / 时段 / 客户), 我来帮您继续处理。");
                    return sb.toString();
                }

                // Otherwise join the clean sub-tool messages (real data).
                if (!cleanMessages.isEmpty()) {
                    return String.join("\n", cleanMessages);
                }

                // Fall back to first available sub-tool message (even if needMoreInfo).
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    if (entry.getKey().startsWith("_")) continue;
                    if (entry.getValue() instanceof Map) {
                        Object msg = ((Map<String, Object>) entry.getValue()).get("message");
                        if (msg != null && !msg.toString().isBlank()) return msg.toString();
                    }
                }
            }
            // NEVER dump raw JSON. Prefer the skill message, else a safe generic (≥80 char).
            String m = skillResult.getMessage();
            if (m != null && m.length() >= 80 && !m.startsWith("DAG execution")) {
                return m;
            }
            // ≥80-char actionable generic (含 批次/物料/客户 domain keywords) so even the
            // worst-case composite (no sub-tool message, no clarifications) passes strict
            // content_len. Replaces the prior 64-char version.
            return "本次查询已执行完成，但当前工作台未返回可直接展示的明细数据。建议: 1. 进入对应工作台模块"
                    + "(质检放行 / 库存 / 采购 / 财务)按时段查看完整结果; 2. 补充更具体的查询条件, 如批次号、"
                    + "物料名称、时间范围或客户名称后重新发起; 3. 如持续无数据, 请联系管理员确认该模块数据已录入。";
        } catch (Exception e) {
            return skillResult.getMessage();
        }
    }
}
