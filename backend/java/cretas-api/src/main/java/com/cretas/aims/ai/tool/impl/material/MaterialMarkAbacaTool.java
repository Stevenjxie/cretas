package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * material_mark_abaca — 标记原料类型为抄码品 (W-ABA-1).
 *
 * <p>典型触发: "把牛肉标记为抄码品" / "牛肉抄码" / "RAW_BEEF 设为抄码"</p>
 *
 * <p>WRITE 操作, 支持 preview (用户可先看变更, 再确认执行).</p>
 */
@Slf4j
@Component
public class MaterialMarkAbacaTool extends AbstractBusinessTool {

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepo;

    @Override
    public String getToolName() {
        return "material_mark_abaca";
    }

    @Override
    public String getDescription() {
        return "标记某原料类型为抄码品 (每箱重量不一, 采购不录箱数, 入库实际称重). " +
                "适用场景: 卤制品工厂的牛肉/猪肉/鸭肉等每箱重量不固定的原料.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("materialCode", Map.of("type", "string",
                "description", "原料编码 (如 RAW_BEEF / RAW_PORK), 工厂内唯一"));
        properties.put("isAbaca", Map.of("type", "boolean",
                "description", "true=标记为抄码品 / false=取消抄码标记", "default", true));
        properties.put("unitPerBox", Map.of("type", "string",
                "description", "箱重区间描述 (UI 提示用, 如 '约 10-15kg/箱')"));
        properties.put("defaultUnit", Map.of("type", "string",
                "description", "默认计量单位 (kg / g)", "default", "kg"));
        schema.put("properties", properties);
        schema.put("required", List.of("materialCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("materialCode");
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
        return RiskLevel.LOW;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
                                             Map<String, Object> params,
                                             Map<String, Object> context) {
        String materialCode = getString(params, "materialCode");
        Boolean isAbaca = getBoolean(params, "isAbaca", Boolean.TRUE);
        String unitPerBox = getString(params, "unitPerBox");
        String defaultUnit = getString(params, "defaultUnit", "kg");

        RawMaterialType type = rawMaterialTypeRepo.findByFactoryIdAndCode(factoryId, materialCode)
                .orElse(null);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("status", "PREVIEW");
        if (type == null) {
            preview.put("message", String.format("⚠️ 未找到原料编码 '%s' (工厂 %s)", materialCode, factoryId));
            return preview;
        }
        preview.put("message", String.format("即将%s原料 '%s' 的抄码标记",
                Boolean.TRUE.equals(isAbaca) ? "设置" : "取消", type.getName()));
        preview.put("materialName", type.getName());
        preview.put("currentIsAbaca", type.getIsAbacaPackaging());
        preview.put("newIsAbaca", isAbaca);
        preview.put("currentUnitPerBox", type.getAbacaUnitPerBox());
        preview.put("newUnitPerBox", unitPerBox);
        preview.put("currentDefaultUnit", type.getAbacaDefaultUnit());
        preview.put("newDefaultUnit", defaultUnit);
        return preview;
    }

    @Override
    @Transactional
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) {
        String materialCode = getString(params, "materialCode");
        Boolean isAbaca = getBoolean(params, "isAbaca", Boolean.TRUE);
        String unitPerBox = getString(params, "unitPerBox");
        String defaultUnit = getString(params, "defaultUnit", "kg");

        RawMaterialType type = rawMaterialTypeRepo.findByFactoryIdAndCode(factoryId, materialCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        String.format("原料编码 '%s' 不存在 (工厂 %s)", materialCode, factoryId)));

        type.setIsAbacaPackaging(isAbaca);
        if (Boolean.TRUE.equals(isAbaca)) {
            if (unitPerBox != null && !unitPerBox.isBlank()) {
                type.setAbacaUnitPerBox(unitPerBox);
            }
            type.setAbacaDefaultUnit(defaultUnit);
        }
        rawMaterialTypeRepo.save(type);

        log.info("material_mark_abaca: factory={} code={} isAbaca={} unitPerBox={} defaultUnit={}",
                factoryId, materialCode, isAbaca, unitPerBox, defaultUnit);

        return buildSimpleResult(
                String.format("原料 '%s' 抄码标记已更新为 %s", type.getName(),
                        Boolean.TRUE.equals(isAbaca) ? "是" : "否"),
                Map.of(
                        "materialId", type.getId(),
                        "materialCode", type.getCode(),
                        "materialName", type.getName(),
                        "isAbacaPackaging", type.getIsAbacaPackaging(),
                        "abacaUnitPerBox", type.getAbacaUnitPerBox() != null ? type.getAbacaUnitPerBox() : "",
                        "abacaDefaultUnit", type.getAbacaDefaultUnit() != null ? type.getAbacaDefaultUnit() : ""
                ));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
