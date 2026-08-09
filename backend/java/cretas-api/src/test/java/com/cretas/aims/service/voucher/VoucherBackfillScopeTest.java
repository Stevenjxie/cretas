package com.cretas.aims.service.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
class VoucherBackfillScopeTest {

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
    @DisplayName("FAILED 可补 — 否则一次瞬时失败就是永久漏账")
    void failedIsRetryable() throws IOException {
        String branch = transferBranch();
        assertTrue(branch.contains("VoucherFlag.FAILED"),
                "FAILED 必须纳入可补范围: 没有任何其它路径会重试它");
        assertTrue(branch.contains("VoucherFlag.UNCREATED"),
                "UNCREATED 仍要补");
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

    @Test
    @DisplayName("已有凭证的排除 — 不制造「越补越失败」")
    void skipsWhenVoucherAlreadyExists() throws IOException {
        assertTrue(transferBranch().contains("findBySourceBusiness(\"INTERNAL_TRANSFER\""),
                "必须排除已有凭证的单据, 否则重复生成撞唯一约束再把 vflag 打回 FAILED");
    }
}
