package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.inventory.impl.TransferServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防呆 R4 (幂等防双击) for 调拨创建 — 2026-06-18.
 *
 * <p>双击/重复提交 5min 窗口内同 (源厂 + 目标厂 + 请求人 + 调拨日期) 的未完成调拨 → 409,
 * 不再创建第二条 DRAFT。dedup 检查位于 createTransfer 早段 (canvas 校验后、生成单号前),
 * 命中即抛, 不进入 item / batch 逻辑 —— 因此此处仅需 mock transferRepository。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferService — 调拨创建幂等防双击 (R4)")
class TransferServiceImplDedupTest {

    @Mock private InternalTransferRepository transferRepository;

    private static final String FACTORY_ID = "F006";
    private static final String TARGET_FACTORY = "F007";
    private static final Long USER_ID = 100L;

    private TransferServiceImpl newService() {
        // 仅 transferRepository 在 dedup 路径被用到; 其余依赖在命中 409 前不触达, 传 null 即可。
        return new TransferServiceImpl(transferRepository, null, null, null, null, null, null);
    }

    private CreateTransferRequest req() {
        CreateTransferRequest r = new CreateTransferRequest();
        r.setTransferType("MATERIAL");
        r.setTargetFactoryId(TARGET_FACTORY);
        r.setTransferDate(LocalDate.now());
        r.setItems(List.of());
        return r;
    }

    @Test
    @DisplayName("create — 5min 窗口内重复调拨 → 409 + 已有单号, 不再 save")
    void createTransfer_rejectsDuplicateWithin5MinWindow() {
        InternalTransfer existing = new InternalTransfer();
        existing.setId("T-EXIST-1");
        existing.setTransferNumber("TR-20260618-0001");
        existing.setTargetFactoryId(TARGET_FACTORY);
        existing.setStatus(TransferStatus.DRAFT);

        when(transferRepository.findRecentDuplicates(
                eq(FACTORY_ID), eq(TARGET_FACTORY), eq(USER_ID), any(), any()))
                .thenReturn(List.of(existing));

        TransferServiceImpl service = newService();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, req(), USER_ID));

        assertEquals(409, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("TR-20260618-0001"),
                "409 message must cite existing transfer number, was: " + ex.getMessage());
        verify(transferRepository, never()).save(any(InternalTransfer.class));
    }
}
