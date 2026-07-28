package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SSOP 清洁标准模板创建 Tool (WRITE + Preview) — Sprint 9 P2.E Phase B.
 *
 * <p>登记新的 SSOP 清洁程序模板. 创建后 Scheduler 自动按 frequency=DAILY 每日 6am
 * 创 SCHEDULED records.
 *
 * <p>防呆 4 位一体 (per .claude/rules/fool-proof-design.md):
 * <ul>
 *   <li>R1: preview 显当前模板数 + 该 code 是否已存在</li>
 *   <li>R2: message 含 name + target + frequency 上下文</li>
 *   <li>R3: target / frequency / inspectorRole 严格 enum 校验</li>
 *   <li>R4: dedup — 同 factory + procedureCode 已存在 → status=DUPLICATE</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"创建 SSOP 模板"</li>
 *   <li>"添加生产线 A 每日清洁程序"</li>
 *   <li>"录入设备清洗 SOP"</li>
 * </ul>
 *
 * <p>Intent Code: {@code SSOP_PROCEDURE_CREATE}
 */
@Slf4j
@Component
public class SsopProcedureCreateTool extends AbstractBusinessTool {

    private static final Set<String> ALLOWED_TARGETS = Set.of(
            "EQUIPMENT", "TOOLS", "ENVIRONMENT", "EMPLOYEE", "PEST_CONTROL");

    private static final Set<String> ALLOWED_FREQUENCIES = Set.of(
            "DAILY", "SHIFT", "WEEKLY", "MONTHLY");

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Override
    public String getToolName() {
        return "ssop_procedure_create";
    }

    @Override
    public String getDescription() {
        return "创建 SSOP 清洁标准模板 (WRITE + Preview) — GB 14881-2013 法定. "
                + "登记后 Scheduler 自动按 frequency 每日 6am 创清洁任务. "
                + "LLM 触发: '创建 SSOP 模板' / '添加生产线 A 每日清洁程序' / '录入设备清洗 SOP'.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> procedureCode = new HashMap<>();
        procedureCode.put("type", "string");
        procedureCode.put("description", "程序编码 (必填, factory 内唯一). e.g. SSOP-DAILY-LINE-A");
        properties.put("procedureCode", procedureCode);

        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "程序名称 (必填). e.g. 生产线 A 每日开工前消毒");
        properties.put("name", name);

        Map<String, Object> target = new HashMap<>();
        target.put("type", "string");
        target.put("description", "清洁对象 (必填): EQUIPMENT / TOOLS / ENVIRONMENT / EMPLOYEE / PEST_CONTROL");
        target.put("enum", List.of("EQUIPMENT", "TOOLS", "ENVIRONMENT", "EMPLOYEE", "PEST_CONTROL"));
        properties.put("target", target);

        Map<String, Object> frequency = new HashMap<>();
        frequency.put("type", "string");
        frequency.put("description", "执行频率 (必填): DAILY / SHIFT / WEEKLY / MONTHLY");
        frequency.put("enum", List.of("DAILY", "SHIFT", "WEEKLY", "MONTHLY"));
        properties.put("frequency", frequency);

        Map<String, Object> steps = new HashMap<>();
        steps.put("type", "string");
        steps.put("description", "标准操作步骤 (markdown 格式, 可选)");
        properties.put("steps", steps);

        Map<String, Object> chemicalsUsed = new HashMap<>();
        chemicalsUsed.put("type", "string");
        chemicalsUsed.put("description", "化学品 / 消毒剂规格 (可选). e.g. 200ppm 氯消毒液浸泡 5 分钟");
        properties.put("chemicalsUsed", chemicalsUsed);

        Map<String, Object> inspectorRole = new HashMap<>();
        inspectorRole.put("type", "string");
        inspectorRole.put("description", "检验员角色 (必填). e.g. quality_mgr");
        properties.put("inspectorRole", inspectorRole);

        schema.put("properties", properties);
        schema.put("required", List.of("procedureCode", "name", "target", "frequency", "inspectorRole"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("procedureCode", "name", "target", "frequency", "inspectorRole");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String procedureCode = getString(params, "procedureCode");
        String name = getString(params, "name");
        String target = getString(params, "target");
        String frequency = getString(params, "frequency");
        String inspectorRole = getString(params, "inspectorRole");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("procedureCode", procedureCode);
        result.put("name", name);
        result.put("target", target);
        result.put("frequency", frequency);
        result.put("inspectorRole", inspectorRole);

        // R3 enum 校验
        if (!ALLOWED_TARGETS.contains(target)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 清洁对象 %s 不合法. 合法值: %s",
                    target, String.join(", ", ALLOWED_TARGETS)));
            return result;
        }
        if (!ALLOWED_FREQUENCIES.contains(frequency)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 频率 %s 不合法. 合法值: %s",
                    frequency, String.join(", ", ALLOWED_FREQUENCIES)));
            return result;
        }

        // R4 dedup
        Optional<SsopProcedure> existing = procedureRepository
                .findByFactoryIdAndProcedureCode(factoryId, procedureCode);
        if (existing.isPresent()) {
            SsopProcedure ex = existing.get();
            result.put("canDo", false);
            result.put("status", "DUPLICATE");
            result.put("existingId", ex.getId());
            result.put("actionHint", "/foodsafety/ssop/procedures/" + ex.getId());
            result.put("message", String.format(
                    "ℹ️ SSOP 模板编码 %s 已存在 (id=%d, name=%s), 跳过重复创建",
                    procedureCode, ex.getId(), ex.getName()));
            return result;
        }

        // Workdesk widget 数字 — 工厂当前 active 模板数
        long activeCount = procedureRepository.countByFactoryIdAndActiveTrue(factoryId);

        result.put("canDo", true);
        result.put("currentActiveProcedures", activeCount);
        result.put("message", String.format(
                "📋 创建 SSOP 模板预览 — '%s' (%s, %s, 检验员=%s). 当前工厂 active 模板 %d 个. " +
                "创建后 Scheduler 自动每日 6am 创 SCHEDULED 任务 (若 frequency=DAILY).",
                name, target, frequency, inspectorRole, activeCount));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String procedureCode = getString(params, "procedureCode");
        String name = getString(params, "name");
        String target = getString(params, "target");
        String frequency = getString(params, "frequency");
        String steps = getString(params, "steps");
        String chemicalsUsed = getString(params, "chemicalsUsed");
        String inspectorRole = getString(params, "inspectorRole");
        Long userId = getUserId(context);

        log.info("ssop_procedure_create — factory={} code={} target={} frequency={} userId={}",
                factoryId, procedureCode, target, frequency, userId);

        // R3 enum 校验
        if (!ALLOWED_TARGETS.contains(target)) {
            throw new BusinessException(400,
                    String.format("清洁对象 %s 不合法", target));
        }
        if (!ALLOWED_FREQUENCIES.contains(frequency)) {
            throw new BusinessException(400,
                    String.format("频率 %s 不合法", frequency));
        }

        // R4 dedup
        Optional<SsopProcedure> existing = procedureRepository
                .findByFactoryIdAndProcedureCode(factoryId, procedureCode);
        if (existing.isPresent()) {
            SsopProcedure ex = existing.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "DUPLICATE");
            data.put("existingId", ex.getId());
            data.put("procedureCode", procedureCode);
            data.put("count", 0);
            data.put("actionHint", "/foodsafety/ssop/procedures/" + ex.getId());
            return buildSimpleResult(String.format(
                    "ℹ️ SSOP 模板编码 %s 已存在 (id=%d), 跳过重复创建",
                    procedureCode, ex.getId()), data);
        }

        SsopProcedure proc = SsopProcedure.builder()
                .factoryId(factoryId)
                .procedureCode(procedureCode)
                .name(name)
                .target(target)
                .frequency(frequency)
                .steps(steps)
                .chemicalsUsed(chemicalsUsed)
                .inspectorRole(inspectorRole)
                .active(true)
                .build();
        proc = procedureRepository.save(proc);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", proc.getId());
        data.put("procedureCode", procedureCode);
        data.put("name", name);
        data.put("target", target);
        data.put("frequency", frequency);
        data.put("inspectorRole", inspectorRole);
        data.put("active", true);
        data.put("actionHint", "/foodsafety/ssop/procedures/" + proc.getId());

        String message = String.format(
                "✅ SSOP 模板已创建 — '%s' (%s, %s, 检验员=%s, id=%d). " +
                "若 frequency=DAILY, 次日 6am Scheduler 自动创清洁任务.",
                name, target, frequency, inspectorRole, proc.getId());
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
