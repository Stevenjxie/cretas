package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BOM 对话式微调工具 —— "对话调偏差"。
 *
 * <p>自然语言仅调整非 RAW 的数量类字段。出成率由正式报工历史统计，价格由物料档案维护，
 * 对话工具不得直接覆盖。返回更新后的整张 BOM 表。preview 先展示
 * 旧→新值 (防呆), 确认后才落库。
 *
 * <p>范围: BOM 原料/辅料/包材与调料均使用版本化 BomRecipe；确认执行时克隆草稿、修改并激活。
 */
@Slf4j
@Component
public class BomAdjustTool extends AbstractBusinessTool {

    /** (把|将)? <物料名> (的)? <字段> (改|设|调)(成|为|到)? <数值>. group1=名, 2=字段, 3=值. */
    private static final Pattern ADJUST = Pattern.compile(
            "(?:把|将)?\\s*(.+?)\\s*(?:的)?\\s*(用量|含量|标准用量|成品含量|损耗|出成率|单价|价格)\\s*(?:改|设|调|变|=)?\\s*(?:成|为|到|至)?\\s*([0-9]+(?:\\.[0-9]+)?)",
            Pattern.UNICODE_CHARACTER_CLASS);

    @Autowired
    private ProductTypeRepository productTypeRepository;
    @Autowired
    private BomRecipeService bomRecipeService;

    @Override
    public String getToolName() {
        return "bom_adjust";
    }

    @Override
    public String getDescription() {
        return "对话式调整 BOM 非原料数量或调料每kg用量。出成率只读，价格请在物料档案维护。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("productTypeId", Map.of("type", "string", "description", "产品 ID (与 productName 二选一)"));
        props.put("productName", Map.of("type", "string", "description", "产品名 (与 productTypeId 二选一)"));
        props.put("instruction", Map.of("type", "string", "description", "微调指令, 如 '把冷冻猪舌用量改成120'"));
        return Map.of("type", "object", "properties", props, "required", List.of("instruction"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("instruction");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.UPDATE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of(
                "production:read_write",
                "rd:read_write",
                "finance:read_write");
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("bom");
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return run(factoryId, params, context, true);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return run(factoryId, params, context, false);
    }

    private Map<String, Object> run(String factoryId, Map<String, Object> params, Map<String, Object> context, boolean preview) {
        String instruction = getString(params, "instruction");
        if (instruction == null || instruction.isBlank()) {
            throw new BusinessException(400, "请给出微调指令, 如 '把冷冻猪舌用量改成120'");
        }
        // 1. 解析产品
        String productTypeId = getString(params, "productTypeId");
        if (productTypeId == null) {
            String productName = getString(params, "productName");
            if (productName == null) {
                throw new BusinessException(400, "请指定产品 (productTypeId 或 productName)");
            }
            ProductType p = productTypeRepository.findByFactoryIdAndName(factoryId, productName)
                    .orElseThrow(() -> new BusinessException(404, "找不到产品: " + productName));
            productTypeId = p.getId();
        }

        // 2. 解析指令
        Matcher m = ADJUST.matcher(instruction);
        if (!m.find()) {
            throw new BusinessException(400,
                    "没看懂微调指令; 支持格式: '把<原料>(用量|损耗|单价)改成<数值>', 如 '把冷冻猪舌用量改成120'");
        }
        String name = m.group(1).trim();
        String fieldWord = m.group(2);
        BigDecimal value = new BigDecimal(m.group(3));
        String field = mapField(fieldWord);
        rejectSystemManagedField(field);

        // 3. 匹配 BOM 行
        BomRecipe currentRecipe = bomRecipeService.getCurrentRecipe(factoryId, productTypeId)
                .map(recipe -> bomRecipeService.getRecipe(factoryId, recipe.getId()))
                .orElseThrow(() -> new BusinessException(400, "该产品尚无已激活 BOM 配方"));
        List<BomRecipeItem> bom = currentRecipe.getItems();
        List<BomRecipeItem> matches = new ArrayList<>();
        for (BomRecipeItem b : bom) {
            String mn = b.getMaterialName() == null ? "" : b.getMaterialName();
            if (mn.contains(name) || name.contains(mn)) {
                matches.add(b);
            }
        }
        if (matches.isEmpty()) {
            // BOM 原料里没有 → 试调料配方 (卤料包/盐 等)
            return adjustSeasoning(factoryId, productTypeId, name, fieldWord, field, value, context, preview);
        }
        if (matches.size() > 1) {
            throw new BusinessException(400, "原料 '" + name + "' 匹配到多条, 请说更具体的原料名");
        }
        BomRecipeItem target = matches.get(0);
        if ("RAW".equalsIgnoreCase(target.getMaterialCategory()) && "standardQuantity".equals(field)) {
            throw new BusinessException(400, "原料 BOM 只建立物料关联，不人工填每成品用量");
        }

        // 4. 旧→新
        BigDecimal oldVal = readField(target, field);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("material", target.getMaterialName());
        change.put("field", fieldWord);
        change.put("oldValue", oldVal);
        change.put("newValue", value);

        if (!preview) {
            BomRecipe draft = bomRecipeService.cloneRecipe(factoryId, currentRecipe.getId());
            BomRecipe draftDetail = bomRecipeService.getRecipe(factoryId, draft.getId());
            BomRecipeItem draftTarget = draftDetail.getItems().stream()
                    .filter(item -> target.getMaterialTypeId().equals(item.getMaterialTypeId()))
                    .filter(item -> java.util.Objects.equals(target.getMaterialCategory(), item.getMaterialCategory()))
                    .filter(item -> java.util.Objects.equals(target.getSortOrder(), item.getSortOrder()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(409, "克隆后的 BOM 明细与原版本不一致，请刷新后重试"));
            CreateBomRecipeRequest.BomRecipeItemDTO dto = toItemDto(draftTarget);
            dto.setStandardQuantity(value);
            bomRecipeService.updateItem(factoryId, draftTarget.getId(), dto);
            BomRecipe activated = bomRecipeService.activateRecipe(factoryId, draft.getId(), null);
            log.info("[BOM-ADJUST] factory={} product={} {} {} {}→{}", factoryId, productTypeId,
                    target.getMaterialName(), fieldWord, oldVal, value);
            bom = bomRecipeService.getRecipe(factoryId, activated.getId()).getItems();
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", preview ? "PREVIEW" : "DONE");
        r.put("change", change);
        r.put("bomTable", bomToTable(bom, preview ? target.getId() : null, preview ? field : null, preview ? value : null));
        r.put("message", preview
                ? "将把「" + target.getMaterialName() + "」的" + fieldWord + "由 " + oldVal + " 改为 " + value
                : "已把「" + target.getMaterialName() + "」的" + fieldWord + "改为 " + value);
        return r;
    }

    private String mapField(String word) {
        switch (word) {
            case "用量": case "含量": case "标准用量": case "成品含量": return "standardQuantity";
            case "损耗": case "出成率": return "yieldRate";
            case "单价": case "价格": return "unitPrice";
            default: throw new BusinessException(400, "不支持的字段: " + word);
        }
    }

    private BigDecimal readField(BomRecipeItem b, String field) {
        switch (field) {
            case "standardQuantity": return b.getStandardQuantity();
            case "yieldRate": return b.getYieldRate();
            case "unitPrice": return b.getUnitPrice();
            default: return null;
        }
    }

    private void rejectSystemManagedField(String field) {
        if ("yieldRate".equals(field)) {
            throw new BusinessException(400, "出成率由同工厂、同 SKU 的正式报工历史自动统计，不允许人工修改");
        }
        if ("unitPrice".equals(field)) {
            throw new BusinessException(400, "BOM 价格从物料档案和入库移动均价继承，请到物料档案维护");
        }
    }

    /** BOM → 表格行 (preview 时把目标行的指定字段标成 newValue, 让前端高亮)。 */
    private List<Map<String, Object>> bomToTable(List<BomRecipeItem> bom, Object previewTargetId, String previewField, BigDecimal previewValue) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomRecipeItem b : bom) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialName", b.getMaterialName());
            row.put("standardQuantity", b.getStandardQuantity());
            row.put("yieldRate", b.getYieldRate());
            row.put("unitPrice", b.getUnitPrice());
            row.put("materialCategory", b.getMaterialCategory());
            row.put("unit", b.getUnit());
            boolean isTarget = previewTargetId != null && previewTargetId.equals(b.getId());
            row.put("_changed", isTarget);
            if (isTarget && previewField != null) {
                row.put(previewField, previewValue); // 预览覆盖显示新值
            }
            rows.add(row);
        }
        return rows;
    }

    private CreateBomRecipeRequest.BomRecipeItemDTO toItemDto(BomRecipeItem item) {
        CreateBomRecipeRequest.BomRecipeItemDTO dto = new CreateBomRecipeRequest.BomRecipeItemDTO();
        dto.setMaterialTypeId(item.getMaterialTypeId());
        dto.setStandardQuantity(item.getStandardQuantity());
        dto.setUnit(item.getUnit());
        dto.setMaterialCategory(item.getMaterialCategory());
        dto.setSortOrder(item.getSortOrder());
        dto.setIsOptional(item.getIsOptional());
        dto.setSubstituteGroup(item.getSubstituteGroup());
        dto.setRemark(item.getRemark());
        dto.setPerPortion(item.getPerPortion());
        dto.setSemiFinishedRefCode(item.getSemiFinishedRefCode());
        dto.setSubProductTypeId(item.getSubProductTypeId());
        dto.setPrimaryCode(item.getPrimaryCode());
        dto.setPrimaryCodeRef(item.getPrimaryCodeRef());
        return dto;
    }

    /**
     * 调料配方微调: 改某调料的 用量(每kg用量 dosagePerKgG) 或 单价(priceSource1)。
     * 调料配方是版本化的: 当前 ACTIVE → clone 出 DRAFT → 全量替换调料(改目标项) → activate(原子换 current)。
     */
    private Map<String, Object> adjustSeasoning(String factoryId, String productTypeId, String name,
                                                String fieldWord, String field, BigDecimal value,
                                                Map<String, Object> context, boolean preview) {
        if ("yieldRate".equals(field)) {
            throw new BusinessException(400, "调料没有损耗字段, 只能改 用量(每kg用量) 或 单价");
        }
        boolean isDosage = "standardQuantity".equals(field); // 用量→dosagePerKgG; 单价→priceSource1
        BomSeasoningResponse sea = bomRecipeService.getSeasoningByProduct(factoryId, productTypeId)
                .orElseThrow(() -> new BusinessException(400, "BOM 原料和调料配方里都找不到 '" + name + "'"));
        List<BomSeasoningItem> items = sea.getSeasoningItems() == null ? new ArrayList<>() : sea.getSeasoningItems();
        List<BomSeasoningItem> matched = new ArrayList<>();
        for (BomSeasoningItem it : items) {
            String n = it.getName() == null ? "" : it.getName();
            if (n.contains(name) || name.contains(n)) {
                matched.add(it);
            }
        }
        if (matched.isEmpty()) {
            throw new BusinessException(400, "BOM 原料和调料配方里都找不到 '" + name + "'");
        }
        if (matched.size() > 1) {
            throw new BusinessException(400, "调料 '" + name + "' 匹配到多条, 请说更具体的调料名");
        }
        BomSeasoningItem target = matched.get(0);
        BigDecimal oldVal = isDosage ? target.getDosagePerKgG() : target.getPriceSource1();

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("material", target.getName());
        change.put("field", fieldWord);
        change.put("oldValue", oldVal);
        change.put("newValue", value);

        if (!preview) {
            // 全量替换请求: 保留 binding 级锅序和 injection-only 配置。
            BomSeasoningSaveRequest req = new BomSeasoningSaveRequest();
            req.setInjectionConfigs(sea.getInjectionConfigs());
            List<BomSeasoningSaveRequest.SeasoningItemDTO> dtos = new ArrayList<>();
            for (BomSeasoningItem it : items) {
                BomSeasoningSaveRequest.SeasoningItemDTO d = new BomSeasoningSaveRequest.SeasoningItemDTO();
                d.setSection(it.getSection());
                d.setWorkProcessId(it.getWorkProcessId());
                d.setMaterialTypeId(it.getMaterialTypeId());
                d.setSeq(it.getSeq());
                d.setName(it.getName());
                boolean isTarget = target.getName().equals(it.getName());
                d.setDosagePerKgG(isTarget && isDosage ? value : it.getDosagePerKgG());
                d.setPriceSource1(isTarget && !isDosage ? value : it.getPriceSource1());
                d.setPriceSource2(it.getPriceSource2());
                d.setCountInSeasoning(it.getCountInSeasoning());
                d.setRemark(it.getRemark());
                d.setSubsequentPotRatio(it.getSubsequentPotRatio());
                dtos.add(d);
            }
            req.setSeasoningItems(dtos);

            // 版本流: ACTIVE → clone DRAFT → saveSeasoning(仅 DRAFT) → activate(原子换 current)
            String recipeId = sea.getBomRecipeId();
            boolean wasActive = sea.getStatus() != null && "ACTIVE".equals(sea.getStatus().name());
            String draftId = recipeId;
            if (wasActive) {
                draftId = bomRecipeService.cloneRecipe(factoryId, recipeId).getId();
            }
            bomRecipeService.saveSeasoning(factoryId, draftId, req);
            if (wasActive) {
                Object uid = context == null ? null : context.get("userId");
                Long userId = uid instanceof Number ? ((Number) uid).longValue() : null;
                bomRecipeService.activateRecipe(factoryId, draftId, userId);
            }
            log.info("[SEASONING-ADJUST] factory={} product={} {} {} {}→{}", factoryId, productTypeId,
                    target.getName(), fieldWord, oldVal, value);
            sea = bomRecipeService.getSeasoningByProduct(factoryId, productTypeId).orElse(sea);
            items = sea.getSeasoningItems() == null ? new ArrayList<>() : sea.getSeasoningItems();
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", preview ? "PREVIEW" : "DONE");
        r.put("change", change);
        r.put("seasoningTable", seasoningToTable(items, preview ? target.getName() : null, isDosage, value));
        r.put("message", preview
                ? "将把调料「" + target.getName() + "」的" + fieldWord + "由 " + oldVal + " 改为 " + value
                : "已把调料「" + target.getName() + "」的" + fieldWord + "改为 " + value);
        return r;
    }

    private List<Map<String, Object>> seasoningToTable(List<BomSeasoningItem> items, String previewTargetName,
                                                       boolean isDosage, BigDecimal previewValue) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomSeasoningItem it : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", it.getName());
            row.put("section", it.getSection());
            boolean isTarget = previewTargetName != null && previewTargetName.equals(it.getName());
            row.put("dosagePerKgG", isTarget && isDosage ? previewValue : it.getDosagePerKgG());
            row.put("priceSource1", isTarget && !isDosage ? previewValue : it.getPriceSource1());
            row.put("_changed", isTarget);
            rows.add(row);
        }
        return rows;
    }
}
