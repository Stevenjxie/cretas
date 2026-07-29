package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.auth.PermissionRegistry;
import com.cretas.aims.entity.auth.PermissionRegistry.Source;
import com.cretas.aims.service.auth.PermissionManifestExporter;
import com.cretas.aims.service.auth.PermissionRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 权限审计工具 (Sprint 4 Wave 2 Chat J — C-CHECKPOWER-1).
 *
 * <p>AIChat 通过此工具回答类似:
 * <ul>
 *   <li>"系统里一共多少权限点?"</li>
 *   <li>"production 模块有哪些权限?"</li>
 *   <li>"生产模块的权限有几个,按 action 分布如何?"</li>
 *   <li>"哪些权限是手动添加的 (source=MANUAL)?"</li>
 * </ul>
 *
 * <p>参数:
 * <ul>
 *   <li>{@code mode} (必填): summary / module / source / list</li>
 *   <li>{@code module} (mode=module 时必填): 模块名 e.g. production</li>
 *   <li>{@code source} (mode=source 时必填): ANNOTATION / MANUAL / SEED</li>
 * </ul>
 *
 * @since 2026-05-24
 */
@Slf4j
@Component
public class PermissionAuditTool extends AbstractBusinessTool {

    @Autowired
    private PermissionRegistryService registryService;

    @Autowired
    private PermissionManifestExporter exporter;

    @Override
    public String getToolName() {
        return "permission_audit";
    }

    @Override
    public String getDescription() {
        return "RBAC 权限审计工具。查询系统中已登记的权限点 (来自 @RequirePermission 反射扫描)。" +
                "支持 4 种模式:summary (整体汇总)、module (按模块列权限)、source (按来源过滤)、" +
                "list (列出全部)。适用场景:权限合规审计、回答\"哪些权限点存在\"、\"某模块有几个权限\"。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> mode = new HashMap<>();
        mode.put("type", "string");
        mode.put("description", "审计模式:summary (汇总) / module (按模块) / source (按来源) / list (全部)");
        mode.put("enum", Arrays.asList("summary", "module", "source", "list"));
        properties.put("mode", mode);

        Map<String, Object> module = new HashMap<>();
        module.put("type", "string");
        module.put("description", "模块名 (mode=module 时必填), e.g. production, quality, finance");
        properties.put("module", module);

        Map<String, Object> source = new HashMap<>();
        source.put("type", "string");
        source.put("description", "权限来源 (mode=source 时必填): ANNOTATION / MANUAL / SEED");
        source.put("enum", Arrays.asList("ANNOTATION", "MANUAL", "SEED"));
        properties.put("source", source);

        schema.put("properties", properties);
        schema.put("required", List.of("mode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("mode");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("mode".equals(paramName)) {
            return "请问要哪种审计模式?summary(汇总) / module(按模块) / source(按来源) / list(全部)";
        }
        if ("module".equals(paramName)) {
            return "请问要查询哪个模块的权限?";
        }
        if ("source".equals(paramName)) {
            return "请问要按哪种来源过滤?ANNOTATION / MANUAL / SEED";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String mode = getString(params, "mode");
        if (mode == null || mode.isBlank()) {
            return buildSimpleResult("请指定审计模式", Map.of("supported", List.of("summary","module","source","list")));
        }

        log.debug("[permission_audit] factoryId={}, mode={}, params={}", factoryId, mode, params);

        switch (mode) {
            case "summary": {
                Map<String, Object> summary = registryService.auditSummary();
                String msg = String.format("系统共 %s 个 active 权限,跨 %s 个模块",
                        summary.get("totalActive"), summary.get("moduleCount"));
                return buildSimpleResult(msg, summary);
            }
            case "module": {
                String moduleName = getString(params, "module");
                if (moduleName == null || moduleName.isBlank()) {
                    return buildSimpleResult("module 模式必须提供 module 参数",
                            Map.of("availableModules", registryService.listModules()));
                }
                Map<String, Object> result = exporter.exportModule(moduleName);
                Object countObj = result.get("count");
                String msg = String.format("模块 %s 共 %s 个权限", moduleName, countObj);
                return buildSimpleResult(msg, result);
            }
            case "source": {
                String sourceStr = getString(params, "source");
                if (sourceStr == null || sourceStr.isBlank()) {
                    return buildSimpleResult("source 模式必须提供 source 参数 (ANNOTATION/MANUAL/SEED)", Map.of());
                }
                Source sourceEnum;
                try {
                    sourceEnum = Source.valueOf(sourceStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return buildSimpleResult("无效 source 值: " + sourceStr,
                            Map.of("valid", List.of("ANNOTATION","MANUAL","SEED")));
                }
                List<PermissionRegistry> list = registryService.listBySource(sourceEnum);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", sourceEnum.name());
                result.put("count", list.size());
                result.put("entries", list.stream().map(this::briefEntry).toList());
                return buildSimpleResult(String.format("来源 %s 共 %d 条权限", sourceEnum, list.size()), result);
            }
            case "list": {
                Map<String, Object> manifest = exporter.exportFullManifest();
                String msg = String.format("已列出全部 %s 个权限,分布于 %s 个模块",
                        manifest.get("totalCount"),
                        ((Map<?, ?>) manifest.get("modules")).size());
                return buildSimpleResult(msg, manifest);
            }
            default:
                return buildSimpleResult("不支持的 mode: " + mode,
                        Map.of("supported", List.of("summary","module","source","list")));
        }
    }

    private Map<String, Object> briefEntry(PermissionRegistry p) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", p.getPermissionCode());
        e.put("module", p.getModule());
        e.put("action", p.getAction());
        e.put("sourceClass", p.getSourceClass());
        e.put("sourceMethod", p.getSourceMethod());
        return e;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
