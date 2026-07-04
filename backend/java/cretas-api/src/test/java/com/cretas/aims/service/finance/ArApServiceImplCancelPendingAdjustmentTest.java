package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #3(a) 退货资金链: {@link ArApService#cancelPendingAdjustmentsBySource} 级联撤销来源单据 (退货单)
 * 挂起的 PENDING AR/AP 调整。
 *
 * <p>Bug: 无货退款单业务审批时挂起一条 PENDING 冲减, 财务随后驳回退货单却从不撤销这条挂起调整
 * → 它仍留在审批队列可被第 2 位审批人通过 → 为已驳回退货单变动客户/供应商余额 (资金泄漏)。
 *
 * <p>核心断言:
 * <ol>
 *   <li>PENDING 调整 → 置 REJECTED (余额不变, 复用 REJECTED 因无 CANCELLED 枚举), remark 追加撤销原因。</li>
 *   <li>系统级联撤销 <b>不校验 4-眼</b> (operatedBy == approvedBy 也放行, 因为是父单据状态机驱动)。</li>
 *   <li>无匹配 PENDING → 返回 0, no-op (幂等, 有货 path 无挂起调整)。</li>
 *   <li>sourceType/sourceId 空 → 0, honest no-op。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("#3(a): ArApService.cancelPendingAdjustmentsBySource 级联撤销挂起冲减")
class ArApServiceImplCancelPendingAdjustmentTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;

    private static final String FACTORY = "F006";
    private static final String SOURCE_TYPE = "RETURN_ORDER";
    private static final String SOURCE_ID = "RO-123";
    private static final Long FINANCE_USER = 88L;

    private ArApServiceImpl service() {
        return new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
    }

    private ArApTransaction pending(Long operatedBy) {
        ArApTransaction t = new ArApTransaction();
        t.setId("txn-1");
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AP_ADJUSTMENT);
        t.setCounterpartyType(CounterpartyType.SUPPLIER);
        t.setCounterpartyId("SUP-001");
        t.setAmount(new BigDecimal("-500.00"));
        t.setApprovalStatus(ArApApprovalStatus.PENDING);
        t.setOperatedBy(operatedBy);
        t.setSourceType(SOURCE_TYPE);
        t.setSourceId(SOURCE_ID);
        t.setRemark("采购退货冲减(无货)-RT-PUR-001");
        return t;
    }

    @Test
    @DisplayName("PENDING 调整 → REJECTED, 余额不动, remark 追加撤销原因, 无 4-眼限制")
    void cancelsPending_flipsRejected_noFourEyes() {
        ArApTransaction txn = pending(FINANCE_USER); // operatedBy == approver → 4-眼会挡, 但级联撤销放行
        when(transactionRepository.findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, SOURCE_ID, ArApApprovalStatus.PENDING))
                .thenReturn(List.of(txn));
        when(transactionRepository.save(txn)).thenReturn(txn);

        int cancelled = service().cancelPendingAdjustmentsBySource(
                FACTORY, SOURCE_TYPE, SOURCE_ID, FINANCE_USER, "退货单财务驳回");

        assertEquals(1, cancelled);
        ArgumentCaptor<ArApTransaction> cap = ArgumentCaptor.forClass(ArApTransaction.class);
        verify(transactionRepository).save(cap.capture());
        ArApTransaction saved = cap.getValue();
        assertEquals(ArApApprovalStatus.REJECTED, saved.getApprovalStatus());
        assertEquals(FINANCE_USER, saved.getApprovedBy());
        assertTrue(saved.getRemark().contains("CANCELLED"));
        // 余额从不在此方法变动 — 未触碰 supplier/customer repository。
        verifyNoInteractions(supplierRepository, customerRepository);
    }

    @Test
    @DisplayName("无匹配 PENDING → 0, no-op (幂等; 有货 path 无挂起调整)")
    void noPending_returnsZero_noop() {
        when(transactionRepository.findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                FACTORY, SOURCE_TYPE, SOURCE_ID, ArApApprovalStatus.PENDING))
                .thenReturn(List.of());

        int cancelled = service().cancelPendingAdjustmentsBySource(
                FACTORY, SOURCE_TYPE, SOURCE_ID, FINANCE_USER, "x");

        assertEquals(0, cancelled);
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("sourceType/sourceId 空 → 0, honest no-op (不查库)")
    void blankSource_returnsZero_noQuery() {
        assertEquals(0, service().cancelPendingAdjustmentsBySource(FACTORY, null, SOURCE_ID, FINANCE_USER, "x"));
        assertEquals(0, service().cancelPendingAdjustmentsBySource(FACTORY, SOURCE_TYPE, "  ", FINANCE_USER, "x"));
        verify(transactionRepository, never())
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), eq(ArApApprovalStatus.PENDING));
    }
}
