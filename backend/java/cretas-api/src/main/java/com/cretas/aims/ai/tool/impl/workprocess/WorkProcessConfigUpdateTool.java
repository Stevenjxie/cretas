package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 产品 × 工序 绑定批量配置 Tool (WRITE preview 支持).
 *
 * <p>触发 (六扇门第四次 line 104):
 *   "给猪蹄加工序: 拆包→分割→卤制→分切→装筐" 一句话配置 5 道工序。
 *
 * <p>语义: REPLACE — 传入的 workProcessNames 列表成为产品的完整工序流程, 旧绑定被替换。
 * processOrder 按列表顺序 0..N。
 *
 * <p>名称解析: workProcessNames 是工序定义的 processName, 后端按名称匹配 WorkProcess.id。
 * 找不到的名称会抛 BusinessException 引导用户先创建工序。
 *
 * <p>Preview 输出当前绑定 vs 提议绑定的对比, 不实际写库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessConfigUpdateTool extends AbstractBusinessTool {

    private final ProductWorkProcessRepository productWorkProcessRepository;
    private final ProductWorkProcessService productWorkProcessService;
    private final WorkProcessRepository workProcessRepository;

    @Override
    public String getToolName() {
        return "work_process_config_update";
    }

    @Override
    public String getDescription() {
        return "为指定产品配置工序流程 (REPLACE 语义, 一次性设置完整顺序)。"
                + "需要 productTypeId 和 workProcessNames (按顺序排列的工序名称列表)。"
                + "支持 preview 预览变更。适用场景: 一句话给产品配置工序 (如\"给猪蹄加工序: 拆包→分割→卤制\")。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productTypeId", Map.of(
                "type", "string",
                "description", "产品类型ID"));
        properties.put("workProcessNames", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "按顺序排列的工序名称列表 (匹配 WorkProcess.processName)"));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productTypeId", "workProcessNames"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productTypeId", "workProcessNames");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Resolution resolution = resolve(factoryId, params);

        List<ProductWorkProcess> existing = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
                        factoryId, resolution.productTypeId);

        List<Map<String, Object>> currentRows = existing.stream()
                .map(b -> Map.<String, Object>of(
                        "order", b.getProcessOrder(),
                        "workProcessId", b.getWorkProcessId(),
                        "processName", lookupName(resolution.allDefinitions, b.getWorkProcessId())))
                .collect(Collectors.toList());

        List<Map<String, Object>> proposedRows = new ArrayList<>();
        for (int i = 0; i < resolution.workProcesses.size(); i++) {
            WorkProcess wp = resolution.workProcesses.get(i);
            proposedRows.add(Map.of(
                    "order", i,
                    "workProcessId", wp.getId(),
                    "processName", wp.getProcessName()));
        }

        Map<String, Object> preview = new HashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("productTypeId", resolution.productTypeId);
        preview.put("currentBindings", currentRows);
        preview.put("proposedBindings", proposedRows);
        preview.put("note", "确认后将 REPLACE 现有绑定: 旧绑定全部移除, 新绑定按列表顺序写入");
        return buildSimpleResult(
                String.format("将为产品 %s 配置 %d 道工序 (REPLACE)",
                        resolution.productTypeId, proposedRows.size()),
                preview);
    }

    @Override
    @Transactional
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Resolution resolution = resolve(factoryId, params);

        // 1. 清空现有绑定. 先按 workProcessId 收旧绑定 —— 修 P0-A: REPLACE 不能静默清零
        //    用户先前配的 unitOverride/estimatedMinutesOverride (违反 fool-proof Rule 1 静默丢数据).
        List<ProductWorkProcess> existing = productWorkProcessRepository
                .findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(
                        factoryId, resolution.productTypeId);
        Map<String, ProductWorkProcess> oldByWorkProcessId = existing.stream()
                .collect(Collectors.toMap(
                        ProductWorkProcess::getWorkProcessId,
                        pwp -> pwp,
                        (a, b) -> a));
        for (ProductWorkProcess pwp : existing) {
            productWorkProcessService.delete(factoryId, pwp.getId());
        }

        // 2. 按列表顺序新建. 若同 workProcessId 命中旧绑定, carry-over override (P0-A).
        List<ProductWorkProcessDTO> created = new ArrayList<>();
        for (int i = 0; i < resolution.workProcesses.size(); i++) {
            WorkProcess wp = resolution.workProcesses.get(i);
            ProductWorkProcess old = oldByWorkProcessId.get(wp.getId());
            ProductWorkProcessDTO dto = ProductWorkProcessDTO.builder()
                    .productTypeId(resolution.productTypeId)
                    .workProcessId(wp.getId())
                    .processOrder(i)
                    .unitOverride(old != null ? old.getUnitOverride() : null)
                    .estimatedMinutesOverride(old != null ? old.getEstimatedMinutesOverride() : null)
                    .isActive(true)
                    .build();
            created.add(productWorkProcessService.create(factoryId, dto));
        }

        log.info("AI 工具更新产品工序配置: productTypeId={}, 新绑定 {} 道",
                resolution.productTypeId, created.size());
        return buildSimpleResult(
                String.format("产品 %s 已配置 %d 道工序: %s",
                        resolution.productTypeId,
                        created.size(),
                        resolution.workProcesses.stream()
                                .map(WorkProcess::getProcessName)
                                .collect(Collectors.joining(" → "))),
                created);
    }

    // ==================== Helpers ====================

    private Resolution resolve(String factoryId, Map<String, Object> params) {
        String productTypeId = getString(params, "productTypeId");
        @SuppressWarnings("unchecked")
        List<Object> names = (List<Object>) params.get("workProcessNames");
        if (names == null || names.isEmpty()) {
            throw new BusinessException(400, "workProcessNames 不能为空")
                    .withHint("请提供至少 1 个工序名称, 如 [拆包, 分割, 卤制]");
        }

        // 一次性拉全部工序定义, 按 name 匹配
        List<WorkProcess> allDefinitions = workProcessRepository.findByFactoryId(factoryId);
        Map<String, WorkProcess> nameToWp = allDefinitions.stream()
                .filter(wp -> Boolean.TRUE.equals(wp.getIsActive()))
                .collect(Collectors.toMap(
                        WorkProcess::getProcessName,
                        wp -> wp,
                        (a, b) -> a));

        List<WorkProcess> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Object nameObj : names) {
            String name = nameObj != null ? nameObj.toString().trim() : "";
            if (name.isEmpty()) continue;
            WorkProcess wp = nameToWp.get(name);
            if (wp == null) {
                missing.add(name);
            } else {
                resolved.add(wp);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    422,
                    "以下工序未找到: " + String.join(", ", missing))
                    .withHint("请先到'工序管理'创建这些工序定义")
                    .withHintTarget("工序管理");
        }
        if (resolved.isEmpty()) {
            throw new BusinessException(400, "解析后工序列表为空")
                    .withHint("请检查 workProcessNames 输入");
        }

        return new Resolution(productTypeId, resolved, allDefinitions);
    }

    private String lookupName(List<WorkProcess> allDefinitions, String workProcessId) {
        return allDefinitions.stream()
                .filter(wp -> wp.getId().equals(workProcessId))
                .map(WorkProcess::getProcessName)
                .findFirst()
                .orElse(workProcessId);
    }

    private record Resolution(
            String productTypeId,
            List<WorkProcess> workProcesses,
            List<WorkProcess> allDefinitions) {}

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
