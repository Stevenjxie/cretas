package com.cretas.aims.service.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-02 落地的四道食品安全闸的<b>判据契约</b>。
 *
 * <h2>为什么需要这个文件</h2>
 *
 * 这四条规则此前<b>只存在于 AI 工具里</b> —— 正常业务路径完全不查, 于是它们
 * 「只有当有人用 AI 问的时候才存在」。2026-08-02 owner 拍板接进真实写路径:
 *
 * <pre>
 * A1 供应商资质  → PurchaseServiceImpl.submitOrder
 * A2 HACCP 放行  → SalesServiceImpl.shipDelivery
 * A3 召回禁回退  → CanvasFoodSafetyController.updateRecall
 * A4 SSOP 台账   → ProductionPlanServiceImpl.startProduction（只记不拦）
 * </pre>
 *
 * <h2>🔴 这里钉的是【两档拆分】, 那是最容易被"顺手统一"掉的东西</h2>
 *
 * A1 与 A2 都<b>刻意分两档</b>: 真失效硬拦 / 数据缺口只告警。
 * 合并成一档看起来更简洁, 但后果不对称 —— 在主数据没录全的工厂会
 * <b>拦死所有采购提交 / 所有发货</b>, 把数据缺口变成停产。
 *
 * <h2>⛔ 这个文件【不执行生产代码】—— 别把它当闸</h2>
 *
 * 这些判据是从各 gate 方法里<b>逐字复刻</b>的, 本类只验证复刻出来的判据自洽。
 * <b>实测确认过</b>: 把 {@code CanvasFoodSafetyController} 里 A3 那道闸整个短路掉,
 * 本类<b>一条都不会红</b>(红的是 {@code CanvasFoodSafetyControllerTest})。
 *
 * <p>所以它的作用是<b>说明书</b>而不是防线: 让下一个人看懂"两档为什么要分开"、
 * "SKIPPED 为什么两边方向相反"。真正会红的防线是:
 *
 * <pre>
 * A3  CanvasFoodSafetyControllerTest#testUpdateRecallForbidsCompletedRollback  ← 真用例, 实测能抓
 *     CanvasFoodSafetyControllerTest#testUpdateRecallStillAllowsOtherTransitions
 * A1  ⚠️ 暂无真用例 —— PurchaseServiceImpl 的既有测试在 origin/main 上本来就是红的
 * A2  ⚠️ 暂无真用例 —— SalesServiceImpl 同上
 * A4  ⚠️ 暂无真用例
 * </pre>
 *
 * <p>A1/A2/A4 补真用例是<b>待办</b>, 不是"已经有覆盖了"。
 */
@DisplayName("食品安全四闸 — 判据契约 (2026-08-02)")
class FoodSafetyGatesContractTest {

    // ── 与 PurchaseServiceImpl 保持一致的口径 ──
    private static final List<String> MANDATORY_TYPES =
            List.of("SC_LICENSE", "BUSINESS_LICENSE");
    private static final List<String> USABLE_STATUSES = List.of("VALID", "EXPIRING");

    private static SupplierQualification qual(String type, String status) {
        SupplierQualification q = new SupplierQualification();
        q.setQualificationType(type);
        q.setStatus(status);
        q.setCertificateNumber("CERT-" + type);
        return q;
    }

    /** 复刻 {@code PurchaseServiceImpl.assertSupplierQualified} 的分档判据。 */
    private static String classifySupplier(List<SupplierQualification> all, String mandatory) {
        List<SupplierQualification> ofType = all.stream()
                .filter(q -> mandatory.equals(q.getQualificationType())).toList();
        boolean hasUsable = ofType.stream().anyMatch(q -> USABLE_STATUSES.contains(q.getStatus()));
        if (hasUsable) {
            return "OK";
        }
        return ofType.isEmpty() ? "MISSING" : "EXPIRED";
    }

    @Nested
    @DisplayName("A1 供应商资质闸 — 两档")
    class SupplierQualificationGate {

        @Test
        @DisplayName("从来没登记过 → MISSING（告警档，不硬拦）")
        void neverRegisteredIsWarnOnly() {
            List<SupplierQualification> none = List.of();
            for (String t : MANDATORY_TYPES) {
                assertEquals("MISSING", classifySupplier(none, t),
                        "资质主数据没录时必须落在告警档 —— 硬拦会拦死所有采购提交");
            }
        }

        @Test
        @DisplayName("曾有但已过期/吊销 → EXPIRED（硬拦档）")
        void expiredOrRevokedIsHardBlock() {
            assertEquals("EXPIRED",
                    classifySupplier(List.of(qual("SC_LICENSE", "EXPIRED")), "SC_LICENSE"));
            assertEquals("EXPIRED",
                    classifySupplier(List.of(qual("SC_LICENSE", "REVOKED")), "SC_LICENSE"));
        }

        @Test
        @DisplayName("VALID / EXPIRING 都算在手 → OK")
        void validOrExpiringPasses() {
            assertEquals("OK",
                    classifySupplier(List.of(qual("SC_LICENSE", "VALID")), "SC_LICENSE"));
            assertEquals("OK",
                    classifySupplier(List.of(qual("SC_LICENSE", "EXPIRING")), "SC_LICENSE"),
                    "临近到期只告警不阻断, 仍算资质在手");
        }
    }

    @Nested
    @DisplayName("A2 HACCP 放行闸 — 两档")
    class HaccpReleaseGate {

        private HaccpMonitoringRecord rec(boolean deviation) {
            HaccpMonitoringRecord r = new HaccpMonitoringRecord();
            r.setDeviation(deviation);
            return r;
        }

        /** 复刻 {@code SalesServiceImpl.assertHaccpReleaseAllowed} 的分档判据。 */
        private String classifyBatch(List<HaccpMonitoringRecord> records) {
            if (records.isEmpty()) {
                return "UNMONITORED";
            }
            return records.stream().anyMatch(HaccpMonitoringRecord::isDeviation)
                    ? "DEVIATING" : "OK";
        }

        @Test
        @DisplayName("无监控记录 → UNMONITORED（告警档，放行）")
        void noRecordsIsWarnOnly() {
            assertEquals("UNMONITORED", classifyBatch(List.of()),
                    "监控没录时必须放行 —— 硬拦会把大量正常批次拦死");
        }

        @Test
        @DisplayName("存在偏离 → DEVIATING（硬拦档）")
        void anyDeviationIsHardBlock() {
            assertEquals("DEVIATING", classifyBatch(List.of(rec(false), rec(true))),
                    "只要有一条偏离就该拦");
        }

        @Test
        @DisplayName("有记录且零偏离 → OK")
        void monitoredAndCleanPasses() {
            assertEquals("OK", classifyBatch(List.of(rec(false), rec(false))));
        }

        @Test
        @DisplayName("🔴 无记录 ≠ 通过：两者必须是不同的档")
        void unmonitoredIsNotSameAsOk() {
            assertFalse(classifyBatch(List.of()).equals(classifyBatch(List.of(rec(false)))),
                    "「没监控过」和「监控通过」是两回事, 不能合并");
        }
    }

    @Nested
    @DisplayName("A3 召回禁止 COMPLETED 回退")
    class RecallRollbackGate {

        /** 复刻 {@code CanvasFoodSafetyController.updateRecall} 的判据。 */
        private boolean forbidden(String current, String next) {
            return "COMPLETED".equals(current) && next != null && !"COMPLETED".equals(next);
        }

        @Test
        @DisplayName("COMPLETED → INVESTIGATING 被禁")
        void completedCannotRollBack() {
            assertTrue(forbidden("COMPLETED", "INVESTIGATING"));
            assertTrue(forbidden("COMPLETED", "FROZEN"));
        }

        @Test
        @DisplayName("COMPLETED → COMPLETED 允许（重复提交是幂等的，不算回退）")
        void completedToItselfIsAllowed() {
            assertFalse(forbidden("COMPLETED", "COMPLETED"));
        }

        @Test
        @DisplayName("🔴 其余方向仍然放行 —— 这不是完整流转矩阵")
        void otherTransitionsStillAllowed() {
            assertFalse(forbidden("REPORTED", "FROZEN"),
                    "只禁 COMPLETED 回退; 补全矩阵会开始拒绝现在能做的操作, 是另一个决定");
            assertFalse(forbidden("INVESTIGATING", "NOTIFYING"));
            assertFalse(forbidden("FROZEN", "INVESTIGATING"));
        }
    }

    @Nested
    @DisplayName("A4 SSOP 阻产台账 — 只记不拦")
    class SsopLedger {

        /** 复刻 {@code ProductionPlanServiceImpl.SSOP_BLOCKING_STATUSES}。 */
        private static final List<String> BLOCKING = List.of("SCHEDULED", "IN_PROGRESS", "FAILED");

        @Test
        @DisplayName("🔴 SKIPPED 不算阻产（有理由跳过的清洁不该记一笔阻产）")
        void skippedIsNotBlocking() {
            assertFalse(BLOCKING.contains("SKIPPED"),
                    "SKIPPED 在完成率里算完成, 在阻产判定里不阻产 —— 方向相反是有意的");
        }

        @Test
        @DisplayName("FAILED 算阻产（该做没做成）")
        void failedIsBlocking() {
            assertTrue(BLOCKING.contains("FAILED"));
        }

        @Test
        @DisplayName("COMPLETED 不算阻产")
        void completedIsNotBlocking() {
            assertFalse(BLOCKING.contains("COMPLETED"));
        }
    }
}
