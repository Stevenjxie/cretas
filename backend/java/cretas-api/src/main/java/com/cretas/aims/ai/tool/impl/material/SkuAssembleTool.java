package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.SkuAssemblyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SKU 组装 AI Tool
 *
 * 拼积木创建 SKU: "给汇庭创建墨鱼圈SKU" → 自动匹配模板+客户+配方
 */
@Slf4j
@Component
public class SkuAssembleTool extends AbstractBusinessTool {

    @Autowired private SkuAssemblyService skuAssemblyService;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private CustomerRepository customerRepository;

    @Override
    public String getToolName() { return "sku_assemble"; }

    @Override
    public String getDescription() {
        return "创建SKU（产品+客户+配方组合）。当用户说'给XX客户创建XX产品的SKU'、'新建SKU'、'组装SKU'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "templateId", Map.of("type", "string", "description", "产品模板ID"),
                "templateName", Map.of("type", "string", "description", "产品模板名称(用于模糊匹配)"),
                "customerId", Map.of("type", "string", "description", "客户ID"),
                "customerName", Map.of("type", "string", "description", "客户名称(用于模糊匹配)"),
                "recipeVersion", Map.of("type", "string", "description", "配方版本(默认default)")
            ),
            "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of(); }

    @Override
    public ActionType getActionType() { return ActionType.GENERATE; }

    @Override
    public RiskLevel getRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public Set<String> getDomainTags() { return Set.of("material", "sku", "product"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        // 解析模板
        String templateId = getString(params, "templateId");
        if (templateId == null) {
            String templateName = getString(params, "templateName");
            if (templateName != null) {
                List<ProductType> templates = productTypeRepository.findByFactoryIdAndTemplateIdIsNullAndIsActiveTrue(factoryId);
                Optional<ProductType> match = templates.stream()
                        .filter(t -> t.getName().contains(templateName) || templateName.contains(t.getName()))
                        .findFirst();
                if (match.isPresent()) {
                    templateId = match.get().getId();
                } else {
                    return buildSimpleResult("未找到产品模板: " + templateName,
                        Map.of("availableTemplates", templates.stream().map(ProductType::getName).collect(Collectors.toList())));
                }
            } else {
                return buildSimpleResult("请提供产品模板名称或ID", Map.of());
            }
        }

        // 解析客户
        String customerId = getString(params, "customerId");
        if (customerId == null) {
            String customerName = getString(params, "customerName");
            if (customerName != null) {
                List<Customer> customers = customerRepository.findByFactoryIdAndIsActive(factoryId, true);
                Optional<Customer> match = customers.stream()
                        .filter(c -> c.getName().contains(customerName) || customerName.contains(c.getName()))
                        .findFirst();
                if (match.isPresent()) {
                    customerId = match.get().getId();
                } else {
                    return buildSimpleResult("未找到客户: " + customerName,
                        Map.of("availableCustomers", customers.stream().map(Customer::getName).collect(Collectors.toList())));
                }
            }
            // customerId can be null (optional for some factories)
        }

        String recipeVersion = getString(params, "recipeVersion");

        ProductType sku = skuAssemblyService.assemblesku(
                factoryId, templateId, customerId, recipeVersion, null,
                getLong(context, "userId"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skuId", sku.getId());
        result.put("skuCode", sku.getCode());
        result.put("skuName", sku.getName());
        result.put("templateId", sku.getTemplateId());
        result.put("customerId", sku.getCustomerId());
        result.put("recipeVersion", sku.getRecipeVersion());

        return buildSimpleResult("SKU创建成功: " + sku.getCode(), result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
