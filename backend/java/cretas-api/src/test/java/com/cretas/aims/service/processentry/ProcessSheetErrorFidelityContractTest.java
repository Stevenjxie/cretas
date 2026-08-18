package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.LaborSegment;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 工序单报错要说真话: 说的必须是它**真的检查过**的那件事。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 多角色演练实测)</h2>
 *
 * <h3>(a) 少填人数 → 「系统处理异常，请稍后重试」</h3>
 * <pre>
 * NullPointerException: LaborSegment.getWorkerCount() is null
 *   at ProcessSheetServiceImpl.totalLaborHours(:1669)
 *   ← synthesizeOutputRequest(:1603) ← saveMultiOutputRow(:1473)
 * </pre>
 * 直接拆箱, 没有 null 防护。**只在一入多出(saveMultiOutputRow)这条路径上** ——
 * 那条路径生产上用得少, 所以一直没被暴露。缺字段是用户输入问题, 该给 400 并说清
 * 缺的是第几段的哪一项, 不是 500 + 一个追踪码。
 *
 * <h3>(b) 字段超长 → 「请检查上游批次、成本和库存数据」</h3>
 * processCode 传了 36 字符 UUID (库里 {@code varchar(32)}), 数据库抛
 * {@code value too long for type character varying(32)}, 被包成一句
 * **指向三个从来没检查过的方向**的话。
 *
 * <p>⚠️ 实体上这些字段没声明 {@code length} (Hibernate 默认 255) ⇒ ORM 层拦不住,
 * 非走到数据库才炸。列长取自 {@code process_sheet_rows} 的**实测定义**:
 * {@code process_code=32 / client_row_id=64 / batch_number=64}。
 *
 * <h2>口径</h2>
 * 这道闸只钉**报错说的和它检查的是同一件事**。写入链路本身由既有集成测试覆盖。
 */
class ProcessSheetErrorFidelityContractTest {

    private static ProcessSheetRowRequest req(String processCode, String clientRowId) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setProcessCode(processCode);
        r.setClientRowId(clientRowId);
        return r;
    }

    private static void assertColumnLimits(ProcessSheetRowRequest r) {
        ReflectionTestUtils.invokeMethod(ProcessSheetServiceImpl.class, "assertColumnLimits", r);
    }

    private static java.math.BigDecimal totalLaborHours(List<LaborSegment> segments) {
        // totalLaborHours 是 private static —— 不需要造实例(那个构造器有几十个协作者)
        return ReflectionTestUtils.invokeMethod(
                ProcessSheetServiceImpl.class, "totalLaborHours", segments);
    }

    private static LaborSegment seg(String start, String end, Integer workers) {
        LaborSegment s = new LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        s.setWorkerCount(workers);
        return s;
    }

    @Test
    @DisplayName("阳性对照: 字段合法时前置校验放行, 工时能正常算出来")
    void happyPathStillWorks() {
        assertColumnLimits(req("chaigu", "flow-p1"));   // 不抛就是通过
        java.math.BigDecimal hours = totalLaborHours(List.of(seg("07:00", "11:00", 2)));
        assertNotNull(hours);
        // 4 小时 × 2 人 = 8 人时 —— 没有这一条, 下面的"该红"断言可能只是因为方法根本没跑
        assertEquals(0, hours.compareTo(new java.math.BigDecimal("8")), "实际 " + hours);
    }

    @Test
    @DisplayName("(a) 少填人数要给 400 并说清是第几段, 不能是 NPE")
    void nullWorkerCountIsBusinessErrorNotNpe() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> totalLaborHours(List.of(seg("07:00", "11:00", 2), seg("11:00", "15:00", null))));
        assertEquals(400, e.getCode(), "缺字段该是 400 用户输入错误, 不是 500");
        assertTrue(e.getMessage().contains("第 2 段"), "没说清是第几段: " + e.getMessage());
        assertTrue(e.getMessage().contains("人数"), e.getMessage());
    }

    @Test
    @DisplayName("(a') 起止时间缺一个也一样 —— parseLaborTime 同样会 NPE")
    void nullTimesAlsoGuarded() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> totalLaborHours(List.of(seg(null, "11:00", 2))));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("第 1 段"), e.getMessage());
    }

    @Test
    @DisplayName("(a'') 人数 0 或负数要拦 —— 否则算出 0 人时, 无声地把人工成本抹平")
    void nonPositiveHeadcountRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> totalLaborHours(List.of(seg("07:00", "11:00", 0))));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("大于 0"), e.getMessage());
    }

    @Test
    @DisplayName("(b) processCode 超长要事先拦住, 并说清是哪个字段、多长、上限多少")
    void overlongProcessCodeIsRejectedUpFront() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertColumnLimits(req("e806ee32-c29d-460f-9089-ba308badc9cf", "flow-p1")));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("processCode"), e.getMessage());
        assertTrue(e.getMessage().contains("36"), "没说实际多长: " + e.getMessage());
        assertTrue(e.getMessage().contains("32"), "没说上限多少: " + e.getMessage());
        // 🔴 阴性对照: 这才是那句误导的话 —— 长度问题绝不能再指向这三个方向
        assertFalse(e.getMessage().contains("上游批次"), e.getMessage());
        assertFalse(e.getMessage().contains("成本"), e.getMessage());
        assertFalse(e.getMessage().contains("库存"), e.getMessage());
    }

    @Test
    @DisplayName("(c) 列长校验必须在 submitRow 的最前面 —— 放晚了等于没有")
    void columnLimitsRunBeforeAnyOtherSubmitCheck() throws Exception {
        // 🔴 2026-08-18 生产复验实测: 这条校验原来只在 saveRow 里, 而 submitRow 会先跑
        //    workflow 端口校验 —— 36 字符 processCode 的探针先撞到
        //    「Workflow 端口选择组不满足 AT_LEAST_ONE」, 前置校验**一次都没被执行到**。
        //    ⚠️ 名义上的"前置"不是前置; 顺序本身就是被守的行为, 所以单独钉一条。
        // ⚠️ 必须先剥注释: 第一版没剥, 闸红在**我自己写在 assertColumnLimits 上面那条注释**里的
        //    "AT_LEAST_ONE" 上 (位置 403 < 555)。本仓形态 A⁗「grep 把 docstring 也数进去」——
        //    这个 session 第三次。注释应该保留, 所以改的是闸。
        String raw = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java"));
        String src = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int submitAt = src.indexOf("public ProcessSheetRowResult submitRow");
        org.junit.jupiter.api.Assertions.assertTrue(submitAt > 0, "没找到 submitRow, 这道闸在读空气");
        String body = src.substring(submitAt, src.indexOf("saveRow(factoryId, planId, req, userId)", submitAt));
        int limitAt = body.indexOf("assertColumnLimits(req)");
        org.junit.jupiter.api.Assertions.assertTrue(limitAt > 0,
                "submitRow 里没有 assertColumnLimits —— 它会被更早的 workflow 校验挡在前面");
        // 必须早于 workflow 端口校验和生产日期校验
        for (String later : new String[]{"必须填写生产日期", "AT_LEAST_ONE", "getOutputs()"}) {
            int at = body.indexOf(later);
            if (at > 0) {
                org.junit.jupiter.api.Assertions.assertTrue(limitAt < at,
                        "assertColumnLimits 排在 \"" + later + "\" 之后, 探针到不了它");
            }
        }
    }

    @Test
    @DisplayName("(b') clientRowId 上限 64 —— 一入多出会在它后面拼 #0/#1, 边界要按库定义")
    void clientRowIdLimitMatchesColumn() {
        assertColumnLimits(req("chaigu", "x".repeat(64)));  // 正好 64 放行
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertColumnLimits(req("chaigu", "x".repeat(65))));
        assertTrue(e.getMessage().contains("clientRowId"), e.getMessage());
    }
}
