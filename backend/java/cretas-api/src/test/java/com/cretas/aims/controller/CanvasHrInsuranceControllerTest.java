package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.hr.HrInsuranceConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.hr.HrInsuranceConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CanvasHrInsuranceController 单元测试 — Canvas Phase BCP3.
 *
 * <p>Coverage:
 * <ol>
 *   <li>list → 倒序</li>
 *   <li>getActive 不存在 → 404 + info</li>
 *   <li>create 非法 rate (越界) → 400</li>
 *   <li>create rate 非数字 → 400</li>
 *   <li>create 成功 → 旧 ACTIVE 自动 ARCHIVED + 新 ACTIVE 保存</li>
 *   <li>update 版本不一致 → 409 (AUD-4 P1)</li>
 *   <li>update 越界 rate → 400</li>
 *   <li>delete ACTIVE → 400 (不允许删 ACTIVE)</li>
 *   <li>delete ARCHIVED → 软删除成功</li>
 * </ol>
 */
@DisplayName("CanvasHrInsuranceController 单元测试")
@ExtendWith(MockitoExtension.class)
class CanvasHrInsuranceControllerTest {

    @Mock
    private HrInsuranceConfigRepository repository;

    @InjectMocks
    private CanvasHrInsuranceController controller;

    private static final String FACTORY_ID = "F001";

    private static HrInsuranceConfig makeConfig(String factoryId, String status, String id) {
        HrInsuranceConfig c = HrInsuranceConfig.builder()
                .id(id != null ? id : UUID.randomUUID().toString())
                .factoryId(factoryId)
                .employeePensionRate(new BigDecimal("0.0800"))
                .employerPensionRate(new BigDecimal("0.1600"))
                .employeeMedicalRate(new BigDecimal("0.0200"))
                .employerMedicalRate(new BigDecimal("0.0800"))
                .employeeUnemploymentRate(new BigDecimal("0.0050"))
                .employerUnemploymentRate(new BigDecimal("0.0050"))
                .employeeProvidentFundRate(new BigDecimal("0.0800"))
                .employerProvidentFundRate(new BigDecimal("0.0800"))
                .effectiveFrom(LocalDate.of(2026, 5, 1))
                .status(status)
                .optLockVersion(0L)
                .build();
        return c;
    }

    @Test
    @DisplayName("list → 倒序")
    void listReverseChronological() {
        when(repository.findByFactoryIdOrderByEffectiveFromDesc(FACTORY_ID))
                .thenReturn(List.of(
                        makeConfig(FACTORY_ID, "ACTIVE", "id-2"),
                        makeConfig(FACTORY_ID, "ARCHIVED", "id-1")
                ));

        ApiResponse<List<HrInsuranceConfig>> resp = controller.list(FACTORY_ID);

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        assertEquals("ACTIVE", resp.getData().get(0).getStatus());
    }

    @Test
    @DisplayName("getActive 不存在 → 404 + info severity")
    void getActiveNotFound() {
        when(repository.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(FACTORY_ID, "ACTIVE"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getActive(FACTORY_ID));
        assertEquals(404, ex.getCode());
        assertEquals("info", ex.getSeverity());
    }

    @Test
    @DisplayName("create rate 越界 → 400 + hintTarget")
    void createRateOutOfRange() {
        Map<String, Object> body = baseValidBody();
        body.put("employeePensionRate", "0.50");  // 超过 0.30

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
        assertEquals("employeePensionRate", ex.getHintTarget());
    }

    @Test
    @DisplayName("create rate 非数字 → 400")
    void createRateNotNumber() {
        Map<String, Object> body = baseValidBody();
        body.put("employeePensionRate", "abc");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.create(FACTORY_ID, body));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("create 成功 → 旧 ACTIVE auto-archive + 新版 ACTIVE save")
    void createArchiveOldActive() {
        HrInsuranceConfig oldActive = makeConfig(FACTORY_ID, "ACTIVE", "old-id");
        when(repository.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(FACTORY_ID, "ACTIVE"))
                .thenReturn(Optional.of(oldActive));
        when(repository.save(any(HrInsuranceConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = baseValidBody();

        ApiResponse<HrInsuranceConfig> resp = controller.create(FACTORY_ID, body);

        assertTrue(resp.getSuccess());
        assertEquals("ACTIVE", resp.getData().getStatus());
        // 旧的应当被改 ARCHIVED + save
        assertEquals("ARCHIVED", oldActive.getStatus());
        verify(repository, times(2)).save(any(HrInsuranceConfig.class));
    }

    @Test
    @DisplayName("update 版本不一致 → 409")
    void updateStaleVersion() {
        HrInsuranceConfig c = makeConfig(FACTORY_ID, "ACTIVE", "id-1");
        c.setOptLockVersion(7L);
        when(repository.findByIdAndFactoryId("id-1", FACTORY_ID)).thenReturn(Optional.of(c));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2L);
        body.put("remark", "改一下");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-1", body));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("v=7"));
    }

    @Test
    @DisplayName("update 越界 rate → 400")
    void updateRateOutOfRange() {
        HrInsuranceConfig c = makeConfig(FACTORY_ID, "ACTIVE", "id-1");
        when(repository.findByIdAndFactoryId("id-1", FACTORY_ID)).thenReturn(Optional.of(c));

        Map<String, Object> body = new HashMap<>();
        body.put("employerPensionRate", "0.99");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.update(FACTORY_ID, "id-1", body));
        assertEquals(400, ex.getCode());
        assertEquals("employerPensionRate", ex.getHintTarget());
    }

    @Test
    @DisplayName("delete ACTIVE → 400 (不允许删 ACTIVE)")
    void deleteActiveBlocked() {
        HrInsuranceConfig c = makeConfig(FACTORY_ID, "ACTIVE", "id-1");
        when(repository.findByIdAndFactoryId("id-1", FACTORY_ID)).thenReturn(Optional.of(c));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.delete(FACTORY_ID, "id-1"));
        assertEquals(400, ex.getCode());
        assertNotNull(ex.getActionHint());
    }

    @Test
    @DisplayName("delete ARCHIVED → 软删除成功")
    void deleteArchivedSuccess() {
        HrInsuranceConfig c = makeConfig(FACTORY_ID, "ARCHIVED", "id-old");
        when(repository.findByIdAndFactoryId("id-old", FACTORY_ID)).thenReturn(Optional.of(c));
        when(repository.save(any(HrInsuranceConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Void> resp = controller.delete(FACTORY_ID, "id-old");

        assertTrue(resp.getSuccess());
        assertTrue(c.isDeleted());
    }

    private static Map<String, Object> baseValidBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("employeePensionRate", "0.08");
        body.put("employerPensionRate", "0.16");
        body.put("employeeMedicalRate", "0.02");
        body.put("employerMedicalRate", "0.08");
        body.put("employeeUnemploymentRate", "0.005");
        body.put("employerUnemploymentRate", "0.005");
        body.put("employeeProvidentFundRate", "0.08");
        body.put("employerProvidentFundRate", "0.08");
        body.put("effectiveFrom", "2026-06-01");
        return body;
    }
}
