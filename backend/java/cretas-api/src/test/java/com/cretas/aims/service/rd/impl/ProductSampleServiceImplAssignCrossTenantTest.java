package com.cretas.aims.service.rd.impl;

import com.cretas.aims.entity.rd.RdRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.repository.rd.ProductSampleRepository;
import com.cretas.aims.repository.rd.ProductSampleTrackingRecordRepository;
import com.cretas.aims.repository.rd.RdRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 round5 多租户隔离: assignRequest(factoryId, requestId, ...) 校验研发需求归属工厂。
 * 此前 findById(requestId) 不校验 → F006 用户可分配别家工厂的研发需求。
 */
@ExtendWith(MockitoExtension.class)
class ProductSampleServiceImplAssignCrossTenantTest {

    @Mock private RdRequestRepository rdRequestRepository;
    @Mock private ProductSampleRepository productSampleRepository;
    @Mock private QuotationTaskRepository quotationTaskRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProductSampleTrackingRecordRepository trackingRecordRepository;

    @InjectMocks
    private ProductSampleServiceImpl service;

    private RdRequest req(String id, String factoryId) {
        RdRequest r = new RdRequest();
        r.setId(id);
        r.setFactoryId(factoryId);
        return r;
    }

    @Test
    void assignRequest_crossTenant_throws403_noSave() {
        when(rdRequestRepository.findById("RD-1")).thenReturn(Optional.of(req("RD-1", "F999")));

        assertThatThrownBy(() -> service.assignRequest("F006", "RD-1", 5L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(rdRequestRepository, never()).save(any());
    }

    @Test
    void assignRequest_sameFactory_proceeds() {
        RdRequest r = req("RD-2", "F006");
        when(rdRequestRepository.findById("RD-2")).thenReturn(Optional.of(r));
        when(rdRequestRepository.save(any(RdRequest.class))).thenAnswer(i -> i.getArgument(0));

        service.assignRequest("F006", "RD-2", 5L);
        verify(rdRequestRepository).save(any(RdRequest.class));
        assertThat(r.getStatus()).isEqualTo("ASSIGNED");
        assertThat(r.getAssignedTo()).isEqualTo(5L);
    }
}
