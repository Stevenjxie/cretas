package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 在制品 (WIP) 物料批次查询工具
 *
 * <p>查询状态为 {@code PRODUCING_RESERVED} 的物料批次 — 即已被进行中的生产批次
 * (ProductionBatch.status = IN_PROGRESS) 占用的原材料批次。封装 PR #732 (Sprint 4
 * Wave 2 M-WIP-1) 中新增的 {@link MaterialBatchService#getWipBatches(String)} 方法,
 * 通过 AIChat 暴露给业务用户。</p>
 *
 * <p><b>防呆设计</b>: 遵循 fool-proof Rule 2 (context-rich) — 返回结果包含
 * batchNumber / materialName / materialCode / currentQuantity / reservedQuantity /
 * supplierName / receiptDate 等关键身份字段, 用户可一眼定位是哪个批次被哪个生产任务
 * 占用。</p>
 *
 * <p>只读 (READ), 无敏感性 (LOW), 无必需参数 — 默认查询当前工厂全部 WIP 批次,
 * 可选过滤 materialTypeId。</p>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-17
 */
@Slf4j
@Component
public class MaterialBatchWipQueryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_wip_query";
    }

    @Override
    public String getDescription() {
        return "查询在制品 (WIP) 物料批次 — 当前被生产任务占用 (PRODUCING_RESERVED 状态) 的原材料批次。" +
               "用户问 '哪些原料正在生产中?' / '在制品库存' / 'WIP 批次' / '什么物料被生产占用' / " +
               "'查在制品' 时调用。返回批次号、物料名、剩余/预留数量、供应商、入库日期。" +
               "可选按 materialTypeId 过滤特定物料类型的 WIP 批次。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // materialTypeId: 可选物料类型过滤
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "可选 — 按物料类型 ID 过滤 (例如只看 '猪肉' 的在制品批次)");
        properties.put("materialTypeId", materialTypeId);

        schema.put("properties", properties);
        // 查询类 Tool 无必需参数 — 默认列出当前工厂全部 WIP 批次
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String materialTypeId = getString(params, "materialTypeId");

        log.info("查询在制品 (WIP) 批次: factoryId={}, materialTypeId={}", factoryId, materialTypeId);

        // 1. 调 Service 获取本工厂 WIP 批次列表
        List<MaterialBatchDTO> batches = materialBatchService.getWipBatches(factoryId);

        // 2. 可选过滤: materialTypeId
        if (materialTypeId != null && !materialTypeId.trim().isEmpty()) {
            final String filterId = materialTypeId.trim();
            batches = batches.stream()
                    .filter(b -> filterId.equals(b.getMaterialTypeId()))
                    .collect(Collectors.toList());
        }

        // 3. 构建结果 (fool-proof R2: context-rich, 让用户一眼看到 batch 身份)
        Map<String, Object> result = new HashMap<>();
        result.put("batches", batches);
        result.put("total", batches.size());
        result.put("factoryId", factoryId);
        if (materialTypeId != null && !materialTypeId.trim().isEmpty()) {
            result.put("materialTypeIdFilter", materialTypeId.trim());
        }

        // 4. 友好消息
        if (batches.isEmpty()) {
            if (materialTypeId != null && !materialTypeId.trim().isEmpty()) {
                result.put("message", String.format("当前工厂 %s 没有物料类型 %s 的在制品批次", factoryId, materialTypeId));
            } else {
                result.put("message", String.format("当前工厂 %s 没有在制品批次 (无 PRODUCING_RESERVED 状态批次)", factoryId));
            }
        } else {
            result.put("message", String.format(
                "当前工厂 %s 共 %d 个在制品批次%s",
                factoryId,
                batches.size(),
                (materialTypeId != null && !materialTypeId.trim().isEmpty())
                    ? " (物料类型=" + materialTypeId + ")"
                    : ""
            ));
        }

        log.info("在制品查询完成: factoryId={}, count={}", factoryId, batches.size());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
