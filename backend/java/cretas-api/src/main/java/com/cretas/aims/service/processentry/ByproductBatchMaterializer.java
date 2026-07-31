package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.bom.ByproductDeclarationResolver;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把报工录入的副产物化成<b>生产仓</b>里的原料批次 —— 副产链条的接线点。
 *
 * <p>🔴 <b>为什么单独有这个类</b>: {@code ProcessSheetServiceImpl.buildByproductBatch} 早就写好了,
 * 但 2026-08-01 走查发现它<b>唯一的调用方是自己的测试</b> —— 生产代码里没有任何地方调它。
 * 于是报工录的副产只做了字段校验, 不落 {@code material_batches}
 * (prod 实测: 895 条批次里 {@code source_doc_type='BYPRODUCT'} <b>0 条</b>),
 * 整条链在这里断掉: 没有副产批次 → 盘点看不到副产 → 抵扣永远没有输入。
 * 这是本项目第 5 次「建好了没人调」, 本类就是把它接上。</p>
 *
 * <p><b>SKU 从哪来</b>: 报工副产只有自由文本 {@code name}(没有 SKU), 而落库必须要
 * {@code materialTypeId}。这里按名称去匹配 BOM 配方内容第四类的副产声明
 * ({@link ByproductDeclarationResolver}, 那份带 SKU)。</p>
 *
 * <p>🔴 <b>匹配不上就跳过, 不臆造</b>: 没有 SKU 就没法说它进的是哪个物料的库存,
 * 编一个是把账做脏。跳过时留 warn 日志。<b>刻意不报错拦住报工</b> —— 线上既有的副产录入
 * 全是自由文本(prod 15 条报工副产, 名称如「肥油」「料头」), 一旦改成硬拦, 那些流程当场断掉。
 * 先接通「声明过的能落库」, 未声明的维持原状(只记在报工里)。</p>
 */
@Service
@RequiredArgsConstructor
public class ByproductBatchMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ByproductBatchMaterializer.class);

    private final ByproductDeclarationResolver declarationResolver;
    private final WarehouseResolver warehouseResolver;
    private final MaterialBatchRepository materialBatchRepository;

    /**
     * 物化一次报工里的副产。
     *
     * @param factoryId       工厂
     * @param productTypeId   本次报工的成品 SKU（用来找它 BOM 上声明了哪些副产）
     * @param reportId        来源报工 ID；写进批次的 {@code byproductSourceReportId}
     * @param reportedByproducts 报工录入的副产 [{name,quantity,unit,unitPrice}]
     * @return 实际落库的批次；一条都没落时返回空列表（不是 null）
     */
    public List<MaterialBatch> materialize(String factoryId, String productTypeId, Long reportId,
                                           List<Map<String, Object>> reportedByproducts) {
        if (factoryId == null || reportId == null
                || reportedByproducts == null || reportedByproducts.isEmpty()) {
            return List.of();
        }
        // 只有 BOM 来源的声明带 SKU；工序上的历史自由文本声明没有 materialTypeId, 落不了库
        List<Map<String, Object>> declarations =
                declarationResolver.resolve(factoryId, productTypeId, List.of());
        if (declarations.isEmpty()) {
            log.debug("[BYPRODUCT] 该 SKU 没有 BOM 副产声明, 报工副产不落库: productTypeId={}", productTypeId);
            return List.of();
        }
        String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
        if (workshopId == null || workshopId.isBlank()) {
            // 禁降级: 没有生产仓就不要随便找个仓塞进去
            log.warn("[BYPRODUCT] 工厂 {} 未配置生产仓, 副产不落库", factoryId);
            return List.of();
        }

        List<MaterialBatch> created = new ArrayList<>();
        for (Map<String, Object> reported : reportedByproducts) {
            if (reported == null) continue;
            String name = text(reported.get("name"));
            BigDecimal quantity = decimal(reported.get("quantity"));
            String unit = text(reported.get("unit"));
            if (name == null || quantity == null || quantity.signum() <= 0 || unit == null) {
                continue; // 数量为 0 / 缺名缺单位 —— 没有可入库的东西
            }
            Map<String, Object> matched = matchDeclaration(declarations, name);
            String materialTypeId = matched == null ? null : text(matched.get("materialTypeId"));
            if (materialTypeId == null) {
                // 🔴 匹配不上不编 SKU, 也不拦报工 —— 见类注释
                log.warn("[BYPRODUCT] 报工副产「{}」在 SKU {} 的 BOM 里没有对应声明, 不落库(仅记在报工)",
                        name, productTypeId);
                continue;
            }
            created.add(materialBatchRepository.save(ProcessSheetServiceImpl.buildByproductBatch(
                    factoryId, materialTypeId, quantity, unit, workshopId, reportId)));
        }
        if (!created.isEmpty()) {
            log.info("[BYPRODUCT] 报工 {} 落生产仓 {} 条副产批次", reportId, created.size());
        }
        return created;
    }

    /**
     * 报工侧的<b>通用占位名</b>。web-admin 逐工序录入的副产列把名字写死成这个
     * ({@code ProcessDataTable.vue}: {@code byproducts = [{ name: '副产', ... }]}),
     * 操作员没有地方选"这是哪个副产 SKU"。
     */
    private static final String GENERIC_BYPRODUCT_NAME = "副产";

    /**
     * 把一条报工副产对上 BOM 声明。
     *
     * <p>两条判据, 按优先级:</p>
     * <ol>
     *   <li><b>名称精确匹配</b> —— RN 等能填具体名字的入口走这条。</li>
     *   <li><b>通用占位 + 该 SKU 只声明了一条副产</b> → 归它。web-admin 的副产列名字写死成
     *       「副产」, 不给这条路的话它<b>永远匹配不上、永远落不了库</b>(2026-08-01 走查发现)。
     *       只声明一条时归属是<b>确定的</b>, 不存在归错。</li>
     * </ol>
     *
     * <p>🔴 <b>刻意不做的</b>: 报工填了具体名字(如「料头」)却匹配不上时, <b>不</b>因为"只有一条声明"
     * 就算到那条头上 —— 那是把一个明确说了是别的东西的产出记到别人账上。声明 2 条以上时同理:
     * 归属不确定就诚实跳过, 不猜。</p>
     */
    private Map<String, Object> matchDeclaration(List<Map<String, Object>> declarations, String name) {
        for (Map<String, Object> declaration : declarations) {
            if (name.equals(text(declaration.get("name")))) {
                return declaration;
            }
        }
        if (GENERIC_BYPRODUCT_NAME.equals(name) && declarations.size() == 1) {
            return declarations.get(0);
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
