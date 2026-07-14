package com.cretas.aims.service.uom;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.UnitConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T159-B: MaterialUomConverter.isWriteUnitCompatible() unit tests.
 *
 * <p>验证写入时维度兼容性校验:
 * <ul>
 *   <li>(a) g BOM vs kg-canonical = ALLOWED (同维度)</li>
 *   <li>(b) 个/pcs BOM vs 件-canonical = ALLOWED (同计数维度)</li>
 *   <li>(c) 个 BOM vs kg-canonical = BLOCKED (跨维度)</li>
 *   <li>(d) abaca material = ABACA_SKIP = ALLOWED (fail-open for unknown weight)</li>
 *   <li>(e) same unit = ALLOWED</li>
 *   <li>(f) null writeUnit = fail-OPEN (unknown → allow)</li>
 *   <li>(g) null material = fail-OPEN (unknown material → allow)</li>
 * </ul>
 */
@DisplayName("T159-B: MaterialUomConverter.isWriteUnitCompatible() — write-time dimension guard")
class MaterialUomConverterWriteCompatTest {

    private MaterialPackagingHierarchyRepository packagingRepo;
    private RawMaterialTypeRepository materialRepo;
    private MaterialUomConverter converter;

    private static final String MAT_PORK = "MT-PORK-001";      // 冷冻猪舌, canonical=kg
    private static final String MAT_BOX = "MT-BOX-001";         // 吸塑盒, canonical=个
    private static final String MAT_ABACA = "MT-BEEF-001";      // 牛肉, abaca=true

    @BeforeEach
    void setUp() {
        packagingRepo = mock(MaterialPackagingHierarchyRepository.class);
        materialRepo = mock(RawMaterialTypeRepository.class);
        converter = new MaterialUomConverter(packagingRepo, materialRepo,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());

        // Default: material not found
        when(materialRepo.findById(anyString())).thenReturn(Optional.empty());
        when(packagingRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());

        // Pork material: canonical=kg, not abaca
        RawMaterialType pork = new RawMaterialType();
        pork.setId(MAT_PORK);
        pork.setName("冷冻猪舌");
        pork.setUnit("kg");
        pork.setIsAbacaPackaging(false);
        when(materialRepo.findById(MAT_PORK)).thenReturn(Optional.of(pork));

        // Box material: canonical=个, not abaca
        RawMaterialType box = new RawMaterialType();
        box.setId(MAT_BOX);
        box.setName("吸塑盒2014-3.5");
        box.setUnit("件");
        box.setIsAbacaPackaging(false);
        when(materialRepo.findById(MAT_BOX)).thenReturn(Optional.of(box));

        // Abaca material
        RawMaterialType beef = new RawMaterialType();
        beef.setId(MAT_ABACA);
        beef.setName("牛肉");
        beef.setUnit("箱");
        beef.setIsAbacaPackaging(true);
        when(materialRepo.findById(MAT_ABACA)).thenReturn(Optional.of(beef));
    }

    // ============================================================
    // (a) g BOM vs kg canonical → ALLOWED (same dimension, g↔kg)
    // ============================================================

    @Test
    @DisplayName("(a) g BOM vs kg-canonical material → ALLOWED (g↔kg same dimension)")
    void gBomVsKgCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "g");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(a) kg BOM vs kg-canonical material → ALLOWED (same unit)")
    void kgBomVsKgCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "kg");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(a) 克 BOM vs kg-canonical material → ALLOWED (alias: 克→g, same dimension)")
    void zhongwenKgBomVsKgCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "克");
        assertThat(result).isTrue();
    }

    // ============================================================
    // (b) 个/pcs BOM vs 件-canonical → ALLOWED (same count dimension)
    // ============================================================

    @Test
    @DisplayName("(b) 个 BOM vs 件-canonical material → ALLOWED (个≡件 alias)")
    void geBomVsJianCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "个");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(b) pcs BOM vs 件-canonical material → ALLOWED (pcs≡个≡件 alias)")
    void pcsBomVsJianCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "pcs");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(b) 件 BOM vs 件-canonical material → ALLOWED (same unit)")
    void jianBomVsJianCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "件");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(b) PCS uppercase BOM vs 件-canonical → ALLOWED (case-insensitive)")
    void pcsUpperBomVsJianCanonical_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "PCS");
        assertThat(result).isTrue();
    }

    // ============================================================
    // (c) 个 BOM vs kg-canonical → BLOCKED (cross-dimension: count vs weight)
    // ============================================================

    @Test
    @DisplayName("(c) 个 BOM vs kg-canonical material → BLOCKED (cross-dimension count vs weight)")
    void geBomVsKgCanonical_blocked() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "个");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("(c) pcs BOM vs kg-canonical material → BLOCKED (count vs weight)")
    void pcsBomVsKgCanonical_blocked() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "pcs");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("(c) kg BOM vs 件-canonical material → BLOCKED (weight vs count)")
    void kgBomVsJianCanonical_blocked() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "kg");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("(c) g BOM vs 件-canonical material → BLOCKED (weight vs count)")
    void gBomVsJianCanonical_blocked() {
        boolean result = converter.isWriteUnitCompatible(MAT_BOX, "g");
        assertThat(result).isFalse();
    }

    // ============================================================
    // (d) abaca material → ABACA_SKIP → ALLOWED (fail-open)
    // ============================================================

    @Test
    @DisplayName("(d) abaca material: any write unit → ALLOWED (ABACA_SKIP fail-open)")
    void abacaMaterial_anyUnit_allowed() {
        // Even kg vs 箱-canonical → ALLOWED for abaca (cannot determine factor)
        boolean result = converter.isWriteUnitCompatible(MAT_ABACA, "kg");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(d) abaca material: g write unit → ALLOWED (ABACA_SKIP)")
    void abacaMaterial_gUnit_allowed() {
        boolean result = converter.isWriteUnitCompatible(MAT_ABACA, "g");
        assertThat(result).isTrue();
    }

    // ============================================================
    // (e) null/blank cases → fail-OPEN (unknown is safe)
    // ============================================================

    @Test
    @DisplayName("(f) null writeUnit → fail-OPEN = true (unknown unit, don't block)")
    void nullWriteUnit_failOpen() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, null);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(f) blank writeUnit → fail-OPEN = true")
    void blankWriteUnit_failOpen() {
        boolean result = converter.isWriteUnitCompatible(MAT_PORK, "  ");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(g) null materialTypeId → fail-OPEN = true (unknown material)")
    void nullMaterialTypeId_failOpen() {
        boolean result = converter.isWriteUnitCompatible(null, "个");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("(g) unknown materialTypeId (not in repo) → fail-OPEN = true")
    void unknownMaterialTypeId_failOpen() {
        boolean result = converter.isWriteUnitCompatible("NONEXISTENT-ID", "个");
        assertThat(result).isTrue();
    }

    // ============================================================
    // (h) 箱 BOM vs 箱 canonical with hierarchy → ALLOWED (same unit via alias)
    // ============================================================

    @Test
    @DisplayName("(h) 箱 BOM vs 箱 canonical material with hierarchy → ALLOWED (same unit passthrough)")
    void xiangBomVsXiangCanonical_allowed() {
        // Material with canonical=箱 (packaging unit like carton)
        RawMaterialType carton = new RawMaterialType();
        carton.setId("MT-CARTON");
        carton.setName("纸箱");
        carton.setUnit("箱");
        carton.setIsAbacaPackaging(false);
        when(materialRepo.findById("MT-CARTON")).thenReturn(Optional.of(carton));

        MaterialPackagingHierarchy h = new MaterialPackagingHierarchy();
        h.setLevel1Unit("kg");
        h.setLevel1PerLevel2(new BigDecimal("10"));
        h.setLevel2Unit("箱");
        when(packagingRepo.findByMaterialTypeId("MT-CARTON")).thenReturn(Optional.of(h));

        boolean result = converter.isWriteUnitCompatible("MT-CARTON", "箱");
        assertThat(result).isTrue();
    }
}
