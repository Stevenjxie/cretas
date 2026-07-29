package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 包材/研发缺口补齐测试:
 * <ul>
 *   <li>P8 — associatedCustomerId 字段往返 (create/update/dto)</li>
 *   <li>P11 — getMaterialTypesByKind 分页 + kind=null 退化</li>
 *   <li>P7 — packaging RBAC: createMaterialType 由 controller 层
 *         @RequirePermission("production:read_write") 守卫, service 层无额外逻辑;
 *         此处仅做服务层烟雾测试 (不测注解, 注解覆盖见 AiToolRbacGateTest)</li>
 *   <li>R14 — suggestMaterialsByPriceRange: 范围过滤 + null min/max + 无报价物料排除</li>
 * </ul>
 */
@DisplayName("包材/研发缺口补齐: P8/P11/P7/R14")
@ExtendWith(MockitoExtension.class)
class PackagingRdFinishGapTest {

    private static final String FACTORY = "F-TEST-GAP";
    private static final String L1_PACKAGING = "002";
    private static final String L2_PACKAGING = "002001";
    private static final String L3_PACKAGING = "0020010001";
    /** 16 位码 = L3 前缀 + 6 位流水; 字典里没有已存在的码时从 000001 起。 */
    private static final String GENERATED_CODE = L3_PACKAGING + "000001";

    @Mock private RawMaterialTypeRepository repo;
    @Mock private MaterialBatchRepository batchRepo;
    @Mock private ConversionRepository convRepo;
    @Mock private MaterialPackagingHierarchyRepository packRepo;
    @Mock private MaterialCodeSegmentRepository codeSegRepo;
    @Mock private ExcelUtil excelUtil;

    private RawMaterialTypeServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new RawMaterialTypeServiceImpl(
                repo, batchRepo, convRepo, packRepo, codeSegRepo, excelUtil,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private RawMaterialType makeMaterial(String id, String name, String category,
                                          BigDecimal unitPrice, String associatedCustomerId) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FACTORY);
        m.setName(name);
        m.setCategory(category);
        m.setUnitPrice(unitPrice);
        m.setAssociatedCustomerId(associatedCustomerId);
        m.setIsActive(true);
        m.setCode("TST-" + id);
        m.setUnit("kg");
        return m;
    }

    /** Build PageRequest DTO (no-arg constructor + setters). */
    private PageRequest pageReq(int page, int size) {
        PageRequest r = new PageRequest();
        r.setPage(page);
        r.setSize(size);
        return r;
    }

    /**
     * PR #1545/#1547 起 createMaterialType 强制走分段字典: 必须给 10 位 L3 segmentCode, 且
     * 16 位编码由 L3 前缀 + 6 位流水自动生成 (DTO 里手填的 code 会被覆盖), category 与
     * primaryCode 一律取自 L1 节点。这里把一条「包材」链 stub 齐, 让 P7/P8 能继续验证它们
     * 真正关心的 associatedCustomerId 往返。
     */
    private void stubPackagingChain() {
        stubSegment(L3_PACKAGING, (short) 3, L2_PACKAGING, "吸塑盒");
        stubSegment(L2_PACKAGING, (short) 2, L1_PACKAGING, "塑料包装");
        stubSegment(L1_PACKAGING, (short) 1, null, "包材");
    }

    private void stubSegment(String code, short level, String parent, String label) {
        MaterialCodeSegment node = MaterialCodeSegment.builder()
                .factoryId(FACTORY)
                .segmentCode(code)
                .level(level)
                .parentCode(parent)
                .segmentLabel(label)
                .isActive(true)
                .build();
        when(codeSegRepo.findByFactoryIdAndSegmentCode(FACTORY, code))
                .thenReturn(java.util.Optional.of(node));
        if (level == 3) {
            lenient().when(codeSegRepo.lockByFactoryIdAndSegmentCode(FACTORY, code))
                    .thenReturn(java.util.Optional.of(node));
        }
    }

    /** Access private convertToDTO via reflection. */
    private RawMaterialTypeDTO invokeConvertToDTO(RawMaterialType m) {
        try {
            var method = RawMaterialTypeServiceImpl.class
                    .getDeclaredMethod("convertToDTO", RawMaterialType.class);
            method.setAccessible(true);
            return (RawMaterialTypeDTO) method.invoke(svc, m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─────────────────────────── P8 ────────────────────────────────

    @Nested
    @DisplayName("P8: associatedCustomerId 字段往返")
    class P8AssociatedCustomerId {

        @Test
        @DisplayName("convertToDTO 将 associatedCustomerId 映射到 DTO")
        void convertToDTO_mapsAssociatedCustomerId() {
            RawMaterialType material = makeMaterial("m1", "吸塑盒", "包材", null, "CUST-001");

            var dto = invokeConvertToDTO(material);

            assertThat(dto.getAssociatedCustomerId()).isEqualTo("CUST-001");
        }

        @Test
        @DisplayName("convertToDTO: associatedCustomerId=null 时 DTO 也为 null")
        void convertToDTO_nullCustomerIdPreserved() {
            RawMaterialType material = makeMaterial("m2", "真空袋", "包材", null, null);

            var dto = invokeConvertToDTO(material);

            assertThat(dto.getAssociatedCustomerId()).isNull();
        }

        @Test
        @DisplayName("非包材物料 associatedCustomerId 为 null — 不污染其他大类")
        void nonPackaging_associatedCustomerIdNull() {
            RawMaterialType raw = makeMaterial("m3", "猪腿肉", "原料", BigDecimal.valueOf(25), null);

            var dto = invokeConvertToDTO(raw);

            assertThat(dto.getAssociatedCustomerId()).isNull();
        }

        @Test
        @DisplayName("associatedCustomerId 在 createMaterialType 时被持久化")
        void create_persistsAssociatedCustomerId() {
            stubPackagingChain();

            RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
            dto.setName("吸塑盒");
            dto.setCategory("包材");
            dto.setUnit("个");
            dto.setSegmentCode(L3_PACKAGING);
            // 包材必须带含税采购参考价 (validateRequiredPricing), 与本用例无关但属必填。
            dto.setTaxIncludedUnitPrice(new BigDecimal("1.20"));
            dto.setAssociatedCustomerId("CUST-ALPHA");

            when(repo.existsByFactoryIdAndCode(FACTORY, GENERATED_CODE)).thenReturn(false);
            when(repo.save(any())).thenAnswer(inv -> {
                RawMaterialType saved = inv.getArgument(0);
                saved.setId("new-id");
                return saved;
            });

            svc.createMaterialType(FACTORY, dto);

            verify(repo).save(argThat(m ->
                    "CUST-ALPHA".equals(m.getAssociatedCustomerId())));
        }
    }

    // ─────────────────────────── P11 ───────────────────────────────

    @Nested
    @DisplayName("P11: getMaterialTypesByKind 分页")
    class P11GetByKind {

        @Test
        @DisplayName("kind='包材' 时调用 findByFactoryIdAndCategoryIgnoreCase 并返回正确数量")
        void byKind_returnsFilteredPage() {
            RawMaterialType box = makeMaterial("k1", "纸箱", "包材", null, null);
            Page<RawMaterialType> mockPage = new PageImpl<>(List.of(box));
            when(repo.findByFactoryIdAndCategoryIgnoreCase(eq(FACTORY), eq("包材"), any(Pageable.class)))
                    .thenReturn(mockPage);

            PageResponse<RawMaterialTypeDTO> result =
                    svc.getMaterialTypesByKind(FACTORY, "包材", pageReq(1, 20));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("纸箱");
            verify(repo).findByFactoryIdAndCategoryIgnoreCase(eq(FACTORY), eq("包材"), any(Pageable.class));
            verify(repo, never()).findByFactoryId(any(String.class), any(Pageable.class));
        }

        @Test
        @DisplayName("kind=null 时退化为 getMaterialTypes (全量分页)")
        void byKind_nullDegradesToAll() {
            RawMaterialType m = makeMaterial("k2", "猪舌", "原料", null, null);
            Page<RawMaterialType> fullPage = new PageImpl<>(List.of(m));
            when(repo.findByFactoryId(eq(FACTORY), any(Pageable.class))).thenReturn(fullPage);

            PageResponse<RawMaterialTypeDTO> result =
                    svc.getMaterialTypesByKind(FACTORY, null, pageReq(1, 20));

            assertThat(result.getContent()).hasSize(1);
            verify(repo).findByFactoryId(eq(FACTORY), any(Pageable.class));
            verify(repo, never()).findByFactoryIdAndCategoryIgnoreCase(any(), any(), any());
        }

        @Test
        @DisplayName("kind=空白字符串时也退化为全量")
        void byKind_emptyStringDegradesToAll() {
            Page<RawMaterialType> fullPage = new PageImpl<>(List.of());
            when(repo.findByFactoryId(eq(FACTORY), any(Pageable.class))).thenReturn(fullPage);

            svc.getMaterialTypesByKind(FACTORY, "  ", pageReq(1, 20));

            verify(repo).findByFactoryId(eq(FACTORY), any(Pageable.class));
            verify(repo, never()).findByFactoryIdAndCategoryIgnoreCase(any(), any(), any());
        }

        @Test
        @DisplayName("kind='原料' 时仅返回原料类物料")
        void byKind_rawMaterialCategory() {
            RawMaterialType m1 = makeMaterial("k3", "猪舌", "原料", null, null);
            RawMaterialType m2 = makeMaterial("k4", "牛腱", "原料", null, null);
            Page<RawMaterialType> page = new PageImpl<>(List.of(m1, m2));
            when(repo.findByFactoryIdAndCategoryIgnoreCase(eq(FACTORY), eq("原料"), any(Pageable.class)))
                    .thenReturn(page);

            PageResponse<RawMaterialTypeDTO> result =
                    svc.getMaterialTypesByKind(FACTORY, "原料", pageReq(1, 50));

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(2L);
        }
    }

    // ─────────────────────────── P7 ────────────────────────────────

    @Nested
    @DisplayName("P7: 包材创建 RBAC — service 层无越权路径")
    class P7PackagingRbac {

        /**
         * P7 的核心守卫是 controller 注解 @RequirePermission("production:read_write").
         * Service 层本身不做 RBAC 判断 (遵循架构分层). 此测试确保 service 直接执行不会
         * 触发额外权限检查异常, 且 associatedCustomerId 正确随 DTO 持久化.
         * 真实 HTTP 层鉴权由 RequirePermissionInterceptor 在 controller 前拦截.
         */
        @Test
        @DisplayName("service.createMaterialType 接受包材 DTO 含 associatedCustomerId — 正常持久化")
        void createPackagingMaterial_noServiceLevelException() {
            stubPackagingChain();

            RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
            dto.setName("吸塑盒");
            dto.setCategory("包材");
            dto.setUnit("个");
            dto.setSegmentCode(L3_PACKAGING);
            dto.setTaxIncludedUnitPrice(new BigDecimal("1.20"));
            dto.setAssociatedCustomerId("CUST-P7");

            when(repo.existsByFactoryIdAndCode(FACTORY, GENERATED_CODE)).thenReturn(false);
            when(repo.save(any())).thenAnswer(inv -> {
                RawMaterialType saved = inv.getArgument(0);
                saved.setId("p7-saved-id");
                return saved;
            });

            assertThatNoException().isThrownBy(() ->
                    svc.createMaterialType(FACTORY, dto));

            verify(repo).save(argThat(m ->
                    "CUST-P7".equals(m.getAssociatedCustomerId())
                    && "包材".equals(m.getCategory())));
        }
    }

    // ─────────────────────────── R14 ───────────────────────────────

    @Nested
    @DisplayName("R14: suggestMaterialsByPriceRange 价格区间选料")
    class R14SuggestByPrice {

        @Test
        @DisplayName("在价格范围内的物料被返回")
        void suggestByPrice_returnsInRange() {
            RawMaterialType m1 = makeMaterial("r1", "猪腿肉", "原料", BigDecimal.valueOf(25), null);
            RawMaterialType m2 = makeMaterial("r2", "牛腱肉", "原料", BigDecimal.valueOf(40), null);
            when(repo.findByPriceRange(eq(FACTORY), eq(BigDecimal.valueOf(10)),
                    eq(BigDecimal.valueOf(50)), any(Pageable.class)))
                    .thenReturn(List.of(m1, m2));

            List<RawMaterialTypeDTO> result = svc.suggestMaterialsByPriceRange(
                    FACTORY, BigDecimal.valueOf(10), BigDecimal.valueOf(50));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(RawMaterialTypeDTO::getName)
                    .containsExactly("猪腿肉", "牛腱肉");
        }

        @Test
        @DisplayName("minPrice=null 时传 null 给 repo (无下限)")
        void suggestByPrice_nullMinPassedToRepo() {
            when(repo.findByPriceRange(eq(FACTORY), isNull(), eq(BigDecimal.valueOf(100)), any(Pageable.class)))
                    .thenReturn(List.of());

            svc.suggestMaterialsByPriceRange(FACTORY, null, BigDecimal.valueOf(100));

            verify(repo).findByPriceRange(eq(FACTORY), isNull(), eq(BigDecimal.valueOf(100)), any(Pageable.class));
        }

        @Test
        @DisplayName("maxPrice=null 时传 null 给 repo (无上限)")
        void suggestByPrice_nullMaxPassedToRepo() {
            when(repo.findByPriceRange(eq(FACTORY), eq(BigDecimal.valueOf(5)), isNull(), any(Pageable.class)))
                    .thenReturn(List.of());

            svc.suggestMaterialsByPriceRange(FACTORY, BigDecimal.valueOf(5), null);

            verify(repo).findByPriceRange(eq(FACTORY), eq(BigDecimal.valueOf(5)), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("min=null, max=null 时传两个 null 给 repo")
        void suggestByPrice_bothNullPassedThrough() {
            when(repo.findByPriceRange(eq(FACTORY), isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(List.of());

            svc.suggestMaterialsByPriceRange(FACTORY, null, null);

            verify(repo).findByPriceRange(eq(FACTORY), isNull(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("无匹配物料时返回空列表")
        void suggestByPrice_emptyResultWhenNoMatch() {
            when(repo.findByPriceRange(any(), any(), any(), any())).thenReturn(List.of());

            List<RawMaterialTypeDTO> result = svc.suggestMaterialsByPriceRange(
                    FACTORY, BigDecimal.valueOf(999), BigDecimal.valueOf(9999));

            assertThat(result).isEmpty();
        }
    }
}
