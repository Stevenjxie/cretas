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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调拨创建 <b>不做</b>重复提交拦截 — 2026-08-03 (原 防呆 R4 已移除)。
 *
 * <p>原去重键是 (源厂 + 目标厂 + 请求人 + 调拨日期), <b>不含任何内容维度</b> —— 同一天同一人
 * 给同一目标厂调<b>不同物料</b>也被判成"相同调拨"。实测备料时先建冻猪蹄的草稿, 紧接着建
 * 成品盒的调拨即被 409 拒绝, 只能挤进同一张单; 而同单里任一物料触发校验就整单失败,
 * 无法分批推进。Steve 拍板: 调拨不做该拦截。
 *
 * <p>本类保留路线校验用例 (调入仓库必填 / 源=目标 拒绝), 它们与去重无关且仍然生效。
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
        r.setTransferType("BRANCH_TO_BRANCH");
        r.setTargetFactoryId(TARGET_FACTORY);
        r.setTransferDate(LocalDate.now());
        r.setItems(List.of());
        return r;
    }

    private CreateTransferRequest warehouseTransferReq(String sourceWarehouseId, String targetWarehouseId) {
        CreateTransferRequest r = new CreateTransferRequest();
        r.setTransferType("WAREHOUSE_TO_WAREHOUSE");
        r.setTargetFactoryId(FACTORY_ID);
        r.setSourceWarehouseId(sourceWarehouseId);
        r.setTargetWarehouseId(targetWarehouseId);
        r.setTransferDate(LocalDate.now());
        r.setItems(List.of());
        return r;
    }

    @Test
    @DisplayName("回归 — 同窗口内已有调拨单也不再拦截, 且根本不查重复")
    void createTransfer_doesNotBlockOnRecentTransfer() {
        // 缺陷版本会因这条"已存在的调拨"抛 409, 导致同一天调不了第二种物料。
        InternalTransfer existing = new InternalTransfer();
        existing.setId("T-EXIST-1");
        existing.setTransferNumber("TR-20260618-0001");
        existing.setTargetFactoryId(TARGET_FACTORY);
        existing.setStatus(TransferStatus.DRAFT);
        lenient().when(transferRepository.findRecentDuplicates(
                any(), any(), any(), any(), any())).thenReturn(List.of(existing));

        TransferServiceImpl service = newService();

        // 走到 item 校验才停 (items 为空 → 400), 说明已越过原 409 去重关卡。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, req(), USER_ID));
        assertEquals(400, ex.getCode().intValue(),
                "不应再因重复调拨抛 409, 实际: " + ex.getCode() + " " + ex.getMessage());
        assertFalse(ex.getMessage().contains("请勿重复提交"),
                "不应再出现重复提交文案, 实际: " + ex.getMessage());

        // 更强的判据: 去重查询本身不该再被调用 (拦截已整段移除, 不只是放宽条件)。
        verify(transferRepository, never())
                .findRecentDuplicates(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create warehouse transfer requires explicit target warehouse")
    void createWarehouseTransfer_rejectsMissingTargetWarehouse() {
        TransferServiceImpl service = newService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, warehouseTransferReq("WH-RAW", null), USER_ID));

        assertEquals(400, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("调入仓库"), "message should name target warehouse: " + ex.getMessage());
        verify(transferRepository, never()).save(any(InternalTransfer.class));
    }

    @Test
    @DisplayName("create warehouse transfer rejects same source and target warehouse")
    void createWarehouseTransfer_rejectsSameSourceAndTargetWarehouse() {
        TransferServiceImpl service = newService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTransfer(FACTORY_ID, warehouseTransferReq("WH-RAW", "WH-RAW"), USER_ID));

        assertEquals(400, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("不能相同"), "message should explain same-warehouse route: " + ex.getMessage());
        verify(transferRepository, never()).save(any(InternalTransfer.class));
    }
}
