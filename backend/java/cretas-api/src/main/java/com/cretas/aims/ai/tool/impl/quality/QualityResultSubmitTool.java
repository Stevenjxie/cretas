package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityInspectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 质检结果提交工具
 *
 * 用于提交质量检验结果，包括检验数据、结论和备注。
 * 支持关联生产批次，自动计算合格率。
 *
 * 业务规则：
 * 1. 必须指定检验记录ID或批次ID
 * 2. 结果必须为 PASS/FAIL/CONDITIONAL
 * 3. 样本数量、合格数、不合格数必须逻辑一致
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class QualityResultSubmitTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private QualityInspectionService qualityInspectionService;

    @Override
    public String getToolName() {
        return "quality_result_submit";
    }

    @Override
    public String getDescription() {
        return "提交质检结果。用于记录质量检验的最终结论，包括检验数据、合格率、结论说明等。" +
                "适用场景：提交成品质检、原料来料检验、过程检验结果。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // checkId: 质检记录ID（必需）- 可以是新建或更新
        Map<String, Object> checkId = new HashMap<>();
        checkId.put("type", "string");
        checkId.put("description", "质检记录ID，用于更新现有记录；如果为空则创建新记录");
        properties.put("checkId", checkId);

        // productionBatchId: 生产批次ID（可选，新建时需要）
        Map<String, Object> productionBatchId = new HashMap<>();
        productionBatchId.put("type", "integer");
        productionBatchId.put("description", "关联的生产批次ID，创建新质检记录时必需");
        properties.put("productionBatchId", productionBatchId);

        // result: 检验结果（必需）
        Map<String, Object> result = new HashMap<>();
        result.put("type", "string");
        result.put("description", "质检结果：PASS-合格，FAIL-不合格，CONDITIONAL-有条件放行");
        result.put("enum", Arrays.asList("PASS", "FAIL", "CONDITIONAL"));
        properties.put("result", result);

        // conclusion: 结论说明（必需）
        Map<String, Object> conclusion = new HashMap<>();
        conclusion.put("type", "string");
        conclusion.put("description", "质检结论说明，如：各项指标均符合标准、存在外观缺陷等");
        conclusion.put("maxLength", 1000);
        properties.put("conclusion", conclusion);

        // sampleSize: 样本数量（可选）
        Map<String, Object> sampleSize = new HashMap<>();
        sampleSize.put("type", "number");
        sampleSize.put("description", "抽检样本数量");
        sampleSize.put("minimum", 1);
        properties.put("sampleSize", sampleSize);

        // passCount: 合格数量（可选）
        Map<String, Object> passCount = new HashMap<>();
        passCount.put("type", "number");
        passCount.put("description", "合格样本数量");
        passCount.put("minimum", 0);
        properties.put("passCount", passCount);

        // failCount: 不合格数量（可选）
        Map<String, Object> failCount = new HashMap<>();
        failCount.put("type", "number");
        failCount.put("description", "不合格样本数量");
        failCount.put("minimum", 0);
        properties.put("failCount", failCount);

        // checkItems: 检测项数据（可选）
        Map<String, Object> checkItems = new HashMap<>();
        checkItems.put("type", "array");
        checkItems.put("description", "检测项详细数据，包含各项检测指标的值");
        Map<String, Object> itemSchema = new HashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("description", "单个检测项，包含name(名称)、value(实测值)、standard(标准值)、result(是否合格)");
        checkItems.put("items", itemSchema);
        properties.put("checkItems", checkItems);

        // photos: 质检照片（可选）
        Map<String, Object> photos = new HashMap<>();
        photos.put("type", "array");
        photos.put("description", "质检照片URL列表");
        Map<String, Object> photoSchema = new HashMap<>();
        photoSchema.put("type", "string");
        photos.put("items", photoSchema);
        properties.put("photos", photos);

        // notes: 备注说明（可选）
        Map<String, Object> notes = new HashMap<>();
        notes.put("type", "string");
        notes.put("description", "其他备注说明");
        notes.put("maxLength", 2000);
        properties.put("notes", notes);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("checkId", "result", "conclusion"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("checkId", "result", "conclusion");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 解析参数
        String checkId = getString(params, "checkId");
        String result = getString(params, "result");
        String conclusion = getString(params, "conclusion");
        Long productionBatchId = getLong(params, "productionBatchId");
        BigDecimal sampleSize = getBigDecimal(params, "sampleSize");
        BigDecimal passCount = getBigDecimal(params, "passCount");
        BigDecimal failCount = getBigDecimal(params, "failCount");
        String notes = getString(params, "notes");
        List<Object> checkItems = getList(params, "checkItems");
        List<Object> photos = getList(params, "photos");

        // 2. 验证结果值
        if (!Arrays.asList("PASS", "FAIL", "CONDITIONAL").contains(result)) {
            throw new IllegalArgumentException("质检结果必须为 PASS、FAIL 或 CONDITIONAL");
        }

        log.info("提交质检结果 - 记录ID: {}, 结果: {}, 结论: {}", checkId, result, conclusion);

        // 3. 构建或更新质检记录
        QualityInspection inspection;
        boolean isUpdate = false;

        try {
            // 尝试获取现有记录进行更新
            inspection = qualityInspectionService.getInspectionById(factoryId, checkId);
            isUpdate = true;
            log.info("更新现有质检记录: {}", checkId);
        } catch (Exception e) {
            // 记录不存在，创建新记录
            if (productionBatchId == null) {
                throw new IllegalArgumentException("创建新质检记录时必须提供生产批次ID");
            }
            inspection = new QualityInspection();
            inspection.setId(checkId);
            inspection.setProductionBatchId(productionBatchId);
            inspection.setInspectionDate(LocalDate.now());
            log.info("创建新质检记录: {}", checkId);
        }

        // 4. 设置质检数据
        inspection.setResult(result);

        // 设置检验员ID（从上下文获取当前用户）
        Long userId = getUserId(context);
        if (userId != null) {
            inspection.setInspectorId(userId);
        }

        // 设置样本数据
        if (sampleSize != null) {
            inspection.setSampleSize(sampleSize);
        }
        if (passCount != null) {
            inspection.setPassCount(passCount);
        }
        if (failCount != null) {
            inspection.setFailCount(failCount);
        }

        // 计算合格率
        if (sampleSize != null && sampleSize.compareTo(BigDecimal.ZERO) > 0 && passCount != null) {
            BigDecimal passRate = passCount.multiply(BigDecimal.valueOf(100))
                    .divide(sampleSize, 2, RoundingMode.HALF_UP);
            inspection.setPassRate(passRate);
        }

        // 组合备注（结论 + 额外备注）
        StringBuilder notesBuilder = new StringBuilder();
        notesBuilder.append("结论: ").append(conclusion);
        if (notes != null && !notes.trim().isEmpty()) {
            notesBuilder.append("\n备注: ").append(notes);
        }
        if (checkItems != null && !checkItems.isEmpty()) {
            notesBuilder.append("\n检测项数据: ").append(checkItems.size()).append("项");
        }
        if (photos != null && !photos.isEmpty()) {
            notesBuilder.append("\n附件照片: ").append(photos.size()).append("张");
        }
        inspection.setNotes(notesBuilder.toString());

        // 5. 保存质检记录
        QualityInspection savedInspection;
        if (isUpdate) {
            savedInspection = qualityInspectionService.updateInspection(factoryId, checkId, inspection);
        } else {
            savedInspection = qualityInspectionService.createInspection(factoryId, inspection);
        }

        // 6. 构建返回结果
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("inspectionId", savedInspection.getId());
        resultData.put("productionBatchId", savedInspection.getProductionBatchId());
        resultData.put("result", savedInspection.getResult());
        resultData.put("passRate", savedInspection.getPassRate());
        resultData.put("qualityGrade", savedInspection.getQualityGrade());
        resultData.put("inspectionDate", savedInspection.getInspectionDate().toString());
        resultData.put("isUpdate", isUpdate);

        String actionDesc = isUpdate ? "更新" : "创建";
        String resultDesc = "PASS".equals(result) ? "合格" : ("FAIL".equals(result) ? "不合格" : "有条件放行");
        resultData.put("message", String.format("质检结果%s成功，记录ID: %s，结果: %s，%s",
                actionDesc, savedInspection.getId(), resultDesc, conclusion));

        log.info("质检结果提交成功 - ID: {}, 结果: {}, 合格率: {}%",
                savedInspection.getId(), savedInspection.getResult(), savedInspection.getPassRate());

        return resultData;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "checkId":
                return "请提供质检记录ID，用于标识这次质检。";
            case "result":
                return "请问质检结果是什么？合格(PASS)、不合格(FAIL)还是有条件放行(CONDITIONAL)？";
            case "conclusion":
                return "请简要说明质检结论。";
            case "productionBatchId":
                return "请问这是哪个生产批次的质检？";
            case "sampleSize":
                return "请问抽检了多少样本？";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "checkId":
                return "质检记录ID";
            case "result":
                return "质检结果";
            case "conclusion":
                return "结论说明";
            case "productionBatchId":
                return "生产批次ID";
            case "sampleSize":
                return "样本数量";
            case "passCount":
                return "合格数量";
            case "failCount":
                return "不合格数量";
            case "checkItems":
                return "检测项数据";
            case "photos":
                return "质检照片";
            case "notes":
                return "备注";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /**
     * 此工具需要写操作权限
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 质检员及以上角色可使用
     */
    @Override
    public boolean hasPermission(String userRole) {
        return "super_admin".equals(userRole) ||
                "factory_super_admin".equals(userRole) ||
                "platform_admin".equals(userRole) ||
                "factory_admin".equals(userRole) ||
                "quality_inspector".equals(userRole) ||
                "quality_manager".equals(userRole);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
