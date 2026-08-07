package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.finding.FindingActionPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link FindingActionPlanTool} 的单测。
 *
 * <p>⚠️ 生成逻辑本身（拒绝不完整检测 / 拒绝编造数字 / 提示词约束 / 空产出）
 * 2026-08-07 随代码搬到了 {@code FindingActionPlanServiceTest} —— 它们没有被删，
 * 是跟着被测对象走了。本文件只留 Tool 自己该负责的两件事：元数据，以及
 * **它委托时用的领域**。
 */
@ExtendWith(MockitoExtension.class)
class FindingActionPlanToolTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private FindingActionPlanService findingActionPlanService;

    private FindingActionPlanTool tool() {
        return new FindingActionPlanTool(findingActionPlanService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(FindingActionPlanTool t) throws Exception {
        Method m = FindingActionPlanTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(t, FACTORY_ID, Map.of(), Map.of());
    }

    @Test
    @DisplayName("UT-FAP-01: metadata —— 生成类工具, 只读访问")
    void metadata() {
        FindingActionPlanTool t = tool();
        assertEquals("system_finding_action_plan", t.getToolName());
        assertEquals(ToolExecutor.ActionType.GENERATE, t.getActionType());
        assertEquals(ToolExecutor.AccessMode.READ, t.getAccessMode());
    }

    @Test
    @DisplayName("UT-FAP-11: 🔴 Tool 固定用 inventory 域 —— 名字说管库存就不能去答别的")
    void delegatesWithInventoryDomain() throws Exception {
        when(findingActionPlanService.generate(FACTORY_ID, "inventory"))
                .thenReturn(Map.of("hasPlan", false));

        execute(tool());

        // 本 Tool 的 name/description 都写着「库存异常」, 语义路由是按那段描述选中
        // 它的。若哪天改成跟随租户类型, 就会出现一个自称管库存的工具悄悄去答餐饮
        // 问题 —— 名字与行为对不上。餐饮走的是 FindingController 的 REST 出口。
        verify(findingActionPlanService).generate(FACTORY_ID, "inventory");
        verify(findingActionPlanService, never()).generate(FACTORY_ID, "restaurant");
    }
}
