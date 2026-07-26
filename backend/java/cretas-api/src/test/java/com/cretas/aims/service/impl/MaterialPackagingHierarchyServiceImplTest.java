package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.dto.material.MaterialPackagingSpecDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialPackagingHierarchyServiceImplTest {

    private final MaterialPackagingHierarchyRepository hierarchyRepository =
            mock(MaterialPackagingHierarchyRepository.class);
    private final MaterialPackagingSpecRepository packagingSpecRepository =
            mock(MaterialPackagingSpecRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final UnitContractService unitContractService = mock(UnitContractService.class);
    private final MaterialPackagingHierarchyServiceImpl service =
            new MaterialPackagingHierarchyServiceImpl(
                    hierarchyRepository, packagingSpecRepository, materialRepository, unitContractService);

    @Test
    void rawMaterialCanUpsertPurchasePackagingConversion() {
        when(materialRepository.findById("M-RAW")).thenReturn(Optional.of(material("M-RAW", "原料")));
        when(hierarchyRepository.findByMaterialTypeId("M-RAW")).thenReturn(Optional.empty());
        when(hierarchyRepository.save(any(MaterialPackagingHierarchy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MaterialPackagingHierarchyDTO saved = service.upsert(
                "F006", "M-RAW", purchasePackagingDto(), 1L);

        assertThat(saved.getLevel1Unit()).isEqualTo("kg");
        assertThat(saved.getLevel1PerLevel2()).isEqualByComparingTo("10");
        assertThat(saved.getLevel2Unit()).isEqualTo("case");
        verify(hierarchyRepository).save(any(MaterialPackagingHierarchy.class));
    }

    @Test
    void standardPackagingCategoryCanUpsertHierarchy() {
        when(materialRepository.findById("M-PACK")).thenReturn(Optional.of(material("M-PACK", "PACKAGING")));
        when(hierarchyRepository.findByMaterialTypeId("M-PACK")).thenReturn(Optional.empty());
        when(hierarchyRepository.save(any(MaterialPackagingHierarchy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.upsert("F006", "M-PACK", validDto(), 1L).getMaterialTypeId())
                .isEqualTo("M-PACK");
        verify(hierarchyRepository).save(any(MaterialPackagingHierarchy.class));
    }

    @Test
    void rejectsASecondBaseUnitThatConflictsWithMaterialInventoryUnit() {
        when(materialRepository.findById("M-RAW")).thenReturn(Optional.of(material("M-RAW", "原料")));

        MaterialPackagingHierarchyDTO invalid = purchasePackagingDto();
        invalid.setLevel1Unit("g");

        assertThatThrownBy(() -> service.upsert("F006", "M-RAW", invalid, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(400);
                    assertThat(business.getHintTarget()).isEqualTo("level1Unit");
                });
    }

    @Test
    void storesAnyNumberOfDirectPackagingRulesLikeSkuPackagingSpecs() {
        when(materialRepository.findById("M-RAW")).thenReturn(Optional.of(material("M-RAW", "原料")));
        when(hierarchyRepository.findByMaterialTypeId("M-RAW")).thenReturn(Optional.empty());
        when(hierarchyRepository.save(any(MaterialPackagingHierarchy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(packagingSpecRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MaterialPackagingHierarchyDTO dto = MaterialPackagingHierarchyDTO.builder()
                .level1Unit("kg")
                .packagingSpecs(List.of(
                        spec("箱", "10", 0),
                        spec("袋", "2.5", 1),
                        spec("桶", "18", 2)))
                .build();

        service.upsert("F006", "M-RAW", dto, 1L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MaterialPackagingSpec>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(packagingSpecRepository).saveAllAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting(MaterialPackagingSpec::getPackageUnit)
                .containsExactly("箱", "袋", "桶");
        assertThat(dto.getLevel2Unit()).isEqualTo("箱");
        assertThat(dto.getLevel3Unit()).isEqualTo("袋");
        assertThat(dto.getLevel2PerLevel3()).isEqualByComparingTo("0.25");
    }

    private RawMaterialType material(String id, String category) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId("F006");
        material.setCategory(category);
        material.setUnit("M-PACK".equals(id) ? "个" : "kg");
        return material;
    }

    private MaterialPackagingHierarchyDTO validDto() {
        return MaterialPackagingHierarchyDTO.builder().level1Unit("个").build();
    }

    private MaterialPackagingHierarchyDTO purchasePackagingDto() {
        return MaterialPackagingHierarchyDTO.builder()
                .level1Unit("kg")
                .level1PerLevel2(new java.math.BigDecimal("10"))
                .level2Unit("case")
                .build();
    }

    private MaterialPackagingSpecDTO spec(String packageUnit, String factor, int order) {
        return new MaterialPackagingSpecDTO(
                null, order == 0 ? "默认包装" : "包装规格 " + (order + 1),
                packageUnit, "kg", new BigDecimal(factor), order == 0, true, order, null);
    }
}
