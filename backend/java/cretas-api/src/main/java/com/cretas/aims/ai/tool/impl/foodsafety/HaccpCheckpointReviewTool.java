package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
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
 * HACCP 关键控制点审查 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 3).
 *
 * <p>查指定 batch 的 HACCP 监控记录 (CCP 全记录) + 标记 deviation +
 * 关联 CCP 配置查 correctiveAction.
 *
 * <p>核心查询 path: {@link HaccpMonitoringRecordRepository#findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc}
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-X 的 HACCP 记录"</li>
 *   <li>"这批 HACCP 通过吗"</li>
 *   <li>"查 B-X 的 CCP 监控"</li>
 *   <li>"今天 CCP 监控"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code HACCP_REVIEW}
 */
@Slf4j
@Component
public class HaccpCheckpointReviewTool extends AbstractBusinessTool {

    @Autowired
    private HaccpMonitoringRecordRepository monitoringRepository;

    @Autowired
    private HaccpCheckpointRepository checkpointRepository;

    @Override
    public String getToolName() {
        return "haccp_checkpoint_review";
    }

    @Override
    public String getDescription() {
        return "HACCP 关键控制点 (CCP) 审查 — 查指定批次的全部 CCP 监控记录 + 标记 deviation + "
                + "提供 correctiveAction 建议. LLM 触发: 'B-X 的 HACCP 记录' / '这批 HACCP 通过吗' / "
                + "'查 B-X 的 CCP 监控' / '今天 CCP 监控'. read-only.";
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
        log.info("haccp_checkpoint_review — factory={} batch={}", factoryId, batchNumber);

        List<HaccpMonitoringRecord> records = monitoringRepository
                .findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(factoryId, batchNumber);

        List<Map<String, Object>> recordList = new ArrayList<>();
        List<Map<String, Object>> deviations = new ArrayList<>();
        int deviationCount = 0;

        // 缓存 checkpoint 配置 (一个 batch 通常涉及几个 CCP)
        Map<Long, HaccpCheckpoint> checkpointCache = new HashMap<>();

        for (HaccpMonitoringRecord r : records) {
            HaccpCheckpoint ccp = checkpointCache.computeIfAbsent(r.getCheckpointId(),
                    id -> {
                        Optional<HaccpCheckpoint> opt = checkpointRepository.findById(id);
                        return opt.orElse(null);
                    });

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordId", r.getId());
            row.put("checkpointId", r.getCheckpointId());
            row.put("checkpointCode", ccp != null ? ccp.getCheckpointCode() : null);
            row.put("checkpointName", ccp != null ? ccp.getName() : null);
            row.put("hazardType", ccp != null ? ccp.getHazardType() : null);
            row.put("monitoringTime", r.getMonitoringTime() != null
                    ? r.getMonitoringTime().toString() : null);
            row.put("measuredValue", r.getMeasuredValue());
            row.put("unit", ccp != null ? ccp.getUnit() : null);
            row.put("criticalLimitMin", ccp != null ? ccp.getCriticalLimitMin() : null);
            row.put("criticalLimitMax", ccp != null ? ccp.getCriticalLimitMax() : null);
            row.put("isDeviation", r.isDeviation());
            row.put("deviationAction", r.getDeviationAction());
            row.put("operatorUserId", r.getOperatorUserId());
            recordList.add(row);

            if (r.isDeviation()) {
                deviationCount++;
                Map<String, Object> dev = new LinkedHashMap<>();
                dev.put("checkpointCode", ccp != null ? ccp.getCheckpointCode() : null);
                dev.put("checkpointName", ccp != null ? ccp.getName() : null);
                dev.put("measuredValue", r.getMeasuredValue());
                dev.put("expectedRange", ccp != null
                        ? String.format("%s ~ %s %s",
                                fmt(ccp.getCriticalLimitMin()), fmt(ccp.getCriticalLimitMax()),
                                ccp.getUnit())
                        : null);
                dev.put("correctiveAction", ccp != null ? ccp.getCorrectiveAction() : null);
                dev.put("monitoringTime", r.getMonitoringTime() != null
                        ? r.getMonitoringTime().toString() : null);
                deviations.add(dev);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("recordCount", records.size());
        data.put("deviationCount", deviationCount);
        data.put("records", recordList);
        data.put("deviations", deviations);
        data.put("passed", deviationCount == 0 && !records.isEmpty());
        data.put("actionHint", "/quality/haccp?batchNumber=" + batchNumber);

        String message;
        if (records.isEmpty()) {
            message = String.format("⚠️ 批次 %s 无 HACCP 监控记录, 食品安全无法验证", batchNumber);
        } else if (deviationCount == 0) {
            message = String.format("✅ 批次 %s HACCP 全部 %d 项监控通过", batchNumber, records.size());
        } else {
            message = String.format("🚨 批次 %s HACCP 共 %d 项监控, %d 项 deviation 偏离限值. 详见 deviations 字段",
                    batchNumber, records.size(), deviationCount);
        }
        return buildSimpleResult(message, data);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
