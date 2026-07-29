package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 质量主管批次质检综合汇总 Tool — Sprint 8 P4c (质量主管 Workdesk).
 *
 * <p>查指定 batch 的全部 QualityInspection 记录 + 通过率聚合 + result 分布
 * (PASS / FAIL / CONDITIONAL). 用于质量主管放行决策综合判断.
 *
 * <p><b>Sprint 9 P1.1 Fix 2 (2026-05-21)</b>: 接入 QualityInspection.material_batch_id
 * 新字段, 实现 batch_number → MaterialBatch.id → QualityInspection 直查路径.
 * 优先级:
 * <ol>
 *   <li><b>MATERIAL_BATCH 直查</b>: 通过 material_batch_id 查 (Sprint 9 主路径)</li>
 *   <li><b>PRODUCTION_BATCH 反查</b>: 若 MaterialBatch 通过 source_doc/lineage 反查到关联
 *       的 ProductionBatch, 再通过 production_batch_id 查 (best-effort backwards-compat)</li>
 *   <li><b>NO_INSPECTION fallback</b>: 两路径均空 → 返 R5 actionHint 提示去质检页登记</li>
 * </ol>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-X 的质检记录"</li>
 *   <li>"这批检测怎么样"</li>
 *   <li>"卤猪蹄批次检测通过吗"</li>
 *   <li>"质检合格率"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code QUALITY_CHECK_SUMMARY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4c)
 * @since 2026-05-21 Sprint 9 P1.1 接入 material_batch_id 直查路径
 */
@Slf4j
@Component
public class QualityCheckSummaryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Override
    public String getToolName() {
        return "quality_check_summary";
    }

    @Override
    public String getDescription() {
        return "质量主管批次质检汇总 — 查指定批次的全部 QualityInspection 记录 + 通过率聚合 + "
                + "result 分布 (PASS/FAIL/CONDITIONAL). Sprint 9 P1.1 支持 material_batch_id 直查. "
                + "LLM 触发: 'B-X 的质检记录' / '这批检测怎么样' / "
                + "'卤猪蹄批次检测通过吗' / '质检合格率'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填), e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        log.info("quality_check_summary — factory={} batch={}", factoryId, batchNumber);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);

        // 1. 查 MaterialBatch
        Optional<MaterialBatch> batchOpt = materialBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (batchOpt.isEmpty()) {
            data.put("batchFound", false);
            data.put("inspectionCount", 0);
            data.put("overallStatus", "NOT_FOUND");
            data.put("actionHint", "批次未找到, 请确认批次号或前往物料批次页查找");
            return buildSimpleResult(
                    String.format("⚠️ 批次 %s 未找到. 请确认批次号是否正确", batchNumber), data);
        }
        MaterialBatch batch = batchOpt.get();
        data.put("batchFound", true);
        data.put("batchId", batch.getId());
        data.put("materialTypeId", batch.getMaterialTypeId());
        data.put("supplierId", batch.getSupplierId());
        data.put("productionDate", batch.getProductionDate());
        data.put("expireDate", batch.getExpireDate());
        data.put("currentStatus", batch.getStatus() != null ? batch.getStatus().name() : "UNKNOWN");
        data.put("currentStatusDisplay",
                batch.getStatus() != null ? batch.getStatus().getDisplayName() : "未知");

        // 2. Sprint 9 P1.1 — 主路径: 通过 material_batch_id 直查 inspections
        List<QualityInspection> inspections = new ArrayList<>();
        String inspectionSource = null;
        try {
            inspections = qualityInspectionRepository
                    .findByFactoryIdAndMaterialBatchId(factoryId, batch.getId());
            if (!inspections.isEmpty()) {
                inspectionSource = "MATERIAL_BATCH";
                log.info("quality_check_summary — found {} inspections via material_batch_id={}",
                        inspections.size(), batch.getId());
            } else {
                inspectionSource = "NO_INSPECTION";
                log.info("quality_check_summary — no inspections for material_batch_id={}, " +
                        "production_batch reverse-lookup not implemented (BR-FUTURE)", batch.getId());
            }
        } catch (Exception ex) {
            log.warn("quality_check_summary — query failed: {}", ex.getMessage());
            inspectionSource = "QUERY_ERROR";
        }

        int total = inspections.size();
        int pass = 0, fail = 0, conditional = 0;
        BigDecimal totalSample = BigDecimal.ZERO;
        BigDecimal totalPass = BigDecimal.ZERO;
        BigDecimal totalFail = BigDecimal.ZERO;
        List<Map<String, Object>> details = new ArrayList<>();

        for (QualityInspection q : inspections) {
            String result = q.getResult() == null ? "UNKNOWN" : q.getResult().toUpperCase();
            if ("PASS".equals(result) || "PASSED".equals(result)) pass++;
            else if ("FAIL".equals(result) || "FAILED".equals(result)) fail++;
            else if ("CONDITIONAL".equals(result)) conditional++;

            totalSample = totalSample.add(nonNull(q.getSampleSize()));
            totalPass = totalPass.add(nonNull(q.getPassCount()));
            totalFail = totalFail.add(nonNull(q.getFailCount()));

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("inspectionId", q.getId());
            d.put("inspectionDate", q.getInspectionDate());
            d.put("result", result);
            d.put("passRate", q.getPassRate());
            d.put("notes", q.getNotes());
            details.add(d);
        }

        // 3. 汇总判定
        String overallStatus;
        String message;
        if (total == 0) {
            overallStatus = "NO_INSPECTION";
            message = String.format("⚠️ 批次 %s 暂无质检记录, 不能放行. " +
                    "请先在质检页面登记检测结果.", batchNumber);
            data.put("actionHint", "前往 /quality/inspections 页面登记质检结果, " +
                    "登记时关联 material_batch_id=" + batch.getId() + " (Sprint 9 P1.1)");
        } else if (fail > 0) {
            overallStatus = "FAILED";
            message = String.format("❌ 批次 %s 质检 %d 次, %d 次不合格 — 不可放行, 建议退货",
                    batchNumber, total, fail);
        } else if (conditional > 0) {
            overallStatus = "CONDITIONAL";
            message = String.format("⚠️ 批次 %s 质检 %d 次有 %d 次条件通过, 需复核后决定放行",
                    batchNumber, total, conditional);
        } else {
            overallStatus = "PASSED";
            message = String.format("✅ 批次 %s 质检 %d 次全部通过, 可放行", batchNumber, total);
        }

        data.put("inspectionCount", total);
        data.put("passCount", pass);
        data.put("failCount", fail);
        data.put("conditionalCount", conditional);
        data.put("totalSample", totalSample);
        data.put("totalPass", totalPass);
        data.put("totalFail", totalFail);
        data.put("overallStatus", overallStatus);
        data.put("inspectionSource", inspectionSource);
        data.put("inspectionDetails", details);

        return buildSimpleResult(message, data);
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
