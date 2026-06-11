package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * #731 SP12: Print Template Editor — Entity Field Metadata Endpoint.
 *
 * <p>Provides per-entityType field schemas consumed by
 * {@code EntityFieldTree.vue} in the print-template-editor. Returns
 * a {@code FieldNode[]} describing the available template variables
 * (label, dotted path, type) for drag-and-drop binding.
 *
 * <p>Before this endpoint existed, {@code EntityFieldTree.vue} always got
 * a 404 and fell back to the hardcoded {@code FIELD_SCHEMA_FALLBACK} constant.
 * This endpoint mirrors that fallback — making the schema dynamic so future
 * fields are picked up without a frontend deploy.
 *
 * <p>Auth: this endpoint is intentionally <b>unauthenticated at the permission
 * layer</b> — the factoryId path-variable is already validated by
 * {@code JwtAuthInterceptor}, and field names are non-sensitive. Adding
 * a @RequirePermission would block designers from using the editor.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/entity-metadata")
@Tag(name = "EntityMetadata", description = "打印模板字段元数据 (EntityFieldTree.vue 使用)")
public class EntityMetadataController {

    /**
     * Return the field schema for a given print entity type.
     *
     * @param factoryId  tenant isolation (validated by JwtAuthInterceptor)
     * @param entityType one of PRINT_SALES_ORDER / PRINT_PURCHASE_ORDER /
     *                   PRINT_QUOTATION / PRINT_PRODUCTION_TASK /
     *                   PRINT_MATERIAL_REQUISITION / PRINT_WEIGHING_SLIP
     */
    @GetMapping("/{entityType}")
    @Operation(summary = "获取打印模板实体字段结构")
    public ApiResponse<List<Map<String, Object>>> getEntityFields(
            @PathVariable String factoryId,
            @PathVariable String entityType) {

        List<Map<String, Object>> schema = switch (entityType) {
            case "PRINT_SALES_ORDER"          -> salesOrderSchema();
            case "PRINT_PURCHASE_ORDER"       -> purchaseOrderSchema();
            case "PRINT_QUOTATION"            -> quotationSchema();
            case "PRINT_PRODUCTION_TASK"      -> productionTaskSchema();
            case "PRINT_MATERIAL_REQUISITION" -> materialRequisitionSchema();
            case "PRINT_WEIGHING_SLIP"        -> weighingSlipSchema();
            default -> {
                log.debug("[EntityMetadata] unknown entityType={} factoryId={}", entityType, factoryId);
                yield List.of();
            }
        };

        return ApiResponse.success(schema);
    }

    // ========================= Schema Definitions =========================
    // Mirror the FIELD_SCHEMA_FALLBACK in EntityFieldTree.vue (SP12 stub).
    // Future: load from database / reflection to stay in sync with actual DTO.

    private List<Map<String, Object>> salesOrderSchema() {
        return List.of(
            field("工厂名", "factoryName", "string"),
            field("订单号", "order.orderNumber", "string"),
            field("订单日期", "order.orderDate", "date"),
            field("客户名", "order.customerName", "string"),
            field("销售员", "order.salesperson", "string"),
            field("总金额", "order.totalAmount", "currency"),
            field("备注", "order.remark", "string"),
            arrayField("明细行", "order.items", List.of(
                field("物料名", "item.materialName", "string"),
                field("数量", "item.quantity", "number"),
                field("单位", "item.unit", "string"),
                field("单价", "item.unitPrice", "currency"),
                field("小计", "item.subtotal", "currency")
            ))
        );
    }

    private List<Map<String, Object>> purchaseOrderSchema() {
        return List.of(
            field("工厂名", "factoryName", "string"),
            field("采购单号", "order.orderNumber", "string"),
            field("采购日期", "order.orderDate", "date"),
            field("供应商", "order.supplierName", "string"),
            field("期望到货", "order.expectedDeliveryDate", "date"),
            field("总金额", "order.totalAmount", "currency"),
            field("二维码 payload", "order.qrPayload", "string"),
            arrayField("明细行", "order.items", List.of(
                field("物料名", "item.materialName", "string"),
                field("规格", "item.spec", "string"),
                field("数量", "item.quantity", "number"),
                field("单位", "item.unit", "string"),
                field("单价", "item.unitPrice", "currency")
            ))
        );
    }

    private List<Map<String, Object>> quotationSchema() {
        return List.of(
            field("报价编号", "quotation.quotationNumber", "string"),
            field("报价日期", "quotation.quotationDate", "date"),
            field("客户", "quotation.customerName", "string"),
            field("有效期", "quotation.validUntil", "date"),
            field("销售员", "quotation.salesperson", "string"),
            field("总金额", "quotation.totalAmount", "currency"),
            arrayField("明细行", "quotation.items", List.of())
        );
    }

    private List<Map<String, Object>> productionTaskSchema() {
        return List.of(
            field("任务号", "task.taskNumber", "string"),
            field("产品", "task.productName", "string"),
            field("计划数量", "task.plannedQuantity", "number"),
            field("单位", "task.unit", "string"),
            field("开始日期", "task.startDate", "date"),
            field("结束日期", "task.endDate", "date"),
            field("车间", "task.workshopName", "string"),
            field("负责人", "task.supervisor", "string"),
            arrayField("工序", "task.processes", List.of())
        );
    }

    private List<Map<String, Object>> materialRequisitionSchema() {
        return List.of(
            field("领料单号", "requisition.requisitionNumber", "string"),
            field("产品名", "requisition.productName", "string"),
            field("车间", "requisition.workshop", "string"),
            field("领料人", "requisition.requester", "string"),
            field("申请日期", "requisition.requestDate", "date"),
            arrayField("明细行", "requisition.items", List.of())
        );
    }

    private List<Map<String, Object>> weighingSlipSchema() {
        return List.of(
            field("称重单号", "slip.slipNumber", "string"),
            field("产品名", "slip.productName", "string"),
            field("客户/供应商", "slip.partnerName", "string"),
            field("过磅日期", "slip.weighDate", "date"),
            field("操作员", "slip.operator", "string"),
            field("总毛重", "slip.grossWeight", "qty"),
            field("总皮重", "slip.tareWeight", "qty"),
            field("总净重", "slip.netWeight", "qty"),
            arrayField("过磅明细", "slip.items", List.of(
                field("箱号", "item.boxNo", "string"),
                field("毛重", "item.grossWeight", "qty"),
                field("皮重", "item.tareWeight", "qty"),
                field("净重", "item.netWeight", "qty")
            ))
        );
    }

    // ========================= Builder Helpers =========================

    private Map<String, Object> field(String label, String path, String type) {
        return Map.of("label", label, "path", path, "type", type);
    }

    private Map<String, Object> arrayField(String label, String path,
                                            List<Map<String, Object>> children) {
        return Map.of("label", label, "path", path, "type", "array",
                      "children", children);
    }
}
