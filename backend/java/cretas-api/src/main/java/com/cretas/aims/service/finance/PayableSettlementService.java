package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.PayableSettlementRequest;
import com.cretas.aims.dto.finance.PayableSettlementResult;
import com.cretas.aims.dto.finance.UnallocatedApPaymentDTO;

import java.util.List;

public interface PayableSettlementService {

    PayableSettlementResult settle(
            String factoryId,
            String payableTransactionId,
            PayableSettlementRequest request,
            Long operatedBy);

    List<UnallocatedApPaymentDTO> listUnallocatedPayments(String factoryId);
}
