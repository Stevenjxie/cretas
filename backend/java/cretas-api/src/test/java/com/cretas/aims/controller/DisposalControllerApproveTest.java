package com.cretas.aims.controller;

import com.cretas.aims.dto.disposal.ApproveDisposalRequest;
import com.cretas.aims.dto.disposal.RejectDisposalRequest;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.service.DisposalRecordService;
import jakarta.servlet.http.HttpServletRequest;
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
 * F-BUG-1 / F-BUG-2 / F-BUG-4 / F-BUG-5 contract verification for
 * {@link DisposalController#approveDisposal} + {@link DisposalController#rejectDisposal}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>F-BUG-4: approver identity (id/name) + role are extracted from the JWT
 *       request attributes, NOT from the request body</li>
 *   <li>F-BUG-1: approve accepts a body-less / remark-only DTO (no @NotNull blowup)</li>
 *   <li>F-BUG-2: reject routes to {@code rejectDisposal} (not {@code approveDisposal})
 *       carrying the rejection reason</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DisposalControllerApproveTest {

    @Mock DisposalRecordService disposalRecordService;

    @InjectMocks DisposalController controller;

    private HttpServletRequest tokenRequest(Integer userId, String username, String role) {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        when(req.getAttribute("userId")).thenReturn(userId);
        when(req.getAttribute("username")).thenReturn(username);
        when(req.getAttribute("role")).thenReturn(role);
        return req;
    }

    @Test
    void approveDisposal_approverFromToken_notFromBody() {
        // body carries only an optional remark — no approverId/approverName (F-BUG-1)
        ApproveDisposalRequest req = new ApproveDisposalRequest();
        req.setRemark("核实无误");

        DisposalRecord approved = new DisposalRecord();
        approved.setId(42L);
        approved.setIsApproved(true);
        when(disposalRecordService.approveDisposal(eq("F001"), eq(42L), eq(7), eq("张三"), eq("finance_manager")))
                .thenReturn(approved);

        ResponseEntity<?> resp = controller.approveDisposal(
                "F001", 42L, req, tokenRequest(7, "张三", "finance_manager"));

        assertNotNull(resp);
        // F-BUG-4: identity + role sourced from token attributes
        Mockito.verify(disposalRecordService)
                .approveDisposal("F001", 42L, 7, "张三", "finance_manager");
    }

    @Test
    void approveDisposal_nullBody_stillWorks() {
        // F-BUG-1: empty/absent body must not break (@RequestBody required=false)
        DisposalRecord approved = new DisposalRecord();
        approved.setId(43L);
        when(disposalRecordService.approveDisposal(eq("F001"), eq(43L), eq(7), eq("李四"), eq("factory_super_admin")))
                .thenReturn(approved);

        ResponseEntity<?> resp = controller.approveDisposal(
                "F001", 43L, null, tokenRequest(7, "李四", "factory_super_admin"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals("报废记录审批成功", body.get("message"));
    }

    @Test
    void rejectDisposal_routesToRejectService_withReason() {
        // F-BUG-2: reject must call rejectDisposal (NOT approveDisposal)
        RejectDisposalRequest req = new RejectDisposalRequest();
        req.setReason("证据不足");

        DisposalRecord rejected = new DisposalRecord();
        rejected.setId(99L);
        when(disposalRecordService.rejectDisposal(
                eq("F002"), eq(99L), eq(8), eq("王五"), eq("证据不足"), eq("finance_manager")))
                .thenReturn(rejected);

        ResponseEntity<?> resp = controller.rejectDisposal(
                "F002", 99L, req, tokenRequest(8, "王五", "finance_manager"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals("报废记录已拒绝", body.get("message"));
        Mockito.verify(disposalRecordService)
                .rejectDisposal("F002", 99L, 8, "王五", "证据不足", "finance_manager");
        // must NOT touch approve path
        Mockito.verify(disposalRecordService, Mockito.never())
                .approveDisposal(Mockito.anyString(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.any());
    }
}
