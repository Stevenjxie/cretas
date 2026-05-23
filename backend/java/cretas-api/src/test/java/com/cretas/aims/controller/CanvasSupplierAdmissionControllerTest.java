package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasSupplierAdmissionController 单元测试 — Canvas Phase BCP3.
 *
 * <p>Coverage:
 * <ol>
 *   <li>list → all 供应商</li>
 *   <li>list with status filter</li>
 *   <li>getById not found → 404</li>
 *   <li>review 非法 status → 400</li>
 *   <li>review REJECTED 无 notes → 400 (强制理由)</li>
 *   <li>review APPROVED → isActive=true + admissionStatus=APPROVED</li>
 *   <li>review SUSPENDED → isActive=false</li>
 *   <li>update 版本不一致 → 409</li>
 *   <li>update 非法 rating → 400</li>
 *   <li>suspend (delete) → admission_status=SUSPENDED + isActive=false</li>
 * </ol>
 */
@DisplayName("CanvasSupplierAdmissionController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasSupplierAdmissionControllerTest {

    @Mock
    private SupplierRepository supplierRepository;

    @InjectMocks
    private CanvasSupplierAdmissionController controller;

    private static final String FACTORY_ID = "F001";

    private static Supplier makeSupplier(String id, String name, String admissionStatus) {
        Supplier s = new Supplier();
        s.setId(id != null ? id : UUID.randomUUID().toString());
        s.setFactoryId(FACTORY_ID);
        s.setSupplierCode("SUP-" + (id == null ? "0001" : id));
        s.setCode(s.getSupplierCode());
        s.setName(name);
        s.setIsActive(true);
        s.setAdmissionStatus(admissionStatus);
        s.setVersion(0L);
        s.setCreatedBy(1L);
        return s;
    }

    @Test
    @DisplayName("list → 全部供应商")
    void listAll() {
        when(supplierRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(
                        makeSupplier("s1", "供应商 A", "APPROVED"),
                        makeSupplier("s2", "供应商 B", "PENDING")
                ));

        ApiResponse<List<Map<String, Object>>> resp = controller.list(FACTORY_ID, null);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        assertEquals("APPROVED", resp.getData().get(0).get("admissionStatus"));
    }

    @Test
    @DisplayName("list with status filter → 按 admissionStatus 过滤")
    void listWithStatusFilter() {
        when(supplierRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(
                        makeSupplier("s1", "供应商 A", "APPROVED"),
                        makeSupplier("s2", "供应商 B", "PENDING")
                ));

        ApiResponse<List<Map<String, Object>>> resp = controller.list(FACTORY_ID, "PENDING");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals("供应商 B", resp.getData().get(0).get("name"));
    }

    @Test
    @DisplayName("getById 不存在 → 404")
    void getByIdNotFound() {
        when(supplierRepository.findByIdAndFactoryId("nope", FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getById(FACTORY_ID, "nope"));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("review 非法 status → 400 + hintTarget")
    void reviewBadStatus() {
        Supplier s = makeSupplier("s1", "供应商 A", "PENDING");
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));

        Map<String, Object> body = new HashMap<>();
        body.put("admissionStatus", "UNKNOWN_STATUS");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.review(FACTORY_ID, "s1", body));
        assertEquals(400, ex.getCode());
        assertEquals("admissionStatus", ex.getHintTarget());
    }

    @Test
    @DisplayName("review REJECTED 无 notes → 400 (强制填理由)")
    void reviewRejectedRequiresNotes() {
        Supplier s = makeSupplier("s1", "供应商 A", "PENDING");
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));

        Map<String, Object> body = new HashMap<>();
        body.put("admissionStatus", "REJECTED");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.review(FACTORY_ID, "s1", body));
        assertEquals(400, ex.getCode());
        assertEquals("notes", ex.getHintTarget());
    }

    @Test
    @DisplayName("review APPROVED → isActive=true + admissionStatus=APPROVED")
    void reviewApproved() {
        Supplier s = makeSupplier("s1", "供应商 A", "PENDING");
        s.setIsActive(false);
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("admissionStatus", "APPROVED");
        body.put("notes", "资质齐全, 准入");

        ApiResponse<Map<String, Object>> resp = controller.review(FACTORY_ID, "s1", body);

        assertTrue(resp.getSuccess());
        assertEquals("APPROVED", resp.getData().get("admissionStatus"));
        assertEquals(Boolean.TRUE, resp.getData().get("isActive"));
        assertNotNull(resp.getData().get("admissionReviewedAt"));
    }

    @Test
    @DisplayName("review SUSPENDED 带 notes → isActive=false + status=SUSPENDED")
    void reviewSuspended() {
        Supplier s = makeSupplier("s1", "供应商 A", "APPROVED");
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));
        when(supplierRepository.saveAndFlush(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("admissionStatus", "SUSPENDED");
        body.put("notes", "近期质量问题, 暂停 30 天");

        ApiResponse<Map<String, Object>> resp = controller.review(FACTORY_ID, "s1", body);

        assertTrue(resp.getSuccess());
        assertEquals("SUSPENDED", resp.getData().get("admissionStatus"));
        assertEquals(Boolean.FALSE, resp.getData().get("isActive"));
    }

    @Test
    @DisplayName("update 版本不一致 → 409")
    void updateStaleVersion() {
        Supplier s = makeSupplier("s1", "供应商 A", "APPROVED");
        s.setVersion(9L);
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 3L);
        body.put("ratingNotes", "改备注");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "s1", body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=9"));
    }

    @Test
    @DisplayName("update 非法 rating → 400")
    void updateBadRating() {
        Supplier s = makeSupplier("s1", "供应商 A", "APPROVED");
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));

        Map<String, Object> body = new HashMap<>();
        body.put("rating", 99);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "s1", body));
        assertEquals(400, ex.getCode());
        assertEquals("rating", ex.getHintTarget());
    }

    @Test
    @DisplayName("suspend (delete) → admission_status=SUSPENDED + isActive=false")
    void suspendSuccess() {
        Supplier s = makeSupplier("s1", "供应商 A", "APPROVED");
        when(supplierRepository.findByIdAndFactoryId("s1", FACTORY_ID)).thenReturn(Optional.of(s));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Void> resp = controller.suspend(FACTORY_ID, "s1");

        assertTrue(resp.getSuccess());
        assertEquals("SUSPENDED", s.getAdmissionStatus());
        assertEquals(Boolean.FALSE, s.getIsActive());
    }
}
