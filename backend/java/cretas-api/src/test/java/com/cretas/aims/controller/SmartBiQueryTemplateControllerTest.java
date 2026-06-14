package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.smartbi.QueryTemplateDTO;
import com.cretas.aims.entity.smartbi.postgres.SmartBiQueryTemplate;
import com.cretas.aims.repository.smartbi.postgres.SmartBiQueryTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartBiQueryTemplateControllerTest {

    @Mock
    private SmartBiQueryTemplateRepository repository;

    @InjectMocks
    private SmartBiQueryTemplateController controller;

    @Test
    void list_mapsEntitiesToDtoScopedByFactory() {
        SmartBiQueryTemplate e = SmartBiQueryTemplate.builder()
                .id(1L).factoryId("F006").name("本月产量").category("生产分析")
                .description("desc").queryTemplate("q").parameters("[]").build();
        when(repository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(List.of(e));

        ApiResponse<List<QueryTemplateDTO>> resp = controller.list("F006");

        assertThat(resp.getSuccess()).isTrue();
        assertThat(resp.getData()).hasSize(1);
        QueryTemplateDTO dto = resp.getData().get(0);
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("本月产量");
        assertThat(dto.getCategory()).isEqualTo("生产分析");
        assertThat(dto.getQueryTemplate()).isEqualTo("q");
    }

    @Test
    void create_persistsWithFactoryAndTimestamps() {
        when(repository.save(any())).thenAnswer(inv -> {
            SmartBiQueryTemplate s = inv.getArgument(0);
            s.setId(7L);
            return s;
        });
        QueryTemplateDTO body = QueryTemplateDTO.builder()
                .name("毛利对比").category("财务分析").queryTemplate("q2").parameters("[]").build();

        ApiResponse<QueryTemplateDTO> resp = controller.create("F006", body);

        assertThat(resp.getData().getId()).isEqualTo(7L);
        ArgumentCaptor<SmartBiQueryTemplate> captor = ArgumentCaptor.forClass(SmartBiQueryTemplate.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFactoryId()).isEqualTo("F006");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void update_appliesChangesToExistingScopedRow() {
        SmartBiQueryTemplate existing = SmartBiQueryTemplate.builder()
                .id(3L).factoryId("F006").name("old").category("自定义").queryTemplate("oldq").build();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(3L, "F006")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        QueryTemplateDTO body = QueryTemplateDTO.builder()
                .name("new").category("销售分析").queryTemplate("newq").parameters("[]").build();

        ApiResponse<QueryTemplateDTO> resp = controller.update("F006", 3L, body);

        assertThat(resp.getData().getName()).isEqualTo("new");
        assertThat(existing.getCategory()).isEqualTo("销售分析");
        assertThat(existing.getUpdatedAt()).isNotNull();
    }

    @Test
    void delete_softDeletesScopedToFactory() {
        SmartBiQueryTemplate e = SmartBiQueryTemplate.builder().id(3L).factoryId("F006").build();
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull(3L, "F006")).thenReturn(Optional.of(e));

        controller.delete("F006", 3L);

        assertThat(e.getDeletedAt()).isNotNull();
        verify(repository).save(e);
    }
}
