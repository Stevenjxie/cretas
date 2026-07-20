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

    // Separators: Chinese/ASCII punctuation + Chinese arrow (→) + ASCII arrow (->)
    // Spaces around arrows are consumed so "解冻 → 分切" splits cleanly.
    private static final Pattern SEPARATOR = Pattern.compile("\\s*[，,。；;、\\n→]+\\s*|\\s*->\\s*");
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
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return FactoryUserRole.factory_super_admin.name().equals(userRole)
                || FactoryUserRole.permission_admin.name().equals(userRole);
    }

    @Override
    public Set<String> getRequiredPermissions() {
        // CanvasAIController's existing authorization contract is role based. The gateway
        // rehydrates this role from DB truth and applies the exact descriptor role allowlist.
        return Set.of();
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("canvas", "production", "work-process", "product");
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
        // E2: skip "new" steps (workProcessId==null) — those need the process to be
        // created in the catalog first before they can be linked to a product.
        List<ProductWorkProcessDTO> applied = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (DraftStep step : plan.steps()) {
            if (step.workProcessId() == null) {
                warnings.add("工序「" + step.processName() + "」尚未在工序管理中创建，无法写入，请先新建该工序");
                continue;
            }
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

        // E1: Deduplicated catalog — when multiple entries have the same normalized name
        // (e.g. "焯水" appears 3 times across legacy catalogs). Catalog order is already
        // deterministic; global sort is not a WorkProcess master-data concept.
        List<WorkProcessDTO> deduplicatedCatalog = deduplicateCatalogByName(catalog);

        // E2: Segment-driven matching: split user input into ordered segments first,
        // then find the best catalog match for each segment.  This guarantees exactly
        // N input segments → N draft steps, preventing the old catalog-driven loop that
        // produced duplicate/collapsed steps when multiple catalog entries matched the
        // same position in the message (e.g. "焯水" ×3 from three catalog rows all
        // matching the single "焯水" in the input).
        List<String> inputSegments = splitInputSegments(message);
        List<SegmentMatch> segmentMatches = matchSegmentsToCatalog(inputSegments, deduplicatedCatalog);

        Map<String, ProductWorkProcessDTO> existingByProcessId = existing.stream()
                .collect(Collectors.toMap(
                        ProductWorkProcessDTO::getWorkProcessId,
                        item -> item,
                        (left, right) -> left));

        List<DraftStep> steps = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        Set<String> seenMissing = new HashSet<>();

        for (int i = 0; i < segmentMatches.size(); i++) {
            SegmentMatch sm = segmentMatches.get(i);
            // B-2 preserved: assignee search bounded by the raw segment text only
            UserDTO assignee = matchAssigneeInSegment(sm.rawSegment(), operators);

            if (sm.catalogEntry() == null) {
                // Not found in catalog — surface as missing/new process
                String candidate = sm.rawSegment().trim();
                String norm = normalize(candidate);
                if (!norm.isBlank() && norm.length() >= 2 && seenMissing.add(norm)) {
                    missing.add(Map.of(
                            "name", candidate,
                            "reason", "本厂工序 catalog 中未找到，请先去工序管理新建"));
                }
                // Still include a placeholder step so the UI knows this input was parsed
                steps.add(new DraftStep(
                        "new",
                        null,
                        null,
                        candidate,
                        null,
                        i + 1,
                        assignee != null ? assignee.getId() : null,
                        assignee != null ? displayUserName(assignee) : null));
            } else {
                ProductWorkProcessDTO existingRow = existingByProcessId.get(sm.catalogEntry().getId());
                steps.add(new DraftStep(
                        existingRow != null ? "update" : "create",
                        existingRow != null ? existingRow.getId() : null,
                        sm.catalogEntry().getId(),
                        sm.catalogEntry().getProcessName(),
                        sm.catalogEntry().getProcessCategory(),
                        i + 1,
                        assignee != null ? assignee.getId() : null,
                        assignee != null ? displayUserName(assignee) : null));
            }
        }

        // E3: Post-processing duplicate detection — if the draft still contains duplicate
        // processName values (which can only happen when the user genuinely typed the same
        // step twice), surface a warning rather than silently delivering a confusing draft.
        List<String> duplicateWarnings = detectDuplicateStepNames(steps);

        return new ProductProcessPlan(productTypeId, steps, missing, duplicateWarnings);
    }

    /**
     * E1: Deduplicate catalog entries by normalized process name.
     * When the factory has the same process name in multiple product-specific catalogs
     * (e.g. "焯水" for 猪舌, 牛腱 and 掌中宝 are three separate rows), keep only the
     * first future-selectable catalog entry so downstream logic sees each name exactly once.
     */
    private List<WorkProcessDTO> deduplicateCatalogByName(List<WorkProcessDTO> catalog) {
        Map<String, WorkProcessDTO> byNormalizedName = new LinkedHashMap<>();
        for (WorkProcessDTO wp : catalog) {
            if (wp.getId() == null || wp.getProcessName() == null) continue;
            String key = normalize(wp.getProcessName());
            if (key.isBlank()) continue;
            byNormalizedName.putIfAbsent(key, wp);
        }
        return new ArrayList<>(byNormalizedName.values());
    }

    /**
     * E2 helper: split the user's natural-language message into ordered segments.
     * Handles arrow separators (→, ->) as well as the original Chinese/ASCII punctuation.
     */
    private List<String> splitInputSegments(String message) {
        List<String> segments = new ArrayList<>();
        for (String raw : SEPARATOR.split(message)) {
            String cleaned = cleanupCandidate(raw);
            if (!cleaned.isBlank()) {
                segments.add(raw.trim()); // keep raw for assignee search; cleaned is for matching
            }
        }
        return segments;
    }

    /**
     * E2: For each user-input segment, find the best catalog match.
     * Matching priority:
     *   1. Exact normalized equality (e.g. "焯水" == "焯水")
     *   2. Catalog name is fully contained in the segment (e.g. "滚揉(保水)" ⊆ "二次滚揉保水")
     *      — only if the catalog entry's normalized name appears as a COMPLETE token in the
     *      segment, not as an arbitrary substring (prevents "焯水" matching in "保水").
     *   3. Segment is fully contained in the catalog name (e.g. "气调" ⊆ "气调(分切装盒)")
     * If no match, catalogEntry is null → surfaces as missing/new.
     */
    private List<SegmentMatch> matchSegmentsToCatalog(
            List<String> inputSegments, List<WorkProcessDTO> catalog) {
        List<SegmentMatch> results = new ArrayList<>();
        for (String rawSegment : inputSegments) {
            String cleaned = cleanupCandidate(rawSegment);
            String normSegment = normalize(cleaned.isBlank() ? rawSegment : cleaned);

            WorkProcessDTO best = null;
            int bestPriority = Integer.MAX_VALUE;
            int bestNameLen = 0; // among equal priority, prefer longer (more specific) name

            for (WorkProcessDTO wp : catalog) {
                String normName = normalize(wp.getProcessName());
                if (normName.isBlank()) continue;

                int priority;
                if (normSegment.equals(normName)) {
                    // Priority 1: exact match
                    priority = 1;
                } else if (normSegment.contains(normName) && normName.length() >= 2) {
                    // Priority 2: catalog name found inside segment — but only accept if
                    // it is not a pure suffix/prefix substring of a longer word.
                    // Guard: the catalog name must cover at least half the segment's length
                    // to prevent "焯水" (2 chars) from matching "二次滚揉保水" (6 chars)
                    // where the name only contributes 2/6 = 33% — below the 50% threshold.
                    if ((double) normName.length() / normSegment.length() >= 0.5) {
                        priority = 2;
                    } else {
                        continue; // too short to be a reliable match
                    }
                } else if (normName.contains(normSegment) && normSegment.length() >= 2) {
                    // Priority 3: segment is a prefix/abbreviation of the catalog name
                    // e.g. user typed "气调" and catalog has "气调(分切装盒)".
                    // Guard: segment must cover ≥ 50% of the catalog name length to be a
                    // genuine abbreviation.  This prevents "分切" (2 chars) from matching
                    // "气调(分切装盒)" normalized "气调分切装盒" (6 chars) where 分切 is
                    // only 2/6 = 33% — it's a coincidental substring, not an abbreviation.
                    if ((double) normSegment.length() / normName.length() >= 0.5) {
                        priority = 3;
                    } else {
                        continue; // incidental substring, not an abbreviation
                    }
                } else {
                    continue;
                }

                if (priority < bestPriority
                        || (priority == bestPriority && normName.length() > bestNameLen)) {
                    best = wp;
                    bestPriority = priority;
                    bestNameLen = normName.length();
                }
            }

            results.add(new SegmentMatch(rawSegment, best));
        }
        return results;
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

    /**
     * E2 assignee matching: search for an operator name within the raw segment text only.
     * Because each segment is already isolated (one step's text), the old B-2 right-boundary
     * logic is no longer needed — the segment IS the boundary.
     */
    private UserDTO matchAssigneeInSegment(String rawSegment, List<UserDTO> operators) {
        String normalizedSegment = normalize(rawSegment);
        UserDTO nearest = null;
        int nearestIndex = Integer.MAX_VALUE;
        for (UserDTO op : operators) {
            int idx = indexOfAnyName(normalizedSegment, op);
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

    /**
     * E3: Detect duplicate processName values in the draft (after catalog matching).
     * Duplicates are only expected if the user genuinely typed the same process twice.
     * Returns human-readable warning messages for surface in the AI reply.
     */
    private List<String> detectDuplicateStepNames(List<DraftStep> steps) {
        Map<String, Long> nameCounts = steps.stream()
                .filter(s -> s.processName() != null && s.workProcessId() != null)
                .collect(Collectors.groupingBy(s -> normalize(s.processName()), Collectors.counting()));
        List<String> warnings = new ArrayList<>();
        nameCounts.forEach((normName, count) -> {
            if (count > 1) {
                // Find original display name from first occurrence
                String displayName = steps.stream()
                        .filter(s -> s.processName() != null && normalize(s.processName()).equals(normName))
                        .map(DraftStep::processName)
                        .findFirst()
                        .orElse(normName);
                warnings.add("「" + displayName + "」出现 " + count + " 次，请确认是否为重复工序");
            }
        });
        return warnings;
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

        long newSteps = plan.steps().stream().filter(s -> s.workProcessId() == null).count();

        String message = (applied ? "已应用 " : "已生成 ")
                + plan.steps().size() + " 道工序草稿";
        if (newSteps > 0) {
            message += "（含 " + newSteps + " 道未找到匹配工序，请先去工序管理新建）";
        } else if (!plan.missingProcesses().isEmpty()) {
            message += "；有 " + plan.missingProcesses().size()
                    + " 个工序未匹配，请先去工序管理新建";
        }
        if (!plan.duplicateWarnings().isEmpty()) {
            message += "；注意：" + String.join("、", plan.duplicateWarnings());
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
            List<Map<String, Object>> missingProcesses,
            List<String> duplicateWarnings) {
    }

    /** E2: Pairs a raw user-input segment with the best-matched catalog entry (null = not found). */
    private record SegmentMatch(String rawSegment, WorkProcessDTO catalogEntry) {
    }

    private record DraftStep(
            String operation,
            Long productWorkProcessId,
            String workProcessId,
            String processName,
            String processCategory,
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
