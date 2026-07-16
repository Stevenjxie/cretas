package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialPackagingHierarchyServiceImplTest {

    private final MaterialPackagingHierarchyRepository hierarchyRepository =
            mock(MaterialPackagingHierarchyRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final MaterialPackagingHierarchyServiceImpl service =
            new MaterialPackagingHierarchyServiceImpl(hierarchyRepository, materialRepository);

    @Test
    void nonPackagingMaterialCannotUpsertHierarchy() {
        when(materialRepository.findById("M-RAW")).thenReturn(Optional.of(material("M-RAW", "原料")));

        assertThatThrownBy(() -> service.upsert("F006", "M-RAW", validDto(), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getHintTarget()).isEqualTo("category");
                });
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

    private RawMaterialType material(String id, String category) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId("F006");
        material.setCategory(category);
        return material;
    }

    private MaterialPackagingHierarchyDTO validDto() {
        return MaterialPackagingHierarchyDTO.builder().level1Unit("个").build();
    }
}
