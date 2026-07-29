package com.cretas.aims.ai.tool.impl.sop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.smartbi.SkuComplexity;
import com.cretas.aims.event.SkuComplexityChangedEvent;
import com.cretas.aims.repository.smartbi.SkuComplexityRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * SKU 复杂度更新工具
 *
 * <p>更新或创建 SKU 的复杂度记录，并发布事件触发排产特征重算。
 *
 * <p>功能:
 * <ul>
 *   <li>创建或更新 SKU 复杂度记录</li>
 *   <li>发布复杂度变更事件</li>
 *   <li>触发排产特征权重重算</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
@Slf4j
@Component
public class SkuUpdateComplexityTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private SkuComplexityRepository skuComplexityRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public String getToolName() {
        return "sku_update_complexity";
    }

    @Override
    public String getDescription() {
        return "更新或创建SKU的复杂度记录。" +
               "支持记录复杂度等级、分析原因、步骤数等详细信息，并自动触发排产特征重算。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // skuCode: SKU编码（必需）
        Map<String, Object> skuCode = new HashMap<>();
        skuCode.put("type", "string");
        skuCode.put("description", "SKU的唯一编码");
        properties.put("skuCode", skuCode);

        // complexity: 复杂度等级（必需）
        Map<String, Object> complexity = new HashMap<>();
        complexity.put("type", "integer");
        complexity.put("description", "复杂度等级，1-5，1为最简单，5为最复杂");
        complexity.put("minimum", 1);
        complexity.put("maximum", 5);
        properties.put("complexity", complexity);

        // reason: 分析原因（可选）
        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "复杂度分析的原因说明");
        properties.put("reason", reason);

        // skuName: SKU名称（可选）
        Map<String, Object> skuName = new HashMap<>();
        skuName.put("type", "string");
        skuName.put("description", "SKU的名称");
        properties.put("skuName", skuName);

        // sopConfigId: 关联的SOP配置ID（可选）
        Map<String, Object> sopConfigId = new HashMap<>();
        sopConfigId.put("type", "string");
        sopConfigId.put("description", "关联的SOP配置ID");
        properties.put("sopConfigId", sopConfigId);

        // stepCount: 步骤数（可选）
        Map<String, Object> stepCount = new HashMap<>();
        stepCount.put("type", "integer");
        stepCount.put("description", "工序步骤数量");
        properties.put("stepCount", stepCount);

        // skillRequired: 技能要求（可选）
        Map<String, Object> skillRequired = new HashMap<>();
        skillRequired.put("type", "integer");
        skillRequired.put("description", "技能要求等级，1-5");
        properties.put("skillRequired", skillRequired);

        // qualityCheckCount: 质检点数（可选）
        Map<String, Object> qualityCheckCount = new HashMap<>();
        qualityCheckCount.put("type", "integer");
        qualityCheckCount.put("description", "质检点数量");
        properties.put("qualityCheckCount", qualityCheckCount);

        // specialEquipment: 是否需要特殊设备（可选）
        Map<String, Object> specialEquipment = new HashMap<>();
        specialEquipment.put("type", "boolean");
        specialEquipment.put("description", "是否需要特殊设备");
        properties.put("specialEquipment", specialEquipment);

        // estimatedMinutes: 预估工时（可选）
        Map<String, Object> estimatedMinutes = new HashMap<>();
        estimatedMinutes.put("type", "integer");
        estimatedMinutes.put("description", "预估工时（分钟）");
        properties.put("estimatedMinutes", estimatedMinutes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("skuCode", "complexity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("skuCode", "complexity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 获取必需参数
        String skuCode = getString(params, "skuCode");
        Integer complexity = getInteger(params, "complexity");

        // 获取可选参数
        String reason = getString(params, "reason", "AI分析");
        String skuName = getString(params, "skuName");
        String sopConfigId = getString(params, "sopConfigId");
        Integer stepCount = getInteger(params, "stepCount");
        Integer skillRequired = getInteger(params, "skillRequired");
        Integer qualityCheckCount = getInteger(params, "qualityCheckCount");
        Boolean specialEquipment = getBoolean(params, "specialEquipment", false);
        Integer estimatedMinutes = getInteger(params, "estimatedMinutes");

        // 验证复杂度范围
        if (complexity < 1 || complexity > 5) {
            throw new IllegalArgumentException("复杂度等级必须在1-5之间");
        }

        log.info("更新SKU复杂度: factoryId={}, skuCode={}, complexity={}",
                factoryId, skuCode, complexity);

        // 查找现有记录
        Optional<SkuComplexity> existingOpt = skuComplexityRepository.findByFactoryIdAndSkuCode(factoryId, skuCode);
        Integer oldComplexity = existingOpt.map(SkuComplexity::getComplexityLevel).orElse(null);

        // 创建或更新记录
        SkuComplexity entity = existingOpt.orElse(new SkuComplexity());

        entity.setFactoryId(factoryId);
        entity.setSkuCode(skuCode);
        entity.setSkuName(skuName);
        entity.setComplexityLevel(complexity);
        entity.setSourceType(SkuComplexity.SOURCE_AI_SOP);
        entity.setAnalysisReason(reason);
        entity.setSopConfigId(sopConfigId);
        entity.setStepCount(stepCount);
        entity.setSkillRequired(skillRequired);
        entity.setQualityCheckCount(qualityCheckCount);
        entity.setSpecialEquipment(specialEquipment);
        entity.setEstimatedMinutes(estimatedMinutes);
        entity.setAnalyzedAt(LocalDateTime.now());
        entity.setAnalyzedBy("AI");

        // 构建分析详情JSON
        Map<String, Object> analysisDetail = new LinkedHashMap<>();
        if (stepCount != null) analysisDetail.put("stepCount", stepCount);
        if (skillRequired != null) analysisDetail.put("skillRequired", skillRequired);
        if (qualityCheckCount != null) analysisDetail.put("qualityCheckCount", qualityCheckCount);
        analysisDetail.put("specialEquipment", specialEquipment);
        if (estimatedMinutes != null) analysisDetail.put("estimatedMinutes", estimatedMinutes);
        analysisDetail.put("analysisMethod", "AI_SOP");
        analysisDetail.put("analyzedAt", LocalDateTime.now().toString());

        try {
            entity.setAnalysisDetailJson(objectMapper.writeValueAsString(analysisDetail));
        } catch (JsonProcessingException e) {
            log.warn("分析详情JSON序列化失败: {}", e.getMessage());
        }

        // 保存记录
        SkuComplexity saved = skuComplexityRepository.save(entity);

        // 发布复杂度变更事件
        SkuComplexityChangedEvent event = new SkuComplexityChangedEvent(
                this,
                factoryId,
                skuCode,
                skuName,
                complexity,
                oldComplexity,
                SkuComplexity.SOURCE_AI_SOP,
                reason,
                "AI"
        );
        eventPublisher.publishEvent(event);

        log.info("SKU复杂度更新完成: id={}, skuCode={}, complexity={}->{}",
                saved.getId(), skuCode, oldComplexity, complexity);

        // 构建返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.getId());
        response.put("skuCode", skuCode);
        response.put("skuName", skuName);
        response.put("newComplexity", complexity);
        response.put("oldComplexity", oldComplexity);
        response.put("complexityDescription", getComplexityDescription(complexity));
        response.put("isNew", oldComplexity == null);
        response.put("schedulingUpdated", true);
        response.put("analysisDetail", analysisDetail);
        response.put("message", buildResultMessage(skuCode, complexity, oldComplexity));

        return response;
    }

    /**
     * 获取复杂度描述
     */
    private String getComplexityDescription(int level) {
        switch (level) {
            case 1: return "简单";
            case 2: return "较简单";
            case 3: return "中等";
            case 4: return "较复杂";
            case 5: return "复杂";
            default: return "未知";
        }
    }

    /**
     * 构建结果消息
     */
    private String buildResultMessage(String skuCode, Integer newComplexity, Integer oldComplexity) {
        if (oldComplexity == null) {
            return String.format("已创建SKU [%s] 的复杂度记录：%d级（%s）",
                    skuCode, newComplexity, getComplexityDescription(newComplexity));
        } else if (newComplexity.equals(oldComplexity)) {
            return String.format("SKU [%s] 的复杂度保持不变：%d级（%s）",
                    skuCode, newComplexity, getComplexityDescription(newComplexity));
        } else {
            String direction = newComplexity > oldComplexity ? "上升" : "下降";
            return String.format("SKU [%s] 的复杂度从 %d级 %s至 %d级（%s）",
                    skuCode, oldComplexity, direction, newComplexity, getComplexityDescription(newComplexity));
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "skuCode", "请提供要更新的SKU编码。",
                "complexity", "请提供复杂度等级（1-5，1最简单，5最复杂）。",
                "reason", "请说明复杂度分析的原因。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "skuCode", "SKU编码",
                "complexity", "复杂度等级",
                "reason", "分析原因",
                "skuName", "SKU名称",
                "stepCount", "步骤数",
                "skillRequired", "技能要求",
                "qualityCheckCount", "质检点数",
                "specialEquipment", "特殊设备",
                "estimatedMinutes", "预估工时"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /**
     * 需要管理权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 只有管理员和超级管理员可以使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return userRole != null && (
                userRole.contains("admin") ||
                userRole.contains("supervisor") ||
                userRole.contains("manager")
        );
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
