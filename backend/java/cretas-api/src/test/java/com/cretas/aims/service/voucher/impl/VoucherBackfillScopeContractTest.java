package com.cretas.aims.service.voucher.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「批量补凭证」对调拨单的取数范围。
 *
 * <p>2026-08-09 prod 审计查出三件事, 这三条断言各守一件:
 * <ol>
 *   <li><b>不能只按 vflag 扫</b>: 凭证改成"确认入库才生成"后, 草稿/已取消的调拨会长期停在
 *       UNCREATED, 只按 vflag 补会给库存没动的单子补出凭证 —— 刚修掉的幽灵凭证从另一个入口
 *       长回来。必须 {@code status == CONFIRMED}。</li>
 *   <li><b>FAILED 必须可补</b>: 生成失败后 vflag=FAILED, 而没有任何路径会重试它 (listener 只在
 *       事件到来时跑一次, 批量补又只扫 UNCREATED)。一次瞬时失败 = 永久漏账且静默。prod 实测
 *       F006 有 4 张已确认、有金额、FAILED、至今无凭证的调拨。</li>
 *   <li><b>已有凭证的必须排除</b>: FAILED 不保证"一定没生成"; 重复生成会撞唯一约束、再把 vflag
 *       打回 FAILED, 制造"越补越失败"。</li>
 * </ol>
 *
 * <p>断言读源码而非起容器: 承重点是这个 filter 的取数条件本身, 而它是一段纯谓词 ——
 * 起 Spring + 造六种业务单据的代价远大于收益, 真实行为由 prod 审计 SQL 复核。
 */
@DisplayName("批量补凭证 — 调拨单取数范围")
class VoucherBackfillScopeContractTest {

    private String transferBranch() throws IOException {
        Path p = Path.of("src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java");
        assertTrue(Files.exists(p), "找不到被测源文件: " + p.toAbsolutePath());
        String src = Files.readString(p, StandardCharsets.UTF_8);
        // ⚠️ 先锚 findUncreatedIds —— `case "INTERNAL_TRANSFER":` 在本文件里不止一处
        // (updateVflag / 解析 factoryId 的 switch 各有一份), 直接 indexOf 会审错代码。
        int method = src.indexOf("private List<String> findUncreatedIds(");
        assertTrue(method > 0, "找不到 findUncreatedIds, 断言锚点失效");
        int idx = src.indexOf("case \"INTERNAL_TRANSFER\":", method);
        assertTrue(idx > 0, "findUncreatedIds 缺 INTERNAL_TRANSFER 分支");
        int end = src.indexOf("case \"WASTAGE_RECORD\":", idx);
        assertTrue(end > idx, "定位不到分支结尾");
        return src.substring(idx, end);
    }

    @Test
    @DisplayName("只补【已确认】的调拨 — 草稿/已取消的不补")
    void onlyConfirmed() throws IOException {
        assertTrue(transferBranch().contains("TransferStatus.CONFIRMED"),
                "必须按 status==CONFIRMED 过滤, 否则给库存没动的单子补出幽灵凭证");
    }

    @Test
    @DisplayName("调拨这一支走统一的 vflag 判定 — 不许再各写各的")
    void transferUsesSharedRetryableFlagCheck() throws IOException {
        // 行为断言在 retryableFlagsCoverDeadEnds (直调生产方法); 这里只守"这一支没有绕过它
        // 自己硬写一份 vflag 条件" —— 各支各写一份正是当初 FAILED/PENDING 漏掉的原因。
        assertTrue(transferBranch().contains("isRetryableFlag("),
                "调拨分支必须复用 isRetryableFlag, 不要内联自己那份 vflag 条件");
    }

    @Test
    @DisplayName("零金额的调拨不补 — 否则 debit=0/credit=0 违反约束, 把整批事务打成 aborted")
    void skipsZeroAmount() throws IOException {
        assertTrue(transferBranch().contains("getTotalAmount().signum() > 0"),
                "必须跳过零金额调拨: listener 一直有这道守卫, 这里缺了就会造出 0/0 分录");
    }

    @Test
    @DisplayName("每单独立事务 — 一单失败不拖垮整批")
    void perItemIndependentTransaction() throws IOException {
        Path p = Path.of("src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java");
        String src = Files.readString(p, StandardCharsets.UTF_8);
        int m = src.indexOf("public int batchCreateForFactory(");
        assertTrue(m > 0, "找不到 batchCreateForFactory");
        int end = src.indexOf("public Voucher post(", m);
        assertTrue(end > m, "定位不到方法结尾");
        String body = src.substring(m, end);
        // 缺陷版本靠整批一个 @Transactional + 逐单 catch —— catch 挡不住 PG 的 aborted transaction
        assertTrue(body.contains("PROPAGATION_REQUIRES_NEW"),
                "每单必须独立事务, 否则一单违约会把整批 DB 会话打成 aborted");
        assertFalse(src.substring(Math.max(0, m - 200), m).contains("@Transactional"),
                "batchCreateForFactory 不该再挂整批 @Transactional");
    }

    // ==================== 其余 4 支: 范围必须与各自 listener 的生成条件一致 ====================

    private String branchOf(String caseName, String nextCase) throws IOException {
        Path p = Path.of("src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java");
        String src = Files.readString(p, StandardCharsets.UTF_8);
        int m = src.indexOf("private List<String> findUncreatedIds(");
        assertTrue(m > 0, "找不到 findUncreatedIds");
        int i = src.indexOf("case \"" + caseName + "\":", m);
        assertTrue(i > 0, "找不到分支 " + caseName);
        int e = src.indexOf("case \"" + nextCase + "\":", i);
        assertTrue(e > i, "定位不到 " + caseName + " 分支结尾");
        return src.substring(i, e);
    }

    @Test
    @DisplayName("销售 — 只补已过财审的 (listener 挂在 SalesOrderFinanceApprovedEvent)")
    void salesOnlyFinanceApproved() throws IOException {
        assertTrue(branchOf("SALES_ORDER", "PURCHASE_ORDER").contains("isBookableSalesStatus"),
                "销售单必须按「已过财审」过滤, 否则给草稿/已取消的单子补出幽灵应收");
    }

    @Test
    @DisplayName("采购 — 只补收过货的 (listener 挂在 PurchaseReceiveConfirmedEvent)")
    void purchaseOnlyReceived() throws IOException {
        assertTrue(branchOf("PURCHASE_ORDER", "RETURN_ORDER").contains("isBookablePurchaseStatus"),
                "采购单必须按「已收货」过滤: 货没到就补 = 幽灵应付");
    }

    @Test
    @DisplayName("退货 — 排除已驳回 (驳回时另有 listener 作废凭证, 补回来等于对着干)")
    void returnExcludesRejected() throws IOException {
        assertTrue(branchOf("RETURN_ORDER", "INTERNAL_TRANSFER").contains("ReturnOrderStatus.REJECTED"),
                "退货单必须排除 REJECTED");
    }

    @Test
    @DisplayName("报损 — 只补已审批的 (无 listener, 补是唯一路径)")
    void wastageOnlyApproved() throws IOException {
        assertTrue(branchOf("WASTAGE_RECORD", "PAYROLL_RECORD").contains("Status.APPROVED"),
                "报损必须只补 APPROVED: 草稿/被驳回的损耗不是损失");
    }

    @Test
    @DisplayName("PENDING / FAILED 都可补 — 两个都是没人捡的死胡同")
    void retryableFlagsCoverDeadEnds() {
        VoucherServiceImpl svc = svc();
        assertTrue(svc.isRetryableFlag(com.cretas.aims.entity.enums.VoucherFlag.UNCREATED));
        // FAILED: 一次瞬时失败 = 永久漏账 (prod 实测 F006 4 张调拨)
        assertTrue(svc.isRetryableFlag(com.cretas.aims.entity.enums.VoucherFlag.FAILED),
                "FAILED 必须可补: 没有任何其它路径会重试它");
        // PENDING: listener 写了「开始生成」就中断 (prod 实测 27 张, 含 1 张真漏账)
        assertTrue(svc.isRetryableFlag(com.cretas.aims.entity.enums.VoucherFlag.PENDING),
                "PENDING 必须可补: 同样没人捡, 且 createFromBusiness 幂等所以安全");
        // 阴性对照: 已生成的不该再被扫进来
        assertFalse(svc.isRetryableFlag(com.cretas.aims.entity.enums.VoucherFlag.CREATED),
                "CREATED 不该进补的范围");
    }

    @Test
    @DisplayName("可入账状态的判定表 — 逐个状态钉死, 不靠「除了几个都算」")
    void bookableStatusTables() {
        // 销售: 财审通过之后才算
        for (com.cretas.aims.entity.enums.SalesOrderStatus s
                : com.cretas.aims.entity.enums.SalesOrderStatus.values()) {
            boolean expected = s.name().equals("FINANCE_APPROVED") || s.name().equals("PROCESSING")
                    || s.name().equals("PARTIAL_DELIVERED") || s.name().equals("COMPLETED");
            assertEquals(expected, svc().isBookableSalesStatus(s),
                    "销售状态 " + s + " 的可入账判定与预期不符");
        }
        // 采购: 收过货才算
        for (com.cretas.aims.entity.enums.PurchaseOrderStatus s
                : com.cretas.aims.entity.enums.PurchaseOrderStatus.values()) {
            boolean expected = s.name().equals("PARTIAL_RECEIVED") || s.name().equals("COMPLETED")
                    || s.name().equals("CLOSED");
            assertEquals(expected, svc().isBookablePurchaseStatus(s),
                    "采购状态 " + s + " 的可入账判定与预期不符");
        }
    }

    /**
     * 直接构造被测实例调【生产方法】—— 2026-08-10 实测: 最初这条断言拿测试里自己抄的一份
     * Set 跟测试里自己的 expected 比, 是个恒真式; 把 CANCELLED 加进生产方法的可入账集合,
     * 这条依然通过。变异救了它。判定表必须问生产代码, 不能问自己。
     */
    private VoucherServiceImpl svc() {
        return new VoucherServiceImpl(null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("已有凭证的排除 — 不制造「越补越失败」")
    void skipsWhenVoucherAlreadyExists() throws IOException {
        assertTrue(transferBranch().contains("findBySourceBusiness(\"INTERNAL_TRANSFER\""),
                "必须排除已有凭证的单据, 否则重复生成撞唯一约束再把 vflag 打回 FAILED");
    }
}
