package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductionPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 生产计划创建工具
 *
 * 通过AI对话创建完整生产计划，支持按名称模糊匹配产品、产线和主管。
 * Intent Code: PRODUCTION_PLAN_CREATE_FULL / PRODUCTION_PLAN_CREATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ProductionPlanCreateTool extends AbstractBusinessTool {

    @Autowired
    private ProductionPlanService productionPlanService;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private com.cretas.aims.service.unit.UnitContractService unitContractService;

    @Autowired(required = false)
    private com.cretas.aims.repository.FactorySettingsRepository factorySettingsRepository;

    private Clock clock = Clock.systemUTC();

    @Autowired(required = false)
    void setClock(Clock clock) {
        if (clock != null) this.clock = clock;
    }

    @Override
    public String getToolName() {
        return "production_plan_create";
    }

    @Override
    public String getDescription() {
        return "创建生产计划。需要提供产品、计划产量、预计完成日期等信息。" +
                "支持通过名称或ID指定产品、产线和主管。" +
                "适用场景：新建生产计划、排产、安排生产任务。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productId = new HashMap<>();
        productId.put("type", "string");
        productId.put("description", "产品名称或ID");
        properties.put("productId", productId);

        Map<String, Object> quantity = new HashMap<>();
        quantity.put("type", "string");
        quantity.put("description", "计划产量，如500kg、100箱");
        properties.put("quantity", quantity);

        properties.put("quantityUnit", Map.of(
                "type", "string",
                "description", "计划数量单位的 canonical code，如 kg、box；quantity 未携带单位时必填"));

        Map<String, Object> expectedDate = new HashMap<>();
        expectedDate.put("type", "string");
        expectedDate.put("description", "预计完成日期，支持 ISO 日期、今天、明天、后天");
        properties.put("expectedDate", expectedDate);

        Map<String, Object> productionLineId = new HashMap<>();
        productionLineId.put("type", "string");
        productionLineId.put("description", "生产线名称或编号");
        properties.put("productionLineId", productionLineId);

        Map<String, Object> estimatedWorkers = new HashMap<>();
        estimatedWorkers.put("type", "integer");
        estimatedWorkers.put("description", "需要工人数");
        properties.put("estimatedWorkers", estimatedWorkers);

        Map<String, Object> supervisorId = new HashMap<>();
        supervisorId.put("type", "string");
        supervisorId.put("description", "负责主管用户名或姓名");
        properties.put("supervisorId", supervisorId);

        Map<String, Object> customerName = new HashMap<>();
        customerName.put("type", "string");
        customerName.put("description", "客户名称（可选）");
        properties.put("customerName", customerName);

        Map<String, Object> processName = new HashMap<>();
        processName.put("type", "string");
        processName.put("description", "工序名称，如分切、包装（可选）");
        properties.put("processName", processName);

        Map<String, Object> batchDate = new HashMap<>();
        batchDate.put("type", "string");
        batchDate.put("description", "批次日期（可选）");
        properties.put("batchDate", batchDate);

        Map<String, Object> priority = new HashMap<>();
        priority.put("type", "integer");
        priority.put("description", "优先级，1-10，默认5");
        properties.put("priority", priority);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productId", "quantity"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productId", "quantity");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String productInput = getString(params, "productId");
        ProductType product = resolveProductType(factoryId, productInput);
        if (product == null) {
            throw new BusinessException(400, "找不到产品: " + productInput);
        }
        ParsedQuantity parsed = parseQuantity(factoryId, getString(params, "quantity"),
                getString(params, "quantityUnit"));
        BigDecimal normalized = normalizeQuantity(factoryId, product, parsed);
        LocalDate plannedDate = parseDate(factoryId, getString(params, "expectedDate"));
        if (plannedDate == null) plannedDate = today(factoryId);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("productId", product.getId());
        preview.put("productName", product.getName());
        preview.put("sourceQuantity", parsed.quantity());
        preview.put("sourceQuantityUnit", parsed.unit());
        preview.put("plannedQuantity", normalized);
        preview.put("plannedQuantityUnit", product.getUnit());
        preview.put("plannedDate", plannedDate);
        preview.put("message", "将创建生产计划；当前仅预览，尚未写入");
        return preview;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "productId" -> "请提供产品名称或ID。";
            case "quantity" -> "请提供计划产量（如500kg、100箱）。";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("创建生产计划 - 工厂ID: {}, 参数: {}", factoryId, params);

        String productId = getString(params, "productId");
        String quantity = getString(params, "quantity");
        String expectedDate = getString(params, "expectedDate");
        String productionLineId = getString(params, "productionLineId");
        Integer estimatedWorkers = getInteger(params, "estimatedWorkers");
        String supervisorId = getString(params, "supervisorId");
        String customerName = getString(params, "customerName");
        String processName = getString(params, "processName");
        Integer priority = getInteger(params, "priority");

        // 解析产品：先尝试按ID查找，再按名称模糊匹配
        ProductType product = resolveProductType(factoryId, productId);
        if (product == null) {
            return Map.of("success", false, "message","找不到产品: " + productId + "。请提供正确的产品名称或ID。");
        }

        ParsedQuantity parsedQuantity = parseQuantity(factoryId, quantity, getString(params, "quantityUnit"));
        BigDecimal plannedQuantity = normalizeQuantity(factoryId, product, parsedQuantity);

        // 解析日期
        LocalDate plannedDate = parseDate(factoryId, expectedDate);
        if (plannedDate == null) {
            plannedDate = today(factoryId);
        }

        // 获取操作人ID
        Long userId = getUserId(context);

        // 构建请求
        CreateProductionPlanRequest request = new CreateProductionPlanRequest();
        request.setProductTypeId(product.getId());
        request.setPlannedQuantity(plannedQuantity);
        request.setPlannedUnit(product.getUnit());
        request.setSourceDisplayQuantity(parsedQuantity.quantity());
        request.setSourceDisplayUnit(parsedQuantity.unit());
        request.setPlannedDate(plannedDate);
        request.setExpectedCompletionDate(plannedDate.plusDays(1));
        if (priority != null) request.setPriority(priority);
        if (estimatedWorkers != null) request.setEstimatedWorkers(estimatedWorkers);
        if (customerName != null) request.setSourceCustomerName(customerName);
        if (processName != null) request.setProcessName(processName);

        // 调用服务创建
        ProductionPlanDTO created = productionPlanService.createProductionPlan(factoryId, request, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("planId", created.getId());
        result.put("planNumber", created.getPlanNumber());
        result.put("productName", created.getProductTypeName());
        result.put("plannedQuantity", created.getPlannedQuantity());
        result.put("plannedQuantityUnit", product.getUnit());
        result.put("plannedDate", created.getPlannedDate());
        result.put("status", created.getStatus());
        result.put("message", String.format("生产计划创建成功！计划编号: %s，产品: %s，计划产量: %s",
                created.getPlanNumber(), created.getProductTypeName(), created.getPlannedQuantity()));

        log.info("生产计划创建完成 - planId={}, planNumber={}", created.getId(), created.getPlanNumber());
        return result;
    }

    /**
     * 解析产品ID：先按ID查找，再按名称模糊匹配
     */
    private ProductType resolveProductType(String factoryId, String productIdOrName) {
        // 先尝试按ID精确查找
        Optional<ProductType> byId = productTypeRepository.findByIdAndFactoryId(productIdOrName, factoryId);
        if (byId.isPresent()) return byId.get();

        // 按编码查找
        Optional<ProductType> byCode = productTypeRepository.findByFactoryIdAndCode(factoryId, productIdOrName);
        if (byCode.isPresent()) return byCode.get();

        // 按名称查找
        Optional<ProductType> byName = productTypeRepository.findByFactoryIdAndName(factoryId, productIdOrName);
        if (byName.isPresent()) return byName.get();

        return null;
    }

    /**
     * 从字符串中提取数字部分，如 "500kg" → 500, "100箱" → 100
     */
    private ParsedQuantity parseQuantity(String factoryId, String quantityStr, String explicitUnit) {
        if (quantityStr == null || quantityStr.isBlank()) {
            throw new IllegalArgumentException("计划产量不能为空");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([^0-9\\s]+)?\\s*$")
                .matcher(quantityStr);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("无法解析产量: " + quantityStr + "；请使用 500kg 或 quantity+quantityUnit");
        }
        try {
            BigDecimal value = new BigDecimal(matcher.group(1));
            String embeddedUnit = matcher.group(2);
            String unit = embeddedUnit != null ? embeddedUnit : explicitUnit;
            if (unit == null || unit.isBlank()) {
                throw new IllegalArgumentException("计划产量必须携带单位，或单独提供 quantityUnit");
            }
            if (embeddedUnit != null && explicitUnit != null && !explicitUnit.isBlank()
                    && !unitContractService.areEquivalent(factoryId, embeddedUnit, explicitUnit)) {
                throw new IllegalArgumentException("quantity 中的单位与 quantityUnit 不一致");
            }
            return new ParsedQuantity(value, unit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析产量: " + quantityStr);
        }
    }

    private BigDecimal normalizeQuantity(String factoryId, ProductType product, ParsedQuantity parsed) {
        if (product.getUnit() == null || product.getUnit().isBlank()) {
            throw new BusinessException(409, "产品未配置计划单位，不能创建生产计划");
        }
        com.cretas.aims.service.unit.UnitConversionResult result = unitContractService.convert(
                parsed.quantity(), new com.cretas.aims.service.unit.UnitConversionContext(
                        factoryId, product.getId(), parsed.unit(), product.getUnit(),
                        java.time.LocalDateTime.now(clock),
                        com.cretas.aims.service.unit.UnitUsageScene.PRODUCTION,
                        6, java.math.RoundingMode.HALF_UP));
        if (!result.succeeded() || result.quantity() == null) {
            throw new BusinessException(400, "计划数量单位无法换算到 SKU 单位: "
                    + parsed.unit() + " → " + product.getUnit());
        }
        return result.quantity();
    }

    /**
     * 解析日期字符串，支持 YYYY-MM-DD 和相对日期（明天、后天）
     */
    private LocalDate parseDate(String factoryId, String dateStr) {
        if (dateStr == null) return null;
        LocalDate today = today(factoryId);
        if (dateStr.contains("明天")) return today.plusDays(1);
        if (dateStr.contains("后天")) return today.plusDays(2);
        if (dateStr.contains("今天")) return today;
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalDate today(String factoryId) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        if (factorySettingsRepository != null) {
            String configured = factorySettingsRepository.findByFactoryId(factoryId)
                    .map(com.cretas.aims.entity.FactorySettings::getTimezone)
                    .orElse(null);
            if (configured != null && !configured.isBlank()) {
                try {
                    zone = ZoneId.of(configured);
                } catch (Exception e) {
                    log.warn("工厂时区无效，使用 Asia/Shanghai: factory={}, timezone={}", factoryId, configured);
                }
            }
        }
        return LocalDate.now(clock.withZone(zone));
    }

    private record ParsedQuantity(BigDecimal quantity, String unit) {
    }
}
