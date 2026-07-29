package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 仓管员 PDA 扫码任务生成 Tool (Sprint 8 P4 — 仓管员 Workdesk V1).
 *
 * <p>F006 真场景: 仓管员张师傅看到今日待收清单 (5 个 PO 共 15 行), 点 "一键扫码" 按钮,
 * 系统生成扫码任务 token + 二维码, 仓管员用 PDA / 手机扫码 app 扫一下, 任务直接装载到
 * 移动端 scanner, 仓管员到货位逐个扫物料条码核对.
 *
 * <p>vs HJ 模式: 仓管员手抄 PO 单到 PDA → PDA 录入 → 上传服务器 (10 分钟). Cretas: 30 秒.
 *
 * <p>本 Tool 仅生成任务 JSON (含 token + items 数组), 客户端 (mobile scanner / PDA app) 凭
 * token 拉取 task detail. 此 Tool 不持久化 task — Sprint 9 接 Redis cache + REST endpoint
 * for PDA app consume. MVP 直接返回完整 task JSON 内嵌于 Workdesk UI 显二维码.
 *
 * <p>Intent Code: {@code PDA_SCAN_TASK_GENERATE}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4)
 */
@Slf4j
@Component
public class PdaScanTaskGenerateTool extends AbstractBusinessTool {

    /** Task token TTL (mins). 生成后 30 min 内可被 PDA app 拉取. */
    private static final long TASK_TOKEN_TTL_MIN = 30;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Override
    public String getToolName() {
        return "pda_scan_task_generate";
    }

    @Override
    public String getDescription() {
        return "生成 PDA / 手机扫码 app 的入库扫码任务 (token + items 数组). LLM 触发场景: "
                + "用户说 '一键扫码入库' / '生成扫码任务' / '给我个二维码' / "
                + "'PDA 任务' / '把这些 PO 给 PDA' / '准备扫码'. "
                + "GENERATE — 输出可被 mobile app 扫码消费的 token, 不实际写数据库.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> poIds = new HashMap<>();
        poIds.put("type", "array");
        poIds.put("description", "采购订单 ID 列表 (必填). 系统聚合多个 PO 的待收行项目到 1 个扫码任务");
        Map<String, Object> itemSchema = new HashMap<>();
        itemSchema.put("type", "string");
        poIds.put("items", itemSchema);
        poIds.put("minItems", 1);
        properties.put("poIds", poIds);

        schema.put("properties", properties);
        schema.put("required", List.of("poIds"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("poIds");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        List<Object> rawIds = getList(params, "poIds");
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalArgumentException("poIds 必填且至少 1 个");
        }
        Long userId = getUserId(context);

        log.info("pda_scan_task_generate — factory={} pos={} user={}",
                factoryId, rawIds.size(), userId);

        List<Map<String, Object>> taskItems = new ArrayList<>();
        List<String> validPos = new ArrayList<>();
        List<String> invalidPos = new ArrayList<>();

        for (Object o : rawIds) {
            String poId = String.valueOf(o);
            Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findById(poId);
            if (poOpt.isEmpty() || !factoryId.equals(poOpt.get().getFactoryId())) {
                invalidPos.add(poId);
                continue;
            }
            PurchaseOrder po = poOpt.get();
            // 已完成 / 取消的 PO 不能再扫
            if (po.getStatus() == PurchaseOrderStatus.COMPLETED
                    || po.getStatus() == PurchaseOrderStatus.CANCELLED
                    || po.getStatus() == PurchaseOrderStatus.CLOSED) {
                invalidPos.add(po.getOrderNumber());
                continue;
            }
            validPos.add(po.getOrderNumber());

            List<PurchaseOrderItem> items = purchaseOrderItemRepository
                    .findByPurchaseOrderId(poId);
            for (PurchaseOrderItem item : items) {
                BigDecimal ordered = nonNull(item.getQuantity());
                BigDecimal received = nonNull(item.getReceivedQuantity());
                BigDecimal pending = ordered.subtract(received);
                if (pending.compareTo(BigDecimal.ZERO) <= 0) continue;

                Map<String, Object> ti = new LinkedHashMap<>();
                ti.put("poId", po.getId());
                ti.put("orderNumber", po.getOrderNumber());
                ti.put("supplierName", po.getSupplierName());
                ti.put("lineId", item.getId());
                ti.put("materialTypeId", item.getMaterialTypeId());
                ti.put("materialName", item.getMaterialName());
                ti.put("specification", item.getSpecification());
                ti.put("expectedQty", pending);
                ti.put("unit", item.getUnit());
                ti.put("scanned", false);
                ti.put("scannedQty", BigDecimal.ZERO);
                taskItems.add(ti);
            }
        }

        // 生成 token
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TASK_TOKEN_TTL_MIN);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", "PDA-" + token);
        task.put("token", token);
        task.put("factoryId", factoryId);
        task.put("createdBy", userId);
        task.put("createdAt", LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        task.put("expiresAt", expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        task.put("ttlMin", TASK_TOKEN_TTL_MIN);
        task.put("validPOs", validPos);
        task.put("invalidPOs", invalidPos);
        task.put("totalItems", taskItems.size());
        task.put("items", taskItems);
        // QR code payload (前端 qrcode lib 直接渲染)
        task.put("qrPayload", String.format(
                "cretas://pda-scan?token=%s&factory=%s", token, factoryId));

        Map<String, Object> data = new HashMap<>();
        data.put("task", task);
        data.put("nextActionHint",
                "请用 PDA / 手机扫码 app 扫描 qrPayload 二维码, 任务会自动加载. "
                + "扫码过程中实时显已扫 / 待扫数量, 完成后一键提交即转入 createReceiveRecord.");

        String message;
        if (taskItems.isEmpty()) {
            message = String.format(
                    "⚠️ 选中 %d 个 PO 均无待收行项目, 无需扫码 (可能已全部到货)", rawIds.size());
        } else if (!invalidPos.isEmpty()) {
            message = String.format(
                    "📲 扫码任务已生成: %d 行待扫 (%d PO 有效, %d 无效). Token 30 min 内有效",
                    taskItems.size(), validPos.size(), invalidPos.size());
        } else {
            message = String.format(
                    "📲 扫码任务已生成: %d PO 共 %d 行待扫. 用 PDA 扫二维码加载任务. Token 30 min 内有效",
                    validPos.size(), taskItems.size());
        }
        return buildSimpleResult(message, data);
    }

    private static String generateToken() {
        // 32 char base36 (UUID without dashes, base16 → encode to base36-ish)
        String raw = UUID.randomUUID().toString().replace("-", "");
        return raw.substring(0, 24);
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
