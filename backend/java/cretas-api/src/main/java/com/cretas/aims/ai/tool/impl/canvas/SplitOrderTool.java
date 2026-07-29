package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.LinkArrayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 订单拆单工具 (Round 4 Fix P2-25)
 *
 * 使用场景:
 *   - 草原鲜牧: 同订单一半切块一半整条 → 拆成两个子订单
 *   - 蓝海水产: 混合活体+冷冻订单 → 拆分以便不同发货流程
 *   - 大额订单分批发货
 *
 * factoryId 隔离豁免说明: doExecute() 使用 fetch-then-verify pattern —
 * salesOrderRepository.findById() 后紧跟 factoryId equals 校验
 * (如果 source.factoryId != 当前 factoryId 则抛 IllegalArgumentException,
 * 子订单创建时 child.setFactoryId(factoryId) 确保 sink 正确). Audit 脚本
 * 的 findById 正则无法识别后续的 guard, 故手动标记豁免.
 *
 * 参数:
 *   sourceOrderId:  源订单 ID
 *   splitMode:      'items' (按行项) | 'quantity' (按数量均分)
 *   splits:         拆分规则
 *     - items mode: [{ itemIds: [1,2], tag: 'A' }, { itemIds: [3], tag: 'B' }]
 *     - quantity mode: [{ ratio: 0.5, tag: 'A' }, { ratio: 0.5, tag: 'B' }]
 *
 * 返回:
 *   {sourceOrderId, childOrderIds: [{orderId, tag, amount}]}
 */
@Slf4j
@Component
public class SplitOrderTool extends AbstractBusinessTool {

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    /** Sprint 3 Track-F: unified BusinessLink — each child SO links back to source. */
    @Autowired(required = false)
    private LinkArrayService linkArrayService;

    @Override
    public String getToolName() {
        return "split_order";
    }

    @Override
    public String getDescription() {
        return "拆单工具：将一个源订单拆分为多个子订单，源订单保留为 CANCELLED 或 PARENT 状态。" +
               "支持按行项拆分 (items) 或按数量比例拆分 (quantity)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("sourceOrderId", Map.of("type", "string", "description", "源订单 ID"));
        properties.put("splitMode", Map.of("type", "string", "enum", List.of("items", "quantity"),
            "description", "拆分模式: items=按行项 / quantity=按数量比例"));
        properties.put("splits", Map.of("type", "array", "description", "拆分规则数组"));
        return Map.of("type", "object", "properties", properties,
            "required", List.of("sourceOrderId", "splitMode", "splits"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("sourceOrderId", "splitMode", "splits");
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String sourceOrderId = getString(params, "sourceOrderId");
        String splitMode = getString(params, "splitMode");
        List<Map<String, Object>> splits = (List<Map<String, Object>>) params.get("splits");

        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("splits 不能为空");
        }

        SalesOrder source = salesOrderRepository.findById(sourceOrderId)
            .orElseThrow(() -> new IllegalArgumentException("源订单不存在: " + sourceOrderId));
        if (!factoryId.equals(source.getFactoryId())) {
            throw new IllegalStateException("源订单不属于当前工厂");
        }
        if (source.getStatus() != SalesOrderStatus.DRAFT) {
            throw new IllegalStateException("只有草稿状态订单可以拆单, 当前状态: " + source.getStatus());
        }

        List<SalesOrderItem> sourceItems = salesOrderItemRepository.findBySalesOrderId(sourceOrderId);
        List<Map<String, Object>> childOrders = new ArrayList<>();

        String datePrefix = "SO-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-SPLIT-";

        for (int i = 0; i < splits.size(); i++) {
            Map<String, Object> split = splits.get(i);
            String tag = (String) split.getOrDefault("tag", "SPLIT-" + (i + 1));
            // The tag is a caller/LLM-supplied parameter and is concatenated into order_number
            // (VARCHAR(64)). datePrefix(18) + tag + "-" + i must stay within the column, so cap the
            // tag defensively — an over-long tag would otherwise roll back the whole split with a
            // "value too long" error (same class as the business_entity_id #325 fix).
            if (tag != null && tag.length() > 20) {
                tag = tag.substring(0, 20);
            }

            SalesOrder child = new SalesOrder();
            child.setFactoryId(factoryId);
            child.setCustomerId(source.getCustomerId());
            child.setOrderDate(source.getOrderDate());
            child.setRequiredDeliveryDate(source.getRequiredDeliveryDate());
            child.setDeliveryAddress(source.getDeliveryAddress());
            child.setSalesperson(source.getSalesperson());
            // R4 audit C1 (Apr 25 2026): also copy FK so child SO inherits the dual-field link.
            // Pre-V20260425_05 this was always NULL so the omission was harmless; now silently
            // resets commission attribution to non-determinism for any split order.
            child.setSalespersonId(source.getSalespersonId());
            child.setStatus(SalesOrderStatus.DRAFT);
            child.setOrderNumber(datePrefix + tag + "-" + i);
            child.setRemark("拆单源: " + source.getOrderNumber() + " / tag=" + tag);
            child = salesOrderRepository.save(child);

            BigDecimal childTotal = BigDecimal.ZERO;
            if ("items".equals(splitMode)) {
                // Split by item IDs — split.itemIds contains which items go to this child
                List<?> itemIdsRaw = (List<?>) split.get("itemIds");
                Set<String> itemIds = new HashSet<>();
                if (itemIdsRaw != null) {
                    for (Object o : itemIdsRaw) itemIds.add(String.valueOf(o));
                }
                for (SalesOrderItem srcItem : sourceItems) {
                    if (itemIds.contains(String.valueOf(srcItem.getId()))) {
                        SalesOrderItem copy = copyItem(srcItem, child.getId());
                        salesOrderItemRepository.save(copy);
                        childTotal = childTotal.add(copy.getLineAmount() != null ? copy.getLineAmount() : BigDecimal.ZERO);
                    }
                }
            } else if ("quantity".equals(splitMode)) {
                // Split by ratio — each child gets a fraction of every source item
                // lineAmount is a @Transient computed property (quantity * unitPrice * (1 - discount))
                // so we just need to set quantity, and getLineAmount() will recompute.
                double ratio = ((Number) split.getOrDefault("ratio", 0)).doubleValue();
                if (ratio <= 0 || ratio > 1) {
                    throw new IllegalArgumentException("ratio 必须在 (0, 1] 范围内: " + ratio);
                }
                for (SalesOrderItem srcItem : sourceItems) {
                    SalesOrderItem copy = copyItem(srcItem, child.getId());
                    BigDecimal newQty = srcItem.getQuantity().multiply(BigDecimal.valueOf(ratio));
                    copy.setQuantity(newQty);
                    salesOrderItemRepository.save(copy);
                    childTotal = childTotal.add(copy.getLineAmount() != null ? copy.getLineAmount() : BigDecimal.ZERO);
                }
            }

            child.setTotalAmount(childTotal);
            salesOrderRepository.save(child);

            // Sprint 3 Track-F (C-LINKARRAY-1): link child SO back to source SO.
            // linkType "free" — the parent-child split isn't a sale relationship, it's
            // a structural split (a tagged subset of the original demand).
            if (linkArrayService != null) {
                try {
                    Long uid = getUserId(context);
                    linkArrayService.link(factoryId,
                            "SALES_ORDER", child.getId(),
                            "free",
                            "SALES_ORDER", sourceOrderId,
                            "拆单源 / tag=" + tag,
                            uid != null ? String.valueOf(uid) : null);
                } catch (Exception e) {
                    log.warn("BusinessLink double-write failed for split child {}: {}", child.getId(), e.getMessage());
                }
            }

            Map<String, Object> childInfo = new HashMap<>();
            childInfo.put("orderId", child.getId());
            childInfo.put("orderNumber", child.getOrderNumber());
            childInfo.put("tag", tag);
            childInfo.put("amount", childTotal);
            childOrders.add(childInfo);
        }

        // Mark source as cancelled (alternative: add PARENT status)
        source.setStatus(SalesOrderStatus.CANCELLED);
        source.setRemark((source.getRemark() != null ? source.getRemark() + " | " : "") +
            "已拆分为 " + splits.size() + " 个子订单");
        salesOrderRepository.save(source);

        log.info("Split order {} into {} children for factory {}",
            sourceOrderId, childOrders.size(), factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("sourceOrderId", sourceOrderId);
        result.put("sourceOrderNumber", source.getOrderNumber());
        result.put("childOrders", childOrders);
        return buildSimpleResult("订单拆分成功", result);
    }

    private SalesOrderItem copyItem(SalesOrderItem src, String newOrderId) {
        SalesOrderItem copy = new SalesOrderItem();
        copy.setSalesOrderId(newOrderId);
        copy.setProductTypeId(src.getProductTypeId());
        copy.setProductName(src.getProductName());
        copy.setQuantity(src.getQuantity());
        copy.setUnit(src.getUnit());
        copy.setUnitPrice(src.getUnitPrice());
        copy.setDiscountRate(src.getDiscountRate());
        copy.setRemark(src.getRemark());
        copy.setSpecification(src.getSpecification());
        copy.setBoxQuantity(src.getBoxQuantity());
        copy.setPackagingSpecId(src.getPackagingSpecId());
        copy.setPackagingSpecName(src.getPackagingSpecName());
        copy.setPackagingUnit(src.getPackagingUnit());
        copy.setPackagingBaseUnit(src.getPackagingBaseUnit());
        copy.setPackagingFactor(src.getPackagingFactor());
        copy.setSourceWarehouseCode(src.getSourceWarehouseCode());
        copy.setDestWarehouseName(src.getDestWarehouseName());
        copy.setDestWarehouseCode(src.getDestWarehouseCode());
        copy.setExternalPoId(src.getExternalPoId());
        copy.setBarcode(src.getBarcode());
        copy.setAppointmentTime(src.getAppointmentTime());
        copy.setRequiredArrivalDate(src.getRequiredArrivalDate());
        return copy;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
