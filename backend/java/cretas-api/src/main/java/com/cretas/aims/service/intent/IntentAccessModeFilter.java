package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 识别层候选过滤 (spec §8.2 消费点 ①)。
 *
 * <p><b>补的是哪个洞</b>: {@code PythonIntentMatchRequest} 早就有 {@code mode} /
 * {@code userPermissions} 两个字段并写好了 javadoc ("READ=剔除写意图候选 |
 * OPERATE=按 userPermissions 剔除无权限写意图"), 但全仓库<b>从来没有任何地方给它们赋过值</b> ——
 * 目录过滤在 Java 侧和 Python 侧都是死的。结果是咨询 tab 下写意图<b>先被识别再被拦</b>:
 * 顶层拦截卡确实挡住了 bestMatch, 但候选集里剩下的写意图仍然会流进多意图执行
 * ({@code additionalIntents} 是真会被执行的) 和 LLM 兜底重排。
 *
 * <p><b>本类只做减法</b>: 只从候选集里剔除, 从不新增、不改置信度、不替换 bestMatch。
 * bestMatch 是<b>刻意保留</b>的 —— 咨询 tab 命中写意图时正是靠它渲染
 * "这是操作类请求, 请切换到【操作】页" 的跳转卡 (防呆 Rule 5: dead-end 改导航)。
 * 把 bestMatch 一并清掉会让用户收到"没听懂"而不是"走错门", 那是更差的结果。
 */
@Slf4j
@Component
public class IntentAccessModeFilter {

    public static final String MODE_READ = "READ";
    public static final String MODE_OPERATE = "OPERATE";

    private final IntentConfigManagementService configService;
    private final WriteGuardService writeGuardService;

    /**
     * 构造参数上标 @Lazy: Tool → (@Lazy) AIIntentService → 本 filter → ToolRegistry → Tool
     * 会成环, 见 ai-intent-tool-skill-architecture rule 第 2 条。
     */
    private final ToolRegistry toolRegistry;

    @Autowired
    public IntentAccessModeFilter(IntentConfigManagementService configService,
                                  WriteGuardService writeGuardService,
                                  @Lazy ToolRegistry toolRegistry) {
        this.configService = configService;
        this.writeGuardService = writeGuardService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 意图是否为写意图 —— 声明优先, 启发式兜底。
     *
     * <p>顺序: 绑定 Tool 的 {@link ToolExecutor#getAccessMode()} 声明 → 意图自身的
     * sensitivity/名称启发式。任一判写即为写 (fail-closed)。意图查不到 / 工具查不到 ⇒ 判写。
     */
    public boolean isWriteIntent(String factoryId, String intentCode) {
        if (intentCode == null || intentCode.isBlank()) {
            return true;
        }
        Optional<AIIntentConfig> cfg;
        try {
            cfg = factoryId != null
                    ? configService.getIntentByCode(factoryId, intentCode)
                    : configService.getIntentByCode(intentCode);
        } catch (Exception e) {
            log.warn("意图配置查询失败, 按写意图处理: intentCode={}, err={}", intentCode, e.getMessage());
            return true;
        }
        if (cfg.isEmpty()) {
            // 候选里出现了一个查不到配置的 intentCode: 无从判定 ⇒ 不放进只读候选集。
            return true;
        }
        return isWriteIntent(cfg.get());
    }

    /** 同上, 但已经持有配置对象 (避免重复查库)。 */
    public boolean isWriteIntent(AIIntentConfig cfg) {
        if (cfg == null) {
            return true;
        }
        if (writeGuardService.isWriteIntent(cfg)) {
            return true;
        }
        String toolName = cfg.getToolName();
        if (toolName == null || toolName.isBlank()) {
            // 未绑定 Tool 的意图 (纯问答/Skill 编排) 只能靠意图侧启发式, 上面已判非写。
            return false;
        }
        try {
            Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
            // 绑定了一个未注册的工具名 ⇒ 判不了 ⇒ 判写
            return tool.map(ToolAccessModes::isWrite).orElse(true);
        } catch (Exception e) {
            log.warn("工具解析失败, 按写处理: tool={}, err={}", toolName, e.getMessage());
            return true;
        }
    }

    /**
     * 按 tab 模式过滤识别结果的候选集。
     *
     * @param result          识别结果; null 直接返回 null
     * @param factoryId       工厂 ID (意图配置按厂覆盖)
     * @param mode            READ / OPERATE; null 或其它值 = 不过滤 (向后兼容旧调用方)
     * @param userPermissions OPERATE 模式下调用者的权限码集合; null = 不按权限过滤
     * @return 过滤后的<b>副本</b>; 原对象不被修改 (它可能正躺在 IntentResultCache 里,
     *         而缓存键不含 mode —— 就地改会把只读过滤的结果串到操作 tab 去)
     */
    public IntentMatchResult filterForMode(IntentMatchResult result, String factoryId,
                                           String mode, Set<String> userPermissions) {
        if (result == null || mode == null || mode.isBlank()) {
            return result;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!MODE_READ.equals(normalized) && !MODE_OPERATE.equals(normalized)) {
            return result;
        }

        List<IntentMatchResult.CandidateIntent> candidates = result.getTopCandidates();
        List<IntentMatchResult.IntentMatch> extras = result.getAdditionalIntents();
        if ((candidates == null || candidates.isEmpty()) && (extras == null || extras.isEmpty())) {
            return result;
        }

        List<IntentMatchResult.CandidateIntent> keptCandidates = null;
        if (candidates != null && !candidates.isEmpty()) {
            keptCandidates = new ArrayList<>(candidates.size());
            for (IntentMatchResult.CandidateIntent c : candidates) {
                if (allowed(factoryId, c == null ? null : c.getIntentCode(), normalized, userPermissions)) {
                    keptCandidates.add(c);
                }
            }
            if (keptCandidates.size() == candidates.size()) {
                keptCandidates = candidates;
            }
        }

        List<IntentMatchResult.IntentMatch> keptExtras = null;
        if (extras != null && !extras.isEmpty()) {
            keptExtras = new ArrayList<>(extras.size());
            for (IntentMatchResult.IntentMatch m : extras) {
                if (allowed(factoryId, m == null ? null : m.getIntentCode(), normalized, userPermissions)) {
                    keptExtras.add(m);
                }
            }
            if (keptExtras.size() == extras.size()) {
                keptExtras = extras;
            }
        }

        boolean unchanged = (keptCandidates == null || keptCandidates == candidates)
                && (keptExtras == null || keptExtras == extras);
        if (unchanged) {
            return result;
        }

        int dropped = (candidates == null ? 0 : candidates.size() - (keptCandidates == null ? 0 : keptCandidates.size()))
                + (extras == null ? 0 : extras.size() - (keptExtras == null ? 0 : keptExtras.size()));
        log.debug("mode={} 过滤掉 {} 个写候选 (factoryId={})", normalized, dropped, factoryId);

        IntentMatchResult copy = result.toBuilder().build();
        if (keptCandidates != null) {
            copy.setTopCandidates(keptCandidates);
        }
        if (keptExtras != null) {
            copy.setAdditionalIntents(keptExtras);
            if (keptExtras.isEmpty()) {
                copy.setIsMultiIntent(Boolean.FALSE);
            }
        }
        return copy;
    }

    private boolean allowed(String factoryId, String intentCode, String mode, Set<String> userPermissions) {
        if (intentCode == null || intentCode.isBlank()) {
            return false;
        }
        if (!isWriteIntent(factoryId, intentCode)) {
            return true;  // 读意图两种模式都放行
        }
        if (MODE_READ.equals(mode)) {
            return false; // 咨询 tab: 写意图一律不进候选集
        }
        // OPERATE: 保留有权限的写意图。userPermissions 未提供时不做权限过滤 —— 真正的鉴权在
        // ToolRbacEnforcer / checkIntentPermission (PermissionService 矩阵), 这里只是提前收窄候选,
        // 绝不能反过来变成"这里放行了就等于有权限"。
        if (userPermissions == null) {
            return true;
        }
        String required = requiredPermission(factoryId, intentCode);
        return required == null || required.isBlank() || userPermissions.contains(required);
    }

    private String requiredPermission(String factoryId, String intentCode) {
        try {
            Optional<AIIntentConfig> cfg = factoryId != null
                    ? configService.getIntentByCode(factoryId, intentCode)
                    : configService.getIntentByCode(intentCode);
            return cfg.map(AIIntentConfig::getRequiredPermission).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
