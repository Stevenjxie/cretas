package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.TransferTerminatedEvent;
import com.cretas.aims.listener.voucher.TransferVoucherListener;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调拨单终止 (取消/驳回) → 作废 INVENTORY_TRANSFER 凭证。
 *
 * <p><b>事故</b> (2026-08-09, 六膳门 TRF-20260809-1790): 该单被取消后, 借贷各 ¥10,000 的
 * {@code V-2026-0023} 仍以 DRAFT 挂在库里 —— 凭证在调拨单<b>创建</b>时就生成 (草稿阶段, 库存
 * 一分没动), 而 cancelTransfer 只翻状态, 从不回收凭证。账上于是留着一张对应不到任何实物流的
 * 内部调拨凭证。物理删除那张坏单时才在闭包里撞见它。
 *
 * <p>与销售侧 2026-07-04 Bug 3 是同一形状 (凭证在前、终止在后、终止不回收凭证), 修法照抄
 * {@code SalesOrderCancelledEvent} + {@code SalesOrderVoucherListener}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("调拨终止 → 凭证作废")
class TransferVoucherVoidOnTerminationTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private VoucherService voucherService;

    private static final String FACTORY = "LIUSHANMEN";
    private static final String TRANSFER_ID = "11288eb0-e2cf-4262-8825-f02c3cda1a76";

    private TransferServiceImpl newService() {
        return new TransferServiceImpl(transferRepository, null, null, null,
                applicationEventPublisher, null, null);
    }

    private InternalTransfer transfer(TransferStatus status) {
        InternalTransfer t = new InternalTransfer();
        t.setId(TRANSFER_ID);
        t.setTransferNumber("TRF-20260809-1790");
        t.setSourceFactoryId(FACTORY);
        t.setTargetFactoryId(FACTORY);
        t.setStatus(status);
        return t;
    }

    private void stubLoad(InternalTransfer t) {
        when(transferRepository.findByIdAndEitherFactoryId(TRANSFER_ID, FACTORY)).thenReturn(Optional.of(t));
        lenient().when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Voucher voucher(VoucherStatus status) {
        Voucher v = new Voucher();
        v.setId("4dfd0aba-12ce-4c98-9e40-39ae2c4b0bdf");
        v.setVoucherNumber("V-2026-0023");
        v.setStatus(status);
        return v;
    }

    // ==================== 服务侧: 终止必须发出事件 ====================

    @Test
    @DisplayName("事故复现 — 取消调拨发出终止事件 (缺陷版本静默翻状态)")
    void cancelTransfer_publishesTerminatedEvent() {
        InternalTransfer t = transfer(TransferStatus.APPROVED);
        stubLoad(t);

        newService().cancelTransfer(FACTORY, TRANSFER_ID, 100L, "同一原料重复两行");

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ApplicationEvent published = captor.getValue();
        assertTrue(published instanceof TransferTerminatedEvent,
                "取消后应发出 TransferTerminatedEvent, 实际: " + published.getClass().getName());
        TransferTerminatedEvent event = (TransferTerminatedEvent) published;
        assertEquals(TRANSFER_ID, event.getTransferId());
        assertEquals(FACTORY, event.getFactoryId());
        assertEquals("CANCELLED", event.getTerminalStatus());
        assertEquals("同一原料重复两行", event.getReason());
    }

    @Test
    @DisplayName("驳回同样发出终止事件, 且状态标成 REJECTED")
    void rejectTransfer_publishesTerminatedEvent() {
        InternalTransfer t = transfer(TransferStatus.REQUESTED);
        stubLoad(t);

        newService().rejectTransfer(FACTORY, TRANSFER_ID, 100L, "数量不对");

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransferTerminatedEvent event = (TransferTerminatedEvent) captor.getValue();
        assertEquals("REJECTED", event.getTerminalStatus());
    }

    // ==================== 监听侧: 事件 → 作废凭证 ====================

    @Test
    @DisplayName("草稿凭证被作废, 作废原因说清是哪种终止")
    void listener_voidsDraftVoucher() {
        when(voucherService.findBySourceBusiness("INTERNAL_TRANSFER", TRANSFER_ID))
                .thenReturn(Optional.of(voucher(VoucherStatus.DRAFT)));
        TransferVoucherListener listener = new TransferVoucherListener(null, voucherService);

        listener.onTransferTerminated(new TransferTerminatedEvent(
                this, FACTORY, TRANSFER_ID, "CANCELLED", "同一原料重复两行"));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(voucherService).voidVoucher(eq(FACTORY), eq("4dfd0aba-12ce-4c98-9e40-39ae2c4b0bdf"),
                reason.capture(), eq(null));
        assertTrue(reason.getValue().contains("取消"), reason.getValue());
        assertTrue(reason.getValue().contains("同一原料重复两行"), reason.getValue());
    }

    @Test
    @DisplayName("幂等 — 已作废/已冲销的凭证不再动它")
    void listener_skipsTerminalVoucher() {
        TransferVoucherListener listener = new TransferVoucherListener(null, voucherService);
        for (VoucherStatus terminal : List.of(VoucherStatus.VOID, VoucherStatus.REVERSED)) {
            when(voucherService.findBySourceBusiness("INTERNAL_TRANSFER", TRANSFER_ID))
                    .thenReturn(Optional.of(voucher(terminal)));
            listener.onTransferTerminated(new TransferTerminatedEvent(
                    this, FACTORY, TRANSFER_ID, "CANCELLED", "x"));
        }
        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("阴性对照 — 没有凭证时静默跳过, 不抛异常也不乱作废")
    void listener_noVoucher_isNoOp() {
        when(voucherService.findBySourceBusiness("INTERNAL_TRANSFER", TRANSFER_ID))
                .thenReturn(Optional.empty());
        TransferVoucherListener listener = new TransferVoucherListener(null, voucherService);

        listener.onTransferTerminated(new TransferTerminatedEvent(
                this, FACTORY, TRANSFER_ID, "REJECTED", null));

        verify(voucherService, never()).voidVoucher(anyString(), anyString(), anyString(), any());
    }

    // ==================== 载体闸: 别再漏第 5 处 ====================

    /**
     * 这道闸守的是「终止调拨单的地方不止一处」。本轮实测有 4 处会把状态写成 CANCELLED/REJECTED:
     * cancelTransfer / rejectTransfer / projectWorkflowState(OA 驳回·撤销·超时) /
     * 生产计划取消时的批量关单。只修头两处就会留下两条一模一样的悬空凭证路径。
     *
     * <p>断言每一处写终态的语句后面 8 行内必须有 publishTerminated —— 将来有人新增第 5 处
     * 而忘了发事件, 这条会红。
     */
    @Test
    @DisplayName("闸 — 每一处写 CANCELLED/REJECTED 的地方都发了终止事件")
    void everyTerminationSitePublishesEvent() throws IOException {
        Path source = Path.of("src/main/java/com/cretas/aims/service/inventory/impl/TransferServiceImpl.java");
        assertTrue(Files.exists(source), "找不到被测源文件: " + source.toAbsolutePath());
        String[] lines = Files.readString(source, StandardCharsets.UTF_8).split("\r?\n");

        Pattern terminal = Pattern.compile("setStatus\\(TransferStatus\\.(CANCELLED|REJECTED)\\)");
        int sites = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = terminal.matcher(lines[i]);
            if (!m.find()) continue;
            sites++;
            boolean published = false;
            for (int j = i; j < Math.min(lines.length, i + 9); j++) {
                // 防御: 排除方法【定义】行。cancelTransfer 后面紧跟着 publishTerminated 的定义,
                // 今天两者相隔 12 行 (中间是 javadoc) 所以窗口够不着 —— 但 javadoc 一旦被删短,
                // 窗口就会撞见定义, 把"有个方法叫这个名"读成"这里调了它", 闸当场变恒真式。
                if (lines[j].contains("private void publishTerminated(")) continue;
                if (lines[j].contains("publishTerminated(")) { published = true; break; }
            }
            assertTrue(published, String.format(
                    "第 %d 行把调拨单写成终态却没发终止事件 → 凭证不会被回收: %s",
                    i + 1, lines[i].trim()));
        }
        // 自检: 一处都没扫到说明这道闸在找错地方 (源码搬家/正则失配), 那是静默通过, 比红更危险。
        assertEquals(4, sites, "写终态的地方数量变了, 请确认新增/删除的那一处是否也接了凭证回收");
    }
}
