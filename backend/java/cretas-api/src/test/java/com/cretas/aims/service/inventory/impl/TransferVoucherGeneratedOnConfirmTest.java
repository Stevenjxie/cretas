package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.TransferConfirmedEvent;
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
 * INVENTORY_TRANSFER 凭证的生成时点 = <b>确认入库</b>, 不是创建。
 *
 * <p><b>缺陷</b> (2026-08-09): 凭证原先挂在 {@code TransferCreatedEvent} —— 调拨单一建出来
 * (还是草稿, 库存一分没动) 账上就有凭证。从没被确认、中途放弃、或被取消的草稿, 都会在账上留下
 * 对应不到实物流的凭证。六膳门 TRF-20260809-1790 的 ¥10,000 {@code V-2026-0023} 即是。
 *
 * <p>采购侧 2026-07-04 Bug 4 修过一模一样的问题 (PURCHASE_PAYMENT 从"入库单草稿创建"迁到
 * "确认入库"), 本次照抄。
 *
 * <p><b>为什么是源码断言而不是 Spring 集成测试</b>: 这条契约的承重点是"listener 监听的是哪个
 * 事件"与"生成入口只有这一个", 起 Spring 容器跑一遍 AFTER_COMMIT 异步 listener 既慢又不比
 * 直接读声明更强。真正的端到端由 prod E2E 覆盖 (建单→无凭证→确认→有凭证)。
 */
@DisplayName("凭证生成时点 = 确认入库")
class TransferVoucherGeneratedOnConfirmTest {

    private static final Path LISTENER = Path.of(
            "src/main/java/com/cretas/aims/listener/voucher/TransferVoucherListener.java");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/inventory/impl/TransferServiceImpl.java");
    private static final Path VOUCHER_SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/voucher/impl/VoucherServiceImpl.java");

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "找不到被测源文件: " + p.toAbsolutePath());
        String s = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(s.length() > 500, "源文件内容异常短, 断言可能落空: " + p);
        return s;
    }

    @Test
    @DisplayName("事故回归 — 凭证生成挂在确认事件上, 且不再挂创建事件")
    void voucherHookListensToConfirmedNotCreated() throws IOException {
        String listener = read(LISTENER);

        assertTrue(listener.contains("public void onTransferConfirmed(TransferConfirmedEvent event)"),
                "凭证生成应监听 TransferConfirmedEvent");
        // 承重断言: 缺陷版本正是靠"监听创建事件"在草稿阶段就记账。
        // 判的是【监听声明】与【import】, 不是文本里出现过这个名字 —— javadoc 里要留下
        // "从 TransferCreatedEvent 迁过来的" 这句历史, 用 contains 会把说明当成实现。
        assertFalse(listener.contains("import com.cretas.aims.event.TransferCreatedEvent;"),
                "不该再 import TransferCreatedEvent");
        assertFalse(listener.contains("(TransferCreatedEvent "),
                "不得再有以 TransferCreatedEvent 为入参的 listener (草稿创建就记账 = 幽灵凭证)");
        // 生成入口有且只有一个 —— 多一个就意味着有一条路绕过了"确认才入账"
        assertEquals(1, listener.split("createFromBusiness", -1).length - 1,
                "凭证生成入口应恰好 1 处");
    }

    @Test
    @DisplayName("confirmTransfer 发出确认事件, 且带的是【调出方】工厂")
    void confirmPublishesEventWithSourceFactory() throws IOException {
        String service = read(SERVICE);
        int idx = service.indexOf("new com.cretas.aims.event.TransferConfirmedEvent(");
        assertTrue(idx > 0, "confirmTransfer 应发布 TransferConfirmedEvent");
        String call = service.substring(idx, Math.min(service.length(), idx + 220));
        // 跨厂调拨由【调入方】执行确认, 而凭证归属调出方 —— 传当前 factoryId 会把凭证记到错的厂
        assertTrue(call.contains("getSourceFactoryId()"),
                "确认事件必须携带调出方工厂 (凭证归属方), 实际: " + call);
    }

    @Test
    @DisplayName("批量补凭证不得给未确认的调拨补 — 否则幽灵凭证从另一个入口长回来")
    void batchBackfillOnlyCoversConfirmedTransfers() throws IOException {
        String vs = read(VOUCHER_SERVICE);
        // ⚠️ 必须先锚到 findUncreatedIds —— `case "INTERNAL_TRANSFER":` 在本文件里不止一处
        // (还有 updateVflag / 解析 factoryId 的 switch)。直接 indexOf 会命中别的 switch,
        // 断言就跑去审一段跟补凭证无关的代码。2026-08-09 首跑就是这么红的。
        int method = vs.indexOf("private List<String> findUncreatedIds(");
        assertTrue(method > 0, "找不到 findUncreatedIds, 断言锚点失效");
        int idx = vs.indexOf("case \"INTERNAL_TRANSFER\":", method);
        assertTrue(idx > 0, "findUncreatedIds 应有 INTERNAL_TRANSFER 分支");
        // 按【下一个 case】截断, 不用固定字符窗口 —— 2026-08-09 实测: 原来写死 900 字符,
        // 后来给这个分支补了段注释, 判据就被挤出窗口, 断言在代码没坏的情况下变红。
        int end = vs.indexOf("case \"WASTAGE_RECORD\":", idx);
        assertTrue(end > idx, "定位不到分支结尾");
        String branch = vs.substring(idx, end);
        assertTrue(branch.contains("TransferStatus.CONFIRMED"),
                "批量补凭证必须只覆盖 CONFIRMED 的调拨单, 实际分支: " + branch);
    }

    @Test
    @DisplayName("阴性对照 — 事件对象本身携带的确实是调出方与单据 id")
    void eventCarriesSourceFactoryAndTransferId() {
        InternalTransfer t = new InternalTransfer();
        t.setId("T-1");
        t.setSourceFactoryId("F-SOURCE");
        t.setTargetFactoryId("F-TARGET");
        t.setStatus(TransferStatus.CONFIRMED);

        TransferConfirmedEvent event = new TransferConfirmedEvent(this, t.getSourceFactoryId(), t.getId());

        assertEquals("F-SOURCE", event.getSourceFactoryId());
        assertEquals("T-1", event.getTransferId());
    }
}
