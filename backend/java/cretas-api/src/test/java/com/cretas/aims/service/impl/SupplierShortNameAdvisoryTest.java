package com.cretas.aims.service.impl;

import com.cretas.aims.dto.supplier.CreateSupplierRequest;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.SupplierMapper;
import com.cretas.aims.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商简称重名 —— **只提示不拦** (Steve 2026-07-30 拍板)。
 *
 * <p>分界判据是「会不会算错账」: 名称/税号重复会让人对错供应商、抵错税, 所以 409 阻断;
 * 简称重复只是下拉里不好认, 不影响任何一笔金额或库存, 于是照常保存、只提醒一句。</p>
 *
 * <p>⚠️ 这个决定必须**三处同时**成立, 缺一处就退化:</p>
 * <ul>
 *   <li>服务层不抛 409;</li>
 *   <li>migration 里没有唯一索引 —— 只删 409 而留着索引, 拦截照旧发生, 只是从可读的 409
 *       变成 DataIntegrityViolation 500, <b>比原来更糟</b>;</li>
 *   <li>提示得真的传回前端 (SupplierDTO.shortNameWarning), 否则用户永远不知道重了。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierShortNameAdvisoryTest {

    private static final String FACTORY = "F006";

    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierMapper supplierMapper;
    @InjectMocks private SupplierServiceImpl service;

    private Supplier existing;

    @BeforeEach
    void setUp() {
        existing = new Supplier();
        existing.setId("SUP-EXISTING");
        existing.setFactoryId(FACTORY);
        existing.setName("青岛远洋水产有限公司");
        existing.setShortName("远洋");

        when(supplierRepository.findByFactoryId(FACTORY)).thenReturn(List.of(existing));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));
        when(supplierMapper.toEntity(any(), any(), any())).thenAnswer(inv -> {
            CreateSupplierRequest req = inv.getArgument(0);
            Supplier s = new Supplier();
            s.setId("SUP-NEW");
            s.setFactoryId(FACTORY);
            s.setName(req.getName());
            s.setShortName(req.getShortName());
            return s;
        });
        when(supplierMapper.toDTO(any(Supplier.class))).thenAnswer(inv -> {
            Supplier s = inv.getArgument(0);
            SupplierDTO dto = new SupplierDTO();
            dto.setId(s.getId());
            dto.setName(s.getName());
            dto.setShortName(s.getShortName());
            return dto;
        });
    }

    private CreateSupplierRequest request(String name, String shortName) {
        CreateSupplierRequest req = new CreateSupplierRequest();
        req.setName(name);
        req.setShortName(shortName);
        req.setContactPerson("王经理");
        req.setPhone("13800138000");
        req.setAddress("山东省青岛市市南区香港中路 1 号");
        return req;
    }

    @Test
    void duplicateShortNameSavesAnywayAndReturnsAWarning() {
        SupplierDTO created = assertDoesNotThrow(
                () -> service.createSupplier(FACTORY, request("青岛远洋渔业有限公司", "远洋"), 1L),
                "简称重名不应该阻断保存 —— 它只影响下拉可读性, 不会算错账");

        // 真的落库了 (不是"没抛异常但也没存")
        ArgumentCaptor<Supplier> saved = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierRepository, times(1)).save(saved.capture());
        assertEquals("远洋", saved.getValue().getShortName(),
                "应当按用户填的简称原样保存, 不是悄悄改名或清空");

        assertNotNull(created.getShortNameWarning(), "重名了却没有任何提示, 用户永远不会去改");
        assertTrue(created.getShortNameWarning().contains("远洋"), "提示里要有冲突的简称");
        assertTrue(created.getShortNameWarning().contains("青岛远洋水产有限公司"),
                "提示里要指名跟谁重了, 否则用户不知道去改哪一家");
    }

    @Test
    void shortNameCollisionIsCaseAndWhitespaceInsensitive() {
        SupplierDTO created = service.createSupplier(FACTORY, request("另一家", "  远 洋  "), 1L);
        assertNotNull(created.getShortNameWarning(),
                "「远 洋」与「远洋」在下拉里一样分不出来, 空格/大小写差异不该让检测失灵");
    }

    @Test
    void uniqueShortNameGetsNoWarning() {
        SupplierDTO created = service.createSupplier(FACTORY, request("大连北纬水产", "北纬"), 1L);
        assertNull(created.getShortNameWarning(), "不重名却提示, 会让用户不再相信这条提示");
    }

    @Test
    void duplicateNameStillBlocks() {
        // 对照组: 名称重复会让人对错供应商 —— 那个仍然必须是硬拦截, 别被这次改动带跑
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSupplier(FACTORY, request("青岛远洋水产有限公司", "别的简称"), 1L));
        assertEquals(409, ex.getCode());
    }

    @Test
    void migrationMustNotKeepAUniqueIndexOnShortName() throws Exception {
        Path migration = Paths.get("src/main/resources/db/flyway")
                .resolve("V20261029_34__supplier_multi_contact_address_bank.sql");
        assertTrue(Files.exists(migration), "找不到 migration —— 文件被改名了, 请同步本测试");
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        // 匹配真实 DDL 而不是裸的索引名 —— migration 的注释里会写"初版建过 uq_suppliers_short_name",
        // 拿裸名字断言会被自己的说明文字误伤 (第一版就这么假红了一次)。
        assertFalse(sql.matches("(?s).*CREATE\\s+UNIQUE\\s+INDEX[^;]*\\bsuppliers\\s*\\(\\s*factory_id\\s*,\\s*lower\\(\\s*short_name.*"),
                "migration 里还留着简称唯一索引 —— 服务层已经不拦了, DB 却照拦, "
                        + "结果是从可读的 409 退化成 DataIntegrityViolation 500, 比原来更糟。");
        assertTrue(sql.contains("idx_suppliers_short_name"),
                "简称是下拉搜索字段且碰撞检测要按它查, 普通索引仍应保留");
    }
}
