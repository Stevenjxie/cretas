package com.cretas.aims.ai.tool.impl.governance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.smartbi.SmartBiSkill;
import com.cretas.aims.entity.tool.ToolMetadata;
import com.cretas.aims.repository.ToolMetadataRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.governance.ToolAutoComposerService;
import com.cretas.aims.service.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 管理工具
 *
 * 通过 AI 对话查看、搜索、启用/禁用 Skill。
 *
 * 示例对话：
 * - "列出所有skill"
 * - "查看已有的技能"
 * - "禁用 xxx skill"
 * - "搜索关于库存的skill"
 * - "有多少个工具注册了"
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * factoryId 隔离豁免说明 (Whitelist — Audit 2026-04-07)
 * ───────────────────────────────────────────────────────────────────────────
 * 同 SkillComposeTool 说明：治理类元工具，操作全局 SmartBiSkill 实体。
 * 依赖上游 AIIntentConfig + 角色权限做隔离。SmartBiSkill factory 级隔离属
 * V3 P2 任务，届时本 Tool 需同步加 factoryId 过滤。
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
public class SkillManageTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private ToolAutoComposerService autoComposerService;

    @Autowired
    @Lazy
    private ToolRegistry toolRegistry;

    @Autowired
    @Lazy
    private SmartBiSkillRepository skillRepository;

    @Autowired
    @Lazy
    private SkillRegistry skillRegistry;

    @Autowired
    @Lazy
    private ToolMetadataRepository toolMetadataRepository;

    @Override
    public String getToolName() {
        return "governance_skill_manage";
    }

    @Override
    public ActionType getActionType() { return ActionType.ANALYZE; }

    @Override
    public String getDescription() {
        return "查看和管理已注册的 Skill 和 Tool。" +
                "适用场景：'列出skill'、'查看技能'、'有多少工具'、'搜索skill'、'禁用skill'、" +
                "'重新激活工具'、'废弃工具'、'工具列表'";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description",
                                "操作: list_skills / list_tools / search / enable / disable / detail / " +
                                "reactivate_tool / deprecate_tool",
                                "enum", List.of("list_skills", "list_tools", "search", "enable", "disable",
                                        "detail", "reactivate_tool", "deprecate_tool")),
                        "query", Map.of("type", "string", "description",
                                "搜索关键词 (search) 或 skill/tool 名称 (enable/disable/detail/reactivate_tool/deprecate_tool)"),
                        "reason", Map.of("type", "string", "description",
                                "废弃原因或迁移说明（仅 deprecate_tool 需要）")
                ),
                "required", Collections.emptyList()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String action = getString(params, "action");
        String query = getString(params, "query");
        if (action == null) action = "list_skills";

        Map<String, Object> result = new HashMap<>();

        switch (action) {
            case "list_skills": {
                List<Map<String, Object>> skills = autoComposerService.listAllSkills();
                result.put("total_skills", skills.size());
                result.put("skills", skills);
                result.put("message", formatSkillListMessage(skills));
                break;
            }

            case "list_tools": {
                var executors = toolRegistry.getAllExecutors();
                // 按 domain 分组
                Map<String, List<String>> byDomain = executors.stream()
                        .collect(Collectors.groupingBy(
                                e -> {
                                    String name = e.getToolName();
                                    return name.contains("_") ? name.substring(0, name.indexOf("_")) : "other";
                                },
                                Collectors.mapping(e -> e.getToolName(), Collectors.toList())
                        ));

                result.put("total_tools", executors.size());
                result.put("domains", byDomain.keySet().stream().sorted().collect(Collectors.toList()));
                result.put("tools_by_domain", byDomain.entrySet().stream()
                        .sorted(Comparator.comparing(e -> -e.getValue().size()))
                        .limit(15)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> Map.of("count", e.getValue().size(), "tools", e.getValue()),
                                (a, b) -> a,
                                LinkedHashMap::new
                        )));
                result.put("message", formatToolListMessage(executors.size(), byDomain));
                break;
            }

            case "search": {
                if (query == null || query.isEmpty()) {
                    result.put("message", "请提供搜索关键词");
                    break;
                }

                // 搜索 skills
                List<SmartBiSkill> matchedSkills = skillRepository
                        .searchByDisplayNameOrDescription(query);

                // 搜索 tools
                String queryLower = query.toLowerCase();
                List<String> matchedTools = toolRegistry.getAllExecutors().stream()
                        .filter(e -> e.getToolName().toLowerCase().contains(queryLower)
                                || e.getDescription().toLowerCase().contains(queryLower))
                        .map(e -> e.getToolName())
                        .collect(Collectors.toList());

                result.put("matched_skills", matchedSkills.stream()
                        .map(s -> Map.of("name", s.getName(), "displayName", s.getDisplayName()))
                        .collect(Collectors.toList()));
                result.put("matched_tools", matchedTools);
                result.put("message", String.format("搜索 '%s' 结果：\n- %d 个 Skill 匹配\n- %d 个 Tool 匹配\n\n%s%s",
                        query,
                        matchedSkills.size(), matchedTools.size(),
                        matchedSkills.isEmpty() ? "" : "Skills: " + matchedSkills.stream()
                                .map(s -> s.getName() + " (" + s.getDisplayName() + ")")
                                .collect(Collectors.joining(", ")) + "\n",
                        matchedTools.isEmpty() ? "" : "Tools: " + String.join(", ", matchedTools.subList(0, Math.min(10, matchedTools.size())))
                ));
                break;
            }

            case "enable":
            case "disable": {
                // 权限检查：仅管理员可启用/禁用 Skill
                String role = context != null ? (String) context.get("role") : null;
                if (role == null || (!role.contains("admin") && !role.contains("ADMIN"))) {
                    result.put("message", "权限不足：仅管理员可" + (action.equals("enable") ? "启用" : "禁用") + " Skill");
                    break;
                }
                if (query == null || query.isEmpty()) {
                    result.put("message", "请提供要" + (action.equals("enable") ? "启用" : "禁用") + "的 Skill 名称");
                    break;
                }
                Optional<SmartBiSkill> skillOpt = skillRepository.findByName(query);
                if (skillOpt.isEmpty()) {
                    result.put("message", "Skill '" + query + "' 不存在");
                    break;
                }
                SmartBiSkill skill = skillOpt.get();
                skill.setEnabled(action.equals("enable"));
                skillRepository.save(skill);
                skillRegistry.refresh();
                result.put("message", String.format("✅ Skill '%s' 已%s",
                        skill.getDisplayName(), action.equals("enable") ? "启用" : "禁用"));
                break;
            }

            case "reactivate_tool": {
                String role = context != null ? (String) context.get("role") : null;
                if (role == null || (!role.contains("admin") && !role.contains("ADMIN"))) {
                    result.put("message", "权限不足：仅管理员可重新激活 Tool");
                    break;
                }
                if (query == null || query.isEmpty()) {
                    result.put("message", "请提供要重新激活的 Tool 名称");
                    break;
                }
                // Verify the tool exists in registry
                if (!toolRegistry.hasExecutor(query)) {
                    result.put("message", "Tool '" + query + "' 不存在于注册表中");
                    break;
                }
                // Clear deprecation_notice in tool_metadata
                Optional<ToolMetadata> metaOpt = toolMetadataRepository.findByToolName(query);
                if (metaOpt.isPresent()) {
                    ToolMetadata meta = metaOpt.get();
                    String oldNotice = meta.getDeprecationNotice();
                    meta.setDeprecationNotice(null);
                    toolMetadataRepository.save(meta);
                    result.put("message", String.format("✅ Tool '%s' 已重新激活（清除了废弃标记: %s）", query,
                            oldNotice != null ? oldNotice : "无"));
                } else {
                    // Tool exists in code but no metadata row yet — it's already active
                    result.put("message", String.format("Tool '%s' 当前没有废弃标记，无需重新激活", query));
                }
                break;
            }

            case "deprecate_tool": {
                String role = context != null ? (String) context.get("role") : null;
                if (role == null || (!role.contains("admin") && !role.contains("ADMIN"))) {
                    result.put("message", "权限不足：仅管理员可废弃 Tool");
                    break;
                }
                if (query == null || query.isEmpty()) {
                    result.put("message", "请提供要废弃的 Tool 名称");
                    break;
                }
                String reason = getString(params, "reason");
                if (reason == null || reason.isEmpty()) {
                    reason = "管理员手动废弃";
                }
                if (!toolRegistry.hasExecutor(query)) {
                    result.put("message", "Tool '" + query + "' 不存在于注册表中");
                    break;
                }
                // Set or create tool_metadata with deprecation_notice
                ToolMetadata meta = toolMetadataRepository.findByToolName(query)
                        .orElse(ToolMetadata.builder().toolName(query).build());
                meta.setDeprecationNotice(reason);
                toolMetadataRepository.save(meta);
                result.put("message", String.format("⚠️ Tool '%s' 已标记为废弃（原因: %s）。" +
                        "该 Tool 仍可调用，但会在治理报告中显示为已废弃。", query, reason));
                break;
            }

            case "detail": {
                if (query == null || query.isEmpty()) {
                    result.put("message", "请提供要查看的 Skill 或 Tool 名称");
                    break;
                }

                // 先查 skill
                Optional<com.cretas.aims.dto.skill.SkillDefinition> skillDefOpt = skillRegistry.getSkill(query);
                if (skillDefOpt.isPresent()) {
                    var skillDef = skillDefOpt.get();
                    result.put("type", "skill");
                    result.put("name", skillDef.getName());
                    result.put("displayName", skillDef.getDisplayName());
                    result.put("description", skillDef.getDescription());
                    result.put("tools", skillDef.getTools());
                    result.put("triggers", skillDef.getTriggers());
                    result.put("source", skillDef.getSource());
                    result.put("message", String.format("Skill: %s\n描述: %s\n工具: %s\n触发词: %s\n来源: %s",
                            skillDef.getDisplayName(), skillDef.getDescription(),
                            skillDef.getTools() != null ? String.join(", ", skillDef.getTools()) : "无",
                            skillDef.getTriggers() != null ? String.join(", ", skillDef.getTriggers()) : "无",
                            skillDef.getSource()));
                    break;
                }

                // 再查 tool
                Optional<com.cretas.aims.ai.tool.ToolExecutor> executorOpt = toolRegistry.getExecutor(query);
                if (executorOpt.isPresent()) {
                    var executor = executorOpt.get();
                    result.put("type", "tool");
                    result.put("name", executor.getToolName());
                    result.put("description", executor.getDescription());
                    result.put("parameters", executor.getParametersSchema());
                    result.put("message", String.format("Tool: %s\n描述: %s\n参数: %s",
                            executor.getToolName(), executor.getDescription(),
                            executor.getParametersSchema().toString()));
                    break;
                }

                result.put("message", "'" + query + "' 不是已注册的 Skill 或 Tool");
                break;
            }
        }

        return result;
    }

    private String formatSkillListMessage(List<Map<String, Object>> skills) {
        if (skills.isEmpty()) {
            return "当前没有已注册的 Skill。";
        }

        // 按 source 分组
        Map<String, List<Map<String, Object>>> bySource = skills.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getOrDefault("source", "unknown").toString()
                ));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共 %d 个已注册的 Skill：\n\n", skills.size()));

        for (Map.Entry<String, List<Map<String, Object>>> entry : bySource.entrySet()) {
            String source = entry.getKey();
            String sourceLabel = source.startsWith("database") ? "📦 数据库" :
                    source.startsWith("file") ? "📄 文件" : "⚙️ 内置";
            sb.append(String.format("**%s** (%d个):\n", sourceLabel, entry.getValue().size()));
            for (Map<String, Object> s : entry.getValue()) {
                sb.append(String.format("  • %s — %s\n", s.get("name"),
                        s.getOrDefault("displayName", "")));
            }
            sb.append("\n");
        }

        sb.append("💡 说「查看 skill名称 详情」了解更多，或「创建skill」来创建新组合");
        return sb.toString();
    }

    private String formatToolListMessage(int total, Map<String, List<String>> byDomain) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共 %d 个已注册的 Tool，分布在 %d 个域：\n\n", total, byDomain.size()));

        byDomain.entrySet().stream()
                .sorted(Comparator.comparing(e -> -e.getValue().size()))
                .limit(15)
                .forEach(e -> sb.append(String.format("  • **%s** — %d 个工具\n",
                        e.getKey(), e.getValue().size())));

        if (byDomain.size() > 15) {
            sb.append(String.format("  • ...及其他 %d 个域\n", byDomain.size() - 15));
        }

        sb.append("\n💡 说「搜索 关键词」查找特定工具，或「查看工具使用模式」发现可组合的模式");
        return sb.toString();
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
