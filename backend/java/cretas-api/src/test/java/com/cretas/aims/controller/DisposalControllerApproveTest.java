package com.cretas.aims.controller;

import com.cretas.aims.dto.disposal.ApproveDisposalRequest;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.service.DisposalRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for
 * {@link DisposalController#approveDisposal} (Issue #384 batch 5).
 *
 * <p>Asserts that the Controller correctly:
 * <ol>
 *   <li>Binds {@link ApproveDisposalRequest} instead of the
 *       untyped {@code Map<String, Object>} (Rule 17.1 anti-pattern fix —
 *       eliminates silent ClassCastException paths)</li>
 *   <li>Extracts {@code approverId} (Integer) + {@code approverName} (String)
 *       from the DTO and passes them to the service</li>
 *   <li>Does NOT touch approval-state mutation (service owns
 *       {@code approve()} call that sets isApproved=true + approvalDate=now)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DisposalControllerApproveTest {

    @Mock DisposalRecordService disposalRecordService;

    @InjectMocks DisposalController controller;

    @Test
    void approveDisposal_happyPath_approverIdAndNamePassedToService() {
        ApproveDisposalRequest req = new ApproveDisposalRequest();
        req.setApproverId(1);
        req.setApproverName("张三");

        DisposalRecord approved = new DisposalRecord();
        approved.setId(42L);
        approved.setIsApproved(true);
        approved.setApprovedBy(1);
        approved.setApprovedByName("张三");
        when(disposalRecordService.approveDisposal(eq("F001"), eq(42L), eq(1), eq("张三")))
                .thenReturn(approved);

        ResponseEntity<?> resp = controller.approveDisposal("F001", 42L, req);

        assertNotNull(resp);
        // Service called with typed args extracted from DTO (replacing Map.get cast)
        Mockito.verify(disposalRecordService).approveDisposal("F001", 42L, 1, "张三");
    }

    @Test
    void approveDisposal_responseEnvelope_successTrueDataPresent() {
        ApproveDisposalRequest req = new ApproveDisposalRequest();
        req.setApproverId(2);
        req.setApproverName("李四");

        DisposalRecord approved = new DisposalRecord();
        approved.setId(99L);
        approved.setIsApproved(true);
        when(disposalRecordService.approveDisposal(eq("F002"), eq(99L), eq(2), eq("李四")))
                .thenReturn(approved);

        ResponseEntity<?> resp = controller.approveDisposal("F002", 99L, req);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals("报废记录审批成功", body.get("message"));
        assertEquals(approved, body.get("data"));
    }
}
