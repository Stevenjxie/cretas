package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.smartbi.QueryTemplateDTO;
import com.cretas.aims.entity.smartbi.postgres.SmartBiQueryTemplate;
import com.cretas.aims.repository.smartbi.postgres.SmartBiQueryTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CRUD for SmartBI 查询模板 (QueryTemplateManager.vue). Previously the frontend called
 * GET/POST/PUT/DELETE on this path but no backend served it → 404 "加载模板失败" for every
 * factory. Backs onto smart_bi_query_templates (smartbi_prod_db) via the smartbi datasource;
 * writes use the smartbi transaction manager.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi/query-templates")
@RequiredArgsConstructor
public class SmartBiQueryTemplateController {

    private final SmartBiQueryTemplateRepository repository;

    @GetMapping
    public ApiResponse<List<QueryTemplateDTO>> list(@PathVariable String factoryId) {
        List<QueryTemplateDTO> result = repository
                .findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId)
                .stream().map(SmartBiQueryTemplateController::toDTO).toList();
        return ApiResponse.success(result);
    }

    @PostMapping
    @Transactional("smartbiPostgresTransactionManager")
    public ApiResponse<QueryTemplateDTO> create(@PathVariable String factoryId,
                                                 @RequestBody QueryTemplateDTO body) {
        LocalDateTime now = LocalDateTime.now();
        SmartBiQueryTemplate entity = SmartBiQueryTemplate.builder()
                .factoryId(factoryId)
                .name(body.getName())
                .category(body.getCategory())
                .description(body.getDescription())
                .queryTemplate(body.getQueryTemplate())
                .parameters(body.getParameters())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return ApiResponse.success(toDTO(repository.save(entity)));
    }

    @PutMapping("/{id}")
    @Transactional("smartbiPostgresTransactionManager")
    public ApiResponse<QueryTemplateDTO> update(@PathVariable String factoryId,
                                                @PathVariable Long id,
                                                @RequestBody QueryTemplateDTO body) {
        SmartBiQueryTemplate entity = repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new IllegalArgumentException("查询模板不存在: " + id));
        entity.setName(body.getName());
        entity.setCategory(body.getCategory());
        entity.setDescription(body.getDescription());
        entity.setQueryTemplate(body.getQueryTemplate());
        entity.setParameters(body.getParameters());
        entity.setUpdatedAt(LocalDateTime.now());
        return ApiResponse.success(toDTO(repository.save(entity)));
    }

    @DeleteMapping("/{id}")
    @Transactional("smartbiPostgresTransactionManager")
    public ApiResponse<Void> delete(@PathVariable String factoryId, @PathVariable Long id) {
        repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId).ifPresent(entity -> {
            entity.setDeletedAt(LocalDateTime.now());
            repository.save(entity);
        });
        return ApiResponse.success();
    }

    private static QueryTemplateDTO toDTO(SmartBiQueryTemplate e) {
        return QueryTemplateDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .category(e.getCategory())
                .description(e.getDescription())
                .queryTemplate(e.getQueryTemplate())
                .parameters(e.getParameters())
                .build();
    }
}
