package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.service.bom.BomVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * M-BOM-VER-1 — BomVersion 历史查询 (Sprint 3 Track-H).
 *
 * <p>mode=HISTORY: 全部历史版本 (newest first).
 * mode=CURRENT: 当前生效版本 (status=APPROVED + effective_to IS NULL).
 * mode=AT_DATE: 某历史日期的有效版本 (订单追溯用).
 *
 * 适用场景: 历史版本对比 / 当前生效查询 / 历史订单追溯审计.
 */
@Slf4j
@Component
public class BomVersionHistoryTool extends AbstractBusinessTool {

    @Autowired
    private BomVersionService versionService;

    @Override
    public String getToolName() {
        return "bom_version_history";
    }

    @Override
    public String getDescription() {
        return "查询 BomVersion. mode=HISTORY 取全历史 newest-first, mode=CURRENT 取当前生效, "
             + "mode=AT_DATE 取某历史日期的生效版本 (订单追溯). "
             + "适用场景: 版本对比 / 当前版本查询 / 历史订单的 BOM 追溯审计.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> bomRecipeId = new HashMap<>();
        bomRecipeId.put("type", "string");
        bomRecipeId.put("description", "BOM 配方 ID");

        Map<String, Object> mode = new HashMap<>();
        mode.put("type", "string");
        mode.put("enum", Arrays.asList("HISTORY", "CURRENT", "AT_DATE"));
        mode.put("description", "查询模式 (默认 HISTORY)");

        Map<String, Object> asOfDate = new HashMap<>();
        asOfDate.put("type", "string");
        asOfDate.put("format", "date");
        asOfDate.put("description", "AT_DATE 时必填, YYYY-MM-DD 格式");

        Map<String, Object> properties = new HashMap<>();
        properties.put("bomRecipeId", bomRecipeId);
        properties.put("mode", mode);
        properties.put("asOfDate", asOfDate);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("bomRecipeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("bomRecipeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String bomRecipeId = getString(params, "bomRecipeId");
        String mode = getString(params, "mode");
        if (mode == null || mode.isBlank()) mode = "HISTORY";

        switch (mode.toUpperCase()) {
            case "CURRENT": {
                Optional<BomVersion> cur = versionService.getCurrent(factoryId, bomRecipeId);
                if (cur.isEmpty()) {
                    return buildSimpleResult("无当前生效 BomVersion",
                            Map.of("found", false, "bomRecipeId", bomRecipeId));
                }
                return buildSimpleResult("当前生效 v" + cur.get().getVersionNumber(), cur.get());
            }
            case "AT_DATE": {
                String dateStr = getString(params, "asOfDate");
                if (dateStr == null || dateStr.isBlank()) {
                    throw new IllegalArgumentException("mode=AT_DATE 必须提供 asOfDate");
                }
                LocalDate date = LocalDate.parse(dateStr);
                Optional<BomVersion> opt = versionService.getEffectiveAt(factoryId, bomRecipeId, date);
                if (opt.isEmpty()) {
                    return buildSimpleResult("该日期无生效 BomVersion: " + dateStr,
                            Map.of("found", false, "asOfDate", dateStr));
                }
                return buildSimpleResult("日期 " + dateStr + " 生效版本: v" + opt.get().getVersionNumber(),
                        opt.get());
            }
            case "HISTORY":
            default: {
                List<BomVersion> hist = versionService.getHistory(factoryId, bomRecipeId);
                return buildSimpleResult("共 " + hist.size() + " 个历史版本",
                        Map.of("count", hist.size(), "versions", hist));
            }
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
