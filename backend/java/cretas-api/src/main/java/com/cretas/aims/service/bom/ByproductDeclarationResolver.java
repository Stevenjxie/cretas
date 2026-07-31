package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这道工序/这个产品预计产出哪些副产」的<b>唯一入口</b>。
 *
 * <p>🔴 <b>为什么需要它</b>: 副产的预先声明线上有<b>两处</b>, 键和粒度都不同 ——</p>
 * <table border="1">
 *   <caption>两个声明位</caption>
 *   <tr><th></th><th>{@code work_processes.expected_byproducts}</th><th>BOM 配方内容第四类</th></tr>
 *   <tr><td>键</td><td>自由文本 name</td><td>原料字典 SKU</td></tr>
 *   <tr><td>粒度</td><td>每道工序</td><td>每个 BOM 版本</td></tr>
 *   <tr><td>线上</td><td>4 条(2 条在真实工厂 F006)</td><td>2026-07-31 刚上线</td></tr>
 * </table>
 *
 * <p>两处并存而没有优先级规则, 迟早会各说各话且不报错 —— 本仓 2026-07-31 一天连修五处
 * 「同一件事多套实现」, 不再开第六处。这里定死<b>优先级</b>: BOM 第四类是权威(它有 SKU,
 * 副产因此能被当原料再投入、能落生产仓、能在盘点里被抵扣); 工序上的自由文本声明只是
 * <b>历史兼容回落</b>, 仅在该产品<b>没有任何</b> BOM 副产行时才用。</p>
 *
 * <p>⚠️ <b>刻意不合并两边</b>: 合并会产生「一半有 SKU 一半没有」的行, 下游没法判断哪些能落库。
 * 要么整份走 BOM(有 SKU), 要么整份走历史声明(无 SKU), 用 {@code source} 字段如实标明出处。</p>
 *
 * <p>🔴 本类<b>有真实调用方</b>({@code WorkProcessTaskServiceImpl#toDTO} → 报工任务的
 * OUTPUT 阶段预填), 不是备用入口。本仓已经有过「建好了没人调」的教训
 * ({@code ByproductCreditService} 一度零调用方、{@code expected_byproducts} 声明了没人读)。</p>
 */
@Service
@RequiredArgsConstructor
public class ByproductDeclarationResolver {

    /** 出处标记: 来自 BOM 配方内容第四类(带 SKU)。 */
    public static final String SOURCE_BOM = "BOM";
    /** 出处标记: 来自工序上的历史自由文本声明(无 SKU)。 */
    public static final String SOURCE_LEGACY_PROCESS = "LEGACY_PROCESS";

    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * 解析预期副产声明。
     *
     * @param factoryId          工厂 ID
     * @param productTypeId      成品 SKU；为空时直接回落历史声明（无从查 BOM）
     * @param legacyDeclarations 工序上的历史声明（{@code work_processes.expected_byproducts}）
     * @return 统一形状的声明列表；两边都没有时返回<b>空列表</b>而不是 null
     */
    public List<Map<String, Object>> resolve(
            String factoryId, String productTypeId, List<Map<String, Object>> legacyDeclarations) {
        List<Map<String, Object>> fromBom = fromBom(factoryId, productTypeId);
        if (!fromBom.isEmpty()) {
            return fromBom;
        }
        return legacy(legacyDeclarations);
    }

    private List<Map<String, Object>> fromBom(String factoryId, String productTypeId) {
        if (factoryId == null || productTypeId == null || productTypeId.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BomRecipeItem item : bomRecipeItemRepository.findCurrentByProduct(factoryId, productTypeId)) {
            if (!BomRecipeItem.isByproductCategory(item.getMaterialCategory())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            // 名称取 SKU 档案名; 查不到就留 null —— 不拿 id 冒充名称(禁降级)
            row.put("name", rawMaterialTypeRepository.findById(item.getMaterialTypeId())
                    .map(type -> (Object) type.getName()).orElse(null));
            row.put("unit", item.getUnit());
            row.put("materialTypeId", item.getMaterialTypeId());
            // 预计产出量; BOM 上没填就是 null, 不臆造 0
            row.put("expectedQuantity", item.getStandardQuantity());
            row.put("defaultEnabled", Boolean.TRUE);
            row.put("source", SOURCE_BOM);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> legacy(List<Map<String, Object>> legacyDeclarations) {
        if (legacyDeclarations == null || legacyDeclarations.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(legacyDeclarations.size());
        for (Map<String, Object> declaration : legacyDeclarations) {
            if (declaration == null) continue;
            Map<String, Object> row = new LinkedHashMap<>(declaration);
            // 历史声明没有 SKU —— 如实标 null, 让下游知道这份不能直接落 material_batches
            row.putIfAbsent("materialTypeId", null);
            row.putIfAbsent("expectedQuantity", null);
            row.put("source", SOURCE_LEGACY_PROCESS);
            result.add(row);
        }
        return result;
    }
}
