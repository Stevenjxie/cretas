package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.WorkProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工序主数据 Canvas AI Tool.
 *
 * <p>支持工序管理页自然语言新增/修改工序，执行前对 create 做
 * 名称+类别重复检查，避免 AI 生成重复工序。工序投入/产出单位由 Workflow/SKU 端口决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessCatalogTool extends AbstractBusinessTool {

    private static final String ACTION_CREATE = "create";
    private static final String ACTION_UPDATE = "update";

    private final WorkProcessService workProcessService;

    @Override
    public String getToolName() {
        return "canvas_work_process_catalog";
    }

    @Override
    public String getDescription() {
        return "工序管理页用于新增或修改工序主数据。支持字段: 工序名称、类别、标准工时、"
                + "出成率上下限、需录投入量、标准时薪。"
                + "投入/产出单位由 Workflow/SKU 端口决定，不在工序主数据重复维护。"
                + "新增前会按名称+类别查重，避免重复创建。"
                + "出成率请传小数，例如 30% 传 0.30。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of(ACTION_CREATE, ACTION_UPDATE),
                "description", "操作类型: create 新建工序, update 修改工序"));
        properties.put("id", Map.of(
                "type", "string",
                "description", "更新时必填的工序ID"));
        properties.put("processName", Map.of(
                "type", "string",
                "description", "工序名称，如 焯水、滚揉、装盒"));
        properties.put("processCategory", Map.of(
                "type", "string",
                "description", "工序类别，如 前处理、加工、包装、质检"));
        properties.put("estimatedMinutes", Map.of(
                "type", "integer",
                "description", "标准工时/预估工时，单位分钟"));
        properties.put("standardYieldMin", Map.of(
                "type", "number",
                "description", "标准出成率下限，小数表示，如 30% 传 0.30"));
        properties.put("standardYieldMax", Map.of(
                "type", "number",
                "description", "标准出成率上限，小数表示，如 60% 传 0.60"));
        properties.put("needsInput", Map.of(
                "type", "boolean",
                "description", "是否需要录入投入量"));
        properties.put("standardHourlyRate", Map.of(
                "type", "number",
                "description", "标准时薪，单位元/小时"));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("action");
    }

    @Override
    public ActionType getActionType() {
        return ToolExecutor.ActionType.UPDATE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    public boolean supportsPreview() {
        return true;
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
        return Set.of();
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("canvas", "production", "work-process", "master-data");
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String action = getString(params, "action", "").trim().toLowerCase();
        WorkProcessDTO draft = buildDto(params);
        if (ACTION_CREATE.equals(action)) {
            draft.setProcessName(getRequiredText(params, "processName", "工序名称不能为空"));
            WorkProcessDTO existing = findExisting(factoryId, draft);
            Map<String, Object> preview = new HashMap<>();
            preview.put("status", existing == null ? "PREVIEW" : "DUPLICATE");
            preview.put("action", ACTION_CREATE);
            preview.put("processName", draft.getProcessName());
            preview.put("processCategory", draft.getProcessCategory());
            preview.put("existingId", existing != null ? existing.getId() : "");
            preview.put("message", existing == null ? "将创建工序: " + draft.getProcessName()
                    : "已存在同名同类别工序，建议直接复用: " + existing.getProcessName());
            return preview;
        }
        if (ACTION_UPDATE.equals(action)) {
            String id = getRequiredText(params, "id", "更新工序时必须提供 id");
            return Map.of(
                    "status", "PREVIEW",
                    "action", ACTION_UPDATE,
                    "id", id,
                    "processName", Objects.toString(draft.getProcessName(), ""),
                    "processCategory", Objects.toString(draft.getProcessCategory(), ""),
                    "message", "将更新工序 " + id);
        }
        throw new BusinessException(400, "不支持的工序操作: " + action);
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String action = getString(params, "action", "").trim().toLowerCase();
        return switch (action) {
            case ACTION_CREATE -> create(factoryId, params);
            case ACTION_UPDATE -> update(factoryId, params);
            default -> throw new BusinessException(400, "不支持的工序操作: " + action)
                    .withHint("请使用 create 或 update")
                    .withHintTarget("action");
        };
    }

    private Map<String, Object> create(String factoryId, Map<String, Object> params) {
        String processName = getRequiredText(params, "processName", "工序名称不能为空");
        WorkProcessDTO dto = buildDto(params);
        dto.setProcessName(processName);
        WorkProcessDTO existing = findExisting(factoryId, dto);
        if (existing != null) {
            log.info("AI 工序新增命中重复: factory={}, processName={}, category={}",
                    factoryId, dto.getProcessName(), dto.getProcessCategory());
            Map<String, Object> result = buildSimpleResult(
                    "已存在相同名称+类别的工序，未重复创建: " + dto.getProcessName(),
                    existing);
            result.put("status", "DUPLICATE");
            result.put("existingId", existing.getId());
            result.put("actionHint", "请直接复用已有工序，或修改名称/类别后再创建");
            return result;
        }

        WorkProcessDTO created = workProcessService.create(factoryId, dto);
        return buildSimpleResult("已创建工序: " + created.getProcessName(), created);
    }

    private Map<String, Object> update(String factoryId, Map<String, Object> params) {
        String id = getRequiredText(params, "id", "更新工序时必须提供 id");
        WorkProcessDTO dto = buildDto(params);
        WorkProcessDTO updated = workProcessService.update(factoryId, id, dto);
        return buildSimpleResult("已更新工序: " + updated.getProcessName(), updated);
    }

    /**
     * B-2 fix: query ALL work-processes (including disabled) to detect duplicates.
     * Previously used listActive() which missed disabled entries — a disabled work-process
     * with the same name/category would slip through, causing create() to throw 409
     * which surfaced to the user as a generic "操作失败" error.
     * Using list(..., Pageable.unpaged()) returns all records regardless of active status,
     * consistent with name/category identity semantics.
     */
    private WorkProcessDTO findExisting(String factoryId, WorkProcessDTO incoming) {
        String processName = normalize(incoming.getProcessName());
        String category = normalize(incoming.getProcessCategory());

        return workProcessService.list(factoryId, Pageable.unpaged()).getContent().stream()
                .filter(wp -> Objects.equals(normalize(wp.getProcessName()), processName)
                        && Objects.equals(normalize(wp.getProcessCategory()), category))
                .findFirst()
                .orElse(null);
    }

    private WorkProcessDTO buildDto(Map<String, Object> params) {
        return WorkProcessDTO.builder()
                .processName(getString(params, "processName"))
                .processCategory(getString(params, "processCategory"))
                .estimatedMinutes(getInteger(params, "estimatedMinutes"))
                .standardYieldMin(getBigDecimal(params, "standardYieldMin"))
                .standardYieldMax(getBigDecimal(params, "standardYieldMax"))
                .needsInput(getBoolean(params, "needsInput"))
                .standardHourlyRate(toScale2(getBigDecimal(params, "standardHourlyRate")))
                .build();
    }

    private String getRequiredText(Map<String, Object> params, String key, String message) {
        String value = getString(params, key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, message)
                    .withHintTarget(key);
        }
        return value.trim();
    }

    private BigDecimal toScale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
