package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 确认生产人员就位工具
 *
 * 查询当前进行中的生产批次及其分配的员工状态，汇总就位人数和缺勤人数。
 * 无必需参数 — 用户说"确认人员已就位"即可自动查询所有进行中批次。
 *
 * Intent Code: PRODUCTION_CONFIRM_WORKERS_PRESENT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-23
 */
@Slf4j
@Component
public class ProductionConfirmWorkersPresentTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "production_confirm_workers_present";
    }

    @Override
    public String getDescription() {
        return "确认生产人员已就位。查询当前进行中的生产批次及其分配的员工状态，" +
                "汇总就位人数和缺勤人数。" +
                "适用场景：确认人员到齐、检查开工就绪状态、人员就位确认。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 批次ID（可选，不传则查所有进行中批次）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "指定批次ID（可选，不传则查询所有进行中批次）");
        properties.put("batchId", batchId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("确认生产人员就位 - 工厂ID: {}, 参数: {}", factoryId, params);

        Long batchId = getLong(params, "batchId");

        if (batchId != null) {
            return confirmSingleBatch(factoryId, batchId);
        } else {
            return confirmAllInProgressBatches(factoryId);
        }
    }

    private Map<String, Object> confirmSingleBatch(String factoryId, Long batchId) throws Exception {
        List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, batchId);

        int totalWorkers = workers != null ? workers.size() : 0;
        int presentCount = 0;
        int absentCount = 0;

        if (workers != null) {
            for (Map<String, Object> worker : workers) {
                Object status = worker.get("status");
                if ("WORKING".equals(status) || "IN_PROGRESS".equals(status)) {
                    presentCount++;
                } else if ("CHECKED_OUT".equals(status) || "COMPLETED".equals(status)) {
                    // Already done, count as present (completed their work)
                    presentCount++;
                } else {
                    absentCount++;
                }
            }
        }

        Map<String, Object> batchSummary = new HashMap<>();
        batchSummary.put("batchId", batchId);
        batchSummary.put("totalWorkers", totalWorkers);
        batchSummary.put("presentCount", presentCount);
        batchSummary.put("absentCount", absentCount);
        batchSummary.put("allPresent", absentCount == 0 && totalWorkers > 0);
        batchSummary.put("workers", workers != null ? workers : Collections.emptyList());

        Map<String, Object> result = new HashMap<>();
        result.put("batchSummary", batchSummary);
        result.put("totalBatches", 1);
        result.put("totalWorkers", totalWorkers);
        result.put("totalPresent", presentCount);
        result.put("totalAbsent", absentCount);
        result.put("allReady", absentCount == 0 && totalWorkers > 0);

        String message;
        if (totalWorkers == 0) {
            message = String.format("批次 %d 暂无分配员工，请先分配人员", batchId);
        } else if (absentCount == 0) {
            message = String.format("批次 %d 人员已全部就位，共 %d 人，可以开始生产", batchId, totalWorkers);
        } else {
            message = String.format("批次 %d 共 %d 人，已就位 %d 人，未到 %d 人",
                    batchId, totalWorkers, presentCount, absentCount);
        }

        return buildSimpleResult(message, result);
    }

    private Map<String, Object> confirmAllInProgressBatches(String factoryId) throws Exception {
        // Query all IN_PROGRESS batches
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(1);
        pageRequest.setSize(100);

        PageResponse<ProductionBatch> batchPage = processingService.getBatches(factoryId, "IN_PROGRESS", pageRequest);

        List<ProductionBatch> batches = batchPage.getContent();
        if (batches == null || batches.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("totalBatches", 0);
            result.put("totalWorkers", 0);
            result.put("totalPresent", 0);
            result.put("totalAbsent", 0);
            result.put("allReady", false);
            result.put("batchDetails", Collections.emptyList());
            return buildSimpleResult("当前没有进行中的生产批次", result);
        }

        int grandTotalWorkers = 0;
        int grandTotalPresent = 0;
        int grandTotalAbsent = 0;
        List<Map<String, Object>> batchDetails = new ArrayList<>();

        for (ProductionBatch batch : batches) {
            Long bId = batch.getId();
            List<Map<String, Object>> workers = processingService.getBatchWorkers(factoryId, bId);

            int total = workers != null ? workers.size() : 0;
            int present = 0;
            int absent = 0;

            if (workers != null) {
                for (Map<String, Object> worker : workers) {
                    Object status = worker.get("status");
                    if ("WORKING".equals(status) || "IN_PROGRESS".equals(status)
                            || "CHECKED_OUT".equals(status) || "COMPLETED".equals(status)) {
                        present++;
                    } else {
                        absent++;
                    }
                }
            }

            Map<String, Object> detail = new HashMap<>();
            detail.put("batchId", bId);
            detail.put("batchNumber", batch.getBatchNumber());
            detail.put("productName", batch.getProductName());
            detail.put("totalWorkers", total);
            detail.put("presentCount", present);
            detail.put("absentCount", absent);
            detail.put("allPresent", absent == 0 && total > 0);
            batchDetails.add(detail);

            grandTotalWorkers += total;
            grandTotalPresent += present;
            grandTotalAbsent += absent;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalBatches", batches.size());
        result.put("totalWorkers", grandTotalWorkers);
        result.put("totalPresent", grandTotalPresent);
        result.put("totalAbsent", grandTotalAbsent);
        result.put("allReady", grandTotalAbsent == 0 && grandTotalWorkers > 0);
        result.put("batchDetails", batchDetails);

        String message;
        if (grandTotalWorkers == 0) {
            message = String.format("当前有 %d 个进行中批次，但均未分配员工", batches.size());
        } else if (grandTotalAbsent == 0) {
            message = String.format("当前 %d 个进行中批次，共 %d 名员工全部就位，可以正常生产",
                    batches.size(), grandTotalWorkers);
        } else {
            message = String.format("当前 %d 个进行中批次，共 %d 名员工，已就位 %d 人，未到 %d 人",
                    batches.size(), grandTotalWorkers, grandTotalPresent, grandTotalAbsent);
        }

        return buildSimpleResult(message, result);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchId".equals(paramName)) {
            return "请问要确认哪个批次的人员就位情况？不指定则查询所有进行中批次。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("batchId".equals(paramName)) {
            return "批次ID";
        }
        return super.getParameterDisplayName(paramName);
    }

    @Override
    public boolean requiresPermission() {
        return false;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
