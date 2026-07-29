package com.cretas.aims.ai.tool.impl.governance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.smartbi.SmartBiSkill;
import com.cretas.aims.service.governance.ToolAutoComposerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 组合创建工具
 *
 * 通过 AI 对话创建新的 Skill（将多个 Tool 组合为一个 Skill）。
 * 支持两种模式：
 * 1. 直接指定 tool 列表创建
 * 2. 根据自动推荐的模式创建
 *
 * 示例对话：
 * - "把 material_batch_query 和 quality_check_query 组合成skill"
 * - "创建一个skill叫库存质检联查，包含库存查询和质检查询"
 * - "创建推荐的 material-auto-combo skill"
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * factoryId 隔离豁免说明 (Whitelist — Audit 2026-04-07)
 * ───────────────────────────────────────────────────────────────────────────
 * 本 Tool 属于"治理类元工具"，操作 SmartBiSkill 实体（当前全局无 factoryId）。
 * 上游隔离手段:
 *   1. AIIntentConfig 通过 factoryId + intent_code 绑定到允许该工厂使用的意图
 *   2. permission 层拦截只允许 admin/super_admin 角色使用治理类 Tool
 *   3. SmartBiSkill factory 级隔离属 V3 P2 任务 (需 DB 迁移)
 * 一旦 SmartBiSkill 加 factory_id (V3 P2)，本 Tool 需同步加 factoryId 过滤。
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
public class SkillComposeTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private ToolAutoComposerService autoComposerService;

    @Autowired
    @Lazy
    private ToolRegistry toolRegistry;

    @Override
    public String getToolName() {
        return "governance_skill_compose";
    }

    @Override
    public ActionType getActionType() { return ActionType.WRITE; }

    @Override
    public String getDescription() {
        return "创建新的 Skill 组合，将多个 Tool 组合成一个 Skill。" +
                "适用场景：'创建skill'、'组合工具'、'把A和B组合成skill'、'新建技能'";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description",
                                "Skill 名称 (slug格式, 如 material-quality-combo)"),
                        "display_name", Map.of("type", "string", "description",
                                "显示名称 (如 '库存质检联查')"),
                        "tools", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "要组合的 tool 名称列表"),
                        "triggers", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "触发关键词列表"),
                        "description", Map.of("type", "string", "description", "Skill 描述"),
                        "category", Map.of("type", "string", "description",
                                "分类: analytics/alerting/reporting/operations/auto-composed")
                ),
                "required", List.of("name", "tools")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("name", "tools");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String name = getString(params, "name");
        List<String> tools = getList(params, "tools");

        Map<String, Object> preview = new HashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("name", name);
        preview.put("tools", tools);

        // 验证 tools 是否存在
        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String tool : tools) {
            if (toolRegistry.getExecutor(tool).isPresent()) {
                valid.add(tool);
            } else {
                invalid.add(tool);
            }
        }
        preview.put("valid_tools", valid);
        preview.put("invalid_tools", invalid);

        if (!invalid.isEmpty()) {
            preview.put("warning", "以下工具未注册: " + String.join(", ", invalid));
        }

        // 检查是否与现有 skill 重叠
        List<Map<String, Object>> existingSkills = autoComposerService.listAllSkills();
        List<String> overlapping = existingSkills.stream()
                .filter(s -> {
                    List<String> sTools = (List<String>) s.get("tools");
                    return sTools != null && !Collections.disjoint(sTools, tools);
                })
                .map(s -> (String) s.get("name"))
                .collect(Collectors.toList());

        if (!overlapping.isEmpty()) {
            preview.put("overlapping_skills", overlapping);
            preview.put("overlap_warning", "以下现有 Skill 包含部分相同工具: " + String.join(", ", overlapping));
        }

        preview.put("message", String.format("将创建 Skill '%s'，包含 %d 个工具 (%d 有效, %d 无效)",
                name, tools.size(), valid.size(), invalid.size()));

        return preview;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        // 权限检查：仅管理员可创建 Skill
        String role = context != null ? (String) context.get("role") : null;
        if (role == null || (!role.contains("admin") && !role.contains("ADMIN"))) {
            return buildSimpleResult("权限不足：仅管理员可创建 Skill", null);
        }

        String name = getString(params, "name");
        String displayName = getString(params, "display_name");
        List<String> tools = getList(params, "tools");
        List<String> triggers = getList(params, "triggers");
        String description = getString(params, "description");
        String category = getString(params, "category");

        if (tools == null || tools.isEmpty()) {
            return buildSimpleResult("请指定要组合的工具列表", null);
        }

        // 验证 tools 存在
        List<String> invalidTools = tools.stream()
                .filter(t -> !toolRegistry.getExecutor(t).isPresent())
                .collect(Collectors.toList());

        if (!invalidTools.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "FAILED");
            result.put("message", "以下工具未注册: " + String.join(", ", invalidTools));
            result.put("invalid_tools", invalidTools);

            // 提供注册的工具列表供参考
            List<String> availableTools = toolRegistry.getAllExecutors().stream()
                    .map(e -> e.getToolName())
                    .sorted()
                    .collect(Collectors.toList());
            result.put("hint", "可用的工具数量: " + availableTools.size());
            return result;
        }

        // 自动生成缺失字段
        if (displayName == null) {
            displayName = name.replace("-", " ").replace("_", " ");
            // 首字母大写，过滤空字符串防止 IndexOutOfBoundsException
            displayName = Arrays.stream(displayName.split(" "))
                    .filter(w -> !w.isEmpty())
                    .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1))
                    .collect(Collectors.joining(" "));
        }

        if (triggers == null || triggers.isEmpty()) {
            // 从 tool name 中提取关键词作为 triggers
            triggers = new ArrayList<>();
            for (String tool : tools) {
                String[] parts = tool.split("_");
                for (String part : parts) {
                    if (part.length() > 2 && !part.equals("query") && !part.equals("list")
                            && !part.equals("get") && !triggers.contains(part)) {
                        triggers.add(part);
                    }
                }
            }
            // 添加 displayName 作为触发词
            triggers.add(displayName);
        }

        try {
            SmartBiSkill skill = autoComposerService.composeSkill(
                    name, displayName, tools, triggers, description, category);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("message", String.format("✅ Skill '%s' 创建成功！\n\n" +
                            "- 名称: %s\n" +
                            "- 包含工具: %s\n" +
                            "- 触发词: %s\n" +
                            "- 已自动注册并生效\n\n" +
                            "现在可以通过触发词直接调用此 Skill。",
                    displayName, name, String.join(", ", tools), String.join(", ", triggers)));
            result.put("skill_id", skill.getId());
            result.put("skill_name", skill.getName());
            result.put("tools", tools);
            result.put("triggers", triggers);
            return result;
        } catch (IllegalArgumentException e) {
            return buildSimpleResult("创建失败: " + e.getMessage(), null);
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
