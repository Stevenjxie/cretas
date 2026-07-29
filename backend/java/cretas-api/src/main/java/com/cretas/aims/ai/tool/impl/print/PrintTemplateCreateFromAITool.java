package com.cretas.aims.ai.tool.impl.print;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FormTemplate;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.FormTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AIChat — "帮我设计一个 X 单 PDF 模板" tool (C-PRT-EDITOR-1, Sprint 3 Track-J).
 *
 * <p>Generates a schema-driven print template via LLM, saves it through
 * {@link FormTemplateService#createFromAI}, returns the new template id so
 * the client can deep-link into PrintTemplateEditor.vue for further tweaks.
 *
 * <p>Example dialogue:
 * <pre>
 *   user: "帮我设计一个销售单 PDF, 顶部 logo + 订单号大字体 + 客户信息 + 物料 table + 右下角二维码"
 *   AI   → invokes print_template_create_from_ai with:
 *            entityType    = PRINT_SALES_ORDER
 *            designPrompt  = the verbatim user description
 *          tool generates schema, saves, returns:
 *            { templateId, name, version, editorUrl }
 * </pre>
 *
 * <p>Output schema mirrors the editor's wire format (Formily-wrapped with
 * {@code properties._printSchema}) so PrintTemplateEditor.vue loads the
 * AI-generated template byte-for-byte the same as a hand-built one.
 *
 * @author Cretas Team — Sprint 3 Track-J
 * @since 2026-05-16 (C-PRT-EDITOR-1 Day 7)
 */
@Slf4j
@Component
public class PrintTemplateCreateFromAITool extends AbstractBusinessTool {

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private FormTemplateService formTemplateService;

    /** entityType → 中文显示名 mapping (must mirror printSchemaTypes.ts ENTITY_TYPES). */
    private static final Map<String, String> ENTITY_LABELS = Map.of(
            "PRINT_SALES_ORDER", "销售单",
            "PRINT_PURCHASE_ORDER", "采购单",
            "PRINT_QUOTATION", "报价单",
            "PRINT_PRODUCTION_TASK", "生产任务单",
            "PRINT_MATERIAL_REQUISITION", "领料单",
            "PRINT_WEIGHING_SLIP", "称重单"
    );

    /** Per-entityType hint about available bindings — sourced from EntityFieldTree.vue stub. */
    private static final Map<String, String> ENTITY_FIELDS_HINT = Map.of(
            "PRINT_SALES_ORDER",
            "可用字段: {{factoryName}}, {{order.orderNumber}}, {{order.orderDate}}, "
                    + "{{order.customerName}}, {{order.salesperson}}, {{order.totalAmount}}, "
                    + "{{order.remark}}, table binding {{order.items}} 包含 item.materialName / "
                    + "item.quantity / item.unit / item.unitPrice / item.subtotal",

            "PRINT_PURCHASE_ORDER",
            "可用字段: {{factoryName}}, {{order.orderNumber}}, {{order.orderDate}}, "
                    + "{{order.supplierName}}, {{order.expectedDeliveryDate}}, {{order.totalAmount}}, "
                    + "{{order.qrPayload}} (二维码内容, 仓管员扫码入库), table binding "
                    + "{{order.items}} 包含 item.materialName / item.spec / item.quantity / "
                    + "item.unit / item.unitPrice",

            "PRINT_QUOTATION",
            "可用字段: {{factoryName}}, {{quotation.quotationNumber}}, {{quotation.quotationDate}}, "
                    + "{{quotation.customerName}}, {{quotation.validUntil}}, {{quotation.salesperson}}, "
                    + "{{quotation.totalAmount}}, table binding {{quotation.items}}",

            "PRINT_PRODUCTION_TASK",
            "可用字段: {{factoryName}}, {{task.taskNumber}}, {{task.productName}}, "
                    + "{{task.plannedQuantity}}, {{task.unit}}, {{task.startDate}}, {{task.endDate}}, "
                    + "{{task.workshopName}}, {{task.supervisor}}, table binding {{task.processes}}. "
                    + "注: 生产任务单不含金额字段",

            "PRINT_MATERIAL_REQUISITION",
            "可用字段: {{factoryName}}, {{requisition.requisitionNumber}}, "
                    + "{{requisition.productName}}, {{requisition.workshop}}, {{requisition.requester}}, "
                    + "{{requisition.requestDate}}, table binding {{requisition.items}}. "
                    + "注: 领料单不含金额字段",

            "PRINT_WEIGHING_SLIP",
            "可用字段: {{factoryName}}, {{slip.slipNumber}}, {{slip.productName}}, "
                    + "{{slip.partnerName}}, {{slip.weighDate}}, {{slip.operator}}, "
                    + "{{slip.grossWeight}}, {{slip.tareWeight}}, {{slip.netWeight}}, table binding "
                    + "{{slip.items}} 包含 item.boxNo / item.grossWeight / item.tareWeight / "
                    + "item.netWeight. 注: F006 食品行业刚需, 表格行数动态"
    );

    @Override
    public String getToolName() {
        return "print_template_create_from_ai";
    }

    @Override
    public String getDescription() {
        return "AI 生成打印模板 — 用户说 '帮我设计 X 单 PDF 模板' / "
                + "'生成一个销售单打印模板' / '设计领料单 PDF 含 logo + 表格 + 二维码' 时触发. "
                + "输入: entityType (PRINT_SALES_ORDER / PRINT_PURCHASE_ORDER / PRINT_QUOTATION / "
                + "PRINT_PRODUCTION_TASK / PRINT_MATERIAL_REQUISITION / PRINT_WEIGHING_SLIP) + "
                + "用户对模板的设计描述. AI 生成 schema → 保存到 FormTemplate → "
                + "返回 templateId 供 PrintTemplateEditor 继续编辑.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> entityType = new HashMap<>();
        entityType.put("type", "string");
        entityType.put("enum", new ArrayList<>(ENTITY_LABELS.keySet()));
        entityType.put("description",
                "单据类型: PRINT_SALES_ORDER / PRINT_PURCHASE_ORDER / PRINT_QUOTATION / "
                        + "PRINT_PRODUCTION_TASK / PRINT_MATERIAL_REQUISITION / PRINT_WEIGHING_SLIP");

        Map<String, Object> designPrompt = new HashMap<>();
        designPrompt.put("type", "string");
        designPrompt.put("description",
                "用户对打印模板的设计需求, 例如 '顶部 logo + 订单号大字体 + 客户信息 + 物料表格 + 右下角二维码'");

        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "模板名称 (可选, 默认 '<单据中文名>-AI生成')");

        Map<String, Object> properties = new HashMap<>();
        properties.put("entityType", entityType);
        properties.put("designPrompt", designPrompt);
        properties.put("name", name);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("entityType", "designPrompt"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("entityType", "designPrompt");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("entityType".equals(paramName)) {
            return "请问要为哪种单据生成打印模板？销售单 / 采购单 / 报价单 / 生产任务单 / 领料单 / 称重单。";
        }
        if ("designPrompt".equals(paramName)) {
            return "请描述您希望的模板布局 (例如：'顶部 logo + 订单号大字体 + 客户信息 + 物料表格 + 右下角二维码')。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        return switch (paramName) {
            case "entityType" -> "单据类型";
            case "designPrompt" -> "设计需求";
            case "name" -> "模板名称";
            default -> super.getParameterDisplayName(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String entityType = getString(params, "entityType");
        String designPrompt = getString(params, "designPrompt");
        String suppliedName = getString(params, "name");
        Long userId = getUserId(context);

        if (entityType == null || !ENTITY_LABELS.containsKey(entityType)) {
            throw new BusinessException(400, "未知 entityType: " + entityType +
                    ", 支持的类型: " + String.join(", ", ENTITY_LABELS.keySet()));
        }
        if (designPrompt == null || designPrompt.isBlank()) {
            throw new BusinessException(400, "designPrompt 不能为空");
        }

        String entityLabel = ENTITY_LABELS.get(entityType);
        String templateName = (suppliedName != null && !suppliedName.isBlank())
                ? suppliedName
                : entityLabel + "-AI生成";

        log.info("PrintTemplateCreateFromAITool: factory={} entityType={} promptLen={}",
                factoryId, entityType, designPrompt.length());

        // 1. Build system prompt — strict about output shape
        String systemPrompt = buildSystemPrompt(entityType, entityLabel);

        // 2. Call LLM (low-temp for deterministic JSON)
        String llmRaw = dashScopeClient.chatLowTemp(systemPrompt, designPrompt);

        // 3. Parse + validate the generated schema
        Map<String, Object> printSchema;
        try {
            printSchema = parseGeneratedSchema(llmRaw);
            validatePrintSchema(printSchema);
        } catch (Exception e) {
            log.warn("AI schema parse/validate failed; falling back to minimal template. err={}",
                    e.getMessage());
            printSchema = buildFallbackSchema(entityLabel);
        }

        // 4. Wrap in Formily envelope (mirror frontend wrapForStorage)
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("type", "object");
        wrapped.put("properties", Map.of("_printSchema", printSchema));
        String schemaJson = objectMapper.writeValueAsString(wrapped);

        // 5. Persist via FormTemplateService.createFromAI (source = AI_ASSISTANT)
        FormTemplate saved = formTemplateService.createFromAI(
                factoryId, entityType, templateName, schemaJson,
                "AI 生成: " + truncate(designPrompt, 200), userId);

        // 6. Compose user-visible result
        Map<String, Object> result = new HashMap<>();
        result.put("message", String.format("已生成 %s 打印模板「%s」(v%d), 可在打印模板设计器继续调整",
                entityLabel, templateName, saved.getVersion()));
        result.put("templateId", saved.getId());
        result.put("templateName", saved.getName());
        result.put("entityType", entityType);
        result.put("version", saved.getVersion());
        result.put("elementCount", elementCount(printSchema));
        result.put("editorUrl", "/print-template-editor?entityType=" + entityType);
        result.put("nextSteps", List.of(
                "打开 editorUrl 在 PrintTemplateEditor 中查看 / 调整模板",
                "点击 '预览 PDF' 查看渲染效果",
                "如不满意可重新让 AI 生成或手动改"
        ));
        return result;
    }

    // ================================================================
    //  Prompt + parser helpers
    // ================================================================

    private String buildSystemPrompt(String entityType, String entityLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 PDF 打印模板设计师。请根据用户对 ").append(entityLabel)
                .append(" 模板的需求, 生成一份 schema-driven 打印模板的 JSON.\n\n");

        sb.append("画布: A4 portrait, 595 x 842 pt, 坐标原点左上角 (Y 向下增).\n");
        sb.append("元素类型: text / field / table / qr / barcode / image / stamp\n\n");

        sb.append("各元素 schema:\n");
        sb.append("- text:    {type:\"text\", x, y, text, fontSize, bold?, color?, align?:\"left|center|right\"}\n");
        sb.append("- field:   {type:\"field\", x, y, binding:\"{{path}}\", fontSize, bold?, color?, align?, format?:\"currency|date|qty|percent\"}\n");
        sb.append("- table:   {type:\"table\", x, y, width, binding:\"{{arrayPath}}\", rowHeight, headerBg?:\"#hex\", columns:[{header, binding:\"{{item.field}}\", width, align?, format?}]}\n");
        sb.append("- qr:      {type:\"qr\", x, y, size, content:\"{{path}} 或 静态\"}\n");
        sb.append("- barcode: {type:\"barcode\", x, y, width, height, content, format?:\"CODE128|EAN13\"}\n");
        sb.append("- image:   {type:\"image\", x, y, width, height, src:\"data:image/... 或 url\"}\n");
        sb.append("- stamp:   {type:\"stamp\", x, y, size, stampId:\"default\", opacity?}\n\n");

        sb.append("可用字段绑定 (用 {{path}} 语法):\n");
        sb.append(ENTITY_FIELDS_HINT.getOrDefault(entityType, "")).append("\n\n");

        sb.append("格式化助手:\n");
        sb.append("- {{format.currency(field)}} → ¥1,234.50\n");
        sb.append("- {{format.date(field, 'YYYY-MM-DD')}}\n");
        sb.append("- {{format.qty(field)}} (整数省小数点)\n");
        sb.append("- {{format.percent(field)}}\n\n");

        sb.append("布局参考: 顶部 50-150pt 放 logo + 标题 (大字体, 加粗), 150-250pt 放单据基本信息, ");
        sb.append("250pt 以下放主表格, 700pt 以下放印章 / 二维码 / 签名行. ");
        sb.append("文本字号 10-12, 标题 18-22.\n\n");

        sb.append("请输出**严格的 JSON**:\n");
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"canvas\": {\"width\": 595, \"height\": 842, \"orientation\": \"portrait\"},\n");
        sb.append("  \"elements\": [...]\n");
        sb.append("}\n\n");

        sb.append("要求:\n");
        sb.append("1. 只输出 JSON 对象 (无 ```json 围栏, 无解释文字)\n");
        sb.append("2. 至少包含 1 个 text (标题) + 几个 field (核心信息) + 1 个 table (明细) + 1 个 stamp 或 qr\n");
        sb.append("3. x/y/width 等都是数字, 元素之间不要重叠\n");
        sb.append("4. binding 必须从上面的可用字段列表中选, 不要编造\n");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeneratedSchema(String llmRaw) throws Exception {
        String cleaned = llmRaw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        // Some LLMs prepend a single line like "Here is the JSON:" — strip until '{'.
        int firstBrace = cleaned.indexOf('{');
        if (firstBrace > 0) {
            cleaned = cleaned.substring(firstBrace);
        }
        return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
    }

    private void validatePrintSchema(Map<String, Object> schema) {
        if (schema == null) {
            throw new IllegalArgumentException("schema is null");
        }
        Object elements = schema.get("elements");
        if (!(elements instanceof List<?>)) {
            throw new IllegalArgumentException("schema.elements must be an array");
        }
        if (((List<?>) elements).isEmpty()) {
            throw new IllegalArgumentException("schema.elements must not be empty");
        }
        // canvas may be missing → defaults applied client-side, but we fill in
        // sensible defaults here so the stored row is self-contained.
        if (!(schema.get("canvas") instanceof Map<?, ?>)) {
            schema.put("canvas", Map.of(
                    "width", 595, "height", 842, "orientation", "portrait"));
        }
        schema.putIfAbsent("version", 1);

        // Validate each element has a known type — drop bad ones rather than throw,
        // so a partial LLM hallucination doesn't kill the whole template.
        Set<String> validTypes = Set.of("text", "field", "table", "qr", "barcode", "image", "stamp");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> els = (List<Map<String, Object>>) elements;
        els.removeIf(el -> {
            Object t = el.get("type");
            boolean bad = !(t instanceof String) || !validTypes.contains((String) t);
            if (bad) {
                log.warn("dropping AI-generated element with unknown type: {}", t);
            }
            return bad;
        });
        if (els.isEmpty()) {
            throw new IllegalArgumentException("schema.elements all invalid after type filter");
        }
    }

    /** Last-resort schema when the LLM output is unusable. Keeps the user moving. */
    private Map<String, Object> buildFallbackSchema(String entityLabel) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("version", 1);
        schema.put("canvas", Map.of("width", 595, "height", 842, "orientation", "portrait"));
        schema.put("elements", List.of(
                Map.of("id", "el_title", "type", "text", "x", 200, "y", 60,
                        "text", entityLabel, "fontSize", 22, "bold", true,
                        "align", "center", "width", 200),
                Map.of("id", "el_note", "type", "text", "x", 50, "y", 110,
                        "text", "AI 生成 fallback 模板 — 请在编辑器中完善", "fontSize", 11,
                        "color", "#9ca3af")
        ));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private int elementCount(Map<String, Object> schema) {
        Object els = schema.get("elements");
        return (els instanceof List<?>) ? ((List<Object>) els).size() : 0;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
