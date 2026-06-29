package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品创建工具 (飞轮衔接) —— "说一句话建产品"。
 *
 * <p>不只建空壳: 通过数据飞轮 ({@code suggestDefaults} 智能填充 + 最相似产品) 给新产品配好<b>大框架</b>:
 * <ul>
 *   <li>属性默认 (类别/单位/克重) 取自飞轮最相似产品;</li>
 *   <li><b>自动继承</b>最相似产品的整条<b>工序链</b> (复用 {@code copyChain} 原子复制) —— 这是"大框架";</li>
 *   <li>把相似产品的 <b>BOM 原料规则 + 调料配方</b> 作为<b>建议</b>返回 (不自动套用, 由用户调用量/损耗细节)。</li>
 * </ul>
 * 用户随后只微调细节。preview 先展示整套计划 (防呆), 确认后才落库。
 */
@Slf4j
@Component
public class ProductCreateTool extends AbstractBusinessTool {

    @Autowired
    private ProductTypeService productTypeService;
    @Autowired
    private ProductTypeRepository productTypeRepository;
    @Autowired
    private ProductWorkProcessService productWorkProcessService;
    @Autowired
    private BomService bomService;
    @Autowired
    private BomRecipeService bomRecipeService;

    @Override
    public String getToolName() {
        return "product_create";
    }

    @Override
    public String getDescription() {
        return "新建产品(SKU)并从数据飞轮继承最相似产品的工序链作为大框架, 同时给出 BOM/调料配置建议供微调。" +
                "适用场景: 用户说'建一个XX产品'/'新增产品XX'。会自动找历史上最像的产品, 把它的工序链复制给新产品, " +
                "并把它的原料BOM/调料配方作为建议返回(不自动套用, 由用户调细节)。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("productName", Map.of("type", "string", "description", "产品名称(必需), 如 '叮咚红烧肉 200g'"));
        props.put("unit", Map.of("type", "string", "description", "计量单位, 如 盒/份/kg; 不填则按飞轮相似产品建议"));
        props.put("productCategory", Map.of("type", "string", "description", "产品类别, 默认 FINISHED_PRODUCT"));
        props.put("specification", Map.of("type", "string", "description", "规格, 如 200g/盒"));
        props.put("customerName", Map.of("type", "string", "description", "关联客户名(可选)"));
        props.put("inheritFrom", Map.of("type", "string", "description", "显式指定继承哪个产品的工序链(可选); 不填则飞轮自动找最相似产品"));
        return Map.of("type", "object", "properties", props, "required", List.of("productName"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("productName");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Plan plan = resolvePlan(factoryId, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "PREVIEW");
        r.put("productName", plan.name);
        r.put("defaults", plan.defaults);
        r.put("inheritFromProduct", plan.sourceName);
        r.put("suggestedProcessChain", plan.processNames);
        r.put("suggestedBom", plan.bomSuggestion);
        r.put("suggestedSeasoning", plan.seasoningSuggestion);
        r.put("message", plan.source == null
                ? "将新建产品「" + plan.name + "」(飞轮未找到可继承工序链的相似产品, 工序需手动配)"
                : "将新建产品「" + plan.name + "」, 大框架沿用最相似产品「" + plan.sourceName + "」的 "
                        + plan.processNames.size() + " 道工序链; BOM/调料作为建议供你调细节");
        return r;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Plan plan = resolvePlan(factoryId, params);

        // 1. 建产品 (属性默认来自飞轮)
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setName(plan.name);
        Object unitDefault = plan.defaults.get("unit");
        dto.setUnit(unitDefault != null ? String.valueOf(unitDefault) : "盒");
        dto.setProductCategory(getString(params, "productCategory", "FINISHED_PRODUCT"));
        dto.setSpecification(getString(params, "specification"));
        Object gpu = plan.defaults.get("gramsPerUnit");
        if (gpu instanceof BigDecimal) {
            dto.setGramsPerUnit((BigDecimal) gpu);
        }
        String customer = getString(params, "customerName");
        if (customer != null) {
            dto.setRelatedCustomer(customer);
        }
        ProductTypeDTO created = productTypeService.createProductType(factoryId, dto);

        // 2. 飞轮继承: 自动复制最相似产品的工序链 (大框架)
        int copiedProcesses = 0;
        if (plan.source != null && !plan.processNames.isEmpty()) {
            try {
                copiedProcesses = productWorkProcessService.copyChain(factoryId, plan.source.getId(), created.getId());
            } catch (Exception e) {
                log.warn("[PRODUCT-CREATE] copyChain 失败 (新产品已建, 工序需手动配): {}", e.getMessage());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productId", created.getId());
        r.put("productName", created.getName());
        r.put("inheritedFromProduct", plan.sourceName);
        r.put("copiedProcessCount", copiedProcesses);
        r.put("suggestedBom", plan.bomSuggestion);
        r.put("suggestedSeasoning", plan.seasoningSuggestion);
        r.put("nextStep", "大框架(工序链)已配好; 请按建议调 BOM 用量/损耗 + 调料配方细节");
        r.put("message", copiedProcesses > 0
                ? "产品「" + created.getName() + "」已建, 已继承「" + plan.sourceName + "」的 " + copiedProcesses
                    + " 道工序链作为大框架; BOM/调料见建议, 请调细节"
                : "产品「" + created.getName() + "」已建 (飞轮无相似工序链可继承, 工序请手动配)");
        return r;
    }

    /** 飞轮解析: 属性默认 + 最相似源产品 + 它的工序链/BOM/调料 (供 preview 与 execute 共用)。 */
    private Plan resolvePlan(String factoryId, Map<String, Object> params) {
        Plan p = new Plan();
        p.name = getString(params, "productName");
        p.defaults = new LinkedHashMap<>();
        p.processNames = new ArrayList<>();
        p.bomSuggestion = new ArrayList<>();
        p.seasoningSuggestion = new ArrayList<>();

        // 飞轮属性建议 (智能填充)
        try {
            ProductTypeSuggestionDTO sug = productTypeService.suggestDefaults(
                    factoryId, p.name, getString(params, "productCategory"));
            if (sug != null) {
                if (sug.getUnit() != null) p.defaults.put("unit", sug.getUnit());
                if (sug.getGramsPerUnit() != null) p.defaults.put("gramsPerUnit", sug.getGramsPerUnit());
                if (sug.getProductCategory() != null) p.defaults.put("productCategory", sug.getProductCategory());
                p.matchedFrom = sug.getBaseProductName() != null ? sug.getBaseProductName() : sug.getMatchedFrom();
            }
        } catch (Exception e) {
            log.warn("[PRODUCT-CREATE] suggestDefaults 失败: {}", e.getMessage());
        }
        // 用户显式 unit 覆盖飞轮建议
        String explicitUnit = getString(params, "unit");
        if (explicitUnit != null) p.defaults.put("unit", explicitUnit);

        // 解析源产品 (inheritFrom 优先, 否则飞轮最相似)
        String sourceName = getString(params, "inheritFrom");
        if (sourceName == null) sourceName = p.matchedFrom;
        if (sourceName != null) {
            p.source = productTypeRepository.findByFactoryIdAndName(factoryId, sourceName).orElse(null);
            if (p.source != null) p.sourceName = p.source.getName();
        }

        // 源的工序链 + BOM + 调料 (建议)
        if (p.source != null) {
            try {
                productWorkProcessService.listByProduct(factoryId, p.source.getId())
                        .forEach(pwp -> p.processNames.add(
                                pwp.getProcessName() != null ? pwp.getProcessName() : pwp.getWorkProcessId()));
            } catch (Exception ignore) { /* 工序链可选 */ }
            try {
                for (BomItem b : bomService.getBomItemsByProduct(factoryId, p.source.getId())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("materialName", b.getMaterialName());
                    row.put("standardQuantity", b.getStandardQuantity());
                    row.put("yieldRate", b.getYieldRate());
                    row.put("materialCategory", b.getMaterialCategory());
                    row.put("unit", b.getUnit());
                    p.bomSuggestion.add(row);
                }
            } catch (Exception ignore) { /* BOM 可选 */ }
            try {
                bomRecipeService.getSeasoningByProduct(factoryId, p.source.getId()).ifPresent(sea -> {
                    if (sea.getSeasoningItems() != null) {
                        sea.getSeasoningItems().forEach(it -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("name", it.getName());
                            row.put("section", it.getSection());
                            row.put("dosagePerKgG", it.getDosagePerKgG());
                            p.seasoningSuggestion.add(row);
                        });
                    }
                });
            } catch (Exception ignore) { /* 调料可选 */ }
        }
        return p;
    }

    /** 飞轮解析结果 (内部传递)。 */
    private static class Plan {
        String name;
        String matchedFrom;
        String sourceName;
        ProductType source;
        Map<String, Object> defaults;
        List<String> processNames;
        List<Map<String, Object>> bomSuggestion;
        List<Map<String, Object>> seasoningSuggestion;
    }
}
