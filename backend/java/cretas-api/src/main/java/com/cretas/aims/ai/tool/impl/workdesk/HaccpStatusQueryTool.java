package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 质量主管 Workdesk HACCP 状态简洁查询 Tool — Sprint 8 P4c.
 *
 * <p>P3 ship 的 {@code haccp_checkpoint_review} 是 detailed audit view, 适合召回场景;
 * 此 Tool 提供质量主管放行决策语境下的简洁汇总 (passed/deviation 数 + 关键偏离列表).
 * 复用 P3 ship 的 HaccpMonitoringRecord / HaccpCheckpoint repos.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"这批 HACCP 通过吗"</li>
 *   <li>"卤猪蹄 HACCP 状态"</li>
 *   <li>"B-X HACCP 检查"</li>
 *   <li>"放行前 HACCP 验证"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code HACCP_STATUS_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4c)
 */
@Slf4j
@Component
public class HaccpStatusQueryTool extends AbstractBusinessTool {

    @Autowired
    private HaccpMonitoringRecordRepository monitoringRepository;

    @Autowired
    private HaccpCheckpointRepository checkpointRepository;

    @Override
    public String getToolName() {
        return "haccp_status_query";
    }

    @Override
    public String getDescription() {
        return "质量主管批次 HACCP 状态简洁查询 — 用于放行前 HACCP 验证, 输出整体通过状态 + "
                + "偏离监控点数 + 关键偏离列表. LLM 触发: '这批 HACCP 通过吗' / "
                + "'卤猪蹄 HACCP 状态' / 'B-X HACCP 检查' / '放行前 HACCP 验证'. read-only.";
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
        log.info("haccp_status_query — factory={} batch={}", factoryId, batchNumber);

        List<HaccpMonitoringRecord> records = monitoringRepository
                .findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(factoryId, batchNumber);

        int total = records.size();
        int deviations = 0;
        List<Map<String, Object>> keyDeviations = new ArrayList<>();
        Map<Long, HaccpCheckpoint> ccpCache = new HashMap<>();

        for (HaccpMonitoringRecord r : records) {
            if (r.isDeviation()) {
                deviations++;
                HaccpCheckpoint ccp = ccpCache.computeIfAbsent(r.getCheckpointId(), id -> {
                    Optional<HaccpCheckpoint> opt = checkpointRepository.findById(id);
                    return opt.orElse(null);
                });
                Map<String, Object> dev = new LinkedHashMap<>();
                dev.put("checkpointCode", ccp != null ? ccp.getCheckpointCode() : null);
                dev.put("checkpointName", ccp != null ? ccp.getName() : "未知监控点");
                dev.put("measuredValue", r.getMeasuredValue());
                dev.put("monitoringTime", r.getMonitoringTime() != null
                        ? r.getMonitoringTime().toString() : null);
                dev.put("correctiveAction", ccp != null ? ccp.getCorrectiveAction() : null);
                keyDeviations.add(dev);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("totalRecords", total);
        data.put("deviationCount", deviations);
        data.put("passed", total > 0 && deviations == 0);
        data.put("keyDeviations", keyDeviations);
        data.put("hasMonitoring", total > 0);

        String message;
        String releaseHint;
        if (total == 0) {
            message = String.format("⚠️ 批次 %s 无 HACCP 监控记录 — 不可放行, 请先登记 CCP 监控", batchNumber);
            releaseHint = "BLOCK_RELEASE";
            data.put("actionHint", "/quality/haccp?batchNumber=" + batchNumber);
        } else if (deviations == 0) {
            message = String.format("✅ 批次 %s HACCP %d 项监控全通过, 食品安全 OK", batchNumber, total);
            releaseHint = "RELEASE_OK";
        } else {
            message = String.format("🚨 批次 %s HACCP %d 项监控中 %d 项偏离, 不建议放行",
                    batchNumber, total, deviations);
            releaseHint = "BLOCK_RELEASE";
            data.put("actionHint", "/quality/haccp?batchNumber=" + batchNumber);
        }
        data.put("releaseHint", releaseHint);
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
