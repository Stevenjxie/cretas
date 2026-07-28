package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.dto.skill.SkillDefinition;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 读写声明的解析入口 (spec §8.2)。
 *
 * <p>Stateless + 无 Spring 依赖, 因此可以被 {@link WriteGuardService} (其契约要求 stateless /
 * thread-safe / 不读 ThreadLocal) 直接调用而不引入 bean 循环。
 *
 * <h2>解析优先级</h2>
 * <ol>
 *   <li><b>类内声明</b> —— 工具覆写 {@link ToolExecutor#getAccessMode()}。</li>
 *   <li><b>迁移种子</b> —— {@link ToolAccessModeSeed#PENDING_IN_CLASS_BACKFILL},
 *       尚未完成类内回填的餐饮包工具。</li>
 *   <li><b>兜底 WRITE</b> —— 以上都没有 ⇒ WRITE。</li>
 * </ol>
 *
 * <p>注意这里<b>刻意不用反射</b>去判断"有没有覆写": 带 {@code @Transactional} 的工具在运行时是
 * Spring 代理, 代理类会重新声明接口上的全部方法, {@code getMethod(..).getDeclaringClass()}
 * 会把未覆写的工具误判成"已声明"。改成直接读值即可自然得到正确优先级 —— 未覆写时
 * {@link ToolExecutor#getAccessMode()} 返回接口默认的 WRITE, 落到种子表再落到兜底。
 * (反射式覆盖率检查只在 CI 测试里做, 那里拿到的是 classpath 上的真实类, 没有代理。)
 */
public final class ToolAccessModes {

    private ToolAccessModes() {
    }

    /**
     * 解析单个工具的访问模式。null 工具按 WRITE 处理 (fail-closed)。
     */
    public static AccessMode resolve(ToolExecutor tool) {
        if (tool == null) {
            return AccessMode.WRITE;
        }
        AccessMode declared;
        try {
            declared = tool.getAccessMode();
        } catch (RuntimeException e) {
            // 声明本身抛异常 ⇒ 不可信 ⇒ 按写处理
            return AccessMode.WRITE;
        }
        if (declared == AccessMode.READ) {
            return AccessMode.READ;
        }
        if (declared == null) {
            return AccessMode.WRITE;
        }
        // declared == WRITE: 可能是真声明写, 也可能是"没覆写"落到了接口默认值。
        // 两者都先当写, 只有迁移种子表能把后者拉回 READ。
        String name = tool.getToolName();
        if (name != null && ToolAccessModeSeed.PENDING_IN_CLASS_BACKFILL.get(name) == AccessMode.READ) {
            return AccessMode.READ;
        }
        return AccessMode.WRITE;
    }

    public static boolean isWrite(ToolExecutor tool) {
        return resolve(tool) == AccessMode.WRITE;
    }

    /**
     * Skill 的访问模式 = {@code max(显式声明, 其编排的全部 Tool 的最大值)}。
     * WRITE &gt; READ, 任一 Tool 为 WRITE ⇒ Skill 为 WRITE。
     *
     * <p>显式声明只能抬高不能压低: 声明 READ 但编排里有写 Tool ⇒ 仍然 WRITE。
     * 解析不到的工具名按 WRITE 计 (fail-closed) —— 编排引用了一个不存在/未注册的工具时,
     * 宁可让整个 Skill 落进写闸, 也不能因为"查不到"就当只读放行。
     *
     * @param skill  技能定义; null ⇒ WRITE
     * @param lookup 工具名 → ToolExecutor 的解析函数 (通常是 ToolRegistry); 返回 null 表示未注册
     */
    public static AccessMode resolveSkill(SkillDefinition skill, Function<String, ToolExecutor> lookup) {
        if (skill == null) {
            return AccessMode.WRITE;
        }
        if ("WRITE".equalsIgnoreCase(skill.getAccessMode())) {
            return AccessMode.WRITE;
        }
        Collection<String> toolNames = collectToolNames(skill);
        if (toolNames.isEmpty()) {
            // 没有编排任何工具: 无从推导, 只信显式声明; 未显式声明 READ ⇒ WRITE。
            return "READ".equalsIgnoreCase(skill.getAccessMode()) ? AccessMode.READ : AccessMode.WRITE;
        }
        if (lookup == null) {
            return AccessMode.WRITE;
        }
        for (String toolName : toolNames) {
            ToolExecutor tool;
            try {
                tool = lookup.apply(toolName);
            } catch (RuntimeException e) {
                return AccessMode.WRITE;
            }
            if (tool == null || resolve(tool) == AccessMode.WRITE) {
                return AccessMode.WRITE;
            }
        }
        return AccessMode.READ;
    }

    /**
     * 收集 Skill 编排涉及的全部工具名: 顺序执行的 {@code tools} + DAG 节点 + 节点的 fallback 工具。
     * 遗漏 fallback 会让"主工具只读、失败回落到写工具"的编排逃出写闸。
     */
    public static Set<String> collectToolNames(SkillDefinition skill) {
        Set<String> names = new LinkedHashSet<>();
        if (skill == null) {
            return names;
        }
        if (skill.getTools() != null) {
            for (String t : skill.getTools()) {
                if (t != null && !t.isBlank()) {
                    names.add(t);
                }
            }
        }
        if (skill.getExecutionGraph() != null) {
            for (SkillDefinition.ExecutionNode node : skill.getExecutionGraph()) {
                if (node == null) {
                    continue;
                }
                if (node.getToolName() != null && !node.getToolName().isBlank()) {
                    names.add(node.getToolName());
                }
                if (node.getFallbackTool() != null && !node.getFallbackTool().isBlank()) {
                    names.add(node.getFallbackTool());
                }
            }
        }
        return names;
    }
}
