package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.BomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BOM 对话式微调工具 —— "对话调偏差"。
 *
 * <p>自然语言改产品某原料的 用量/损耗/单价, 如 "把冷冻猪舌用量改成120" / "猪舌损耗改成90%" /
 * "冷冻猪舌单价改成 12"。返回更新后的整张 BOM 表 (前端可直接渲染成表格)。preview 先展示
 * 旧→新值 (防呆), 确认后才落库。
 *
 * <p>范围: BOM 原料/辅料/包材 (bom_items)。调料配方 (bom_seasoning) 微调需配方 clone 流程, 后续增量。
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
    private BomService bomService;

    @Override
    public String getToolName() {
        return "bom_adjust";
    }

    @Override
    public String getDescription() {
        return "对话式微调产品 BOM 原料: 改某原料的 用量/损耗/单价。例: '把冷冻猪舌用量改成120'、'猪舌损耗改成90%'、" +
                "'冷冻猪舌单价改成12'。返回更新后的 BOM 表。仅 BOM 原辅料/包材; 调料配方微调暂不支持。";
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
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return run(factoryId, params, true);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return run(factoryId, params, false);
    }

    private Map<String, Object> run(String factoryId, Map<String, Object> params, boolean preview) {
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

        // 3. 匹配 BOM 行
        List<BomItem> bom = bomService.getBomItemsByProduct(factoryId, productTypeId);
        List<BomItem> matches = new ArrayList<>();
        for (BomItem b : bom) {
            String mn = b.getMaterialName() == null ? "" : b.getMaterialName();
            if (mn.contains(name) || name.contains(mn)) {
                matches.add(b);
            }
        }
        if (matches.isEmpty()) {
            throw new BusinessException(400, "BOM 里找不到原料 '" + name + "' (注: 调料如卤料包/盐在调料配方里, 暂不支持对话微调)");
        }
        if (matches.size() > 1) {
            throw new BusinessException(400, "原料 '" + name + "' 匹配到多条, 请说更具体的原料名");
        }
        BomItem target = matches.get(0);

        // 4. 旧→新
        BigDecimal oldVal = readField(target, field);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("material", target.getMaterialName());
        change.put("field", fieldWord);
        change.put("oldValue", oldVal);
        change.put("newValue", value);

        if (!preview) {
            applyField(target, field, value);
            bomService.saveBomItem(target);
            log.info("[BOM-ADJUST] factory={} product={} {} {} {}→{}", factoryId, productTypeId,
                    target.getMaterialName(), fieldWord, oldVal, value);
            bom = bomService.getBomItemsByProduct(factoryId, productTypeId);
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

    private BigDecimal readField(BomItem b, String field) {
        switch (field) {
            case "standardQuantity": return b.getStandardQuantity();
            case "yieldRate": return b.getYieldRate();
            case "unitPrice": return b.getUnitPrice();
            default: return null;
        }
    }

    private void applyField(BomItem b, String field, BigDecimal v) {
        switch (field) {
            case "standardQuantity": b.setStandardQuantity(v); break;
            case "yieldRate": b.setYieldRate(v); break;
            case "unitPrice": b.setUnitPrice(v); break;
            default: throw new BusinessException(400, "不支持的字段: " + field);
        }
    }

    /** BOM → 表格行 (preview 时把目标行的指定字段标成 newValue, 让前端高亮)。 */
    private List<Map<String, Object>> bomToTable(List<BomItem> bom, Object previewTargetId, String previewField, BigDecimal previewValue) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BomItem b : bom) {
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
}
