package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.WorkProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * D1: 产品工序配置页自然语言配工序 Tool.
 *
 * <p>Plan/preview mode returns a draft for the C4 pendingOps UI. Execute only writes
 * when callers pass {@code apply=true}; this keeps the AI chat review-first by default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductWorkProcessConfigTool extends AbstractBusinessTool {

    private static final Pattern SEPARATOR = Pattern.compile("[，,。；;、\\n]+");
    private static final Pattern STEP_PREFIX = Pattern.compile(
            "^(第?[一二三四五六七八九十百\\d]+步|步骤[一二三四五六七八九十百\\d]+|\\d+[.)、])");
    private static final Pattern ASSIGNEE_SUFFIX = Pattern.compile(
            "(交给|归|给|由|小组长|负责人|负责).*");
    private static final Set<String> FILLER_WORDS = Set.of(
            "先", "再", "然后", "最后", "配置", "安排", "工序", "流程");

    private final ProductWorkProcessService productWorkProcessService;
    private final WorkProcessService workProcessService;
    private final UserService userService;

    @Override
    public String getToolName() {
        return "canvas_product_work_process_config";
    }

    @Override
    public String getDescription() {
        return "将产品工序配置页的自然语言说明解析为工序链草稿，可按本厂工序 catalog 和操作员名单匹配默认责任小组长。"
                + "默认只返回草稿；apply=true 时调用 ProductWorkProcessService create/update 写入配置。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("productTypeId", Map.of(
                "type", "string",
                "description", "产品类型ID"));
        properties.put("message", Map.of(
                "type", "string",
                "description", "自然语言工序配置说明，例如：第一步修油，滚揉交给莫云，第三步焯水"));
        properties.put("apply", Map.of(
                "type", "boolean",
                "description", "是否直接写库。默认 false，仅返回草稿供前端 pendingOps 预览"));

        schema.put("properties", properties);
        schema.put("required", List.of("productTypeId", "message"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productTypeId", "message");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        ProductProcessPlan plan = buildPlan(factoryId, params);
        return buildResult(plan, false);
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        ProductProcessPlan plan = buildPlan(factoryId, params);
        boolean apply = Boolean.TRUE.equals(getBoolean(params, "apply", false));
        if (!apply) {
            return buildResult(plan, false);
        }

        // B-3: fail-soft apply — collect per-step errors instead of aborting mid-write
        List<ProductWorkProcessDTO> applied = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (DraftStep step : plan.steps()) {
            try {
                applied.add(applyStep(factoryId, plan.productTypeId(), step));
            } catch (Exception e) {
                String stepLabel = step.processName() != null ? step.processName() : step.workProcessId();
                String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                warnings.add("工序「" + stepLabel + "」保存失败: " + errorDetail);
                log.warn("AI 产品工序配置步骤写入失败: productTypeId={}, step={}, error={}",
                        plan.productTypeId(), stepLabel, e.getMessage(), e);
            }
        }

        log.info("AI 产品工序配置已应用: productTypeId={}, applied={}/{}, missing={}, warnings={}",
                plan.productTypeId(), applied.size(), plan.steps().size(),
                plan.missingProcesses().size(), warnings.size());

        Map<String, Object> result = buildResult(plan, true);
        result.put("appliedRows", applied);
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
            // Clarify in message that only some steps succeeded
            result.put("message", result.get("message")
                    + "；" + warnings.size() + " 道写入失败: " + String.join("；", warnings));
        }
        return result;
    }

    private ProductWorkProcessDTO applyStep(String factoryId, String productTypeId, DraftStep step) {
        ProductWorkProcessDTO dto = step.toProductWorkProcessDTO(productTypeId);
        if (step.productWorkProcessId() != null) {
            return productWorkProcessService.update(factoryId, step.productWorkProcessId(), dto);
        }
        return productWorkProcessService.create(factoryId, dto);
    }

    private ProductProcessPlan buildPlan(String factoryId, Map<String, Object> params) {
        String productTypeId = getString(params, "productTypeId");
        String message = getString(params, "message");

        List<WorkProcessDTO> catalog = workProcessService.listActive(factoryId);
        List<UserDTO> operators = loadAssignableUsers(factoryId);
        List<ProductWorkProcessDTO> existing =
                productWorkProcessService.listByProduct(factoryId, productTypeId);

        List<MatchedProcess> matchedProcesses = matchProcesses(message, catalog);
        Map<String, ProductWorkProcessDTO> existingByProcessId = existing.stream()
                .collect(Collectors.toMap(
                        ProductWorkProcessDTO::getWorkProcessId,
                        item -> item,
                        (left, right) -> left));

        String normalizedMessage = normalize(message);
        List<DraftStep> steps = new ArrayList<>();
        for (int i = 0; i < matchedProcesses.size(); i++) {
            MatchedProcess matched = matchedProcesses.get(i);
            // B-2: compute right boundary = start of next matched process (or end of string)
            // so assignee search for step N cannot bleed into step N+1's text
            int nextBoundary;
            if (i + 1 < matchedProcesses.size()) {
                nextBoundary = matchedProcesses.get(i + 1).startIndex();
            } else {
                nextBoundary = normalizedMessage.length();
            }
            UserDTO assignee = matchAssignee(normalizedMessage, matched, operators, nextBoundary);
            ProductWorkProcessDTO existingRow = existingByProcessId.get(matched.workProcess().getId());
            steps.add(new DraftStep(
                    existingRow != null ? "update" : "create",
                    existingRow != null ? existingRow.getId() : null,
                    matched.workProcess().getId(),
                    matched.workProcess().getProcessName(),
                    matched.workProcess().getProcessCategory(),
                    matched.workProcess().getUnit(),
                    i + 1,
                    assignee != null ? assignee.getId() : null,
                    assignee != null ? displayUserName(assignee) : null));
        }

        List<Map<String, Object>> missing = detectMissingProcessNames(message, matchedProcesses);
        return new ProductProcessPlan(productTypeId, steps, missing);
    }

    private List<UserDTO> loadAssignableUsers(String factoryId) {
        List<UserDTO> result = new ArrayList<>();
        result.addAll(userService.getUsersByRole(factoryId, FactoryUserRole.operator));
        result.addAll(userService.getUsersByRole(factoryId, FactoryUserRole.group_leader));
        // I-4: whitelist only explicitly-active users; isActive=null must NOT pass through
        return result.stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .collect(Collectors.toMap(
                        UserDTO::getId,
                        u -> u,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private List<MatchedProcess> matchProcesses(String message, List<WorkProcessDTO> catalog) {
        String normalizedMessage = normalize(message);
        return catalog.stream()
                .filter(wp -> wp.getId() != null && wp.getProcessName() != null)
                .map(wp -> new MatchedProcess(
                        wp,
                        normalizedMessage.indexOf(normalize(wp.getProcessName()))))
                .filter(m -> m.startIndex() >= 0)
                .sorted(Comparator.comparingInt(MatchedProcess::startIndex))
                .toList();
    }

    /**
     * B-2 fix: segment is now bounded on the right by {@code nextBoundary} (start index of the
     * next matched process in the normalized message, or end-of-string for the last step).
     * This prevents an assignee mentioned after a later process from being erroneously
     * attributed to an earlier process (e.g. "修油，滚揉，焯水交给魏振江" — 修油 and 滚揉
     * get no assignee; only 焯水 gets 魏振江).
     *
     * @param normalizedMessage already-normalized message (to avoid re-normalizing in each call)
     * @param current           the process whose assignee we're looking for
     * @param operators         candidate users (already filtered to active)
     * @param nextBoundary      right bound in normalizedMessage (exclusive) for the search segment
     */
    private UserDTO matchAssignee(
            String normalizedMessage, MatchedProcess current,
            List<UserDTO> operators, int nextBoundary) {
        int segmentStart = current.startIndex();
        String currentName = normalize(current.workProcess().getProcessName());
        int currentNameEnd = segmentStart + currentName.length();

        // Clamp nextBoundary to valid range and ensure currentNameEnd <= nextBoundary
        int safeNextBoundary = Math.min(nextBoundary, normalizedMessage.length());
        int safeSegmentEnd = Math.max(currentNameEnd, safeNextBoundary);
        String segment = normalizedMessage.substring(currentNameEnd, safeSegmentEnd);
        UserDTO nearest = null;
        int nearestIndex = Integer.MAX_VALUE;
        for (UserDTO op : operators) {
            int idx = indexOfAnyName(segment, op);
            if (idx >= 0 && idx < nearestIndex) {
                nearest = op;
                nearestIndex = idx;
            }
        }
        return nearest;
    }

    private int indexOfAnyName(String segment, UserDTO op) {
        List<String> names = new ArrayList<>();
        if (op.getFullName() != null) {
            names.add(op.getFullName());
        }
        if (op.getUsername() != null) {
            names.add(op.getUsername());
        }
        for (String name : names) {
            String normalized = normalize(name);
            if (!normalized.isBlank()) {
                int idx = segment.indexOf(normalized);
                if (idx >= 0) {
                    return idx;
                }
            }
        }
        return -1;
    }

    private List<Map<String, Object>> detectMissingProcessNames(
            String message, List<MatchedProcess> matchedProcesses) {
        Set<String> matchedNames = matchedProcesses.stream()
                .map(m -> normalize(m.workProcess().getProcessName()))
                .collect(Collectors.toCollection(HashSet::new));

        List<Map<String, Object>> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawPart : SEPARATOR.split(message)) {
            String candidate = cleanupCandidate(rawPart);
            if (candidate.isBlank()) {
                continue;
            }
            String normalized = normalize(candidate);
            if (normalized.length() < 2 || matchedNames.contains(normalized)) {
                continue;
            }
            boolean alreadyCovered = matchedNames.stream().anyMatch(normalized::contains);
            if (alreadyCovered) {
                continue;
            }
            if (seen.add(normalized)) {
                missing.add(Map.of(
                        "name", candidate,
                        "reason", "本厂工序 catalog 中未找到，请先去工序管理新建"));
            }
        }
        return missing;
    }

    private String cleanupCandidate(String rawPart) {
        String value = STEP_PREFIX.matcher(rawPart.trim()).replaceFirst("");
        value = ASSIGNEE_SUFFIX.matcher(value).replaceFirst("");
        for (String filler : FILLER_WORDS) {
            value = value.replace(filler, "");
        }
        return value.trim();
    }

    private Map<String, Object> buildResult(ProductProcessPlan plan, boolean applied) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", applied ? "APPLIED" : "PREVIEW");
        result.put("applied", applied);
        result.put("productTypeId", plan.productTypeId());
        result.put("draft", plan.steps().stream().map(DraftStep::toMap).toList());
        result.put("missingProcesses", plan.missingProcesses());

        String message = (applied ? "已应用 " : "已生成 ")
                + plan.steps().size() + " 道工序草稿";
        if (!plan.missingProcesses().isEmpty()) {
            message += "；有 " + plan.missingProcesses().size()
                    + " 个工序未匹配，请先去工序管理新建";
        }
        result.put("message", message);
        return result;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\s\\p{Punct}，。；、：:（）()【】\\[\\]《》<>“”\"'·_-]+", "");
    }

    private String displayUserName(UserDTO user) {
        return Objects.requireNonNullElse(user.getFullName(), user.getUsername());
    }

    private record ProductProcessPlan(
            String productTypeId,
            List<DraftStep> steps,
            List<Map<String, Object>> missingProcesses) {
    }

    private record MatchedProcess(WorkProcessDTO workProcess, int startIndex) {
    }

    private record DraftStep(
            String operation,
            Long productWorkProcessId,
            String workProcessId,
            String processName,
            String processCategory,
            String unit,
            Integer processOrder,
            Long responsibleWorkerId,
            String responsibleWorkerName) {

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", operation);
            row.put("productWorkProcessId", productWorkProcessId);
            row.put("workProcessId", workProcessId);
            row.put("processName", processName);
            row.put("processCategory", processCategory);
            row.put("unit", unit);
            row.put("processOrder", processOrder);
            row.put("responsibleWorkerId", responsibleWorkerId);
            row.put("responsibleWorkerName", responsibleWorkerName);
            return row;
        }

        private ProductWorkProcessDTO toProductWorkProcessDTO(String productTypeId) {
            return ProductWorkProcessDTO.builder()
                    .productTypeId(productTypeId)
                    .workProcessId(workProcessId)
                    .processOrder(processOrder)
                    .responsibleWorkerId(responsibleWorkerId)
                    .build();
        }
    }
}
