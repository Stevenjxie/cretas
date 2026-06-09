package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.entity.finance.VoucherSubjectMapping;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.finance.VoucherSubjectMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP11: VoucherSubjectMappingServiceImpl 单元测试.
 *
 * @since SP11 2026-06-10
 */
@DisplayName("VoucherSubjectMappingServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class VoucherSubjectMappingServiceTest {

    @Mock
    private VoucherSubjectMappingRepository mappingRepo;

    private VoucherSubjectMappingServiceImpl service;

    private static final String FACTORY_ID = "F-SP11";
    private static final String DEFAULT_FACTORY = "__default__";

    @BeforeEach
    void setUp() {
        service = new VoucherSubjectMappingServiceImpl(mappingRepo);
    }

    // ========================= Helpers =========================

    private VoucherSubjectMapping buildMapping(String factoryId, SettlementType type,
                                                String debitCode, String creditCode) {
        VoucherSubjectMapping m = new VoucherSubjectMapping();
        m.setId("ID-" + type.name());
        m.setFactoryId(factoryId);
        m.setSettlementType(type);
        m.setDebitSubjectCode(debitCode);
        m.setDebitSubjectName("借方" + debitCode);
        m.setCreditSubjectCode(creditCode);
        m.setCreditSubjectName("贷方" + creditCode);
        return m;
    }

    // ========================= Tests =========================

    @Test
    @DisplayName("T1: 工厂无映射时, listByFactory 自动从 __default__ 复制")
    void testListByFactory_autoInitFromDefault() {
        when(mappingRepo.existsByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(false);
        when(mappingRepo.findByFactoryIdAndDeletedAtIsNull(DEFAULT_FACTORY)).thenReturn(List.of(
                buildMapping(DEFAULT_FACTORY, SettlementType.PREPAID, "1002", "6601"),
                buildMapping(DEFAULT_FACTORY, SettlementType.MONTHLY, "2202", "6601")
        ));
        when(mappingRepo.findByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(List.of(
                buildMapping(FACTORY_ID, SettlementType.PREPAID, "1002", "6601"),
                buildMapping(FACTORY_ID, SettlementType.MONTHLY, "2202", "6601")
        ));
        when(mappingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<VoucherSubjectMappingDTO> result = service.listByFactory(FACTORY_ID);

        assertEquals(2, result.size(), "应自动初始化 2 条来自 __default__");
        verify(mappingRepo, times(2)).save(any(VoucherSubjectMapping.class));
    }

    @Test
    @DisplayName("T2: 工厂已有映射时, listByFactory 直接返回无需初始化")
    void testListByFactory_existingMappings_noInit() {
        when(mappingRepo.existsByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(true);
        when(mappingRepo.findByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(List.of(
                buildMapping(FACTORY_ID, SettlementType.CREDIT_FIRST, "2202", "1002")
        ));

        List<VoucherSubjectMappingDTO> result = service.listByFactory(FACTORY_ID);

        assertEquals(1, result.size());
        verify(mappingRepo, never()).findByFactoryIdAndDeletedAtIsNull(DEFAULT_FACTORY);
        verify(mappingRepo, never()).save(any());
    }

    @Test
    @DisplayName("T3: findMapping 精确匹配 businessType 优先, 兜底 null businessType")
    void testFindMapping_exactMatchThenFallback() {
        when(mappingRepo.existsByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(true);

        VoucherSubjectMapping exactMapping =
                buildMapping(FACTORY_ID, SettlementType.PREPAID, "1002", "6002");
        exactMapping.setBusinessType("PURCHASE");

        // 精确匹配 PURCHASE
        when(mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.PREPAID, "PURCHASE"))
                .thenReturn(Optional.of(exactMapping));

        Optional<VoucherSubjectMappingDTO> result =
                service.findMapping(FACTORY_ID, SettlementType.PREPAID, "PURCHASE");

        assertTrue(result.isPresent());
        assertEquals("1002", result.get().getDebitSubjectCode());
        // 兜底查询未调用
        verify(mappingRepo, never()).findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.PREPAID, null);
    }

    @Test
    @DisplayName("T4: findMapping 精确匹配无结果, 兜底 null businessType")
    void testFindMapping_fallbackToNullBusinessType() {
        when(mappingRepo.existsByFactoryIdAndDeletedAtIsNull(FACTORY_ID)).thenReturn(true);

        VoucherSubjectMapping fallbackMapping =
                buildMapping(FACTORY_ID, SettlementType.PREPAID, "2241", "6001");

        when(mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.PREPAID, "UNKNOWN_TYPE"))
                .thenReturn(Optional.empty());
        when(mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.PREPAID, null))
                .thenReturn(Optional.of(fallbackMapping));

        Optional<VoucherSubjectMappingDTO> result =
                service.findMapping(FACTORY_ID, SettlementType.PREPAID, "UNKNOWN_TYPE");

        assertTrue(result.isPresent(), "兜底 null businessType 应返 fallback 映射");
        assertEquals("2241", result.get().getDebitSubjectCode());
    }

    @Test
    @DisplayName("T5: upsert 新记录 — 生成新 id 保存")
    void testUpsert_newMapping_createsNew() {
        when(mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.IMMEDIATE, null))
                .thenReturn(Optional.empty());
        when(mappingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VoucherSubjectMappingDTO dto = VoucherSubjectMappingDTO.builder()
                .factoryId(FACTORY_ID)
                .settlementType(SettlementType.IMMEDIATE)
                .debitSubjectCode("6001")
                .creditSubjectCode("1001")
                .build();

        VoucherSubjectMappingDTO result = service.upsert(FACTORY_ID, dto);

        assertNotNull(result.getId(), "新建记录应生成 UUID id");
        ArgumentCaptor<VoucherSubjectMapping> cap = ArgumentCaptor.forClass(VoucherSubjectMapping.class);
        verify(mappingRepo).save(cap.capture());
        assertEquals(FACTORY_ID, cap.getValue().getFactoryId());
        assertEquals(SettlementType.IMMEDIATE, cap.getValue().getSettlementType());
    }

    @Test
    @DisplayName("T6: upsert 已存在记录 — 更新不新增")
    void testUpsert_existingMapping_updates() {
        VoucherSubjectMapping existing =
                buildMapping(FACTORY_ID, SettlementType.MONTHLY, "2202", "1002");
        when(mappingRepo.findByFactoryIdAndSettlementTypeAndBusinessTypeAndDeletedAtIsNull(
                FACTORY_ID, SettlementType.MONTHLY, null))
                .thenReturn(Optional.of(existing));
        when(mappingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VoucherSubjectMappingDTO dto = VoucherSubjectMappingDTO.builder()
                .settlementType(SettlementType.MONTHLY)
                .debitSubjectCode("NEW_CODE")
                .creditSubjectCode("2202")
                .build();

        service.upsert(FACTORY_ID, dto);

        ArgumentCaptor<VoucherSubjectMapping> cap = ArgumentCaptor.forClass(VoucherSubjectMapping.class);
        verify(mappingRepo).save(cap.capture());
        assertEquals("NEW_CODE", cap.getValue().getDebitSubjectCode(), "应更新借方科目");
        assertEquals("ID-MONTHLY", cap.getValue().getId(), "应保留原始 id");
    }

    @Test
    @DisplayName("T7: delete 跨工厂 → BusinessException")
    void testDelete_crossFactory_throwsBusinessException() {
        VoucherSubjectMapping mapping = buildMapping("OTHER_FACTORY", SettlementType.PREPAID, "1002", "2202");
        when(mappingRepo.findById("ID-PREPAID")).thenReturn(Optional.of(mapping));

        assertThrows(BusinessException.class,
                () -> service.delete(FACTORY_ID, "ID-PREPAID"),
                "不同工厂的科目映射不能被删除");
    }
}
