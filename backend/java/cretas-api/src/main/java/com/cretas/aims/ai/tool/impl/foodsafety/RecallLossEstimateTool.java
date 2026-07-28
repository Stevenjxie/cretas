package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 召回损失预估 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 6).
 *
 * <p>聚合召回事件的预估损失: 冻结库存价值 + 客户退货预估 + 行政成本.
 *
 * <p>Phase B 估算公式 (粗算):
 * <ul>
 *   <li>冻结库存价值 = sum of MaterialBatch.totalCost (status=FROZEN, 关联到本 event)</li>
 *   <li>客户退货预估 = sum of ShipmentRecord.totalAmount (相同 batchNumber 出货)</li>
 *   <li>行政成本 = ¥5000 固定值 (人工 + 短信 + 监管申报费, Phase C 改可配置)</li>
 *   <li>预估总损失 = 上三项之和 + 品牌损失系数 (20% 加成)</li>
 * </ul>
 *
 * <p>更新 RecallEvent.estimatedLoss 字段供 UI 展示.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"这次召回损失多少"</li>
 *   <li>"召回成本预估"</li>
 *   <li>"损失多少钱"</li>
 * </ul>
 *
 * <p>read + light update (仅 update estimatedLoss).
 *
 * <p>Intent Code: {@code RECALL_LOSS_ESTIMATE}
 */
@Slf4j
@Component
public class RecallLossEstimateTool extends AbstractBusinessTool {

    /** 行政成本固定值 — Phase C 可改 Factory 级配置. */
    private static final BigDecimal ADMIN_COST = new BigDecimal("5000");

    /** 品牌损失系数 — 总损失加成 20%. */
    private static final BigDecimal BRAND_LOSS_RATE = new BigDecimal("0.20");

    @Autowired
    private RecallEventRepository recallEventRepository;

    @Autowired
    private MaterialBatchRepository materialBatchRepository;

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Override
    public String getToolName() {
        return "recall_loss_estimate";
    }

    @Override
    public String getDescription() {
        return "召回损失预估 — 聚合冻结库存价值 + 客户退货金额 + 行政成本 + 品牌损失 (20% 加成). "
                + "LLM 触发: '这次召回损失多少' / '召回成本预估' / '损失多少钱' / "
                + "'召回会赔多少'. 更新 RecallEvent.estimatedLoss.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> recallEventId = new HashMap<>();
        recallEventId.put("type", "integer");
        recallEventId.put("description", "RecallEvent ID (必填)");
        properties.put("recallEventId", recallEventId);

        // 可选: 提供 batchNumbers list, 缺省走 RecallAction.targetEntityId 拿
        Map<String, Object> batchNumbers = new HashMap<>();
        batchNumbers.put("type", "array");
        batchNumbers.put("description", "涉事批次号列表 (可选). 缺省时从 RecallAction 推导");
        properties.put("batchNumbers", batchNumbers);

        schema.put("properties", properties);
        schema.put("required", List.of("recallEventId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("recallEventId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long recallEventId = getLong(params, "recallEventId");
        List<String> batchNumbers = getList(params, "batchNumbers");

        log.info("recall_loss_estimate — factory={} event={} batches={}",
                factoryId, recallEventId, batchNumbers);

        Optional<RecallEvent> opt = recallEventRepository.findById(recallEventId);
        if (opt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("RecallEvent #%d 不存在", recallEventId));
        }
        RecallEvent event = opt.get();
        if (!factoryId.equals(event.getFactoryId())) {
            throw new BusinessException(403,
                    String.format("RecallEvent #%d 属于其他工厂", recallEventId));
        }

        // 1. 冻结库存价值 (优先 batchNumbers, 兜底查 factory 所有 FROZEN 批次)
        BigDecimal frozenInventoryValue = BigDecimal.ZERO;
        int frozenBatchCount = 0;
        if (batchNumbers != null && !batchNumbers.isEmpty()) {
            for (String bn : batchNumbers) {
                Optional<MaterialBatch> mb = materialBatchRepository
                        .findByFactoryIdAndBatchNumber(factoryId, bn);
                if (mb.isPresent() && mb.get().getStatus() == MaterialBatchStatus.FROZEN) {
                    BigDecimal val = computeBatchValue(mb.get());
                    if (val != null) {
                        frozenInventoryValue = frozenInventoryValue.add(val);
                    }
                    frozenBatchCount++;
                }
            }
        } else {
            // 兜底: 查 factory 全部 FROZEN 批次 (粗略, 可能含其他事件的冻结)
            List<MaterialBatch> allFrozen = materialBatchRepository
                    .findByStatus(MaterialBatchStatus.FROZEN).stream()
                    .filter(b -> factoryId.equals(b.getFactoryId()))
                    .collect(Collectors.toList());
            for (MaterialBatch mb : allFrozen) {
                BigDecimal val = computeBatchValue(mb);
                if (val != null) frozenInventoryValue = frozenInventoryValue.add(val);
            }
            frozenBatchCount = allFrozen.size();
        }

        // 2. 客户退货预估 — 同 batchNumbers 出货金额合计
        BigDecimal customerReturnEstimate = BigDecimal.ZERO;
        int affectedShipmentCount = 0;
        if (batchNumbers != null && !batchNumbers.isEmpty()) {
            for (String bn : batchNumbers) {
                List<ShipmentRecord> shipments = shipmentRecordRepository
                        .findByFactoryIdAndBatchNumber(factoryId, bn);
                for (ShipmentRecord s : shipments) {
                    if (s.getTotalAmount() != null) {
                        customerReturnEstimate = customerReturnEstimate.add(s.getTotalAmount());
                    }
                    affectedShipmentCount++;
                }
            }
        }

        // 3. 行政成本
        BigDecimal adminCost = ADMIN_COST;

        // 4. 品牌损失 (20% 加成)
        BigDecimal subtotal = frozenInventoryValue.add(customerReturnEstimate).add(adminCost);
        BigDecimal brandLoss = subtotal.multiply(BRAND_LOSS_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalLoss = subtotal.add(brandLoss).setScale(2, RoundingMode.HALF_UP);

        // 更新 RecallEvent.estimatedLoss
        event.setEstimatedLoss(totalLoss);
        recallEventRepository.save(event);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recallEventId", recallEventId);
        data.put("eventCode", event.getEventCode());
        data.put("frozenInventoryValue", frozenInventoryValue.setScale(2, RoundingMode.HALF_UP));
        data.put("frozenBatchCount", frozenBatchCount);
        data.put("customerReturnEstimate", customerReturnEstimate.setScale(2, RoundingMode.HALF_UP));
        data.put("affectedShipmentCount", affectedShipmentCount);
        data.put("adminCost", adminCost);
        data.put("brandLoss", brandLoss);
        data.put("brandLossRate", BRAND_LOSS_RATE);
        data.put("totalEstimatedLoss", totalLoss);
        data.put("actionHint", "/quality/recall/" + recallEventId);

        String message = String.format(
                "召回 %s 预估损失: ¥%s (冻结库存 ¥%s + 退货 ¥%s + 行政 ¥%s + 品牌损失 ¥%s)",
                event.getEventCode(), totalLoss,
                frozenInventoryValue.setScale(2, RoundingMode.HALF_UP),
                customerReturnEstimate.setScale(2, RoundingMode.HALF_UP),
                adminCost, brandLoss);
        return buildSimpleResult(message, data);
    }

    /**
     * 计算批次库存价值 = MaterialBatch.totalCost (unitPrice * receiptQuantity).
     *
     * <p>{@link MaterialBatch#getTotalCost()} 是 @PriceSensitive — 若 unitPrice 因角色过滤被
     * stripped 为 null 则返 null. 这里用 BigDecimal.ZERO 兜底, 召回估算不应因价格脱敏而崩.
     */
    private BigDecimal computeBatchValue(MaterialBatch batch) {
        if (batch == null) return BigDecimal.ZERO;
        BigDecimal v = batch.getTotalCost();
        return v != null ? v : BigDecimal.ZERO;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
