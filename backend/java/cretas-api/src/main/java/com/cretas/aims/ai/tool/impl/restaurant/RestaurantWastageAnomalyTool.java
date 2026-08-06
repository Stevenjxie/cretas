package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 损耗异常检测工具 —— 出口，不是规则。
 *
 * <p>判定全部来自 {@code domain="restaurant"} 的 {@link FindingService}。
 *
 * <p>⛔ 2026-08-06 之前本工具读主库 {@code material_batches.findExpiredBatches}。
 * 实测 MOCK_REST 在该表 <b>0 行</b>（餐饮损耗数据在 smartbi 库的
 * {@code fact_restaurant_wastage}，9,458 行 / ¥934,580），于是它恒定返回
 * 「近7天未检测到明显损耗异常，库存管理状态良好」——手里躺着 30 天 ¥894K 的损耗
 * 却告诉店长一切良好。catch 块另返回「功能正在建设中」，同样把失败说成了正常。
 * 主库读取路径已整个删除，不保留。
 *
 * @author Cretas Team
 * @since 2026-03-07（2026-08-06 换成发现层出口）
 */
@Slf4j
@Component
public class RestaurantWastageAnomalyTool extends AbstractBusinessTool {

    /** 发现层的领域名。与两个 provider 的 {@code domain()} 逐字一致。 */
    private static final String DOMAIN = "restaurant";

    @Autowired
    private FindingService findingService;

    @Autowired
    private FindingTextRenderer findingTextRenderer;

    @Override
    public String getToolName() {
        return "restaurant_wastage_anomaly";
    }

    @Override
    public String getDescription() {
        return "损耗异常检测，识别损耗类型集中和食材损耗离群。" +
                "适用场景：异常预警、成本管控、运营问题排查。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        log.info("执行损耗异常检测 - 工厂ID: {}", factoryId);

        // 刻意不 try/catch：规则级失败已由 FindingServiceImpl 隔离并落进
        // failedRules。在这里再兜一层只会把「哪条规则挂了」的信息吃掉，
        // 退化成上一版那句「功能正在建设中」。
        FindingService.Result result = findingService.detectInline(factoryId, DOMAIN);
        String findingsText = findingTextRenderer.renderInline(result);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", result.findings());
        out.put("findingsText", findingsText);
        out.put("checkedRules", result.checkedRules());
        out.put("skippedRules", result.skippedRules());
        out.put("failedRules", result.failedRules());
        out.put("complete", result.complete());
        out.put("message", buildMessage(result, findingsText));

        log.info("损耗异常检测完成 - findings={} checked={} skipped={} failed={}",
                result.findings().size(), result.checkedRules().size(),
                result.skippedRules().size(), result.failedRules().size());
        return out;
    }

    /**
     * findingsText 为空只发生在「一条规则都没跑完且无跳过」。此时**必须**区分
     * 「全挂了」和「没有可用规则」——统一说一句好话就是上一版那个缺陷。
     */
    private String buildMessage(FindingService.Result result, String findingsText) {
        if (!findingsText.isEmpty()) {
            return findingsText;
        }
        if (!result.complete()) {
            return "损耗检查失败：" + String.join(" / ", result.failedRules()) + "，暂无法判断。";
        }
        return "本次没有可用的损耗检查规则。";
    }
}
