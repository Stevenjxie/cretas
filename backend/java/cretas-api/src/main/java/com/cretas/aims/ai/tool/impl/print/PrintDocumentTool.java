package com.cretas.aims.ai.tool.impl.print;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AIChat — "打印这张单" tool (C-PRT-1).
 *
 * <p>用户对话样例:
 * <ul>
 *   <li>"打印这张采购单 PO-20260514-001" → 触发 print_document(PURCHASE_ORDER, PO-20260514-001)</li>
 *   <li>"下载销售订单 SO-001 的 PDF" → SALES_ORDER</li>
 *   <li>"发给供应商 PO-20260514-001" → PURCHASE_ORDER</li>
 * </ul>
 *
 * <p>Tool 不直接生成 PDF, 仅返回客户端可访问的 download URL —
 * 客户端 (RN / web-admin) 收到 URL 后调 {@code GET} 取 PDF 流并打开/下载.
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-PRT-1)
 */
@Slf4j
@Component
public class PrintDocumentTool extends AbstractBusinessTool {

    /** documentType → endpoint path 段映射. */
    private static final Map<String, String> TYPE_TO_PATH = Map.of(
            "SALES_ORDER", "sales-order",
            "PURCHASE_ORDER", "purchase-order",
            "QUOTATION", "quotation",
            "PRODUCTION_TASK", "production-task",
            "PRODUCTION_WORK_ORDER", "production-work-order",
            "MATERIAL_REQUISITION", "material-requisition"
    );

    private static final Map<String, String> TYPE_TO_LABEL = Map.of(
            "SALES_ORDER", "销售订单",
            "PURCHASE_ORDER", "采购订单",
            "QUOTATION", "报价单",
            "PRODUCTION_TASK", "生产任务单",
            "PRODUCTION_WORK_ORDER", "生产工单",
            "MATERIAL_REQUISITION", "领料单"
    );

    @Override
    public String getToolName() {
        return "print_document";
    }

    @Override
    public String getDescription() {
        return "打印单据 PDF (销售订单 / 采购订单 / 报价单 / 生产任务单 / 领料单). " +
               "用户说'打印这张单' / '下载 PDF' / '发给供应商' / '打印采购单 PO-XXX' 时触发. " +
               "返回前端可访问的 download URL, 客户端打开 URL 即下载 PDF.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> docType = new HashMap<>();
        docType.put("type", "string");
        docType.put("enum", new ArrayList<>(TYPE_TO_PATH.keySet()));
        docType.put("description", "单据类型: SALES_ORDER / PURCHASE_ORDER / QUOTATION / PRODUCTION_TASK / PRODUCTION_WORK_ORDER / MATERIAL_REQUISITION");

        Map<String, Object> docId = new HashMap<>();
        docId.put("type", "string");
        docId.put("description", "单据 ID 或编号 (如 PO-20260514-001 / SO-001 / Q-20260515-002)");

        Map<String, Object> properties = new HashMap<>();
        properties.put("documentType", docType);
        properties.put("documentId", docId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("documentType", "documentId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("documentType", "documentId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("documentType".equals(paramName)) {
            return "请问要打印哪种单据？销售订单 / 采购订单 / 报价单 / 生产任务单 / 领料单。";
        }
        if ("documentId".equals(paramName)) {
            return "请提供单据编号 (例如 PO-20260514-001)。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("documentType".equals(paramName)) return "单据类型";
        if ("documentId".equals(paramName)) return "单据编号";
        return super.getParameterDisplayName(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) {
        String type = getString(params, "documentType");
        String id = getString(params, "documentId");

        if (type == null || !TYPE_TO_PATH.containsKey(type)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "未知 documentType: " + type);
            err.put("supportedTypes", new ArrayList<>(TYPE_TO_PATH.keySet()));
            return err;
        }
        if (id == null || id.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "documentId 缺失");
            return err;
        }

        String pathSegment = TYPE_TO_PATH.get(type);
        String label = TYPE_TO_LABEL.get(type);
        String downloadUrl = String.format("/api/mobile/%s/print/%s/%s", factoryId, pathSegment, id);

        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("%s PDF 生成中, 即将下载 (%s)", label, id));
        result.put("downloadUrl", downloadUrl);
        result.put("documentType", type);
        result.put("documentId", id);
        result.put("nextSteps", List.of(
                "客户端打开 downloadUrl 自动下载 PDF",
                "如需修改单据内容, 请回到对应模块编辑后再次打印"
        ));
        log.info("PrintDocumentTool: factory={} type={} id={} → {}", factoryId, type, id, downloadUrl);
        return result;
    }
}
